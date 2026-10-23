from __future__ import annotations

import asyncio
import threading

import pytest
from copilot_agents import (
    AgentDefinition,
    AgentInvoker,
    StopReason,
    arun_loop,
    run_loop,
)


def make(runtime):
    return AgentInvoker(AgentDefinition(name="ralph", permissions="deny_all"), runtime)


def test_each_iteration_uses_a_fresh_session(fake, runtime):
    fake.agent("ralph").replies("one", "two", "three")
    seen = []

    result = run_loop(
        make(runtime), "Do the next task", max_iterations=3, on_iteration=seen.append
    )

    assert result.stop_reason is StopReason.MAX_ITERATIONS
    assert [i.output for i in result.iterations] == ["one", "two", "three"]
    assert [i.number for i in seen] == [1, 2, 3]
    assert len(set(fake.created)) == 3
    assert fake.deleted == fake.created
    assert fake.prompts("ralph") == ["Do the next task"] * 3


def test_prompt_function_is_evaluated_per_iteration(fake, runtime):
    fake.agent("ralph").replies("a", "b")

    run_loop(make(runtime), lambda n: f"prompt {n}", max_iterations=2)

    assert fake.prompts("ralph") == ["prompt 1", "prompt 2"]


def test_stop_condition_ends_the_loop(fake, runtime):
    fake.agent("ralph").replies("working", "DONE", "never")

    result = run_loop(
        make(runtime), "go", max_iterations=5, stop_when=lambda i: "DONE" in i.output
    )

    assert result.stop_reason is StopReason.STOP_CONDITION
    assert result.last.output == "DONE"
    assert len(result.iterations) == 2


def test_check_runs_only_between_iterations(fake, runtime):
    fake.agent("ralph").replies("a", "b", "c")
    checked = []

    def check(iteration):
        checked.append(iteration.number)
        return iteration.number < 2

    result = run_loop(make(runtime), "go", max_iterations=3, check=check)
    assert result.stop_reason is StopReason.CHECK
    assert checked == [1, 2]

    fake.agent("ralph").replies("x")
    checked.clear()
    result = run_loop(make(runtime), "go", max_iterations=1, check=check)
    assert result.stop_reason is StopReason.MAX_ITERATIONS
    assert checked == []


def test_failure_stops_unless_continue_on_failure():
    def step(number, prompt):
        if number == 1:
            raise RuntimeError("boom")
        return "ok"

    result = run_loop(step, "go", max_iterations=3)
    assert result.stop_reason is StopReason.FAILED
    assert isinstance(result.last.error, RuntimeError)
    assert not result.last.succeeded

    result = run_loop(step, "go", max_iterations=3, continue_on_failure=True)
    assert result.stop_reason is StopReason.MAX_ITERATIONS
    assert [i.succeeded for i in result.iterations] == [False, True, True]


def test_cancel_event_stops_before_next_iteration():
    cancel = threading.Event()

    def step(number, prompt):
        cancel.set()
        return "ok"

    result = run_loop(step, "go", max_iterations=5, cancel=cancel)
    assert result.stop_reason is StopReason.CANCELLED
    assert len(result.iterations) == 1


def test_listener_errors_do_not_change_the_outcome():
    def listener(_):
        raise RuntimeError("listener")

    result = run_loop(lambda n, p: "ok", "go", max_iterations=2, on_iteration=listener)
    assert result.stop_reason is StopReason.MAX_ITERATIONS


def test_validation():
    with pytest.raises(ValueError):
        run_loop(lambda n, p: "ok", "go", max_iterations=0)
    with pytest.raises(ValueError):
        run_loop(lambda n, p: "ok", "go", max_iterations=1, iteration_timeout=0)
    with pytest.raises(ValueError):
        run_loop(lambda n, p: "ok", "go", max_iterations=1, iteration_timeout=1)


def test_async_loop_with_invoker(fake, runtime):
    fake.agent("ralph").replies("one", "DONE")

    async def stop_when(iteration):
        return iteration.output == "DONE"

    result = asyncio.run(
        arun_loop(make(runtime), "go", max_iterations=5, stop_when=stop_when)
    )

    assert result.stop_reason is StopReason.STOP_CONDITION
    assert len(set(fake.created)) == 2


def test_async_iteration_timeout():
    async def step(number, prompt):
        if number == 1:
            await asyncio.sleep(5)
        return "ok"

    result = asyncio.run(
        arun_loop(
            step,
            "go",
            max_iterations=2,
            iteration_timeout=0.05,
            continue_on_failure=True,
        )
    )

    assert isinstance(result.iterations[0].error, TimeoutError)
    assert result.iterations[1].output == "ok"
