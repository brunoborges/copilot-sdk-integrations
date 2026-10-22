from __future__ import annotations

from collections.abc import Mapping
from typing import Any

from django.core.management.base import BaseCommand, CommandError

from copilot_agents import CopilotConfigurationError

from ... import registry
from ...conf import get_settings
from ...runtime import get_runtime

SECRET_KEYS = ("token", "key", "secret", "password", "authorization")


def redact(value: Any) -> Any:
    if isinstance(value, Mapping):
        return {
            k: ("***" if any(s in str(k).lower() for s in SECRET_KEYS) and v else redact(v))
            for k, v in value.items()
        }
    if isinstance(value, list | tuple):
        return [redact(v) for v in value]
    return value


class Command(BaseCommand):
    help = "Start the Copilot runtime and report auth status, models, and each agent's configuration."

    def add_arguments(self, parser: Any) -> None:
        parser.add_argument("--no-runtime", action="store_true", help="Only report agent configuration.")
        parser.add_argument("--timeout", type=float, default=60.0)

    def handle(self, *args: Any, **options: Any) -> None:
        conf = get_settings()
        runtime = conf.RUNTIME
        self.stdout.write(f"Connection: {runtime['CONNECTION']}" + (f" ({runtime['URL']})" if runtime["URL"] else ""))
        self.stdout.write(f"Base directory: {conf.base_directory}")
        failed = False
        if not options["no_runtime"]:
            failed = not self._runtime(options["timeout"])
        for name, cls in sorted(registry.agents.items()):
            self.stdout.write("")
            self.stdout.write(self.style.MIGRATE_HEADING(f"Agent {name!r} ({cls.__module__}.{cls.__qualname__})"))
            try:
                definition = cls.definition()
            except CopilotConfigurationError as exc:
                failed = True
                self.stdout.write(self.style.ERROR(f"  invalid: {exc}"))
                continue
            self.stdout.write(f"  model: {definition.model or '(runtime default)'}")
            if definition.provider:
                self.stdout.write(f"  provider: {redact(dict(definition.provider))}")
            self.stdout.write(f"  tools: {', '.join(t.name for t in definition.tools) or '(none)'}")
            for server, config in definition.mcp_servers.items():
                self.stdout.write(f"  mcp server {server}: {redact(dict(config))}")
            if definition.available_tools is not None:
                self.stdout.write(f"  available tools: {', '.join(definition.available_tools)}")
            if definition.effective_excluded_tools:
                self.stdout.write(f"  excluded tools: {', '.join(definition.effective_excluded_tools)}")
            self.stdout.write(f"  permissions: {definition.permissions.describe()}")
            self.stdout.write(f"  hooks: {', '.join(type(h).__name__ for h in definition.hooks) or '(none)'}")
            resolver = definition.token_resolver
            self.stdout.write(f"  identity: {type(resolver).__name__ if resolver else 'ambient'}")
        if failed:
            raise CommandError("Copilot check failed")

    def _runtime(self, timeout: float) -> bool:
        async def probe(client: Any) -> tuple[Any, list[Any]]:
            status = await client.get_auth_status()
            models = await client.list_models()
            return status, models

        try:
            status, models = get_runtime().call(probe, timeout)
        except Exception as exc:  # noqa: BLE001 - reported to the operator
            self.stdout.write(self.style.ERROR(f"Runtime: failed to start ({type(exc).__name__}: {exc})"))
            return False
        self.stdout.write(self.style.SUCCESS(f"Runtime: {get_runtime().status()}"))
        authenticated = getattr(status, "is_authenticated", False)
        login = getattr(status, "login", None)
        auth = f"authenticated as {login}" if authenticated and login else ("authenticated" if authenticated else "not authenticated")
        self.stdout.write(f"Auth: {auth}")
        self.stdout.write(f"Models: {', '.join(getattr(m, 'id', str(m)) for m in models) or '(none)'}")
        return bool(authenticated)
