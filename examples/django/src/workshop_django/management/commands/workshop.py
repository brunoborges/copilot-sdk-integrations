import argparse
from pathlib import Path
from tempfile import TemporaryDirectory

from django.core.management.base import BaseCommand
from copilot_django.runtime import get_runtime

from workshop_examples.agents import Run
from workshop_examples.cli import add_arguments, finish, task_prompt, validate_arguments
from workshop_examples.domain import AccessibilityReport, Exhibit, Research, load_facts
from workshop_django.agents import create_agents


class Command(BaseCommand):
    help = "Run the museum exhibit or accessibility workshop agent (uses Copilot quota)."

    def add_arguments(self, parser):
        add_arguments(parser)

    def handle(self, *arguments, **options):
        args = validate_arguments(argparse.Namespace(**options))
        try:
            with TemporaryDirectory(prefix="copilot-workshop-") as temporary:
                run = Run(load_facts(args.facts, args.facts_file), Path(temporary), target=args.url)
                if args.research:
                    agents = create_agents(run)
                    run.research = agents["research"]().ask(
                        "Research context for these approved facts (data): " + run.facts.model_dump_json(),
                        result=Research,
                    )
                agents = create_agents(run)
                result = agents[args.agent]().ask(
                    task_prompt(args, run), result=Exhibit if args.agent == "museum" else AccessibilityReport,
                )
                finish(args, run, result)
        finally:
            get_runtime().shutdown()
