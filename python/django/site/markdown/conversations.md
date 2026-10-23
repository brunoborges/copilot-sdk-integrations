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

## Listing, reading and deleting sessions

`get_session_store()` returns a `SessionStore` for administrative work across conversations,
for example a chat history page or an admin action. Each method has an `a`-prefixed async
variant.

```python
from copilot_django import get_session_store

store = get_session_store()
store.list()                       # local session metadata, most recently modified first
session_id = store.session_id_for(SupportAgent, f"ticket-{ticket.pk}", user=request.user)
store.get(session_id)              # metadata, or None
store.exists(session_id)
for message in store.get_messages(session_id) or []:   # None when the session is missing
    print(message.role, message.content)               # "user" / "assistant"
store.delete(session_id)           # False if it did not exist; removes the working directory
```

`session_id_for` accepts an agent class, an agent instance (which supplies its own user), or a
registered agent name. `get_messages` reads the persisted history without resuming the session
and skips tool calls and sub-agent messages. `delete` waits for a turn in flight on that
session to finish.

## Removing old sessions

Cleanup is off by default. Set `COPILOT["SESSIONS"]["MAX_AGE"]` to delete sessions that were
created by this integration (their IDs start with `agent-`) and have not been modified for
that long. Remote sessions and sessions with a turn in flight are never deleted, and a sweep
never starts the runtime; see [Configuration](configuration.md#session-cleanup).

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
