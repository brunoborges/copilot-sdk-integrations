"""Immutable agent definition (counterpart of ``CopilotAgentDefinition`` in java-core)."""

from __future__ import annotations

from collections.abc import Callable, Mapping, Sequence
from dataclasses import dataclass, field
from typing import Any, Literal

from .context import AgentContext
from .errors import CopilotConfigurationError
from .hooks import CopilotAgentHooks
from .policies import PermissionPolicy, as_policy
from .tokens import CopilotTokenResolver
from .tools import CopilotTool, as_tool

ALL_BUILT_IN_TOOLS = "builtin:*"
TOOL_SEARCH_KEYS = frozenset({"enabled", "defer_threshold"})

InstructionsMode = Literal["append", "replace", "customize"]
Listener = Callable[[AgentContext, Any], Any]
Customizer = Callable[[dict[str, Any], AgentContext], Any]
WorkingDirectory = str | Callable[[AgentContext], str | None] | None


@dataclass(frozen=True)
class ConfigIssue:
    """A validation problem; ``kind`` is ``tool-filters``, ``byok``, ``duplicate``, or ``invalid``."""

    kind: str
    message: str

    def __str__(self) -> str:
        return self.message


@dataclass(frozen=True)
class AgentDefinition:
    """Everything needed to open sessions for one agent.

    ``available_tools`` / ``excluded_tools`` use runtime tool filters such as
    ``builtin:view``, ``custom:*``, or ``mcp:github/*``. ``built_in_tools=False`` adds
    ``builtin:*`` to the excluded tools. An allow-list is never widened: when the agent has
    tools or MCP servers, it must include ``custom:`` or ``mcp:`` entries.

    ``tool_search`` overrides the runtime's tool search (``{"enabled": bool,
    "defer_threshold": int}``, either key optional); ``None`` keeps the runtime default.
    """

    name: str
    type_name: str = ""
    instructions: str | None = None
    instructions_mode: InstructionsMode = "append"
    system_message: Mapping[str, Any] | None = None
    model: str | None = None
    reasoning_effort: str | None = None
    provider: Mapping[str, Any] | None = None
    tools: Sequence[CopilotTool] = ()
    mcp_servers: Mapping[str, Mapping[str, Any]] = field(default_factory=dict)
    available_tools: Sequence[str] | None = None
    excluded_tools: Sequence[str] = ()
    excluded_built_in_agents: Sequence[str] = ()
    built_in_tools: bool = True
    tool_search: Mapping[str, Any] | None = None
    working_directory: WorkingDirectory = None
    release_working_directory: Callable[[AgentContext], Any] | None = None
    additional_directories: Sequence[str] = ()
    permissions: PermissionPolicy | Callable[..., Any] | str | Mapping[str, Any] | None = None
    hooks: Sequence[CopilotAgentHooks] = ()
    listeners: Sequence[Listener] = ()
    customizers: Sequence[Customizer] = ()
    token_resolver: CopilotTokenResolver | None = None
    timeout: float = 300.0
    expose_tool_errors: bool = False

    def __post_init__(self) -> None:
        set_ = object.__setattr__
        if not self.name:
            raise CopilotConfigurationError("Copilot agent needs a name")
        set_(self, "type_name", self.type_name or self.name)
        set_(self, "tools", tuple(as_tool(t) for t in self.tools))
        set_(self, "mcp_servers", dict(self.mcp_servers))
        set_(self, "excluded_tools", tuple(self.excluded_tools))
        set_(self, "excluded_built_in_agents", tuple(self.excluded_built_in_agents))
        set_(self, "additional_directories", tuple(self.additional_directories))
        set_(self, "hooks", tuple(self.hooks))
        set_(self, "listeners", tuple(self.listeners))
        set_(self, "customizers", tuple(self.customizers))
        if self.available_tools is not None:
            set_(self, "available_tools", tuple(self.available_tools))
        if isinstance(self.tool_search, Mapping):
            set_(self, "tool_search", dict(self.tool_search))
        errors = self.validate()
        if errors:
            raise CopilotConfigurationError("; ".join(map(str, errors)), errors)
        set_(self, "permissions", as_policy(self.permissions).bind(self))

    def validate(self) -> list[ConfigIssue]:
        """Return configuration problems (empty when valid)."""
        errors: list[ConfigIssue] = []
        prefix = f"Copilot agent {self.name!r}"
        available = list(self.available_tools or ())
        if self.instructions_mode not in ("append", "replace", "customize"):
            errors.append(ConfigIssue("invalid", f"{prefix}: instructions_mode must be append, replace, or customize"))
        if not self.built_in_tools and available:
            errors.append(ConfigIssue(
                "tool-filters",
                f"{prefix}: built_in_tools=False cannot be combined with available_tools; "
                "list the wanted built-in tools instead",
            ))
        elif available and self.excluded_tools:
            errors.append(ConfigIssue(
                "tool-filters",
                f"{prefix}: available_tools and excluded_tools are mutually exclusive because "
                "the runtime ignores the deny-list when an allow-list is set",
            ))
        if available:
            if self.tools and not any(name.startswith("custom:") for name in available):
                errors.append(ConfigIssue(
                    "tool-filters",
                    f"{prefix}: available_tools hides every tool bound to the agent; add "
                    '"custom:*" or specific "custom:<tool>" entries, or remove the agent\'s tools',
                ))
            if self.mcp_servers and not any(name.startswith("mcp:") for name in available):
                errors.append(ConfigIssue(
                    "tool-filters",
                    f"{prefix}: available_tools hides every MCP server tool bound to the agent; "
                    'add "mcp:*" or specific "mcp:<server>/<tool>" entries',
                ))
        errors += self._validate_tool_search(prefix)
        if self.provider is not None and not self.model:
            errors.append(ConfigIssue("byok", f"{prefix}: a BYOK provider requires a model"))
        names = [t.name for t in self.tools]
        duplicates = sorted({n for n in names if names.count(n) > 1})
        if duplicates:
            errors.append(ConfigIssue("duplicate", f"{prefix}: duplicate tool names {duplicates}"))
        if self.timeout <= 0:
            errors.append(ConfigIssue("invalid", f"{prefix}: timeout must be positive"))
        return errors

    def _validate_tool_search(self, prefix: str) -> list[ConfigIssue]:
        config = self.tool_search
        if config is None:
            return []
        if not isinstance(config, Mapping):
            return [ConfigIssue("invalid", f"{prefix}: tool_search must be a mapping with enabled / defer_threshold")]
        errors: list[ConfigIssue] = []
        unknown = sorted(str(key) for key in config if key not in TOOL_SEARCH_KEYS)
        if unknown:
            errors.append(ConfigIssue(
                "invalid", f"{prefix}: unknown tool_search keys {unknown}; expected enabled, defer_threshold"
            ))
        enabled = config.get("enabled")
        if "enabled" in config and not isinstance(enabled, bool):
            errors.append(ConfigIssue("invalid", f"{prefix}: tool_search enabled must be a bool"))
        threshold = config.get("defer_threshold")
        if "defer_threshold" in config:
            if isinstance(threshold, bool) or not isinstance(threshold, int) or threshold < 1:
                errors.append(ConfigIssue("invalid", f"{prefix}: tool_search defer_threshold must be an int >= 1"))
            elif enabled is False:
                errors.append(ConfigIssue(
                    "invalid", f"{prefix}: tool_search defer_threshold has no effect when enabled is False"
                ))
        return errors

    @property
    def effective_excluded_tools(self) -> tuple[str, ...]:
        excluded = tuple(self.excluded_tools)
        if not self.built_in_tools and ALL_BUILT_IN_TOOLS not in excluded:
            excluded += (ALL_BUILT_IN_TOOLS,)
        return excluded

    def system_message_config(self) -> dict[str, Any] | None:
        if self.system_message is not None:
            return dict(self.system_message)
        if self.instructions:
            return {"mode": self.instructions_mode, "content": self.instructions}
        return None
