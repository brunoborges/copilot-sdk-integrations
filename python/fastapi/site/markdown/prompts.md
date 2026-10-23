---
title: Prompts and typed results
---

# Prompts and typed results

`@prompt` turns a method stub into an agent call. The decorator's template is rendered with the
method's arguments, and the return annotation types the reply.

```python
from pydantic import BaseModel
from copilot_fastapi import CopilotAgent, prompt


class Decision(BaseModel):
    approve: bool
    reason: str


class SupportAgent(CopilotAgent):
    @prompt("Should order {order_id} be refunded? The customer says: {reason}")
    async def decide_refund(self, order_id: int, reason: str) -> Decision: ...

    @prompt("Greet {user.name} in one sentence.")
    def greet(self) -> str: ...
```

```python
decision = await agent.decide_refund(42, "arrived broken")
assert isinstance(decision, Decision)

greeting = agent.greet()          # sync stub: call it from a def endpoint
```

- The method body is never run.
- An `async def` stub produces an awaitable method; a `def` stub blocks.
- Pass `conversation_id="..."` to continue a [conversation](conversations.md) instead of
  using a one-shot session.

## Result types

| Annotation | Result |
|---|---|
| `str` or none | The reply text. |
| A pydantic model | The runtime is asked for JSON matching the model's schema, and the reply is validated into the model. |
| `CopilotReply` | Text plus session ID, conversation ID, and tool calls. |

A reply that doesn't validate raises `CopilotInvocationError`.

The return annotation is resolved on the first call, so forward references and
`from __future__ import annotations` work. If it can't be resolved, pass the type explicitly:
`@prompt("...", result=Decision)`.

## Templates

Templates use `str.format` syntax. They can reference the method arguments plus `user`,
`request`, `agent`, and `conversation_id`, including attribute and index access such as
`{user.name}` or `{items[0]}`.

- **Unknown names fail early**: a template that references a name that is neither an argument
  nor one of the built-in names raises `PromptRenderError` when the class is defined.
- **Missing values raise** `PromptRenderError` at call time instead of rendering as empty
  strings.
- Use `{{` and `}}` for literal braces, for example in JSON examples.
