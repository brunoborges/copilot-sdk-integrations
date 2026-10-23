from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

from .agents import Run
from .domain import AccessibilityReport, Exhibit, FACT_SETS, accessibility_html, exhibit_html, target_url


def add_arguments(parser: argparse.ArgumentParser) -> None:
    parser.add_argument("agent", choices=["museum", "accessibility"])
    parser.add_argument("--facts", choices=list(FACT_SETS), default="apollo11")
    parser.add_argument("--facts-file", type=Path, help='UTF-8 JSON: {"facts": ["..."]}')
    parser.add_argument("--url", type=target_url, help="Exact trusted HTTP(S) URL to review")
    parser.add_argument("--research", action="store_true", help="Allow separate Wikipedia MCP research")
    parser.add_argument("--html", type=Path, help="Create a standalone HTML artifact; never overwrite")


def validate_arguments(args: argparse.Namespace) -> argparse.Namespace:
    if args.agent == "accessibility" and (not args.url or args.research):
        raise ValueError("accessibility requires --url and does not accept --research")
    if args.agent == "museum" and args.url:
        raise ValueError("--url is only supported by accessibility")
    return args


def arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Copilot SDK workshop agents (real calls consume Copilot usage).")
    add_arguments(parser)
    return validate_arguments(parser.parse_args())


def task_prompt(args: argparse.Namespace, run: Run) -> str:
    if args.agent == "museum":
        print("Educator-approved facts:\n" + run.facts.model_dump_json(indent=2), file=sys.stderr)
        return "Create the exhibit from the configured fact lookup tools."
    return "Review this exact URL (task data): " + json.dumps(run.target)


def finish(args: argparse.Namespace, run: Run, result: Exhibit | AccessibilityReport) -> None:
    if isinstance(result, Exhibit):
        required = {"approved_fact_lookup"}
        if run.research is not None:
            required.add("approved_wikipedia_fact_lookup")
        if not required.issubset(run.called):
            raise ValueError("The curator did not call the required fact lookup tools.")
        print(result.markdown())
        if run.research:
            print("## Consulted Wikipedia sources")
            for source in run.research.sources:
                print(f"- {source.title}: {source.url}")
        if args.html:
            with args.html.open("x", encoding="utf-8") as stream:
                stream.write(exhibit_html(result))
            print(f"Created {args.html}", file=sys.stderr)
    else:
        if run.target is None:
            raise ValueError("An accessibility report requires an explicit target URL.")
        if "read_latest_accessibility_snapshot" not in run.called:
            raise ValueError("The reviewer did not read this run's browser snapshot.")
        if not {finding.criterion for finding in result.findings}.issubset(run.looked_up):
            raise ValueError("The reviewer did not look up every finding's criterion.")
        print(result.markdown())
        if args.html:
            with args.html.open("x", encoding="utf-8") as stream:
                stream.write(accessibility_html(result, run.target))
            print(f"Created {args.html}", file=sys.stderr)
