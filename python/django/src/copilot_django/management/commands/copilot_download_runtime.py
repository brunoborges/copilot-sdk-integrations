from __future__ import annotations

import subprocess
import sys
from typing import Any

from django.core.management.base import BaseCommand, CommandError


class Command(BaseCommand):
    help = "Download the Copilot runtime used by the Python SDK (wraps `python -m copilot download-runtime`)."

    def add_arguments(self, parser: Any) -> None:
        parser.add_argument("--force", action="store_true", help="Re-download even if already cached.")
        parser.add_argument("--runtime-version", dest="runtime_version", help="Runtime version (default: the SDK's pinned version).")
        parser.add_argument("--in-process", action="store_true", help="Also download the in-process (FFI) runtime library.")

    def handle(self, *args: Any, **options: Any) -> None:
        command = [sys.executable, "-m", "copilot", "download-runtime"]
        if options["force"]:
            command.append("--force")
        if options["runtime_version"]:
            command += ["--version", options["runtime_version"]]
        if options["in_process"]:
            command.append("--in-process")
        result = subprocess.run(command, check=False)
        if result.returncode != 0:
            raise CommandError(f"`{' '.join(command[1:])}` failed with exit code {result.returncode}")
