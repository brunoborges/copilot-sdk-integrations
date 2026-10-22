"""Copilot permission policies (counterpart of ``CopilotPermissionPolicies`` in java-core).

Registering a tool never approves it. Each agent gets one SDK permission handler built
from a policy, and the default denies everything and logs. Policies *approve* or
*abstain*; a request nobody approves is rejected. :func:`any_of` combines policies.
"""

from __future__ import annotations

import logging
from collections.abc import Callable, Iterable, Mapping
from typing import TYPE_CHECKING, Any

from copilot.generated.rpc import PermissionDecisionApproveOnce, PermissionDecisionReject
from copilot.session import PermissionNoResult

from .errors import CopilotConfigurationError

if TYPE_CHECKING:
    from .definition import AgentDefinition

log = logging.getLogger("copilot_agents.permissions")

KINDS = frozenset(
    {
        "shell",
        "write",
        "read",
        "mcp",
        "custom-tool",
        "url",
        "memory",
        "hook",
        "workflow",
        "extension-management",
        "extension-permission-access",
        "extension-env-access",
    }
)
"""Permission request kinds reported by the runtime."""

DENIED_FEEDBACK = "Denied by the application's Copilot permission policy."

PermissionCallable = Callable[[Any, Mapping[str, Any]], Any]


def approve() -> Any:
    return PermissionDecisionApproveOnce()


def reject(feedback: str | None = DENIED_FEEDBACK) -> Any:
    return PermissionDecisionReject(feedback=feedback)


def _is_approval(decision: Any) -> bool:
    return decision is True or isinstance(decision, PermissionDecisionApproveOnce) or (
        str(getattr(decision, "kind", "") or "").startswith("approve")
    )


def _describe(request: Any) -> str:
    kind = getattr(request, "kind", "?")
    tool = getattr(request, "tool_name", None)
    server = getattr(request, "server_name", None)
    if tool and server:
        return f"{kind} {server}/{tool}"
    return f"{kind} {tool}" if tool else kind


class PermissionPolicy:
    """Approves (returns an approval) or abstains (returns ``None``) per request."""

    pure = True
    """Pure policies run inline on the Copilot loop; others are dispatched like tools."""

    def decide(self, request: Any, invocation: Mapping[str, Any]) -> Any:
        return None

    def bind(self, agent: AgentDefinition) -> PermissionPolicy:
        """Specialize the policy for an agent (for example, to learn its tool names)."""
        return self

    def describe(self) -> str:
        return type(self).__name__

    def __call__(self, request: Any, invocation: Mapping[str, Any]) -> Any:
        decision = self.decide(request, invocation)
        if decision is None or decision is False:
            log.info(
                "Copilot permission request denied: %s (session %s)",
                _describe(request),
                invocation.get("session_id") if isinstance(invocation, Mapping) else None,
            )
            return reject()
        if decision is True:
            decision = approve()
        if _is_approval(decision) and getattr(request, "managed_approval_required", False) is True:
            return PermissionNoResult()
        return decision

    def __repr__(self) -> str:
        return f"<{self.describe()}>"


class _DenyAll(PermissionPolicy):
    def describe(self) -> str:
        return "deny_all()"


class _AllowAll(PermissionPolicy):
    def decide(self, request: Any, invocation: Mapping[str, Any]) -> Any:
        return approve()

    def describe(self) -> str:
        return "allow_all()"


class _AllowKinds(PermissionPolicy):
    def __init__(self, kinds: Iterable[str]) -> None:
        self.kinds = frozenset(kinds)
        unknown = self.kinds - KINDS
        if unknown:
            raise CopilotConfigurationError(
                f"Unknown permission kinds {sorted(unknown)}; expected some of {sorted(KINDS)}"
            )

    def decide(self, request: Any, invocation: Mapping[str, Any]) -> Any:
        return approve() if getattr(request, "kind", None) in self.kinds else None

    def describe(self) -> str:
        return f"allow_kinds({', '.join(sorted(self.kinds))})"


class _AllowTools(PermissionPolicy):
    """Approves custom-tool and MCP requests for named tools (``name`` or ``server/name``)."""

    def __init__(self, names: Iterable[str]) -> None:
        self.names = frozenset(names)

    def decide(self, request: Any, invocation: Mapping[str, Any]) -> Any:
        kind = getattr(request, "kind", None)
        tool = getattr(request, "tool_name", None)
        if kind == "custom-tool" and tool in self.names:
            return approve()
        if kind == "mcp":
            server = getattr(request, "server_name", None)
            if tool in self.names or f"{server}/{tool}" in self.names:
                return approve()
        return None

    def describe(self) -> str:
        return f"allow_tools({', '.join(sorted(self.names))})"


class _AllowMcpServers(PermissionPolicy):
    def __init__(self, servers: Iterable[str]) -> None:
        self.servers = frozenset(servers)

    def decide(self, request: Any, invocation: Mapping[str, Any]) -> Any:
        if getattr(request, "kind", None) == "mcp" and getattr(request, "server_name", None) in self.servers:
            return approve()
        return None

    def describe(self) -> str:
        return f"allow_mcp_servers({', '.join(sorted(self.servers))})"


class _AllowReadOnly(PermissionPolicy):
    def decide(self, request: Any, invocation: Mapping[str, Any]) -> Any:
        kind = getattr(request, "kind", None)
        if kind == "read" or (kind == "mcp" and getattr(request, "read_only", False) is True):
            return approve()
        return None

    def describe(self) -> str:
        return "allow_read_only()"


