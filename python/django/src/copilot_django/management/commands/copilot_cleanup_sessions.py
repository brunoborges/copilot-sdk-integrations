from __future__ import annotations

from typing import Any

from django.core.management.base import BaseCommand, CommandError

from ...sessions import get_session_cleanup


class Command(BaseCommand):
    help = (
        "Delete Copilot sessions created by this app that were not modified within "
        "COPILOT['SESSIONS']['MAX_AGE'] (or --max-age). Starts the runtime if needed."
    )

    def add_arguments(self, parser: Any) -> None:
        parser.add_argument(
            "--max-age",
            type=float,
            default=None,
            help="Maximum session age in seconds (defaults to COPILOT['SESSIONS']['MAX_AGE']).",
        )

    def handle(self, *args: Any, **options: Any) -> None:
        max_age = options["max_age"]
        if max_age is not None and max_age <= 0:
            raise CommandError("--max-age must be a positive number of seconds")
        cleanup = get_session_cleanup(max_age)
        if cleanup is None:
            raise CommandError("Set COPILOT['SESSIONS']['MAX_AGE'] or pass --max-age to clean up sessions")
        try:
            deleted = cleanup.sweep(start_runtime=True)
        except Exception as exc:  # noqa: BLE001 - reported to the operator
            raise CommandError(f"Copilot session cleanup failed ({type(exc).__name__}: {exc})") from exc
        self.stdout.write(
            self.style.SUCCESS(f"Deleted {deleted} Copilot session(s) older than {cleanup.max_age}.")
        )
