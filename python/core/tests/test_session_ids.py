from copilot_agents import SHARED, derive_session_id


def test_matches_java_vector():
    # Computed with java-core's CopilotAgentInvoker.sessionId algorithm (jshell).
    assert (
        derive_session_id("com.example.SupportAgent", "support", "ticket-42", "user-7")
        == "agent-d079d6accfc74f10baa1efd9c90b2e87fa731081fd74ac20698fd5a0b7b4ad58"
    )


def test_shared_partition_equals_unpartitioned():
    assert derive_session_id("t", "n", "c", SHARED) == derive_session_id("t", "n", "c", None)


def test_partition_changes_id():
    assert derive_session_id("t", "n", "c", "1") != derive_session_id("t", "n", "c", "2")