class _AllowRegisteredTools(PermissionPolicy):
    def bind(self, agent: AgentDefinition) -> PermissionPolicy:
        return _AllowTools(tool.name for tool in agent.tools)

    def decide(self, request: Any, invocation: Mapping[str, Any]) -> Any:
        raise RuntimeError("allow_registered_tools() must be bound to an agent before use")

    def describe(self) -> str:
        return "allow_registered_tools()"


class _Callable(PermissionPolicy):
    """Adapts a user callable ``(request, invocation) -> decision | bool | None``."""

    pure = False

    def __init__(self, fn: PermissionCallable) -> None:
        self.fn = fn

    def decide(self, request: Any, invocation: Mapping[str, Any]) -> Any:
        decision = self.fn(request, invocation)
        if decision is None or decision is False:
            return None
        if isinstance(decision, PermissionDecisionReject) or decision.__class__.__name__.startswith(
            "PermissionDecisionDenied"
        ):
            return None
        return decision

    def describe(self) -> str:
        return getattr(self.fn, "__qualname__", repr(self.fn))


class _AnyOf(PermissionPolicy):
    def __init__(self, policies: Iterable[PermissionPolicy]) -> None:
        self.policies = tuple(policies)
        self.pure = all(policy.pure for policy in self.policies)

    def bind(self, agent: AgentDefinition) -> PermissionPolicy:
        return _AnyOf(policy.bind(agent) for policy in self.policies)

    def decide(self, request: Any, invocation: Mapping[str, Any]) -> Any:
        for policy in self.policies:
            decision = policy.decide(request, invocation)
            if decision is not None and decision is not False:
                return decision
        return None

    def describe(self) -> str:
        return f"any_of({', '.join(policy.describe() for policy in self.policies)})"


def deny_all() -> PermissionPolicy:
    """Rejects every request and logs it (the default)."""
    return _DenyAll()


def allow_all() -> PermissionPolicy:
    """Approves every request. Reserve for trusted, non-user-facing agents."""
    return _AllowAll()


def allow_kinds(*kinds: str) -> PermissionPolicy:
    """Approves requests of the given kinds, for example ``"read"`` or ``"custom-tool"``."""
    return _AllowKinds(kinds)


def allow_tools(*names: str) -> PermissionPolicy:
    """Approves custom-tool and MCP requests for the named tools."""
    return _AllowTools(names)


def allow_mcp_servers(*servers: str) -> PermissionPolicy:
    """Approves every tool of the named MCP servers."""
    return _AllowMcpServers(servers)


def allow_read_only() -> PermissionPolicy:
    """Approves reads and MCP tools that declare themselves read-only."""
    return _AllowReadOnly()


def allow_registered_tools() -> PermissionPolicy:
    """Approves the agent's own registered tools and nothing else."""
    return _AllowRegisteredTools()


def any_of(*policies: PermissionPolicy | PermissionCallable) -> PermissionPolicy:
    """Approves a request when any member approves it."""
    return _AnyOf(as_policy(policy) for policy in policies)


def as_policy(spec: Any) -> PermissionPolicy:
    """Normalize a policy object, callable, string, or settings mapping to a policy.

    Strings: ``"deny"`` / ``"deny_all"``, ``"allow_all"``, ``"allow_registered_tools"``.
    Mappings use settings keys: ``ALLOW_ALL``, ``ALLOW_KINDS``, ``ALLOW_TOOLS``,
    ``ALLOW_MCP_SERVERS``, ``ALLOW_READ_ONLY``, ``ALLOW_REGISTERED_TOOLS``.
    """
    if spec is None:
        return deny_all()
    if isinstance(spec, PermissionPolicy):
        return spec
    if isinstance(spec, str):
        named = {
            "deny": deny_all,
            "deny_all": deny_all,
            "allow_all": allow_all,
            "allow_read_only": allow_read_only,
            "allow_registered_tools": allow_registered_tools,
        }
        if spec not in named:
            raise CopilotConfigurationError(
                f"Unknown permission policy {spec!r}; expected one of {sorted(named)}"
            )
        return named[spec]()
    if isinstance(spec, Mapping):
        unknown = set(spec) - {
            "ALLOW_ALL",
            "ALLOW_KINDS",
            "ALLOW_TOOLS",
            "ALLOW_MCP_SERVERS",
            "ALLOW_READ_ONLY",
            "ALLOW_REGISTERED_TOOLS",
        }
        if unknown:
            raise CopilotConfigurationError(f"Unknown permission policy keys {sorted(unknown)}")
        if spec.get("ALLOW_ALL"):
            return allow_all()
        members: list[PermissionPolicy] = []
        if spec.get("ALLOW_KINDS"):
            members.append(allow_kinds(*spec["ALLOW_KINDS"]))
        if spec.get("ALLOW_TOOLS"):
            members.append(allow_tools(*spec["ALLOW_TOOLS"]))
        if spec.get("ALLOW_MCP_SERVERS"):
            members.append(allow_mcp_servers(*spec["ALLOW_MCP_SERVERS"]))
        if spec.get("ALLOW_READ_ONLY"):
            members.append(allow_read_only())
        if spec.get("ALLOW_REGISTERED_TOOLS"):
            members.append(allow_registered_tools())
        return any_of(*members) if members else deny_all()
    if callable(spec):
        return _Callable(spec)
    raise CopilotConfigurationError(f"Unsupported permission policy {spec!r}")


def approves_everything(policy: PermissionPolicy) -> bool:
    """True when the policy (or any member) is :func:`allow_all`."""
    if isinstance(policy, _AllowAll):
        return True
    if isinstance(policy, _AnyOf):
        return any(approves_everything(member) for member in policy.policies)
    return False
