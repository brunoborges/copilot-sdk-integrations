from __future__ import annotations

import html
import json
import re
from pathlib import Path
from typing import Annotated
from urllib.parse import urlsplit

from pydantic import BaseModel, ConfigDict, Field, StringConstraints, field_validator

Text = Annotated[str, StringConstraints(strip_whitespace=True, min_length=1, max_length=4000)]
Fact = Annotated[str, StringConstraints(strip_whitespace=True, min_length=1, max_length=500)]
DISCLAIMER = (
    "Structural validation does not establish factual grounding. "
    "Unsupported claims require human review."
)
REVIEW_LIMITS = (
    "This is a focused review of browser-observable evidence, not a full WCAG conformance audit."
)
FACT_SETS = {
    "apollo11": [
        "Apollo 11 launched July 16, 1969.",
        "It landed on the Moon July 20, 1969.",
        "Neil Armstrong and Buzz Aldrin walked on the Moon.",
        "Michael Collins remained in lunar orbit.",
        "The mission returned to Earth July 24, 1969.",
    ],
    "reef": [
        "The Great Barrier Reef lies off Queensland, Australia.",
        "It extends for about 2,300 kilometres.",
        "It includes more than 2,900 individual reefs.",
        "It became a UNESCO World Heritage site in 1981.",
        "Rising sea temperatures have caused repeated coral bleaching.",
    ],
    "terracotta": [
        "The Terracotta Army was buried near the tomb of Qin Shi Huang.",
        "Farmers digging a well discovered the site in 1974.",
        "The pits contain thousands of life-sized clay soldiers.",
        "Figures were assembled from moulded parts and finished by hand.",
        "The site is near Xi'an in Shaanxi Province, China.",
    ],
}


class Contract(BaseModel):
    model_config = ConfigDict(extra="forbid")


class ApprovedFacts(Contract):
    facts: list[Fact] = Field(min_length=1, max_length=20)


class Exhibit(Contract):
    title: Text
    narrative: Text
    questions: list[Text] = Field(min_length=3, max_length=3)

    @field_validator("title")
    @classmethod
    def single_title(cls, value: str) -> str:
        if "\n" in value or "\r" in value:
            raise ValueError("The exhibit must have one single-line title.")
        return value

    @field_validator("narrative")
    @classmethod
    def narrative_length(cls, value: str) -> str:
        count = len(value.split())
        if not 100 <= count <= 140:
            raise ValueError(f"Narrative has {count} words; expected 100-140.")
        return value

    @field_validator("questions")
    @classmethod
    def distinct_questions(cls, value: list[str]) -> list[str]:
        normalized = {re.sub(r"\s+", " ", item).casefold() for item in value}
        if len(normalized) != 3 or any(not item.endswith("?") for item in value):
            raise ValueError("Provide three distinct questions ending in '?'.")
        return value

    def markdown(self) -> str:
        questions = "\n".join(f"{i}. {q}" for i, q in enumerate(self.questions, 1))
        return (
            f"# {self.title}\n\n## Narrative\n{self.narrative}\n\n"
            f"## Visitor questions\n{questions}\n\n{DISCLAIMER}\n"
        )


RULES = {
    "1.1.1": {
        "title": "Non-text Content",
        "keywords": ["image", "alternative", "alt"],
        "recommendation": "Give informative images meaningful text alternatives; mark decorative images appropriately.",
    },
    "1.3.1": {
        "title": "Info and Relationships",
        "keywords": ["heading", "structure", "table", "landmark"],
        "recommendation": "Represent visual structure and relationships with semantic HTML.",
    },
    "2.4.4": {
        "title": "Link Purpose (In Context)",
        "keywords": ["link", "click here"],
        "recommendation": "Use link text that identifies the destination in context.",
    },
    "3.3.2": {
        "title": "Labels or Instructions",
        "keywords": ["label", "input", "form"],
        "recommendation": "Provide visible labels and instructions associated with form controls.",
    },
    "4.1.2": {
        "title": "Name, Role, Value",
        "keywords": ["button", "name", "role", "control"],
        "recommendation": "Expose each control's accessible name, role, state and value.",
    },
}


class Finding(Contract):
    title: Text
    evidence: Text
    criterion: Text
    remediation: Text

    @field_validator("criterion")
    @classmethod
    def catalog_criterion(cls, value: str) -> str:
        if value not in RULES:
            raise ValueError("Use an exact criterion ID from accessibility_rule_lookup.")
        return value


