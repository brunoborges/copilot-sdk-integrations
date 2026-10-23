from __future__ import annotations

import pytest

from copilot_agents import (
    AgentContext,
    AgentDefinition,
    AgentInvoker,
    CopilotAgentHooks,
    ErrorRecoveryHooks,
    SdkErrorCategory,
    ToolError,
    ToolResultCategory,
    tool,
)
from copilot_agents.recovery import DEFAULT_ERROR_NOTE, DEFAULT_PERMISSION_NOTE

CTX = AgentContext(agent_name="agent", session_id="s1")


def result(tool_name, text, session="s1"):
    return {"sessionId": session, "toolName": tool_name, "toolResult": text}


def failure(tool_name, error, session="s1"):
    return {"sessionId": session, "toolName": tool_name, "error": error}


def test_classifies_tool_results_by_phrase():
    hooks = ErrorRecoveryHooks()
    assert hooks.classify_tool_result("open: Permission Denied") is ToolResultCategory.PERMISSION_DENIED
    assert hooks.classify_tool_result("bash: rg: command not found") is ToolResultCategory.SHELL_ERROR
    assert hooks.classify_tool_result("done\n<exited with exit code 0>") is ToolResultCategory.NORMAL
    assert hooks.classify_tool_result(None) is ToolResultCategory.NORMAL


def test_notes_errors_without_changing_the_result():
    hooks = ErrorRecoveryHooks()
    assert hooks.on_post_tool_use(result("bash", "No such file or directory"), CTX) == {
        "additionalContext": DEFAULT_ERROR_NOTE
    }
    assert hooks.on_post_tool_use(result("view", {"textResultForLlm": "EACCES"}), CTX) == {
        "additionalContext": DEFAULT_PERMISSION_NOTE
    }
    assert hooks.on_post_tool_use(result("bash", "hello"), CTX) is None
    assert hooks.on_post_tool_use_failure(failure("lookup", "Order not found"), CTX) == {
        "additionalContext": DEFAULT_ERROR_NOTE
    }


def test_caps_consecutive_failures_per_session_and_tool():
    hooks = ErrorRecoveryHooks(max_consecutive_failures=2)
    assert hooks.on_post_tool_use_failure(failure("bash", "boom"), CTX)
    assert hooks.on_post_tool_use_failure(failure("bash", "boom"), CTX)
    assert hooks.on_post_tool_use_failure(failure("bash", "boom"), CTX) is None
    assert hooks.on_post_tool_use_failure(failure("view", "boom"), CTX)
    assert hooks.on_post_tool_use_failure(failure("bash", "boom", session="s2"), CTX)
    assert hooks.on_post_tool_use(result("bash", "ok"), CTX) is None
    assert hooks.on_post_tool_use_failure(failure("bash", "boom"), CTX)


def test_session_end_forgets_counts_and_zero_disables_the_cap():
    hooks = ErrorRecoveryHooks(max_consecutive_failures=1)
    assert hooks.on_post_tool_use_failure(failure("bash", "boom"), CTX)
    assert hooks.on_post_tool_use_failure(failure("bash", "boom"), CTX) is None
    hooks.on_session_end({"sessionId": "s1"}, CTX)
    assert hooks.on_post_tool_use_failure(failure("bash", "boom"), CTX)
    unlimited = ErrorRecoveryHooks(max_consecutive_failures=0)
    assert all(unlimited.on_post_tool_use_failure(failure("bash", "boom"), CTX) for _ in range(10))


def test_retries_transient_sdk_errors_and_skips_the_rest():
    hooks = ErrorRecoveryHooks(retry_count=3)
    assert hooks.classify_sdk_error("Request timed out") is SdkErrorCategory.TRANSIENT
    assert hooks.classify_sdk_error("HTTP 401 Unauthorized") is SdkErrorCategory.CLIENT_ERROR
    assert hooks.classify_sdk_error("weird", recoverable=True) is SdkErrorCategory.TRANSIENT
    assert hooks.classify_sdk_error("weird") is SdkErrorCategory.NON_RECOVERABLE
    assert hooks.on_error_occurred({"error": "429 Too Many Requests"}, CTX) == {
        "errorHandling": "retry",
        "retryCount": 3,
    }
    assert hooks.on_error_occurred({"error": "400 bad request"}, CTX) == {
        "errorHandling": "skip",
        "userNotification": "Error occurred — continuing investigation.",
    }
    no_opinion = ErrorRecoveryHooks(unrecoverable_handling=None)
    assert no_opinion.on_error_occurred({"error": "400 bad request"}, CTX) is None
    assert ErrorRecoveryHooks(unrecoverable_handling="abort", user_notification=None).on_error_occurred(
        {"error": "boom"}, CTX
    ) == {"errorHandling": "abort"}


def test_configuration_is_validated():
    custom = ErrorRecoveryHooks(permission_phrases=["Not Authorized"], shell_error_phrases=["fatal:"])
    assert custom.classify_tool_result("permission denied") is ToolResultCategory.NORMAL
    assert custom.classify_tool_result("401 NOT AUTHORIZED") is ToolResultCategory.PERMISSION_DENIED
    for kwargs in (
        {"max_consecutive_failures": -1},
        {"retry_count": 0},
        {"unrecoverable_handling": "explode"},
        {"error_note": " "},
    ):
        with pytest.raises(ValueError):
            ErrorRecoveryHooks(**kwargs)
    with pytest.raises(TypeError):
        ErrorRecoveryHooks(permission_phrases="denied")


@tool
def flaky() -> str:
    """Always fails."""
    raise ToolError("cat: data.csv: No such file or directory")


def test_runs_as_an_agent_hook(fake, runtime):
    notes = []

    class Spy(CopilotAgentHooks):
        def __init__(self):
            self.inner = ErrorRecoveryHooks()

        def on_post_tool_use_failure(self, input, ctx):
            output = self.inner.on_post_tool_use_failure(input, ctx)
            notes.append(output)
            return output

    fake.agent("support").calls_tool("flaky", {}).replies("recovered")
    invoker = AgentInvoker(AgentDefinition(name="support", tools=[flaky], hooks=[Spy()], permissions="allow_registered_tools"), runtime)
    assert invoker.invoke("x") == "recovered"
    assert notes == [{"additionalContext": DEFAULT_ERROR_NOTE}]
