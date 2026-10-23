"""The ``COPILOT`` settings dict, merged with defaults and rebuilt on ``setting_changed``."""

from __future__ import annotations

import tempfile
from collections.abc import Mapping
from pathlib import Path
from typing import Any

from django.conf import settings as django_settings

CONNECTIONS = ("stdio", "uri", "tcp", "inprocess")

DEFAULTS: dict[str, Any] = {
    "RUNTIME": {
        "CONNECTION": "stdio",
        "URL": None,
        "CONNECTION_TOKEN": None,
        "CLI_PATH": None,
        "PORT": 0,
        "BASE_DIRECTORY": None,
        "LOG_LEVEL": "info",
        "START": "lazy",
        "REQUEST_TIMEOUT": 300,
        "MODE": "copilot-cli",
    },
    "AUTH": {
        "GITHUB_TOKEN": None,
        "TOKEN_RESOLVER": None,
    },
    "DEFAULTS": {
        "MODEL": None,
        "REASONING_EFFORT": None,
        "PERMISSIONS": "deny",
        "WORKING_DIRECTORY": "isolated",
    },
    "MCP_SERVERS": {},
    "PROVIDERS": {},
    "AGENTS": {},
    "HOOKS": [],
    "TELEMETRY": None,
}

AGENT_KEYS = frozenset(
    {
        "INSTRUCTIONS",
        "INSTRUCTIONS_MODE",
        "MODEL",
        "REASONING_EFFORT",
        "PROVIDER",
        "TOOLS",
        "MCP_SERVERS",
        "AVAILABLE_TOOLS",
        "EXCLUDED_TOOLS",
        "EXCLUDED_BUILT_IN_AGENTS",
        "BUILT_IN_TOOLS",
        "TOOL_SEARCH",
        "WORKING_DIRECTORY",
        "ADDITIONAL_DIRECTORIES",
        "PERMISSIONS",
        "HOOKS",
        "TOKEN_RESOLVER",
        "TIMEOUT",
    }
)

_NESTED = ("RUNTIME", "AUTH", "DEFAULTS")
_MAPPINGS = ("MCP_SERVERS", "PROVIDERS", "AGENTS")


class CopilotSettings:
    """Read-only view of ``settings.COPILOT`` merged with :data:`DEFAULTS`."""

    def __init__(self, raw: Mapping[str, Any] | None) -> None:
        raw = dict(raw or {})
        self.raw = raw
        merged: dict[str, Any] = {}
        for key, default in DEFAULTS.items():
            value = raw.get(key, default)
            if key in _NESTED and isinstance(value, Mapping):
                value = {**default, **value}
            merged[key] = value
        self._values = merged

    def __getattr__(self, key: str) -> Any:
        try:
            return self._values[key]
        except KeyError:
            raise AttributeError(key) from None

    @property
    def base_directory(self) -> Path:
        """Runtime state root; also the parent of isolated agent workspaces."""
        configured = self.RUNTIME.get("BASE_DIRECTORY")
        if configured:
            return Path(configured)
        base_dir = getattr(django_settings, "BASE_DIR", None)
        if base_dir:
            return Path(base_dir) / ".copilot"
        return Path(tempfile.gettempdir()) / "copilot-django"

    def agent_overrides(self, name: str) -> Mapping[str, Any]:
        agents = self.AGENTS if isinstance(self.AGENTS, Mapping) else {}
        return agents.get(name) or {}


def validate(raw: Any) -> list[str]:
    """Return problems with ``settings.COPILOT`` (used by system check ``copilot.E001``)."""
    if raw is None:
        return []
    if not isinstance(raw, Mapping):
        return ["COPILOT must be a dict"]
    errors = [f"Unknown key COPILOT[{key!r}]" for key in raw if key not in DEFAULTS]
    for key in _NESTED:
        value = raw.get(key)
        if value is None:
            continue
        if not isinstance(value, Mapping):
            errors.append(f"COPILOT[{key!r}] must be a dict")
            continue
        errors += [f"Unknown key COPILOT[{key!r}][{sub!r}]" for sub in value if sub not in DEFAULTS[key]]
    for key in _MAPPINGS:
        if key in raw and not isinstance(raw[key], Mapping):
            errors.append(f"COPILOT[{key!r}] must be a dict")
    if "HOOKS" in raw and not isinstance(raw["HOOKS"], list | tuple):
        errors.append("COPILOT['HOOKS'] must be a list")
    if raw.get("TELEMETRY") is not None and not isinstance(raw["TELEMETRY"], Mapping):
        errors.append("COPILOT['TELEMETRY'] must be a dict or None")

    runtime = {**DEFAULTS["RUNTIME"], **(raw.get("RUNTIME") or {})} if isinstance(raw.get("RUNTIME") or {}, Mapping) else {}
    if runtime:
        if runtime["CONNECTION"] not in CONNECTIONS:
            errors.append(f"COPILOT['RUNTIME']['CONNECTION'] must be one of {', '.join(CONNECTIONS)}")
        if runtime["CONNECTION"] == "uri" and not runtime["URL"]:
            errors.append("COPILOT['RUNTIME']['URL'] is required when CONNECTION is 'uri'")
        if runtime["START"] not in ("lazy", "eager"):
            errors.append("COPILOT['RUNTIME']['START'] must be 'lazy' or 'eager'")
        timeout = runtime["REQUEST_TIMEOUT"]
        if not isinstance(timeout, int | float) or isinstance(timeout, bool) or timeout <= 0:
            errors.append("COPILOT['RUNTIME']['REQUEST_TIMEOUT'] must be a positive number")

    agents = raw.get("AGENTS")
    if isinstance(agents, Mapping):
        for name, overrides in agents.items():
            if not isinstance(overrides, Mapping):
                errors.append(f"COPILOT['AGENTS'][{name!r}] must be a dict")
                continue
            errors += [
                f"Unknown key COPILOT['AGENTS'][{name!r}][{key!r}]"
                for key in overrides
                if key not in AGENT_KEYS
            ]
    return errors


_cached: CopilotSettings | None = None


def get_settings() -> CopilotSettings:
    global _cached
    if _cached is None:
        _cached = CopilotSettings(getattr(django_settings, "COPILOT", None))
    return _cached


def reload() -> None:
    global _cached
    _cached = None
