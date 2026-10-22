---
title: Conversations and identity
---

# Conversations and identity

## Conversations

A conversation is a multi-turn session identified by a string you choose. The Copilot runtime
stores the history; Django stores nothing.

```python
conversation = SupportAgent().conversation(f"ticket-{ticket.pk}")
conversation.send("My order hasn't arrived.")             # created on first message
conversation.send("It was order 42.")                     # continues the same session

SupportAgent().resume(f"ticket-{ticket.pk}").send("Any update?")   # must already exist

conversation.exists()
conversation.delete()                                     # also removes its working directory
```

Each has an async variant: `asend`, `aresume`, `aexists`, `adelete`. `resume()` raises
`CopilotConversationNotFound` when the conversation doesn't exist. `@prompt` methods accept a
`conversation_id=` keyword.

Each call opens the session, sends one message, waits for the reply, and disconnects. Nothing
is held open between requests, so any worker process can continue any conversation.

## Partitioning by user

The runtime session ID is a hash of the agent class, the agent name, the conversation ID, and a
**partition**:

| Caller | Partition |
|---|---|
| Agent with a token resolver | The resolver's `partition_key()`. |
| Authenticated user | The user's primary key. |
| Anonymous or no user | Shared (no partition). |

So `conversation("ticket-7")` for user 1 and user 2 are different sessions, and one user can't
resume another's conversation. The session ID scheme is the same as the Java integrations.

The user comes from, in order: `SupportAgent(user=...)`, `SupportAgent(request=...)`, or the
request recorded by `CopilotContextMiddleware`. In async views the user is loaded with
`request.auser()`.

Outside a request (Celery tasks, management commands), pass the user explicitly:

```python
@shared_task
def triage_ticket(ticket_id):
    ticket = Ticket.objects.get(pk=ticket_id)
    TriageAgent(user=ticket.owner).triage(ticket.pk)
```

## Per-user tokens

By default every call uses `COPILOT["AUTH"]["GITHUB_TOKEN"]`, or the runtime's signed-in user.
To call Copilot as each end user, subclass `UserTokenResolver`:

```python
from copilot_django.auth import UserTokenResolver


class SocialAuthTokens(UserTokenResolver):
    def token_for_user(self, user):
        return user.social_auth.get(provider="github").extra_data["access_token"]
```

```python
COPILOT = {"AUTH": {"TOKEN_RESOLVER": "myproject.copilot.SocialAuthTokens"}}
```

`UserTokenResolver` partitions sessions by user pk. Anonymous callers fall back to the default
token. Implement `resolve(identity)` and `partition_key(identity)` directly for other schemes.
