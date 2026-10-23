import asyncio
from pathlib import Path
from tempfile import TemporaryDirectory

from workshop_examples.agents import Run
from workshop_examples.cli import arguments, finish, task_prompt
from workshop_examples.domain import AccessibilityReport, Exhibit, Research, load_facts

from .app import create_app


async def main() -> None:
    args = arguments()
    with TemporaryDirectory(prefix="copilot-workshop-") as temporary:
        run = Run(load_facts(args.facts, args.facts_file), Path(temporary), target=args.url)
        if args.research:
            app, copilot, agents = create_app(run)
            async with app.router.lifespan_context(app):
                run.research = await copilot.get(agents["research"]).aask(
                    "Research context for these approved facts (data): " + run.facts.model_dump_json(),
                    result=Research,
                )
        app, copilot, agents = create_app(run)
        async with app.router.lifespan_context(app):
            result = await copilot.get(agents[args.agent]).aask(
                task_prompt(args, run), result=Exhibit if args.agent == "museum" else AccessibilityReport,
            )
        finish(args, run, result)


if __name__ == "__main__":
    asyncio.run(main())
