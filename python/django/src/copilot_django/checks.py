"""System checks (``manage.py check``). They never start the Copilot runtime."""

from __future__ import annotations

import os
from typing import Any

from django.conf import settings as django_settings
from django.core.checks import Error, Tags, Warning, register

from copilot_agents import CopilotConfigurationError
from copilot_agents.policies import approves_everything

from . import conf, registry
from .conf import get_settings

_ISSUE_IDS = {
    "reference": "copilot.E002",
    "tool-filters": "copilot.E003",
    "byok": "copilot.E004",
    "duplicate": "copilot.E005",
}


@register(Tags.compatibility, "copilot")
def check_settings(app_configs: Any = None, **kwargs: Any) -> list[Any]:
    return [
        Error(message, id="copilot.E001", obj="settings.COPILOT")
        for message in conf.validate(getattr(django_settings, "COPILOT", None))
    ]


@register("copilot")
def check_agents(app_configs: Any = None, **kwargs: Any) -> list[Any]:
    if conf.validate(getattr(django_settings, "COPILOT", None)):
        return []  # reported by check_settings
    messages: list[Any] = [Error(text, id="copilot.E005") for text in registry.duplicates]
    for name, cls in sorted(registry.agents.items()):
        try:
            definition = cls.definition()
        except CopilotConfigurationError as exc:
            issues = exc.issues or ()
            if not issues:
                messages.append(Error(str(exc), id="copilot.E001", obj=cls))
            for issue in issues:
                messages.append(Error(issue.message, id=_ISSUE_IDS.get(issue.kind, "copilot.E001"), obj=cls))
            continue
        if approves_everything(definition.permissions):
            messages.append(
                Warning(
                    f"Copilot agent {name!r} approves every permission request (allow_all).",
                    hint="Prefer policies.allow_registered_tools(), allow_tools(...), or allow_kinds(...).",
                    id="copilot.W002",
                    obj=cls,
                )
            )
    return messages


@register("copilot")
def check_runtime(app_configs: Any = None, **kwargs: Any) -> list[Any]:
    if conf.validate(getattr(django_settings, "COPILOT", None)):
        return []
    runtime = get_settings().RUNTIME
    messages: list[Any] = []
    connection = runtime["CONNECTION"]
    if connection in ("stdio", "tcp") and not runtime["CLI_PATH"] and not os.environ.get("COPILOT_CLI_PATH"):
        try:
            from copilot._cli_download import get_cached_cli_path

            missing = get_cached_cli_path() is None
        except Exception:  # noqa: BLE001 - SDK internals may change; skip the check
            missing = False
        if missing:
            messages.append(
                Warning(
                    "The Copilot runtime has not been downloaded; the first agent call will download it.",
                    hint="Run `python manage.py copilot_download_runtime` at build time.",
                    id="copilot.W001",
                )
            )
    if connection == "stdio":
        try:
            workers = int(os.environ.get("WEB_CONCURRENCY", "0"))
        except ValueError:
            workers = 0
        if workers > 2:
            messages.append(
                Warning(
                    f"WEB_CONCURRENCY={workers} with CONNECTION='stdio' starts one Copilot runtime per worker.",
                    hint="Run a shared runtime and set COPILOT['RUNTIME']['CONNECTION'] = 'uri'.",
                    id="copilot.W003",
                )
            )
    return messages
