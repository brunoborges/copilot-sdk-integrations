---
title: Getting started
---

# Getting started

## Requirements

- Python 3.11 or later and Django 5.2 or later.
- The Copilot runtime. The Python SDK downloads it on first use; production images should
  download it at build time with [`copilot_download_runtime`](operations.md#management-commands).
- A GitHub identity with Copilot access: a `GITHUB_TOKEN`, a signed-in runtime
  (`copilot login`), or a [per-user token resolver](conversations.md#per-user-tokens).

## Install

The packages are not on PyPI yet. Install them from a clone:

```sh
git clone https://github.com/brunoborges/copilot-sdk-integrations
pip install -e copilot-sdk-integrations/python/core -e copilot-sdk-integrations/python/django
```

## Configure

```python
# settings.py
INSTALLED_APPS = [
    # ...
    "copilot_django",
]

MIDDLEWARE = [
    # ...
    "django.contrib.auth.middleware.AuthenticationMiddleware",
    "copilot_django.middleware.CopilotContextMiddleware",   # after AuthenticationMiddleware
]

COPILOT = {
    "AUTH": {"GITHUB_TOKEN": os.environ.get("GITHUB_TOKEN")},
    "DEFAULTS": {"MODEL": "gpt-5"},
}
```

The middleware records the current request so agents created in a view know the user without
you passing it. See [Configuration](configuration.md) for every setting.

## Add a tool

Tools live in a `copilot_tools.py` module in any installed app:

```python
# shop/copilot_tools.py
from copilot_django import ToolContext, copilot_tool
from .models import Order


@copilot_tool
def lookup_order(order_id: int, ctx: ToolContext) -> dict:
    """Look up one of the current user's orders."""
    order = Order.objects.get(pk=order_id, owner=ctx.user)
    return {"id": order.pk, "status": order.status}
```

## Add an agent

Agents live in a `copilot_agents.py` module:

```python
# shop/copilot_agents.py
from copilot_django import CopilotAgent, policies, prompt
from .copilot_tools import lookup_order


class SupportAgent(CopilotAgent):
    instructions = "You answer questions about the user's orders."
    tools = [lookup_order]
    built_in_tools = False
    permissions = policies.allow_registered_tools()

    @prompt("Summarize the status of order {{ order_id }} in one sentence.")
    def summarize(self, order_id: int) -> str: ...
```

## Call it from a view

```python
from django.http import JsonResponse
from .copilot_agents import SupportAgent


def order_summary(request, pk):
    return JsonResponse({"summary": SupportAgent().summarize(pk)})


async def chat(request, pk):
    answer = await SupportAgent().conversation(f"order-{pk}").asend(request.POST["message"])
    return JsonResponse({"answer": answer})
```

## Check the setup

```sh
python manage.py check
python manage.py copilot_check
```

`check` reports configuration errors such as unknown tools or MCP servers. `copilot_check`
prints the effective configuration (secrets redacted) and starts the runtime to confirm
authentication.
