---
title: Conversations and identity
---

# Conversations and identity

## Conversations

A conversation is a multi-turn session identified by a string you choose. The Copilot runtime
stores the history; your application stores nothing.

```python
conversation = agent.conversation(f"ticket-{ticket_id}")
await conversation.asend("My order hasn't arrived.")      # created on first message
await conversation.asend("It was order 42.")              # continues the same session

conversation = await agent.aresume(f"ticket-{ticket_id}") # must already exist
await conversation.asend("Any update?")

await conversation.aexists()
await conversation.adelete()                              # also removes its working directory
```

Each has a blocking variant for `def` endpoints: `send`, `resume`, `exists`, `delete`.
`resume()` raises `CopilotConversationNotFound` (HTTP 404 with the exception handlers) when the
conversation doesn't exist. `@prompt` methods accept a `conversation_id=` keyword.

Each call opens the session, sends one message, waits for the reply, and disconnects. Nothing
is held open between requests, so any worker process can continue any conversation.

## Listing, reading and deleting sessions

`copilot.session_store` returns a `CopilotSessionStore` for administrative work across
conversations, for example a chat history endpoint. Use it as a dependency. Every method has
a blocking variant without the `a` prefix.

```python
from copilot_fastapi import CopilotSessionStore

Sessions = Annotated[CopilotSessionStore, Depends(copilot.session_store)]

@app.get("/chats/{chat_id}")
async def history(chat_id: str, agent: Support, sessions: Sessions):
    messages = await sessions.aget_messages(sessions.session_id_for(agent, chat_id))
    if messages is None:
        raise HTTPException(404)
    return [{"role": m.role, "content": m.content} for m in messages]
```

- `alist()`: local session metadata, most recently modified first.
- `aget(id)` / `aexists(id)`: metadata or `None`, and a boolean.
- `aget_messages(id)`: user and assistant messages read from the persisted history without
  resuming the session. Tool calls and sub-agent messages are skipped.
- `adelete(id)`: `False` if the session did not exist. It waits for a turn in flight on that
  session and removes the session's working directory.
- `session_id_for(agent, conversation_id)`: an agent instance supplies its own user; an agent
  class or name takes `user=`.

## Removing old sessions

Cleanup is off by default. Set `COPILOT_SESSIONS__MAX_AGE` and the lifespan runs a background
task that deletes sessions created by this integration (their IDs start with `agent-`) that
have not been modified for that long. Remote sessions and sessions with a turn in flight are
never deleted, and a sweep never starts the runtime; see
[Configuration](configuration.md#session-cleanup).

## Partitioning by user

The runtime session ID is a hash of the agent class, the agent name, the conversation ID, and a
**partition**:

| Caller | Partition |
|---|---|
| Agent with a token resolver | The resolver's `partition_key()`. |
| A user | `user_key(user)`. |
| No user (`None`) | Shared (no partition). |

So `conversation("ticket-7")` for two users are different sessions, and one user can't resume
another's conversation. The session ID scheme is the same as the Java integrations.

The user is whatever your `current_user` dependency returns. The default `user_key` uses the
value itself when it is a `str` or `int`, and otherwise its `id`, `pk`, `sub`, or `username`
attribute (or key, for dicts). Pass your own function when that doesn't fit:

```python
copilot = Copilot(current_user=get_current_user, user_key=lambda user: user.email)
```

If a user has no key, the call fails with `CopilotConfigurationError`. Conversations are never
silently shared.

## Per-user tokens

By default every call uses `COPILOT_AUTH__GITHUB_TOKEN`, or the runtime's signed-in user. To
call Copilot as each end user, subclass `UserTokenResolver`:

```python
from copilot_fastapi import UserTokenResolver


class GitHubTokens(UserTokenResolver):
    def token_for_user(self, user):
        return user.github_access_token


copilot = Copilot(current_user=get_current_user, token_resolver=GitHubTokens())
```

`UserTokenResolver` partitions sessions with the app's `user_key` unless you pass
`UserTokenResolver(user_key=...)`. Callers without a user fall back to the default token and
shared sessions. Implement `resolve(identity)` and `partition_key(identity)` directly for other
schemes.
