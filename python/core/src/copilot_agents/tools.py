"""Tool adapter: plain Python callables become SDK ``Tool`` objects.

Parameters come from a single Pydantic model argument or are synthesized from the
signature's type hints. A parameter annotated with :class:`ToolContext` receives the
invocation context. Sync callables run through the invocation's dispatcher (the caller's
thread for sync callers), async callables run on the Copilot loop.
"""

from __future__ import annotations

import asyncio
import contextvars
import inspect
import json
import logging
import re
from collections.abc import Callable
from dataclasses import dataclass
from typing import Any, get_type_hints

from copilot import Tool, ToolInvocation, ToolResult
from pydantic import BaseModel, ValidationError, create_model

from . import context as _context
from .context import AgentContext
from .errors import CopilotConfigurationError
from .executor import Dispatcher

log = logging.getLogger("copilot_agents.tools")

GENERIC_FAILURE = "The tool failed. Detailed information is not available."
_NAME = re.compile(r"^[A-Za-z0-9_-]{1,64}$")


class ToolError(Exception):
    """Raise from a tool to return this message to the model as a failure result."""


@dataclass(frozen=True)
class ToolContext:
    """Passed to tools that declare a parameter annotated with ``ToolContext``."""

    agent: AgentContext
    invocation: ToolInvocation

    @property
    def user(self) -> Any:
        return self.agent.user

    @property
    def request(self) -> Any:
        return self.agent.request

    @property
    def agent_name(self) -> str:
        return self.agent.agent_name

    @property
    def conversation_id(self) -> str | None:
        return self.agent.conversation_id

    @property
    def session_id(self) -> str:
        return self.agent.session_id


def failure(message: str, error: str | None = None) -> ToolResult:
    return ToolResult(text_result_for_llm=message, result_type="failure", error=error or message)


def to_tool_result(value: Any) -> ToolResult:
    """``str`` → text; Pydantic, ``dict``, ``list`` → JSON; ``ToolResult`` passes through."""
    if isinstance(value, ToolResult):
        return value
    if value is None:
        return ToolResult(text_result_for_llm="")
    if isinstance(value, str):
        return ToolResult(text_result_for_llm=value)
    if isinstance(value, BaseModel):
        return ToolResult(text_result_for_llm=value.model_dump_json())
    return ToolResult(text_result_for_llm=json.dumps(value, default=str))


def _camel(name: str) -> str:
    return "".join(part.capitalize() for part in re.split(r"[^A-Za-z0-9]", name) if part)


def _run_with_context(agent: AgentContext, fn: Callable[[], Any]) -> Any:
    token = _context._current.set(agent)
    try:
        return fn()
    finally:
        _context._current.reset(token)


