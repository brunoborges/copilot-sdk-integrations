import os

os.environ.setdefault("DJANGO_SETTINGS_MODULE", "workshop_django.settings")

import django
django.setup()

from django.core.management import call_command
from django.test import override_settings
from django.conf import settings
from copilot_django.test import copilot_test_runtime
from workshop_django.agents import create_agents
from workshop_examples.agents import Run
from workshop_examples.domain import AccessibilityReport, ApprovedFacts, Exhibit


def exhibit_data():
    return {
        "title": "Apollo 11",
        "narrative": " ".join(["Exploration"] * 110),
        "questions": ["What would you explore?", "How would you prepare?", "Who would you bring?"],
    }


def test_django_registered_agent_and_tool(tmp_path):
    run = Run(ApprovedFacts(facts=["Apollo 11 launched July 16, 1969."]), tmp_path)
    agents = create_agents(run)
    assert agents["museum"].instructions is None
    assert agents["museum"].instructions_template == "workshop/curator.txt"
    with copilot_test_runtime() as fake:
        fake.agent("museum").calls_tool("approved_fact_lookup", {}).replies_json(exhibit_data())
        result = agents["museum"]().ask("Create exhibit.", result=Exhibit)
        assert result.title == "Apollo 11"
        assert run.called == ["approved_fact_lookup"]
        assert all(call.ok for call in fake.tool_calls("museum"))


def test_django_management_command(capsys):
    with copilot_test_runtime() as fake:
        fake.agent("museum").calls_tool("approved_fact_lookup", {}).replies_json(exhibit_data())
        call_command("workshop", "museum", "--facts", "apollo11")
    output = capsys.readouterr()
    assert "# Apollo 11" in output.out
    assert "Unsupported claims require human review." in output.out
    assert "Educator-approved facts" in output.err


def test_django_accessibility_tool_composition(tmp_path):
    (tmp_path / "page-current.yml").write_text('- heading "Museum" [level=1]')
    run = Run(ApprovedFacts(facts=["Approved."]), tmp_path, target="https://example.com/")
    agents = create_agents(run)
    findings = [
        {"title": name, "evidence": "Observed example", "criterion": criterion, "remediation": "Fix example"}
        for name, criterion in [("Missing alt", "1.1.1"), ("Headings", "1.3.1"), ("Button name", "4.1.2")]
    ]
    with copilot_test_runtime() as fake:
        script = fake.agent("accessibility").calls_tool("read_latest_accessibility_snapshot", {})
        for finding in findings:
            script.calls_tool("accessibility_rule_lookup", {"query": finding["criterion"]})
        script.replies_json({"findings": findings})
        report = agents["accessibility"]().ask("Review target.", result=AccessibilityReport)
        assert len(report.findings) == 3
        assert run.looked_up == {"1.1.1", "1.3.1", "4.1.2"}
        assert all(call.ok for call in fake.tool_calls("accessibility"))


def test_django_management_command_separates_research_and_generation(capsys):
    with copilot_test_runtime() as fake:
        fake.agent("research").replies_json({
            "summary": "Supplemental lunar exploration context.",
            "sources": [{"title": "Apollo 11", "url": "https://en.wikipedia.org/wiki/Apollo_11"}],
        })
        fake.agent("museum").calls_tool("approved_fact_lookup", {}).calls_tool(
            "approved_wikipedia_fact_lookup", {},
        ).replies_json(exhibit_data())
        call_command("workshop", "museum", "--research")
        assert len(fake.created) == 2
    assert "Consulted Wikipedia sources" in capsys.readouterr().out


def test_django_native_settings_and_resource_prompts(tmp_path):
    configuration = {
        **settings.COPILOT,
        "AGENTS": {"museum": {"MODEL": "configured-model", "TIMEOUT": 45}},
    }
    with override_settings(COPILOT=configuration, WORKSHOP={"BROWSER": "firefox", "HEADLESS": False}):
        run = Run(ApprovedFacts(facts=["Approved."]), tmp_path)
        agents = create_agents(run)
        definition = agents["museum"].definition()
        assert definition.model == "configured-model"
        assert definition.timeout == 45
        assert "museum exhibit curator" in definition.instructions
        arguments = agents["accessibility"].definition().mcp_servers["playwright"]["args"]
        assert "--browser=firefox" in arguments
        assert "--headless" not in arguments
