# copilot-django

Django integration for the [GitHub Copilot SDK for Python](https://pypi.org/project/github-copilot-sdk/).
Declare agents as classes, expose ORM-aware functions as tools, and call agents from views,
management commands or Celery tasks, sync or async. Built on [`copilot-agent-core`](../core/).

Status: **M1 + M2 of [DESIGN.md](DESIGN.md) implemented** (core, settings, agents, typed
prompts, ORM tools, permissions, checks, management commands, testing). Streaming, interactive
approvals, signals and the admin integration are planned (M3–M5).

## Quickstart

```python
# settings.py
INSTALLED_APPS = [..., "copilot_django"]
MIDDLEWARE = [..., "copilot_django.middleware.CopilotContextMiddleware"]  # after AuthenticationMiddleware
COPILOT = {
    "AUTH": {"GITHUB_TOKEN": env("GITHUB_TOKEN", default=None)},
    "DEFAULTS": {"MODEL": "gpt-5"},
}
```

```python
# shop/copilot_tools.py
from copilot_django import ToolContext, copilot_tool
from .models import Order

@copilot_tool
def lookup_order(order_id: int, ctx: ToolContext) -> dict:
    """Look up one of the current user's orders."""
    order = Order.objects.get(pk=order_id, owner=ctx.user)
    return {"id": order.pk, "status": order.status}

@copilot_tool(permission_required="shop.refund_order", atomic=True)
def refund_order(order_id: int, ctx: ToolContext) -> str:
    """Refund an order."""
    ...
```

```python
# shop/copilot_agents.py
from pydantic import BaseModel
from copilot_django import CopilotAgent, policies, prompt
from .copilot_tools import lookup_order, refund_order

class Decision(BaseModel):
    approve: bool
    reason: str

class SupportAgent(CopilotAgent):
    instructions = "You are a support agent. Be concise."
    tools = [lookup_order, refund_order]
    built_in_tools = False
    permissions = policies.allow_registered_tools()

    @prompt("Should order {{ order_id }} be refunded? Reason given: {{ reason }}")
    def decide_refund(self, order_id: int, reason: str) -> Decision: ...
```

```python
# shop/views.py
def refund(request, pk):
    decision = SupportAgent(request=request).decide_refund(pk, request.POST["reason"])
    ...

async def chat(request, pk):
    answer = await SupportAgent(request=request).conversation(f"order-{pk}").asend(message)
    ...
```

`copilot_tools` and `copilot_agents` modules are autodiscovered in every installed app.
`SupportAgent()` picks up the current request and user from `CopilotContextMiddleware`.

## Features

- **Agents** — `CopilotAgent` subclasses with `instructions` (or `instructions_template`), `model`,
  `tools`, `mcp_servers`, `available_tools` / `excluded_tools`, `built_in_tools`,
  `permissions`, `hooks`, `working_directory`, `provider`, `token_resolver`. Any attribute can
  be overridden per environment in `COPILOT["AGENTS"]["<name>"]`.
- **Calls** — `ask` / `aask`, `conversation(id).send` / `asend`, `resume`, `exists`, `delete`,
  and `@prompt` methods whose return annotation (`str` or a pydantic model) types the reply.
  Prompt templates use the Django template engine with autoescaping off; undefined variables
  raise `PromptRenderError`.
- **Tools** — `@copilot_tool` adds `permission_required` (checked with `user.has_perm` /
  `ahas_perms`), `atomic=True` (`transaction.atomic`, sync tools only), and maps
  `ObjectDoesNotExist` / `PermissionDenied` to tool errors. Sync tools called from sync views
  run on the request thread and see its uncommitted transaction; from async views they run via
  `sync_to_async(thread_sensitive=True)`.
- **Conversations** are partitioned per user: the session ID includes the authenticated user's
  pk (or a token resolver's partition), so users cannot resume each other's conversations.
- **Working directories** default to an isolated directory per session under
  `COPILOT["RUNTIME"]["BASE_DIRECTORY"]`, removed when an ephemeral session ends or a
  conversation is deleted.
- **Sessions** — `get_session_store()` lists, reads (without resuming), checks and deletes
  sessions; `store.session_id_for(SupportAgent, "ticket-7", user=request.user)` maps a
  conversation to its session ID. Set `COPILOT["SESSIONS"]["MAX_AGE"]` to delete this
  integration's sessions that have not been modified for that long (off by default).
- **System checks** — `copilot.E001`–`E005` (invalid settings, unknown references, tool-filter
  conflicts, BYOK misconfiguration, duplicate names), `W001` (runtime not downloaded), `W002`
  (`allow_all`), `W003` (many stdio workers).
- **Commands** — `copilot_download_runtime [--force] [--runtime-version V]` (run at image build
  time), `copilot_check` (prints the redacted effective configuration and probes the runtime)
  and `copilot_cleanup_sessions [--max-age SECONDS]` (one cleanup sweep, for cron).

## Settings

```python
COPILOT = {
    "RUNTIME": {
        "CONNECTION": "stdio",       # stdio | uri | tcp | inprocess
        "URL": None, "CONNECTION_TOKEN": None, "CLI_PATH": None, "PORT": 0,
        "BASE_DIRECTORY": None,       # default: BASE_DIR / ".copilot"
        "LOG_LEVEL": "info", "START": "lazy", "REQUEST_TIMEOUT": 300, "MODE": "copilot-cli",
    },
    "AUTH": {"GITHUB_TOKEN": None, "TOKEN_RESOLVER": None},
    "DEFAULTS": {"MODEL": None, "REASONING_EFFORT": None,
                 "PERMISSIONS": "deny", "WORKING_DIRECTORY": "isolated"},
    "MCP_SERVERS": {},   # name -> SDK MCP server config
    "PROVIDERS": {},     # name -> BYOK provider config
    "AGENTS": {},        # agent name -> attribute overrides (upper-case keys)
    "HOOKS": [],         # dotted paths to CopilotAgentHooks applied to every agent
    "SESSIONS": {
        "MAX_AGE": None,              # seconds or timedelta; None disables cleanup
        "CLEANUP_INTERVAL": None,     # default: MAX_AGE / 2, between 1 minute and 1 hour
        "BACKGROUND_CLEANUP": True,   # sweep in a daemon thread per process when MAX_AGE is set
    },
    "TELEMETRY": None,
}
```

## Testing

```python
from copilot_django.test import CopilotTestMixin

class RefundTests(CopilotTestMixin, TestCase):
    def test_refund(self):
        self.copilot.agent("support").calls_tool("lookup_order", {"order_id": 1}).replies_json(
            {"approve": True, "reason": "late"}
        )
        ...
        self.assertEqual(self.copilot.tool_calls("support")[0].name, "lookup_order")
```

With pytest, installing the package provides a `copilot_runtime` fixture. No CLI or network
access is needed; tool calls really execute against the test database.

## Differences from the design

- Sessions are opened per call (no long-lived `conversation.session` / `close()`); a
  conversation is just an ID.
- The default conversation partition is the authenticated user's pk; anonymous callers share
  the unpartitioned namespace.
- The `|default` filter cannot rescue an undefined template variable; use `{% if %}`.

## Example

[`example/`](example/) is a small helpdesk project with a triage agent:

```bash
cd python/django/example
../../.venv/bin/python manage.py migrate
../../.venv/bin/python manage.py copilot_download_runtime
../../.venv/bin/python manage.py test
```
