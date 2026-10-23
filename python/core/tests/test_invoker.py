from __future__ import annotations

import asyncio
import threading

import pytest
from pydantic import BaseModel

from copilot_agents import (
    AgentDefinition,
    AgentInvoker,
    CopilotAgentHooks,
    CopilotConversationNotFound,
    CopilotInvocationError,
    CopilotReply,
    StaticTokenResolver,
    ToolContext,
    ToolError,
    current_invocation,
    tool,
)

seen: dict[str, object] = {}


@tool
def lookup_order(order_id: int, ctx: ToolContext) -> str:
    """Look up an order."""
    seen["thread"] = threading.get_ident()
    seen["user"] = ctx.user
    seen["invocation"] = current_invocation()
    return f"order {order_id}: shipped"


@tool
def explode() -> str:
    """Always fails."""
    raise RuntimeError("secret detail")


@tool
def refuse() -> str:
    """Fails with a message for the model."""
    raise ToolError("Order is locked.")


class Summary(BaseModel):
    title: str
    priority: int


def make(runtime, **kwargs):
    kwargs.setdefault("name", "support")
    kwargs.setdefault("tools", [lookup_order, explode, refuse])
    kwargs.setdefault("permissions", "allow_registered_tools")
    return AgentInvoker(AgentDefinition(**kwargs), runtime)


def test_one_shot_is_ephemeral(fake, runtime):
    fake.agent("support").replies("hello")
    assert make(runtime).invoke("hi") == "hello"
    assert fake.prompts("support") == ["hi"]
    assert fake.sessions("support") == []
    assert fake.deleted == fake.created


def test_tool_runs_on_caller_thread_with_context(fake, runtime):
    fake.agent("support").calls_tool("lookup_order", {"order_id": 7}).replies("done")
    reply = make(runtime).invoke("where?", result=CopilotReply, user="alice")
    assert seen["thread"] == threading.get_ident()
    assert seen["user"] == "alice"
    assert seen["invocation"].agent_name == "support"
    assert reply.text == "done"
    assert [c.name for c in reply.tool_calls] == ["lookup_order"]
    assert fake.tool_calls("support")[0].text == "order 7: shipped"


def test_conversation_created_then_resumed(fake, runtime):
    fake.agent("support").replies("one", "two")
    invoker = make(runtime)
    invoker.invoke("a", conversation_id="c1")
    invoker.invoke("b", conversation_id="c1")
    sid = invoker.session_id("c1")
    assert fake.created == [sid] and fake.resumed == [sid]
    assert invoker.exists("c1")
    invoker.delete("c1")
    assert not invoker.exists("c1")


def test_require_existing(fake, runtime):
    with pytest.raises(CopilotConversationNotFound):
        make(runtime).invoke("a", conversation_id="missing", require_existing=True)


def test_typed_result(fake, runtime):
    fake.agent("support").replies_json({"title": "Broken", "priority": 2})
    assert make(runtime).invoke("x", result=Summary) == Summary(title="Broken", priority=2)


def test_invalid_typed_result_raises(fake, runtime):
    fake.agent("support").replies("not json")
    with pytest.raises(CopilotInvocationError):
        make(runtime).invoke("x", result=Summary)


def test_tool_errors_are_sanitized(fake, runtime):
    fake.agent("support").calls_tool("explode").calls_tool("refuse").replies("ok")
    make(runtime).invoke("x")
    failed, refused = fake.tool_calls("support")
    assert not failed.ok and "secret" not in (failed.text or "")
    assert refused.text == "Order is locked."


def test_denied_tool_is_not_called(fake, runtime):
    seen.clear()
    fake.agent("support").calls_tool("lookup_order", {"order_id": 1}).replies("ok")
    make(runtime, permissions="deny").invoke("x")
    assert "thread" not in seen
    assert not fake.tool_calls("support")[0].approved


def test_runtime_failure_wrapped(fake, runtime):
    fake.agent("support").fails("boom")
    with pytest.raises(CopilotInvocationError):
        make(runtime).invoke("x")


