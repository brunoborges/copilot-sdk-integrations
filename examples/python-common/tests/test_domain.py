from argparse import Namespace
from types import SimpleNamespace

import pytest
from pydantic import ValidationError

from copilot_agents.tools import ToolError
from workshop_examples.agents import Run, accessibility_options, museum_options, research_options
from workshop_examples.cli import finish
from workshop_examples.domain import (
    AccessibilityReport, ApprovedFacts, Exhibit, FACT_SETS, Research,
    accessibility_html, exhibit_html, same_url, target_url,
)


def exhibit_data(words=110):
    return {
        "title": "Journey to the Moon",
        "narrative": " ".join(["Exploration"] * words),
        "questions": ["What would you explore?", "How would you prepare?", "Who would you bring?"],
    }


@pytest.mark.parametrize("words", [100, 140])
def test_narrative_boundaries(words):
    assert len(Exhibit.model_validate(exhibit_data(words)).narrative.split()) == words


@pytest.mark.parametrize("words", [0, 99, 141])
def test_narrative_rejects_invalid_lengths(words):
    with pytest.raises(ValidationError):
        Exhibit.model_validate(exhibit_data(words))


@pytest.mark.parametrize("changes", [
    {"title": "Two\nTitles"},
    {"questions": ["What?", "WHAT?", "Where?"]},
    {"questions": ["What?", "Where?", "Not a question"]},
    {"questions": ["What?", "Where?"]},
])
def test_invalid_exhibit_structure(changes):
    with pytest.raises(ValidationError):
        Exhibit.model_validate({**exhibit_data(), **changes})


@pytest.mark.parametrize("facts", [[], [" "], ["a" * 501], ["fact"] * 21])
def test_bounded_approved_facts(facts):
    with pytest.raises(ValidationError):
        ApprovedFacts(facts=facts)


def test_all_bundled_fact_sets_are_valid():
    for facts in FACT_SETS.values():
        assert ApprovedFacts(facts=facts).facts


def test_target_and_permission_scope(tmp_path):
    target = "https://example.com/exhibit?a=1#title"
    run = Run(ApprovedFacts(facts=["Approved."]), tmp_path, target=target)
    def request(url=target, **changes):
        values = {"kind": "mcp", "server_name": "playwright", "tool_name": "browser_navigate", "args": {"url": url}}
        return SimpleNamespace(**{**values, **changes})
    assert run.navigation_permission(request(), {})
    for url in [
        "https://other.example/exhibit?a=1#title", "http://example.com/exhibit?a=1#title",
        "https://example.com/exhibit?a=2#title", "https://example.com/exhibit?a=1",
        "https://user@example.com/exhibit?a=1#title",
    ]:
        assert not run.navigation_permission(request(url), {})
    assert not run.navigation_permission(request(kind="shell"), {})
    assert not run.navigation_permission(request(tool_name="browser_evaluate"), {})
    assert not run.navigation_permission(request(server_name="other"), {})
    assert not run.navigation_permission(request(args={"url": None}), {})
    assert same_url("https://EXAMPLE.com/", "https://example.com/")


@pytest.mark.parametrize("url", ["file:///etc/passwd", "https://", "https://user:pass@example.com", "https://example.com:bad", "https://example.com/a b"])
def test_invalid_url(url):
    with pytest.raises(ValueError):
        target_url(url)


def test_snapshot_is_fresh_bounded_and_not_symlink(tmp_path):
    run = Run(ApprovedFacts(facts=["Approved."]), tmp_path)
    with pytest.raises(ToolError):
        run.read_latest_accessibility_snapshot()
    stale = tmp_path.parent / "outside-snapshot.yml"
    stale.write_text("outside")
    (tmp_path / "page-link.yml").symlink_to(stale)
    (tmp_path / "page-large.yml").write_bytes(b"x" * 1_000_001)
    (tmp_path / "page-empty.yml").touch()
    with pytest.raises(ToolError):
        run.read_latest_accessibility_snapshot()
    (tmp_path / "page-current.yml").write_text('- heading "Museum" [level=1]')
    assert "Museum" in run.read_latest_accessibility_snapshot()
    assert run.called == ["read_latest_accessibility_snapshot"]


def test_catalog_lookup_and_unknown_criterion(tmp_path):
    run = Run(ApprovedFacts(facts=["Approved."]), tmp_path)
    assert run.accessibility_rule_lookup("image alternative")["criterion"] == "1.1.1"
    assert run.looked_up == {"1.1.1"}
    with pytest.raises(ToolError):
        run.accessibility_rule_lookup("not represented")


