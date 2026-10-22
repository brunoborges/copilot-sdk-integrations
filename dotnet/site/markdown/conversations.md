---
title: Conversations
---

# Conversations

The Copilot runtime keeps conversation history in its session. Your app keeps only the
conversation id and passes it as `ChatOptions.ConversationId`; the integration creates or
resumes the session and makes sure two requests never send to the same session at once.

**In this chapter**

- [Create and resume](#create-and-resume)
- [What is sent each turn](#what-is-sent-each-turn)
- [Concurrency](#concurrency)
- [Ownership](#ownership)
- [Session lifetime](#session-lifetime)

## Create and resume

```cs
app.MapPost("/chat", async (IChatClient chat, ChatRequest req, CancellationToken ct) =>
{
    var response = await chat.GetResponseAsync(req.Message, new() { ConversationId = req.ConversationId }, ct);
    return new ChatReply(response.Text, response.ConversationId);
});
```

| `ChatOptions.ConversationId` | Behavior |
| --- | --- |
| Not set (or blank) | A new Copilot session is created. `ChatResponse.ConversationId` returns its id so the caller can continue it. |
| Set | That Copilot session is resumed with the agent's current settings. |

The conversation id is the Copilot session id. Streaming updates carry it too, in
`ChatResponseUpdate.ConversationId`.

## What is sent each turn

- **The new turn** is every user message after the last assistant message, combined into one
  prompt. `DataContent` in those messages becomes an attachment. Earlier history already lives
  in the runtime.
- **Do not replay history.** A request that contains assistant messages but no
  `ConversationId` throws `InvalidOperationException`, because replaying a transcript into a
  new session would silently diverge from the runtime's history. Send only the new user message
  with the `ConversationId`.
- **A request with no user message** (or a user message with no text and no data) throws
  `ArgumentException`.
- **System messages and `ChatOptions.Instructions`** are appended to the system message of new
  conversations only. The agent's configured `Instructions` apply to every session.
- **`ChatOptions.ModelId`, `Reasoning`, and `Tools`** apply per call. See
  [Per-call ChatOptions](chat-clients.md#per-call-chatoptions).

## Concurrency

- **Per conversation:** concurrent calls with the same `ConversationId` are queued, not
  interleaved. The lock is in-process; if several instances of your app share a runtime, route
  a conversation to one instance or serialize at your own layer.
- **Across the app:** set `Copilot:MaxConcurrentTurns` to cap concurrent turns. Extra turns wait
  in a queue of up to 8× the limit; beyond that, the call throws `InvalidOperationException`.
- **Timeouts:** `ResponseTimeout` (default two minutes) starts after the turn acquires its
  conversation lock and concurrency slot, so waiting in the queue doesn't count against it.
  See [Timeouts and cancellation](streaming.md#timeouts-and-cancellation).
- **Retries:** none. A turn that ran tools is not safe to replay. Use
  `Microsoft.Extensions.Resilience` around calls where retries make sense.

## Ownership

The conversation id gives access to the conversation's history. Store it on your own entity
(a ticket, a customer, a chat thread) and authorize access to that entity as usual. Never
accept a raw conversation id from one user and resume it for another.

```cs
app.MapPost("/tickets/{id:int}/chat", async (int id, ChatRequest req, TicketStore tickets,
    IChatClient chat, ClaimsPrincipal user, CancellationToken ct) =>
{
    var ticket = await tickets.FindForUserAsync(id, user, ct);   // your authorization
    if (ticket is null) return Results.NotFound();

    var response = await chat.GetResponseAsync(req.Message, new() { ConversationId = ticket.ConversationId }, ct);
    ticket.ConversationId = response.ConversationId;
    await tickets.SaveAsync(ticket, ct);
    return Results.Ok(new ChatReply(response.Text, response.ConversationId));
});
```

`TicketStore` here stands for your own persistence; it is not part of the library.

## Session lifetime

Sessions are disconnected after each call and resumed on the next; history stays in the
runtime (see [Runtime and identity](identity.md#client-mode-and-runtime-home) for where the
SDK-managed runtime keeps its state). For full session management, use the injected
[`CopilotClient`](chat-clients.md#the-copilotclient-escape-hatch).
