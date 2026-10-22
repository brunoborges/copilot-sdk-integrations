---
title: Conversations
---

# Conversations

Copilot keeps conversation history in its runtime session, not in your application. A
conversation is identified by an agent, a principal, and a conversation id you choose.

**In this chapter**

- [Ephemeral and persistent turns](#ephemeral-and-persistent-turns)
- [Session ids](#session-ids)
- [Conversation modes](#conversation-modes)
- [Deleting conversations](#deleting-conversations)
- [Turn admission](#turn-admission)
- [Timeouts and cancellation](#timeouts-and-cancellation)
- [Session stores and multiple replicas](#session-stores-and-multiple-replicas)

## Ephemeral and persistent turns

| Call | Session |
| --- | --- |
| `agent.send({ prompt })` | Ephemeral: `copilot-<agent>-<uuid>`, deleted after the turn. |
| `agent.send({ prompt, conversationId })` | Persistent: created on the first turn, resumed afterwards (`create-or-resume`). |
| `agent.conversation(id, { mode })` | A bound handle with an explicit mode; `resume-only` by default. |

The session is always disconnected after the turn; persistent sessions remain on the runtime
and are resumed by id.

```ts
const chat = agent.conversation("c1", { mode: "create-or-resume" });
await chat.send("Hi, I need help with order 42");
for await (const event of chat.stream("What's its status?")) { /* ... */ }
```

## Session ids

`deriveSessionId(agentName, principal, conversationId)` returns
`copilot-<agent>-<32 hex chars>`: a SHA-256 digest of the length-prefixed agent name,
principal, and conversation id.

- The principal comes from the explicit `principal` option, then `context.principal` of the
  send call, then the ambient [`CopilotContext`](context-and-identity.md).
- Two users with the same conversation id get different sessions, and a caller cannot resume
  another principal's conversation.
- An anonymous caller (`principal` undefined) never shares a session with a principal named `""`.
- The agent name is part of the id; renaming an agent orphans its sessions.

## Conversation modes

| Mode | Missing conversation | Existing conversation |
| --- | --- | --- |
| `create-or-resume` | created | resumed |
| `resume-only` | `CopilotConversationNotFoundError` (HTTP `404`) | resumed |
| `create-only` | created | `CopilotConversationExistsError` (HTTP `409`) |

In NestJS, `CopilotAgents.create(Agent, id)` is `create-or-resume` and
`CopilotAgents.bind(Agent, id, { mode })` defaults to `resume-only`. A method called directly
with a `@ConversationId()` argument uses `create-or-resume`.

```ts
@Post(":id/messages")
continue(@Param("id") id: string, @Body("text") text: string) {
  return this.agents.bind(SupportAgent, id).answer(text); // 404 for unknown conversations
}
```

## Deleting conversations

```ts
await agent.deleteConversation("c1");                     // core
await agents.delete(SupportAgent, "c1");                  // NestJS
await agents.delete("support", "c1", { principal: "u1" }); // explicit principal
```

Deletion takes the conversation lock, so it waits for a running turn.

## Turn admission

Each turn goes through, in order:

1. **Queue.** A place in the admission queue. When `limits.maxQueuedTurns` (default 64) are
   already waiting, the turn fails immediately with `CopilotAdmissionRejectedError`
   (HTTP `429` with `Retry-After`).
2. **Conversation lock.** One turn per conversation at a time. A second turn waits up to
   `limits.conversationWaitMs` (default 30 s), then fails with `CopilotConversationBusyError`
   (HTTP `409`).
3. **Store lease.** With a `store`, a cross-replica lease on `copilot:conversation:<sessionId>`.
4. **Execution slot.** At most `limits.maxConcurrentTurns` (default 16) turns run at once.

`copilot.capacity()` reports `activeTurns`, `queuedTurns`, and the limits.

## Timeouts and cancellation

- `responseTimeout` (default 5 minutes), or the per-call `timeout`, bounds the whole turn
  **including** time spent queued. On expiry the turn fails with `CopilotTimeoutError` (HTTP `504`).
- An aborted `signal` fails the turn with `CopilotAbortedError` (HTTP `499`). In NestJS the
  request signal is aborted when the client disconnects.
- On timeout or abort, the runtime session is aborted (`session.abort()`), so the model stops
  generating and tools stop being called.
- Breaking out of a stream, or unsubscribing from an Observable, aborts the turn.

## Session stores and multiple replicas

The conversation lock is per process. With several replicas, configure a `store` so that two
replicas never run a turn of the same conversation at once:

```ts
interface SessionStore {
  acquireLease(key: string, ttlMs: number, waitMs: number): Promise<(() => Promise<void>) | undefined>;
  get(key: string): Promise<string | undefined>;
  set(key: string, value: string, ttlMs?: number): Promise<void>;
  delete(key: string): Promise<void>;
}
```

The lease TTL is the turn timeout plus 30 seconds, so a crashed replica cannot block a
conversation forever. `memoryStore()` is a single-process implementation for tests and
single-replica deployments. No shared (for example Redis) store ships yet; implement the
interface over your store of choice.

All replicas must also reach the same Copilot runtime state for sessions to resume: connect
them to a shared runtime server with `cliUrl`, or route each conversation to one replica.
