"""Typed settings, bound from ``COPILOT_*`` environment variables (or ``.env``) with pydantic-settings.

Nested fields use ``__`` as the delimiter, for example ``COPILOT_RUNTIME__CONNECTION=uri`` or
``COPILOT_AUTH__GITHUB_TOKEN=...``. Mappings (``MCP_SERVERS``, ``PROVIDERS``, ``AGENTS``) can be
set as JSON, for example ``COPILOT_AGENTS='{"support": {"model": "gpt-5"}}'``.
"""

from __future__ import annotations

from collections.abc import Mapping
from datetime import timedelta
from pathlib import Path
from typing import Any, Literal

from pydantic import BaseModel, ConfigDict, Field, PositiveFloat, PositiveInt, SecretStr, field_validator, model_validator
from pydantic_settings import BaseSettings, SettingsConfigDict

Connection = Literal["stdio", "uri", "tcp", "inprocess"]


class RuntimeSettings(BaseModel):
    """How the process connects to the Copilot runtime."""

    model_config = ConfigDict(extra="forbid")

    connection: Connection = "stdio"
    url: str | None = None
    connection_token: SecretStr | None = None
    cli_path: str | None = None
    port: int = 0
    base_directory: Path | None = None
    log_level: str = "info"
    start: Literal["lazy", "eager"] = "lazy"
    request_timeout: PositiveFloat = 300.0
    mode: str = "copilot-cli"

    @model_validator(mode="after")
    def _url_for_uri(self) -> RuntimeSettings:
        if self.connection == "uri" and not self.url:
            raise ValueError("runtime.url is required when runtime.connection is 'uri'")
        return self


class AuthSettings(BaseModel):
    model_config = ConfigDict(extra="forbid")

    github_token: SecretStr | None = None
    token_resolver: str | None = Field(default=None, description="Dotted path to a CopilotTokenResolver")


class DefaultSettings(BaseModel):
    """Defaults applied to every agent unless its class or an override sets them."""

    model_config = ConfigDict(extra="forbid")

    model: str | None = None
    reasoning_effort: str | None = None
    permissions: str | dict[str, Any] = "deny"
    working_directory: str = "isolated"


class SessionSettings(BaseModel):
    """Session retention. Cleanup is off unless ``max_age`` is set.

    Durations accept seconds or ISO 8601 (``COPILOT_SESSIONS__MAX_AGE=P7D``).
    """

    model_config = ConfigDict(extra="forbid")

    max_age: timedelta | None = Field(
        default=None, description="Delete sessions created by this app that were not modified within this age"
    )
    cleanup_interval: timedelta | None = Field(
        default=None, description="Time between sweeps; defaults to half of max_age, between 1 minute and 1 hour"
    )

    @field_validator("max_age", "cleanup_interval", mode="before")
    @classmethod
    def _seconds(cls, value: object) -> object:
        if isinstance(value, str):
            try:
                return float(value)
            except ValueError:
                return value
        return value

    @field_validator("max_age", "cleanup_interval")
    @classmethod
    def _positive(cls, value: timedelta | None) -> timedelta | None:
        if value is not None and value <= timedelta(0):
            raise ValueError("must be a positive duration")
        return value


class ToolSearchSettings(BaseModel):
    """Runtime tool search override; unset fields keep the runtime default."""

    model_config = ConfigDict(extra="forbid")

    enabled: bool | None = None
    defer_threshold: PositiveInt | None = None

    @field_validator("defer_threshold", mode="before")
    @classmethod
    def _reject_bool(cls, value: Any) -> Any:
        if isinstance(value, bool):
            raise ValueError("defer_threshold must be an integer, not a bool")
        return value


class AgentOverrides(BaseModel):
    """Per-agent overrides; only fields that are set replace the agent's class attributes."""

    model_config = ConfigDict(extra="forbid")

    instructions: str | None = None
    instructions_mode: Literal["append", "replace", "customize"] | None = None
    model: str | None = None
    reasoning_effort: str | None = None
    provider: str | dict[str, Any] | None = None
    tools: list[str] | None = None
    mcp_servers: list[str] | dict[str, dict[str, Any]] | None = None
    available_tools: list[str] | None = None
    excluded_tools: list[str] | None = None
    excluded_built_in_agents: list[str] | None = None
    built_in_tools: bool | None = None
    tool_search: ToolSearchSettings | None = None
    working_directory: str | None = None
    additional_directories: list[str] | None = None
    permissions: str | dict[str, Any] | None = None
    hooks: list[str] | None = None
    token_resolver: str | None = None
    timeout: PositiveFloat | None = None
    message_source: str | None = None

    def values(self) -> Mapping[str, Any]:
        """The fields explicitly set."""
        values = {name: getattr(self, name) for name in self.model_fields_set}
        if isinstance(values.get("tool_search"), ToolSearchSettings):
            values["tool_search"] = values["tool_search"].model_dump(exclude_unset=True, exclude_none=True)
        return values


class CopilotSettings(BaseSettings):
    """Root settings object; pass an instance to :class:`~copilot_fastapi.Copilot`."""

    model_config = SettingsConfigDict(
        env_prefix="COPILOT_",
        env_nested_delimiter="__",
        env_file=".env",
        extra="ignore",
    )

    runtime: RuntimeSettings = Field(default_factory=RuntimeSettings)
    auth: AuthSettings = Field(default_factory=AuthSettings)
    defaults: DefaultSettings = Field(default_factory=DefaultSettings)
    sessions: SessionSettings = Field(default_factory=SessionSettings)
    mcp_servers: dict[str, dict[str, Any]] = Field(default_factory=dict)
    providers: dict[str, dict[str, Any]] = Field(default_factory=dict)
    agents: dict[str, AgentOverrides] = Field(default_factory=dict)
    hooks: list[str] = Field(default_factory=list, description="Dotted paths to global CopilotAgentHooks")
    telemetry: dict[str, Any] | None = None
    validate_on_startup: bool = True

    @property
    def base_directory(self) -> Path:
        """Runtime state root; also the parent of isolated agent workspaces."""
        return self.runtime.base_directory or Path.cwd() / ".copilot"

    def agent_overrides(self, name: str) -> Mapping[str, Any]:
        overrides = self.agents.get(name)
        return overrides.values() if overrides is not None else {}
