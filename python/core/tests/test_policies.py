import pytest
from copilot.generated.rpc import PermissionDecisionApproveOnce
from copilot.generated.session_events import PermissionRequestCustomTool, PermissionRequestMcp

from copilot_agents import AgentDefinition, CopilotConfigurationError, policies, tool


def custom(name):
    return PermissionRequestCustomTool(tool_description="", tool_name=name, args={}, tool_call_id="1")


def mcp(server, name, read_only=False):
    return PermissionRequestMcp(server_name=server, tool_name=name, read_only=read_only, tool_call_id="1", tool_title=name)


def approved(policy, request):
    return isinstance(policy(request, {}), PermissionDecisionApproveOnce)


def test_deny_all_rejects():
    assert not approved(policies.deny_all(), custom("x"))


def test_allow_tools_matches_custom_and_mcp():
    policy = policies.allow_tools("lookup", "github/get_issue")
    assert approved(policy, custom("lookup"))
    assert not approved(policy, custom("other"))
    assert approved(policy, mcp("github", "get_issue"))
    assert not approved(policy, mcp("github", "delete_repo"))


def test_allow_mcp_servers_and_read_only():
    assert approved(policies.allow_mcp_servers("docs"), mcp("docs", "anything"))
    assert approved(policies.allow_read_only(), mcp("x", "y", read_only=True))
    assert not approved(policies.allow_read_only(), mcp("x", "y"))


def test_allow_kinds_validates():
    with pytest.raises(CopilotConfigurationError):
        policies.allow_kinds("nonsense")
    assert approved(policies.allow_kinds("custom-tool"), custom("any"))


def test_callable_bool_shorthand_and_any_of():
    policy = policies.any_of(lambda req, inv: req.tool_name == "a", policies.allow_tools("b"))
    assert approved(policy, custom("a"))
    assert approved(policy, custom("b"))
    assert not approved(policy, custom("c"))


def test_as_policy_from_settings_mapping():
    policy = policies.as_policy({"ALLOW_TOOLS": ["t"], "ALLOW_MCP_SERVERS": ["docs"]})
    assert approved(policy, custom("t"))
    assert approved(policy, mcp("docs", "x"))
    assert not approved(policy, custom("u"))
    with pytest.raises(CopilotConfigurationError):
        policies.as_policy({"ALLOW_EVERYTHING": True})
    with pytest.raises(CopilotConfigurationError):
        policies.as_policy("yolo")


def test_allow_registered_tools_binds_to_agent():
    @tool
    def ping() -> str:
        """Ping."""
        return "pong"

    definition = AgentDefinition(name="a", tools=[ping], permissions="allow_registered_tools")
    assert approved(definition.permissions, custom("ping"))
    assert not approved(definition.permissions, custom("pong"))


def test_approves_everything():
    assert policies.approves_everything(policies.as_policy({"ALLOW_ALL": True}))
    assert policies.approves_everything(policies.any_of(policies.allow_all()))
    assert not policies.approves_everything(policies.allow_tools("x"))
