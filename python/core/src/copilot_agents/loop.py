"""Fresh-session loop runner (the "Ralph loop" pattern).

Runs the same prompt repeatedly, each iteration in a new ephemeral session. State lives
on disk (for example ``IMPLEMENTATION_PLAN.md`` in the agent's working directory), not in
model context. With an :class:`AgentInvoker`, every iteration is an ordinary one-shot
``invoke`` (no ``conversation_id``), so the agent's permission policy, tools, and hooks
apply unchanged; nothing is approved implicitly.
"""

from __future__ import annotations

import asyncio
import inspect
import logging
import time
from collections.abc import Awaitable, Callable
from dataclasses import dataclass
from enum import StrEnum
from typing import Any, Protocol

from .invoker import AgentInvoker

log = logging.getLogger(__name__)

__all__ = ["LoopIteration", "LoopResult", "StopReason", "arun_loop", "run_loop"]


class StopReason(StrEnum):
    STOP_CONDITION = "stop_condition"
    """``stop_when`` matched a successful iteration."""
    CHECK = "check"
    """The between-iteration ``check`` returned false."""
    MAX_ITERATIONS = "max_iterations"
    FAILED = "failed"
    """An iteration raised (including ``CopilotTimeout``) and ``continue_on_failure`` is off."""
    CANCELLED = "cancelled"
    """The ``cancel`` event was set."""


@dataclass(frozen=True)
class LoopIteration:
    number: int
    """1-based iteration number."""
    prompt: str
    output: str | None = None
    error: BaseException | None = None
    duration: float = 0.0
    """Wall-clock seconds."""

    @property
    def succeeded(self) -> bool:
        return self.error is None


@dataclass(frozen=True)
class LoopResult:
    stop_reason: StopReason
    iterations: tuple[LoopIteration, ...]

    @property
    def last(self) -> LoopIteration | None:
        return self.iterations[-1] if self.iterations else None


class _Event(Protocol):
    def is_set(self) -> bool: ...


Prompt = str | Callable[[int], str]


def run_loop(
    agent: AgentInvoker | Callable[[int, str], str | None],
    prompt: Prompt,
    *,
    max_iterations: int,
    iteration_timeout: float | None = None,
    check: Callable[[LoopIteration], bool] | None = None,
    stop_when: Callable[[LoopIteration], bool] | None = None,
    on_iteration: Callable[[LoopIteration], None] | None = None,
    cancel: _Event | None = None,
    continue_on_failure: bool = False,
) -> LoopResult:
    """Run ``prompt`` up to ``max_iterations`` times, each in a fresh session, on this thread.

    ``agent`` is an :class:`AgentInvoker` or a ``step(iteration, prompt) -> output`` that
    must use a fresh session itself. ``prompt`` may be a function of the iteration number
    (for example to re-read ``PROMPT.md``). ``iteration_timeout`` (seconds) is passed to
    ``invoke`` and is only supported with an ``AgentInvoker``. ``check`` runs between
    iterations (for example the test suite) and stops the loop by returning false;
    ``stop_when`` stops it after a matching successful iteration. ``cancel`` (such as a
    :class:`threading.Event`) is checked before every iteration.
    """
    _validate(max_iterations, iteration_timeout)
    if isinstance(agent, AgentInvoker):
        invoker = agent

        def step(_: int, text: str) -> str | None:
            return invoker.invoke(text, timeout=iteration_timeout)
    else:
        if iteration_timeout is not None:
            raise ValueError(
                "iteration_timeout requires an AgentInvoker; enforce timeouts inside the step"
            )
        step = agent

    iterations: list[LoopIteration] = []
    for number in range(1, max_iterations + 1):
        if cancel is not None and cancel.is_set():
            return LoopResult(StopReason.CANCELLED, tuple(iterations))
        text = _prompt(prompt, number)
        started = time.monotonic()
        try:
            iteration = LoopIteration(
                number,
                text,
                _text(step(number, text)),
                None,
                time.monotonic() - started,
            )
        except Exception as exc:  # noqa: BLE001 - recorded on the iteration
            iteration = LoopIteration(
                number, text, None, exc, time.monotonic() - started
            )
        iterations.append(iteration)
        reason = _after(iteration, on_iteration, continue_on_failure)
        if (
            reason is None
            and iteration.succeeded
            and stop_when is not None
            and stop_when(iteration)
        ):
            reason = StopReason.STOP_CONDITION
        if (
            reason is None
            and number < max_iterations
            and check is not None
            and not check(iteration)
        ):
            reason = StopReason.CHECK
        if reason is not None:
            return LoopResult(reason, tuple(iterations))
    return LoopResult(StopReason.MAX_ITERATIONS, tuple(iterations))


