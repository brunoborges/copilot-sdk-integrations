from __future__ import annotations

from dataclasses import dataclass, field
from importlib.resources import files
from pathlib import Path
from typing import Any

from copilot_agents import policies
from copilot_agents.tools import ToolError

from .domain import ApprovedFacts, RULES, Research, same_url

def instructions(name: str) -> str:
    content = files("workshop_examples").joinpath("prompts", f"{name}.txt").read_text(encoding="utf-8")
    if not content.strip():
        raise ValueError(f"Empty prompt resource: {name}.")
    return content


@dataclass
class Run:
    facts: ApprovedFacts
    output_directory: Path
    target: str | None = None
    research: Research | None = None
    called: list[str] = field(default_factory=list)
    looked_up: set[str] = field(default_factory=set)
    browser: str = "chromium"
    headless: bool = True

    def approved_fact_lookup(self) -> dict:
        """Return the bounded educator-approved facts for this exhibit."""
        self.called.append("approved_fact_lookup")
        return self.facts.model_dump()

    def approved_wikipedia_fact_lookup(self) -> dict:
        """Return the application-accepted research snapshot, not live or educator-approved facts."""
        if self.research is None:
            raise ToolError("No accepted research is available.")
        self.called.append("approved_wikipedia_fact_lookup")
        return self.research.model_dump()

    def read_latest_accessibility_snapshot(self) -> str:
        """Read the newest bounded Playwright accessibility snapshot from this run only."""
        root = self.output_directory.resolve()
        candidates = [
            path for path in root.glob("page-*.yml")
            if not path.is_symlink() and path.is_file()
            and path.resolve().parent == root and 0 < path.stat().st_size <= 1_000_000
        ]
        if not candidates:
            raise ToolError("No current-run snapshot exists. Navigate to the requested URL first.")
        newest = max(candidates, key=lambda path: path.stat().st_mtime_ns)
        with newest.open("rb") as stream:
            data = stream.read(1_000_001)
        if len(data) > 1_000_000:
            raise ToolError("Snapshot exceeds 1,000,000 bytes.")
        self.called.append("read_latest_accessibility_snapshot")
        return data.decode("utf-8")

    def accessibility_rule_lookup(self, query: str) -> dict:
        """Look up a WCAG criterion by exact ID, title, or issue keyword in the local catalog."""
        normalized = query.strip().casefold()
        for criterion, rule in RULES.items():
            if criterion in normalized or rule["title"].casefold() in normalized or any(
                keyword in normalized for keyword in rule["keywords"]
            ):
                self.looked_up.add(criterion)
                return {"criterion": criterion, **rule}
        raise ToolError("No catalog match. Consult the full WCAG reference; do not invent a criterion.")

    def navigation_permission(self, request: Any, invocation: Any) -> bool:
        return (
            getattr(request, "kind", None) == "mcp"
            and getattr(request, "server_name", None) == "playwright"
            and getattr(request, "tool_name", None) in ("browser_navigate", "playwright-browser_navigate")
            and isinstance(getattr(request, "args", None), dict)
            and isinstance(request.args.get("url"), str)
            and self.target is not None
            and same_url(request.args["url"], self.target)
        )


def museum_options(run: Run, tool) -> dict:
    tools = [tool(run.approved_fact_lookup)]
    if run.research is not None:
        tools.append(tool(run.approved_wikipedia_fact_lookup))
    return {
        "instructions": instructions("curator"), "instructions_mode": "replace",
        "tools": tools, "built_in_tools": False,
        "permissions": policies.allow_registered_tools(),
    }


def research_options() -> dict:
    def permission(request, invocation):
        return (
            getattr(request, "kind", None) == "mcp"
            and getattr(request, "server_name", None) == "wikipedia"
            and getattr(request, "tool_name", None) in (
                "search", "readArticle", "wikipedia-search", "wikipedia-readArticle",
            )
        )
    return {
        "instructions": instructions("researcher"), "instructions_mode": "replace",
        "built_in_tools": False, "permissions": permission,
        "mcp_servers": {"wikipedia": {
            "command": "npx", "args": ["-y", "wikipedia-mcp@1.0.3"],
            "tools": ["search", "readArticle"],
        }},
    }


def accessibility_options(run: Run, tool) -> dict:
    return {
        "instructions": instructions("reviewer"), "instructions_mode": "replace",
        "built_in_tools": False,
        "tools": [tool(run.read_latest_accessibility_snapshot), tool(run.accessibility_rule_lookup)],
        "permissions": policies.any_of(policies.allow_registered_tools(), run.navigation_permission),
        "mcp_servers": {"playwright": {
            "command": "npx",
            "args": [
                "-y", "@playwright/mcp@0.0.78", f"--browser={run.browser}", "--isolated",
                *(["--headless"] if run.headless else []),
                "--output-dir", str(run.output_directory), "--output-mode", "file",
            ],
            "working_directory": str(run.output_directory),
            "tools": ["browser_navigate"],
        }},
    }