class CopilotTool:
    """A callable exposed to agents as an SDK tool. Calling it calls the function."""

    def __init__(
        self,
        func: Callable[..., Any],
        *,
        name: str | None = None,
        description: str | None = None,
        skip_permission: bool = False,
        overrides_built_in_tool: bool = False,
    ) -> None:
        target = inspect.unwrap(func)
        self.func = func
        self.name = name or target.__name__
        if not _NAME.match(self.name):
            raise CopilotConfigurationError(
                f"Invalid tool name {self.name!r}: use letters, digits, '_' or '-' (max 64)"
            )
        self.description = (description or inspect.getdoc(target) or "").strip()
        if not self.description:
            raise CopilotConfigurationError(
                f"Tool {self.name!r} needs a description (argument or docstring)"
            )
        self.skip_permission = skip_permission
        self.overrides_built_in_tool = overrides_built_in_tool
        self.is_async = inspect.iscoroutinefunction(func)
        self.context_param: str | None = None
        self.model_param: str | None = None

        hints = get_type_hints(target, include_extras=True)
        values: list[tuple[inspect.Parameter, Any]] = []
        for param in inspect.signature(func).parameters.values():
            if param.kind in (param.VAR_POSITIONAL, param.VAR_KEYWORD):
                raise CopilotConfigurationError(f"Tool {self.name!r}: *args/**kwargs are not supported")
            annotation = hints.get(param.name, Any)
            if annotation is ToolContext or (param.name == "ctx" and param.name not in hints):
                self.context_param = param.name
                continue
            values.append((param, annotation))

        if (
            len(values) == 1
            and inspect.isclass(values[0][1])
            and issubclass(values[0][1], BaseModel)
        ):
            self.model_param = values[0][0].name
            self.params_model: type[BaseModel] = values[0][1]
        else:
            fields: dict[str, Any] = {
                param.name: (
                    annotation,
                    ... if param.default is inspect.Parameter.empty else param.default,
                )
                for param, annotation in values
            }
            self.params_model = create_model(f"{_camel(self.name)}Params", **fields)

    def __call__(self, *args: Any, **kwargs: Any) -> Any:
        return self.func(*args, **kwargs)

    def __repr__(self) -> str:
        return f"<CopilotTool {self.name}>"

    @property
    def parameters(self) -> dict[str, Any]:
        return self.params_model.model_json_schema()

    def _arguments(self, parsed: BaseModel, tool_context: ToolContext) -> dict[str, Any]:
        if self.model_param is not None:
            kwargs: dict[str, Any] = {self.model_param: parsed}
        else:
            kwargs = {name: getattr(parsed, name) for name in type(parsed).model_fields}
        if self.context_param is not None:
            kwargs[self.context_param] = tool_context
        return kwargs

    async def execute(
        self,
        invocation: ToolInvocation,
        agent: AgentContext,
        dispatcher: Dispatcher,
        *,
        expose_errors: bool = False,
    ) -> ToolResult:
        try:
            parsed = self.params_model.model_validate(invocation.arguments or {})
        except ValidationError as exc:
            details = "\n".join(
                f"{'.'.join(map(str, err['loc']))}: {err['msg']}" if err["loc"] else err["msg"]
                for err in exc.errors()
            )
            return failure(f"Invalid tool arguments:\n{details}", str(exc))
        kwargs = self._arguments(parsed, ToolContext(agent=agent, invocation=invocation))
        try:
            if self.is_async:
                base = getattr(dispatcher, "context", None)
                ctx = base.copy() if base is not None else contextvars.copy_context()
                ctx.run(_context._current.set, agent)
                result = await asyncio.create_task(self.func(**kwargs), context=ctx)
            else:
                result = await dispatcher.call(lambda: _run_with_context(agent, lambda: self.func(**kwargs)))
        except ToolError as exc:
            return failure(str(exc))
        except Exception as exc:  # noqa: BLE001 - reported to the model as a failure
            log.exception(
                "Copilot tool %r failed (agent %s, session %s)",
                self.name,
                agent.agent_name,
                agent.session_id,
            )
            return failure(str(exc) if expose_errors else GENERIC_FAILURE, str(exc))
        return to_tool_result(result)

    def to_sdk_tool(
        self,
        agent: Callable[[], AgentContext],
        dispatcher: Callable[[], Dispatcher],
        *,
        expose_errors: bool = False,
    ) -> Tool:
        """Build the SDK tool; ``agent`` and ``dispatcher`` are read per call."""

        async def handler(invocation: ToolInvocation) -> ToolResult:
            return await self.execute(
                invocation, agent(), dispatcher(), expose_errors=expose_errors
            )

        return Tool(
            name=self.name,
            description=self.description,
            handler=handler,
            parameters=self.parameters,
            skip_permission=self.skip_permission,
            overrides_built_in_tool=self.overrides_built_in_tool,
        )


def tool(
    func: Callable[..., Any] | None = None,
    *,
    name: str | None = None,
    description: str | None = None,
    skip_permission: bool = False,
    overrides_built_in_tool: bool = False,
) -> Any:
    """Decorator (with or without arguments) that turns a callable into a :class:`CopilotTool`."""

    def decorate(fn: Callable[..., Any]) -> CopilotTool:
        return CopilotTool(
            fn,
            name=name,
            description=description,
            skip_permission=skip_permission,
            overrides_built_in_tool=overrides_built_in_tool,
        )

    return decorate(func) if func is not None else decorate


def as_tool(value: Any) -> CopilotTool:
    """Accept a :class:`CopilotTool` or wrap a plain callable."""
    if isinstance(value, CopilotTool):
        return value
    if callable(value):
        return CopilotTool(value)
    raise CopilotConfigurationError(f"Not a tool: {value!r}")
