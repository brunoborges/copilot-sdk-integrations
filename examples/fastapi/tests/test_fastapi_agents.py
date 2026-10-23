import pytest
from fastapi.testclient import TestClient

from copilot_fastapi.testing import copilot_test_runtime
from copilot_agents import CopilotInvocationError
from workshop_examples.agents import Run
from workshop_examples.domain import AccessibilityReport, ApprovedFacts, Exhibit
from workshop_fastapi.app import create_app


def exhibit_data():
    return {
        "title": "Apollo 11",
        "narrative": " ".join(["Exploration"] * 110),
        "questions": ["What would you explore?", "How would you prepare?", "Who would you bring?"],
    }


def test_fastapi_lifecycle_and_museum_tool(tmp_path):
    run = Run(ApprovedFacts(facts=["Apollo 11 launched July 16, 1969."]), tmp_path)
    app, copilot, agents = create_app(run)
    with copilot_test_runtime(copilot) as fake:
        fake.agent("museum").calls_tool("approved_fact_lookup", {}).replies_json(exhibit_data())
        with TestClient(app) as client:
            assert client.get("/copilot/health").status_code == 200
            exhibit = copilot.get(agents["museum"]).ask("Create exhibit.", result=Exhibit)
            assert exhibit.title == "Apollo 11"
        assert run.called == ["approved_fact_lookup"]
        assert all(call.ok for call in fake.tool_calls("museum"))


def test_fastapi_accessibility_tool_composition(tmp_path):
    (tmp_path / "page-current.yml").write_text('- heading "Museum" [level=1]')
    run = Run(ApprovedFacts(facts=["Approved."]), tmp_path, target="https://example.com/")
    app, copilot, agents = create_app(run)
    findings = [
        {"title": name, "evidence": "Observed example", "criterion": criterion, "remediation": "Fix example"}
        for name, criterion in [("Missing alt", "1.1.1"), ("Headings", "1.3.1"), ("Button name", "4.1.2")]
    ]
    with copilot_test_runtime(copilot) as fake:
        script = fake.agent("accessibility").calls_tool("read_latest_accessibility_snapshot", {})
        for finding in findings:
            script.calls_tool("accessibility_rule_lookup", {"query": finding["criterion"]})
        script.replies_json({"findings": findings})
        with TestClient(app):
            result = copilot.get(agents["accessibility"]).ask("Review target.", result=AccessibilityReport)
        assert len(result.findings) == 3
        assert all(call.ok for call in fake.tool_calls("accessibility"))
        assert run.looked_up == {"1.1.1", "1.3.1", "4.1.2"}


def test_fastapi_rejects_invalid_structured_reply(tmp_path):
    run = Run(ApprovedFacts(facts=["Approved."]), tmp_path)
    app, copilot, agents = create_app(run)
    with copilot_test_runtime(copilot) as fake:
        fake.agent("museum").replies_json({**exhibit_data(), "narrative": "Too short."})
        with TestClient(app), pytest.raises(CopilotInvocationError, match="100-140"):
            copilot.get(agents["museum"]).ask("Create exhibit.", result=Exhibit)


def test_fastapi_cli_research_and_generation(tmp_path, monkeypatch, capsys):
    import asyncio
    from argparse import Namespace
    from workshop_fastapi import __main__ as entry

    original_create = create_app
    runtimes = []
    def scripted_app(run):
        app, copilot, agents = original_create(run)
        manager = copilot_test_runtime(copilot)
        fake = manager.__enter__()
        runtimes.append(manager)
        if run.research is None:
            fake.agent("research").replies_json({
                "summary": "Supplemental context.",
                "sources": [{"title": "Apollo 11", "url": "https://en.wikipedia.org/wiki/Apollo_11"}],
            })
        else:
            fake.agent("museum").calls_tool("approved_fact_lookup", {}).calls_tool(
                "approved_wikipedia_fact_lookup", {},
            ).replies_json(exhibit_data())
        return app, copilot, agents

    monkeypatch.setattr(entry, "arguments", lambda: Namespace(
        agent="museum", facts="apollo11", facts_file=None, url=None, research=True, html=tmp_path / "exhibit.html",
    ))
    monkeypatch.setattr(entry, "create_app", scripted_app)
    try:
        asyncio.run(entry.main())
    finally:
        for manager in reversed(runtimes):
            manager.__exit__(None, None, None)
    assert len(runtimes) == 2
    assert "Consulted Wikipedia sources" in capsys.readouterr().out
    assert (tmp_path / "exhibit.html").exists()


def test_fastapi_external_settings_and_resource_prompts(tmp_path, monkeypatch):
    monkeypatch.setenv("COPILOT_AGENTS", '{"museum":{"model":"configured-model","timeout":45}}')
    monkeypatch.setenv("WORKSHOP_BROWSER", "firefox")
    monkeypatch.setenv("WORKSHOP_HEADLESS", "false")
    run = Run(ApprovedFacts(facts=["Approved."]), tmp_path)
    app, copilot, agents = create_app(run)
    definition = copilot.definition(agents["museum"])
    assert definition.model == "configured-model"
    assert definition.timeout == 45
    assert "museum exhibit curator" in definition.instructions
    review = copilot.definition(agents["accessibility"])
    arguments = review.mcp_servers["playwright"]["args"]
    assert "--browser=firefox" in arguments
    assert "--headless" not in arguments
