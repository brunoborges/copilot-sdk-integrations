"""Django template rendering for prompts: autoescape off, undefined variables raise."""

from __future__ import annotations

from functools import lru_cache
from typing import Any

from django.conf import settings
from django.template import Context, Engine, Template

from copilot_agents import CopilotConfigurationError


class PromptRenderError(CopilotConfigurationError):
    """A prompt template referenced an undefined variable."""


class _Undefined(str):
    # Django formats string_if_invalid with the variable name when it contains "%s".
    def __contains__(self, item: object) -> bool:
        return True

    def __mod__(self, name: object) -> str:
        raise PromptRenderError(f"Undefined variable {name!r} in Copilot prompt template")


@lru_cache(maxsize=1)
def engine() -> Engine:
    dirs: list[Any] = []
    for backend in getattr(settings, "TEMPLATES", []):
        if backend.get("BACKEND") == "django.template.backends.django.DjangoTemplates":
            dirs.extend(backend.get("DIRS", []))
    return Engine(dirs=dirs, app_dirs=True, autoescape=False, string_if_invalid=_Undefined("%s"))


@lru_cache(maxsize=256)
def _compile(source: str) -> Template:
    return engine().from_string(source)


def render_string(source: str, context: dict[str, Any]) -> str:
    return _compile(source).render(Context(context, autoescape=False)).strip()


def render_template(name: str, context: dict[str, Any]) -> str:
    return engine().get_template(name).render(Context(context, autoescape=False)).strip()


def reset() -> None:
    engine.cache_clear()
    _compile.cache_clear()
