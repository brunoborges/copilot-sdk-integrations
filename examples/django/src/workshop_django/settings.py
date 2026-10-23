import os
from pathlib import Path

BASE_DIR = Path.cwd()
SECRET_KEY = os.environ.get("DJANGO_SECRET_KEY", "local-workshop-command-not-a-web-server")
INSTALLED_APPS = ["copilot_django", "workshop_django"]
TEMPLATES = [{"BACKEND": "django.template.backends.django.DjangoTemplates", "APP_DIRS": True}]
DATABASES = {"default": {"ENGINE": "django.db.backends.sqlite3", "NAME": ":memory:"}}
COPILOT = {
    "RUNTIME": {"CLI_PATH": os.environ.get("COPILOT_CLI_PATH")},
    "DEFAULTS": {"MODEL": os.environ.get("COPILOT_MODEL")},
    "AGENTS": {
        "museum": {"TIMEOUT": float(os.environ.get("COPILOT_MUSEUM_TIMEOUT", "120"))},
        "accessibility": {"TIMEOUT": float(os.environ.get("COPILOT_ACCESSIBILITY_TIMEOUT", "120"))},
        "research": {"TIMEOUT": float(os.environ.get("COPILOT_RESEARCH_TIMEOUT", "90"))},
    },
}
_headless = os.environ.get("WORKSHOP_HEADLESS", "true").lower()
if _headless not in ("true", "false"):
    raise ValueError("WORKSHOP_HEADLESS must be true or false.")
WORKSHOP = {
    "BROWSER": os.environ.get("WORKSHOP_BROWSER", "chromium"),
    "HEADLESS": _headless == "true",
}
