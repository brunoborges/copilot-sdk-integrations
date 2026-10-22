---
title: Streaming and structured output
---

# Streaming and structured output

Streaming, typed results, and cancellation come from the `Microsoft.Extensions.AI` interface and
map to Copilot SDK features, so there is no extra API to learn.

**In this chapter**

- [Streaming](#streaming)
- [Server-sent events](#server-sent-events)
- [Structured output](#structured-output)
- [Usage](#usage)
- [Timeouts and cancellation](#timeouts-and-cancellation)
- [Errors](#errors)

## Streaming

```cs
await foreach (var update in chat.GetStreamingResponseAsync(question, new() { ConversationId = id }, ct))
{
    Console.Write(update.Text);
}
```

A streaming call turns on the SDK's streaming for the session and yields one
`ChatResponseUpdate` per runtime event of the turn:

| Update | Contents |
| --- | --- |
| Text delta | `Text` with the next chunk, plus `MessageId`, `ConversationId`, and `ModelId`. If the model doesn't stream deltas, the whole message arrives as one update. |
| Usage | A `UsageContent` with the token counts of a model call (`Text` is empty). |
| Final | `FinishReason = ChatFinishReason.Stop`, with no text. |

Every update has `RawRepresentation` set to the SDK session event. Only the root agent's events
are streamed. `ToChatResponseAsync()` from `Microsoft.Extensions.AI` combines the updates into a
`ChatResponse`.

## Server-sent events

On .NET 10, `TypedResults.ServerSentEvents` streams the text to a browser with no extra code.
This is the `/chat/stream` endpoint of the [`SupportApi` sample](samples.md#supportapi):

```cs
app.MapPost("/chat/stream", (IChatClient chat, ChatRequest req, CancellationToken ct) =>
    TypedResults.ServerSentEvents(chat
        .GetStreamingResponseAsync(req.Message, new() { ConversationId = req.ConversationId }, ct)
        .Where(update => update.Text.Length > 0)
        .Select(update => update.Text)));
```

```bash
curl -N -H 'content-type: application/json' -d '{"message":"What can you help me with?"}' localhost:5180/chat/stream
```

The `Where` filter drops the usage and final updates, which have no text. When the client
disconnects, ASP.NET Core cancels the request token, which aborts the turn.

## Structured output

`GetResponseAsync<T>` returns a typed result:

```cs
public sealed record TicketTriage(
    [property: Description("One of: billing, shipping, product, account, other.")] string Category,
    [property: Description("One of: low, medium, high, urgent.")] string Priority,
    [property: Description("One-sentence summary of the customer's problem.")] string Summary);

var triage = await chat.GetResponseAsync<TicketTriage>(
    $"Triage this support ticket.\nSubject: {req.Subject}\n\n{req.Body}", cancellationToken: ct);
return triage.Result;
```

`Microsoft.Extensions.AI` builds a JSON schema for `T` and sends it in
`ChatOptions.ResponseFormat`. The integration passes it to the runtime as the message's response
schema, which constrains the reply, then `Microsoft.Extensions.AI` deserializes it. The schema
is made strict automatically (`additionalProperties: false`, every property required), as the
runtime's strict mode requires. `[Description]` on properties is passed to the model.

The [`InvoiceWorker` sample](samples.md#invoiceworker) uses the same call in a
`BackgroundService` to extract an `InvoiceExtraction` record from each invoice.

## Usage

`ChatResponse.Usage` sums the token usage of every model call in the turn (a turn that calls
tools makes several). Streaming calls report usage as `UsageContent` updates instead.

## Timeouts and cancellation

| Event | Result |
| --- | --- |
| `ResponseTimeout` elapses (default two minutes) | The runtime turn is aborted and `TimeoutException` is thrown. |
| The caller's `CancellationToken` is cancelled | The runtime turn is aborted and `OperationCanceledException` is thrown. |

The timeout clock starts after the turn acquires its
[conversation lock and concurrency slot](conversations.md#concurrency). Set the timeout in
configuration:

```json
{ "Copilot": { "ResponseTimeout": "00:05:00" } }
```

## Errors

| Situation | Exception |
| --- | --- |
| While streaming, the runtime reports a session error for the turn | `InvalidOperationException` (`Session error: ...`) |
| While streaming, the session is aborted before the turn completes | `InvalidOperationException` |
| The `MaxConcurrentTurns` queue is full | `InvalidOperationException` |
| History without a `ConversationId` | `InvalidOperationException` (see [Conversations](conversations.md#what-is-sent-each-turn)) |
| A `GitHubTokenProvider` returns `null` | `InvalidOperationException` (see [Per-user identity](identity.md#per-user-identity)) |

A failing tool does not fail the turn; see [Tools](tools.md#errors).