def test_exhibit_html_escapes_model_content():
    data = exhibit_data()
    data["title"] = "<script>alert('x')</script>"
    document = exhibit_html(Exhibit.model_validate(data))
    assert "<h1><script>" not in document
    assert "&lt;script&gt;" in document
    assert 'aria-live="polite"' in document
    assert "Unsupported claims require human review." in document


def test_missing_fact_call_prevents_publication(tmp_path, capsys):
    run = Run(ApprovedFacts(facts=["Approved."]), tmp_path)
    args = Namespace(html=None)
    with pytest.raises(ValueError, match="required fact lookup"):
        finish(args, run, Exhibit.model_validate(exhibit_data()))
    assert not capsys.readouterr().out


def test_html_never_overwrites(tmp_path):
    run = Run(ApprovedFacts(facts=["Approved."]), tmp_path)
    run.approved_fact_lookup()
    destination = tmp_path / "exhibit.html"
    destination.write_text("Keep me")
    with pytest.raises(FileExistsError):
        finish(Namespace(html=destination), run, Exhibit.model_validate(exhibit_data()))
    assert destination.read_text() == "Keep me"


def test_research_requires_real_wikipedia_urls():
    with pytest.raises(ValidationError):
        Research(summary="Research", sources=[{"title": "Fake", "url": "https://wikipedia.org.evil.test/wiki/Fake"}])


def test_agent_options_have_no_builtins_and_only_scoped_mcp(tmp_path):
    from copilot_agents.tools import CopilotTool as copilot_tool
    run = Run(ApprovedFacts(facts=["Approved."]), tmp_path, target="https://example.com/")
    museum = museum_options(run, copilot_tool)
    review = accessibility_options(run, copilot_tool)
    research = research_options()
    assert museum["built_in_tools"] is False
    assert review["built_in_tools"] is False
    assert research["built_in_tools"] is False
    assert review["mcp_servers"]["playwright"]["tools"] == ["browser_navigate"]
    assert research["mcp_servers"]["wikipedia"]["tools"] == ["search", "readArticle"]


def report_data():
    return {
        "findings": [
            {"title": title, "evidence": "Observed <input>", "criterion": criterion, "remediation": "Add semantics"}
            for title, criterion in [("Image alternative", "1.1.1"), ("Structure", "1.3.1"), ("Name", "4.1.2")]
        ],
    }


@pytest.mark.parametrize("count", [0, 2, 6])
def test_report_finding_count(count):
    data = report_data()
    data["findings"] = [data["findings"][0]] * count
    with pytest.raises(ValidationError):
        AccessibilityReport.model_validate(data)


def test_report_unknown_criterion():
    data = report_data()
    data["findings"][0]["criterion"] = "9.9.9"
    with pytest.raises(ValidationError):
        AccessibilityReport.model_validate(data)


def test_report_html_and_publication_guards(tmp_path, capsys):
    report = AccessibilityReport.model_validate(report_data())
    run = Run(ApprovedFacts(facts=["Approved."]), tmp_path, target="https://example.com/?a=<b>")
    args = Namespace(html=tmp_path / "report.html")
    with pytest.raises(ValueError, match="snapshot"):
        finish(args, run, report)
    run.called.append("read_latest_accessibility_snapshot")
    with pytest.raises(ValueError, match="every finding"):
        finish(args, run, report)
    assert not capsys.readouterr().out
    assert not args.html.exists()
    run.looked_up.update(finding.criterion for finding in report.findings)
    finish(args, run, report)
    document = args.html.read_text()
    assert "Observed &lt;input&gt;" in document
    assert "?a=&lt;b&gt;" in document
    assert 'aria-live="polite"' in document
    assert "not a full WCAG conformance audit" in document


def test_research_snapshot_is_separate_and_required(tmp_path, capsys):
    from copilot_agents.tools import CopilotTool
    run = Run(ApprovedFacts(facts=["Educator approved."]), tmp_path)
    run.research = Research(
        summary="Supplemental context.",
        sources=[{"title": "Apollo 11", "url": "https://en.wikipedia.org/wiki/Apollo_11"}],
    )
    options = museum_options(run, CopilotTool)
    assert [tool.name for tool in options["tools"]] == [
        "approved_fact_lookup", "approved_wikipedia_fact_lookup",
    ]
    assert run.approved_fact_lookup() == {"facts": ["Educator approved."]}
    with pytest.raises(ValueError, match="required fact lookup"):
        finish(Namespace(html=None), run, Exhibit.model_validate(exhibit_data()))
    assert not capsys.readouterr().out
    assert run.approved_wikipedia_fact_lookup()["summary"] == "Supplemental context."
    finish(Namespace(html=None), run, Exhibit.model_validate(exhibit_data()))
    assert "Consulted Wikipedia sources" in capsys.readouterr().out
