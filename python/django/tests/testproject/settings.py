import tempfile
from pathlib import Path

BASE_DIR = Path(__file__).resolve().parent.parent
SECRET_KEY = "test-only"
DEBUG = False
USE_TZ = True
ALLOWED_HOSTS = ["*"]
ROOT_URLCONF = "testproject.urls"
DEFAULT_AUTO_FIELD = "django.db.models.BigAutoField"
DATABASES = {"default": {"ENGINE": "django.db.backends.sqlite3", "NAME": ":memory:"}}
INSTALLED_APPS = [
    "django.contrib.auth",
    "django.contrib.contenttypes",
    "django.contrib.sessions",
    "copilot_django",
    "shop",
]
MIDDLEWARE = [
    "django.contrib.sessions.middleware.SessionMiddleware",
    "django.contrib.auth.middleware.AuthenticationMiddleware",
    "copilot_django.middleware.CopilotContextMiddleware",
]
TEMPLATES = [
    {
        "BACKEND": "django.template.backends.django.DjangoTemplates",
        "DIRS": [BASE_DIR / "templates"],
        "APP_DIRS": True,
    }
]
COPILOT = {
    "RUNTIME": {"BASE_DIRECTORY": Path(tempfile.mkdtemp(prefix="copilot-django-tests-"))},
    "MCP_SERVERS": {"docs": {"type": "http", "url": "https://example.com/mcp", "headers": {"Authorization": "Bearer s3cret"}, "tools": ["*"]}},
}