def test_hooks_run_and_can_deny(fake, runtime):
    calls = []

    class Guard(CopilotAgentHooks):
        def on_pre_tool_use(self, input, ctx):
            calls.append((input["toolName"], ctx.agent_name, threading.get_ident()))
            return {"permissionDecision": "deny"}

    fake.agent("support").calls_tool("lookup_order", {"order_id": 1}).replies("ok")
    make(runtime, hooks=[Guard()]).invoke("x")
    assert calls == [("lookup_order", "support", threading.get_ident())]
    assert not fake.tool_calls("support")[0].approved


def test_token_resolver_requires_partition(fake, runtime):
    class PerUser:
        def resolve(self, identity):
            return "token"

        def partition_key(self, identity):
            return None

    with pytest.raises(Exception, match="partition key"):
        make(runtime, token_resolver=PerUser()).invoke("x")


def test_partition_isolates_conversations(fake, runtime):
    invoker = AgentInvoker(
        AgentDefinition(name="support"), runtime, partition_fallback=lambda identity: identity.user
    )
    assert invoker.session_id("c", user="1") != invoker.session_id("c", user="2")


def test_static_token_is_passed(fake, runtime):
    fake.agent("support").replies("ok")
    make(runtime, token_resolver=StaticTokenResolver("ghu_x")).invoke("x")
    assert fake.last_session_options("support")["github_token"] == "ghu_x"


def test_session_options_reflect_definition(fake, runtime):
    fake.agent("support").replies("ok")
    make(runtime, model="gpt-5", instructions="Be brief.", built_in_tools=False).invoke("x")
    options = fake.last_session_options("support")
    assert options["model"] == "gpt-5"
    assert "builtin:*" in options["excluded_tools"]
    assert options["system_message"]["content"] == "Be brief."


def test_tool_search_not_passed_by_default(fake, runtime):
    fake.agent("support").replies("ok")
    make(runtime).invoke("x")
    assert "tool_search" not in fake.last_session_options("support")


def test_tool_search_passed_to_create_and_resume(fake, runtime):
    fake.agent("support").replies("one", "two")
    invoker = make(runtime, tool_search={"enabled": True, "defer_threshold": 5})
    invoker.invoke("a", conversation_id="c1")
    created = fake.last_session_options("support")["tool_search"]
    invoker.invoke("b", conversation_id="c1")
    resumed = fake.last_session_options("support")["tool_search"]
    assert fake.resumed == [invoker.session_id("c1")]
    assert created == resumed == {"enabled": True, "defer_threshold": 5}
    assert created is not resumed and created is not invoker.definition.tool_search


def test_sync_invoke_rejects_running_loop(fake, runtime):
    async def main():
        make(runtime).invoke("x")

    with pytest.raises(RuntimeError, match="ainvoke"):
        asyncio.run(main())


def test_async_invoke(fake, runtime):
    fake.agent("support").calls_tool("lookup_order", {"order_id": 3}).replies("async ok")

    async def main():
        return await make(runtime).ainvoke("x", result=CopilotReply), threading.get_ident()

    reply, loop_thread = asyncio.run(main())
    assert reply.text == "async ok"
    # Sync tools run via the sync runner (a worker thread by default), never on the loop.
    assert seen["thread"] != loop_thread
    assert seen["invocation"].agent_name == "support"


def test_async_invoke_with_custom_sync_runner(fake, runtime):
    ran = []

    async def runner(fn):
        ran.append(True)
        return fn()

    fake.agent("support").calls_tool("lookup_order", {"order_id": 3}).replies("ok")
    invoker = AgentInvoker(
        AgentDefinition(name="support", tools=[lookup_order], permissions="allow_registered_tools"),
        runtime,
        sync_runner=runner,
    )
    asyncio.run(invoker.ainvoke("x"))
    assert ran


def test_release_working_directory(fake, runtime):
    released = []
    fake.agent("support").replies("ok")
    make(runtime, release_working_directory=lambda ctx: released.append(ctx.session_id)).invoke("x")
    assert released == fake.deleted
