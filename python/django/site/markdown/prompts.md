---
title: Prompts and typed results
---

# Prompts and typed results

`@prompt` turns a method stub into an agent call. The decorator's template is rendered with the
method's arguments, and the return annotation types the reply.

```python
from pydantic import BaseModel
from copilot_django import CopilotAgent, prompt


class Decision(BaseModel):
    approve: bool
    reason: str


class SupportAgent(CopilotAgent):
    @prompt("Should order {{ order_id }} be refunded? The customer says: {{ reason }}")
    def decide_refund(self, order_id: int, reason: str) -> Decision: ...

    @prompt(template_name="support/greeting.txt")
    def greet(self) -> str: ...

    @prompt("Summarize ticket {{ ticket.pk }}: {{ ticket.body }}")
    async def asummarize(self, ticket) -> str: ...
```

```python
decision = SupportAgent().decide_refund(42, "arrived broken")
assert isinstance(decision, Decision)

summary = await SupportAgent().asummarize(ticket)
```

- The method body is never run.
- An `async def` stub produces an async method.
- Pass `conversation_id="..."` to continue a [conversation](conversations.md) instead of
  using a one-shot session.

## Result types

| Annotation | Result |
|---|---|
| `str` or none | The reply text. |
| A pydantic model | The runtime is asked for JSON matching the model's schema, and the reply is validated into the model. |
| `CopilotReply` | Text plus session ID, conversation ID, and tool calls. |

A reply that doesn't validate raises `CopilotInvocationError`.

## Templates

Prompts use the Django template engine from `TEMPLATES`, so `template_name` finds templates in
app `templates/` directories. The context contains the method arguments plus `agent`, `user`,
`request`, and `conversation_id`.

Prompt rendering differs from HTML rendering in two ways:

- **Autoescaping is off**: prompts are text, so `<`, `&` and quotes are passed through
  unchanged.
- **Undefined variables raise** `PromptRenderError` instead of rendering as empty strings.
  Because of this, the `|default` filter can't rescue an undefined variable; use
  `{% if %}` instead.
