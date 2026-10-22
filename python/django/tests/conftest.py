import pytest

from copilot_django import registry


@pytest.fixture
def isolated_registry():
    """Lets a test define throwaway agents and tools without leaking them."""
    agents, tools, duplicates = dict(registry.agents), dict(registry.tools), list(registry.duplicates)
    yield registry
    registry.agents.clear()
    registry.agents.update(agents)
    registry.tools.clear()
    registry.tools.update(tools)
    registry.duplicates[:] = duplicates
    registry.clear_cache()


@pytest.fixture(autouse=True)
def _reset_tool_calls():
    from shop import copilot_tools

    copilot_tools.calls.clear()