class AccessibilityReport(Contract):
    findings: list[Finding] = Field(min_length=3, max_length=5)

    def markdown(self) -> str:
        sections = ["# Accessibility review"]
        for i, finding in enumerate(self.findings, 1):
            sections.append(
                f"## Finding {i}: {finding.title}\n"
                f"- Evidence: {finding.evidence}\n"
                f"- WCAG criterion: {finding.criterion} {RULES[finding.criterion]['title']}\n"
                f"- Recommended remediation: {finding.remediation}"
            )
        sections.append(f"## Review limits\n{REVIEW_LIMITS}")
        return "\n\n".join(sections) + "\n"


class Source(Contract):
    title: Text
    url: Text

    @field_validator("url")
    @classmethod
    def wikipedia_url(cls, value: str) -> str:
        parts = urlsplit(value)
        if (
            parts.scheme != "https"
            or not parts.hostname
            or not parts.hostname.endswith(".wikipedia.org")
            or parts.username
            or parts.password
            or parts.port not in (None, 443)
            or not parts.path.startswith("/wiki/")
        ):
            raise ValueError("A canonical HTTPS Wikipedia article URL is required.")
        return value


class Research(Contract):
    summary: Text
    sources: list[Source] = Field(min_length=1, max_length=5)


def target_url(value: str) -> str:
    parts = urlsplit(value)
    if (
        parts.scheme not in ("http", "https")
        or not parts.hostname
        or parts.username
        or parts.password
        or any(char.isspace() for char in value)
    ):
        raise ValueError("Provide an absolute HTTP(S) URL without credentials or whitespace.")
    parts.port
    return value


def same_url(left: str, right: str) -> bool:
    try:
        def key(value: str) -> tuple:
            p = urlsplit(target_url(value))
            return p.scheme, p.hostname, p.port, p.path, p.query, p.fragment
        return key(left) == key(right)
    except (ValueError, TypeError):
        return False


def exhibit_html(exhibit: Exhibit) -> str:
    questions = "".join(f"<li data-filter-item>{html.escape(q)}</li>" for q in exhibit.questions)
    content = f"""<section aria-labelledby="narrative"><h2 id="narrative">Narrative</h2>
<p>{html.escape(exhibit.narrative)}</p></section>
<section aria-labelledby="items"><h2 id="items">Visitor questions</h2>
<ol>{questions}</ol></section><p>{html.escape(DISCLAIMER)}</p>"""
    return html_document(exhibit.title, content, "questions", 3)


def accessibility_html(report: AccessibilityReport, target: str) -> str:
    cards = "".join(
        f"""<article data-filter-item><h3>{html.escape(finding.title)}</h3>
<dl><dt>Evidence</dt><dd>{html.escape(finding.evidence)}</dd>
<dt>WCAG criterion</dt><dd>{html.escape(finding.criterion)} {html.escape(RULES[finding.criterion]["title"])}</dd>
<dt>Remediation</dt><dd>{html.escape(finding.remediation)}</dd></dl></article>"""
        for finding in report.findings
    )
    content = f"""<p>Target: {html.escape(target)}</p>
<section aria-labelledby="items"><h2 id="items">Findings</h2>{cards}</section>
<section aria-labelledby="limits"><h2 id="limits">Review limits</h2>
<p>{html.escape(REVIEW_LIMITS)}</p></section>"""
    return html_document("Accessibility review", content, "findings", len(report.findings))


def html_document(title: str, content: str, item_name: str, count: int) -> str:
    return f"""<!doctype html>
<html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>{html.escape(title)}</title>
<style>body{{font:1.1rem system-ui;max-width:60rem;margin:3rem auto;padding:1rem}}
:focus-visible{{outline:3px solid #176b92;outline-offset:3px}}input{{font:inherit}}</style>
</head><body><main><h1>{html.escape(title)}</h1>
<label for="filter">Filter {item_name}</label> <input id="filter" type="search" aria-controls="items">
<p id="count" role="status" aria-live="polite">{count} {item_name}</p>{content}</main>
<script>const input=document.querySelector('#filter');
input.addEventListener('input',()=>{{let count=0;
for(const item of document.querySelectorAll('[data-filter-item]')){{
item.hidden=!item.textContent.toLowerCase().includes(input.value.toLowerCase());
if(!item.hidden)count++;}}document.querySelector('#count').textContent=count+' {item_name}';}});
</script></body></html>
"""


def load_facts(fact_set: str, path: Path | None) -> ApprovedFacts:
    if path is None:
        return ApprovedFacts(facts=FACT_SETS[fact_set])
    if path.stat().st_size > 20_000:
        raise ValueError("Facts JSON must not exceed 20,000 bytes.")
    return ApprovedFacts.model_validate(json.loads(path.read_text(encoding="utf-8")))
