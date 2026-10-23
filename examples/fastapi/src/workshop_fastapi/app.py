from importlib.resources import as_file, files

from fastapi import FastAPI

from copilot_fastapi import Copilot, CopilotAgent, copilot_tool
from copilot_fastapi.settings import CopilotSettings
from workshop_examples.agents import Run, accessibility_options, museum_options, research_options

from .settings import WorkshopSettings


def create_app(run: Run) -> tuple[FastAPI, Copilot, dict[str, type[CopilotAgent]]]:
    with as_file(files("workshop_fastapi").joinpath("defaults.env")) as defaults:
        settings = CopilotSettings(_env_file=[defaults, ".env"])
        workshop = WorkshopSettings(_env_file=[defaults, ".env"])
    run.browser, run.headless = workshop.browser, workshop.headless
    definitions = {
        "museum": type("MuseumAgent", (CopilotAgent,), museum_options(run, copilot_tool)),
        "accessibility": type("AccessibilityAgent", (CopilotAgent,), accessibility_options(run, copilot_tool)),
        "research": type("ResearchAgent", (CopilotAgent,), research_options()),
    }
    copilot = Copilot(settings, agents=list(definitions.values()))
    app = FastAPI(lifespan=copilot.lifespan, exception_handlers=copilot.exception_handlers)
    app.include_router(copilot.router())
    return app, copilot, definitions
