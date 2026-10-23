from django.conf import settings

from copilot_django import CopilotAgent, copilot_tool
from workshop_examples.agents import Run, accessibility_options, museum_options, research_options


def create_agents(run: Run) -> dict[str, type[CopilotAgent]]:
    run.browser = settings.WORKSHOP["BROWSER"]
    run.headless = settings.WORKSHOP["HEADLESS"]
    if run.browser not in ("chromium", "chrome", "msedge", "firefox", "webkit"):
        raise ValueError("WORKSHOP['BROWSER'] must be a supported Playwright browser.")
    return {
        "museum": type("MuseumAgent", (CopilotAgent,), {
            "__module__": __name__, **museum_options(run, copilot_tool),
            "instructions": None, "instructions_template": "workshop/curator.txt",
        }),
        "accessibility": type("AccessibilityAgent", (CopilotAgent,), {
            "__module__": __name__, **accessibility_options(run, copilot_tool),
            "instructions": None, "instructions_template": "workshop/reviewer.txt",
        }),
        "research": type("ResearchAgent", (CopilotAgent,), {
            "__module__": __name__, **research_options(),
            "instructions": None, "instructions_template": "workshop/researcher.txt",
        }),
    }