async def arun_loop(
    agent: AgentInvoker | Callable[[int, str], Awaitable[str | None]],
    prompt: str | Callable[[int], str | Awaitable[str]],
    *,
    max_iterations: int,
    iteration_timeout: float | None = None,
    check: Callable[[LoopIteration], bool | Awaitable[bool]] | None = None,
    stop_when: Callable[[LoopIteration], bool | Awaitable[bool]] | None = None,
    on_iteration: Callable[[LoopIteration], None] | None = None,
    cancel: _Event | None = None,
    continue_on_failure: bool = False,
) -> LoopResult:
    """Async variant of :func:`run_loop`.

    ``iteration_timeout`` applies to any step (via :func:`asyncio.wait_for`). Cancelling the
    awaiting task aborts the running iteration and propagates :class:`asyncio.CancelledError`;
    a set ``cancel`` event stops the loop before the next iteration with ``CANCELLED``.
    """
    _validate(max_iterations, iteration_timeout)
    if isinstance(agent, AgentInvoker):
        invoker = agent

        async def step(_: int, text: str) -> Any:
            return await invoker.ainvoke(text, timeout=iteration_timeout)
    else:
        step = agent

    iterations: list[LoopIteration] = []
    for number in range(1, max_iterations + 1):
        if cancel is not None and cancel.is_set():
            return LoopResult(StopReason.CANCELLED, tuple(iterations))
        text = await _maybe(_prompt(prompt, number))
        started = time.monotonic()
        try:
            run = step(number, text)
            output = await (
                asyncio.wait_for(run, iteration_timeout)
                if iteration_timeout is not None
                else run
            )
            iteration = LoopIteration(
                number, text, _text(output), None, time.monotonic() - started
            )
        except Exception as exc:  # noqa: BLE001 - recorded on the iteration
            iteration = LoopIteration(
                number, text, None, exc, time.monotonic() - started
            )
        iterations.append(iteration)
        reason = _after(iteration, on_iteration, continue_on_failure)
        if (
            reason is None
            and iteration.succeeded
            and stop_when is not None
            and await _maybe(stop_when(iteration))
        ):
            reason = StopReason.STOP_CONDITION
        if (
            reason is None
            and number < max_iterations
            and check is not None
            and not await _maybe(check(iteration))
        ):
            reason = StopReason.CHECK
        if reason is not None:
            return LoopResult(reason, tuple(iterations))
    return LoopResult(StopReason.MAX_ITERATIONS, tuple(iterations))


def _validate(max_iterations: int, iteration_timeout: float | None) -> None:
    if not isinstance(max_iterations, int) or max_iterations < 1:
        raise ValueError("max_iterations must be an integer of at least 1")
    if iteration_timeout is not None and iteration_timeout <= 0:
        raise ValueError("iteration_timeout must be positive")


def _prompt(prompt: Any, number: int) -> Any:
    text = prompt(number) if callable(prompt) else prompt
    if not inspect.isawaitable(text) and not isinstance(text, str):
        raise TypeError("Loop prompt must be a string")
    return text


def _text(output: Any) -> str | None:
    return None if output is None else str(output)


async def _maybe(value: Any) -> Any:
    return await value if inspect.isawaitable(value) else value


def _after(
    iteration: LoopIteration,
    on_iteration: Callable[[LoopIteration], None] | None,
    continue_on_failure: bool,
) -> StopReason | None:
    if on_iteration is not None:
        try:
            on_iteration(iteration)
        except Exception:
            log.exception("Copilot loop listener failed")
    if not iteration.succeeded and not continue_on_failure:
        return StopReason.FAILED
    return None
