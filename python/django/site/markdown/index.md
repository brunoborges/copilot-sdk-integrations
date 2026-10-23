---
title: Documentation
---

# Copilot for Django

`copilot-django` connects the
[GitHub Copilot SDK for Python](https://github.com/github/copilot-sdk/tree/main/python) to
Django. Declare agents as classes, expose ORM-aware functions as tools, and call agents from
views, management commands, or background tasks, synchronously or with `async`/`await`.

> **Status:** preview. Milestones M1 and M2 of the
> [design](https://github.com/brunoborges/copilot-sdk-integrations/blob/main/python/django/DESIGN.md)
> are implemented and tested, but the packages are not on PyPI yet. Install them from the
> [repository](https://github.com/brunoborges/copilot-sdk-integrations/tree/main/python). See
> [Getting started](getting-started.md).

```python
class SupportAgent(CopilotAgent):
    instructions = "You are a support agent. Be concise."
    tools = [lookup_order, refund_order]          # @copilot_tool functions
    built_in_tools = False
    permissions = policies.allow_registered_tools()

    @prompt("Should order {{ order_id }} be refunded? Reason: {{ reason }}")
    def decide_refund(self, order_id: int, reason: str) -> Decision: ...


def refund(request, pk):
    decision = SupportAgent().decide_refund(pk, request.POST["reason"])   # typed pydantic result
    ...
```

## What you get

- **Agents as classes**: [`CopilotAgent`](agents.md) subclasses are autodiscovered from each
  app's `copilot_agents` module and can be overridden per environment in settings.
- **Typed prompts**: [`@prompt`](prompts.md) methods render a Django template and parse the
  reply into the return annotation (`str` or a pydantic model).
- **ORM-aware tools**: [`@copilot_tool`](tools.md) functions see the request's user, check
  Django permissions, and can run in `transaction.atomic`. In sync views, tools run on the
  request thread, so they see uncommitted rows.
- **Deny-by-default permissions**: [policies](permissions.md) decide which tools, MCP servers
  and built-in tool kinds the agent may use.
- **Per-user conversations**: [conversations](conversations.md) are partitioned by the
  authenticated user, so one user can't resume another user's conversation.
- **Operations**: [system checks and management commands](operations.md) catch
  misconfiguration at startup and download the runtime at build time.
- **Testing**: a [scripted in-memory runtime](testing.md) that needs no CLI or network access.

## Packages

| Package | Import | Purpose |
|---|---|---|
| `copilot-agent-core` | `copilot_agents` | Framework-neutral agent layer, shared with future Python integrations. |
| `copilot-django` | `copilot_django` | The Django app: agents, tools, settings, checks, commands, test helpers. |

## Not yet implemented

Streaming responses, interactive approvals, Django signals, and the admin integration are
planned for later milestones (M3–M5).
