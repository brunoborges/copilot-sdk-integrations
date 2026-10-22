"""Registries of discovered agents and tools."""

from __future__ import annotations

import threading
from typing import TYPE_CHECKING, Any

from copilot_agents import CopilotTool

if TYPE_CHECKING:
    from .agents import CopilotAgent

_lock = threading.RLock()
agents: dict[str, type[CopilotAgent]] = {}
tools: dict[str, CopilotTool] = {}
duplicates: list[str] = []
_cache: dict[type, Any] = {}


def _key(obj: Any) -> str:
    target = getattr(obj, "func", obj)
    return f"{getattr(target, '__module__', '?')}.{getattr(target, '__qualname__', repr(target))}"


def register_agent(cls: type[CopilotAgent]) -> None:
    with _lock:
        existing = agents.get(cls.name)
        if existing is not None and _key(existing) != _key(cls):
            duplicates.append(
                f"Copilot agent name {cls.name!r} is used by {_key(existing)} and {_key(cls)}"
            )
            return
        agents[cls.name] = cls
        clear_cache()


def register_tool(tool: CopilotTool) -> None:
    with _lock:
        existing = tools.get(tool.name)
        if existing is not None and _key(existing) != _key(tool):
            duplicates.append(
                f"Copilot tool name {tool.name!r} is used by {_key(existing)} and {_key(tool)}"
            )
            return
        tools[tool.name] = tool
        clear_cache()


def get_agent(name: str) -> type[CopilotAgent]:
    try:
        return agents[name]
    except KeyError:
        raise LookupError(f"No Copilot agent named {name!r}; known: {sorted(agents)}") from None


def cached(cls: type, build: Any) -> Any:
    """Per-agent-class cache of the built definition and invoker."""
    with _lock:
        if cls not in _cache:
            _cache[cls] = build()
        return _cache[cls]


def clear_cache() -> None:
    with _lock:
        _cache.clear()
