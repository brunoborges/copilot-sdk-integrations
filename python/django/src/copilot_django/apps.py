from __future__ import annotations

from typing import Any

from django.apps import AppConfig
from django.core.signals import setting_changed
from django.utils.module_loading import autodiscover_modules


class CopilotConfig(AppConfig):
    name = "copilot_django"
    label = "copilot"
    verbose_name = "GitHub Copilot"

    def ready(self) -> None:
        from . import checks  # noqa: F401 - registers system checks
        from .conf import get_settings

        autodiscover_modules("copilot_tools")
        autodiscover_modules("copilot_agents")
        setting_changed.connect(_on_setting_changed, dispatch_uid="copilot_django.settings")
        if get_settings().RUNTIME.get("START") == "eager":
            from .runtime import start

            start()


def _on_setting_changed(*, setting: str, **kwargs: Any) -> None:
    from . import conf, registry, runtime, templating

    if setting in ("COPILOT", "BASE_DIR"):
        conf.reload()
        registry.clear_cache()
        if setting == "COPILOT":
            runtime.settings_changed()
    elif setting == "DEBUG":
        registry.clear_cache()
    elif setting in ("TEMPLATES", "INSTALLED_APPS"):
        templating.reset()
        registry.clear_cache()
