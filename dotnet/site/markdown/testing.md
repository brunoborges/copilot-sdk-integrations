---
title: Testing
---

# Testing

Application code depends on `IChatClient`, not on the runtime, so tests replace it with a fake.
Tool classes are plain classes and are tested like any other service.

**In this chapter**

- [Fake the chat client](#fake-the-chat-client)
- [Integration tests with WebApplicationFactory](#integration-tests-with-webapplicationfactory)
- [Test tools directly](#test-tools-directly)
- [Test approval policies](#test-approval-policies)
- [The library's own tests](#the-librarys-own-tests)

## Fake the chat client

A minimal fake that returns a canned reply:

```cs
using Microsoft.Extensions.AI;

sealed class FakeChatClient(string reply) : IChatClient
{
    public List<ChatMessage> Received { get; } = [];

    public Task<ChatResponse> GetResponseAsync(
        IEnumerable<ChatMessage> messages, ChatOptions? options = null, CancellationToken cancellationToken = default)
    {
        Received.AddRange(messages);
        return Task.FromResult(new ChatResponse(new ChatMessage(ChatRole.Assistant, reply))
        {
            ConversationId = options?.ConversationId ?? "test-conversation",
        });
    }

    public async IAsyncEnumerable<ChatResponseUpdate> GetStreamingResponseAsync(
        IEnumerable<ChatMessage> messages, ChatOptions? options = null,
        [System.Runtime.CompilerServices.EnumeratorCancellation] CancellationToken cancellationToken = default)
    {
        var response = await GetResponseAsync(messages, options, cancellationToken);
        foreach (var update in response.ToChatResponseUpdates())
            yield return update;
    }

    public object? GetService(Type serviceType, object? serviceKey = null) => null;

    public void Dispose() { }
}
```

For [structured output](streaming.md#structured-output), return the JSON the model would
produce; `GetResponseAsync<T>` deserializes it from the response text.

## Integration tests with WebApplicationFactory

`AddCopilot` registers `IChatClient` with `TryAdd`, and the client only starts on first use,
so registering a fake in the test host is enough. No runtime is started and no token is
needed.

```cs
public class ChatEndpointTests(WebApplicationFactory<Program> factory)
    : IClassFixture<WebApplicationFactory<Program>>
{
    [Fact]
    public async Task Chat_returns_the_reply()
    {
        var fake = new FakeChatClient("Order 1002 has shipped.");
        var client = factory.WithWebHostBuilder(b => b.ConfigureTestServices(services =>
            services.AddSingleton<IChatClient>(fake))).CreateClient();

        var response = await client.PostAsJsonAsync("/chat", new { message = "Where is order 1002?" });

        response.EnsureSuccessStatusCode();
        Assert.Contains("1002", fake.Received.Single().Text);
    }
}
```

For a [named agent](chat-clients.md#named-agents), register the fake as a keyed service with
the agent's name: `services.AddKeyedSingleton<IChatClient>("triage", fake)`.

The [health check](observability.md#health-checks) reports Healthy while the client hasn't
started, so `/health` also passes without a runtime.

## Test tools directly

A tool class is an ordinary class with constructor dependencies, so call its methods directly:

```cs
[Fact]
public void LookupOrder_returns_the_order()
{
    var tools = new OrderTools(new InMemoryOrderRepository());

    var order = tools.LookupOrder(1002);

    Assert.Equal("4K monitor", order?.Product);
}
```

What the model sees (tool names, descriptions, and the JSON schema of parameters) is derived
by `Microsoft.Extensions.AI` from the method signature and `[Description]` attributes; see
[Tools](tools.md).

## Test approval policies

An `OnApproval` delegate is plain code. Keep the policy in a method you can call from a test,
and assign it in `AddCopilot`:

```cs
public static class RefundPolicy
{
    public static ValueTask<bool> Approve(CopilotApprovalRequest call) =>
        ValueTask.FromResult(call.GetArgument<decimal>("amount") < 100);
}

builder.Services.AddCopilot(o => o.OnApproval = RefundPolicy.Approve).WithTools<OrderTools>();
```

See [Approvals and permissions](permissions.md).

## The library's own tests

The repository's tests use xunit v3 on the Microsoft.Testing.Platform runner (set in
`global.json`). They run against an internal fake session gateway, so they need no runtime and
no token. That fake is an implementation detail, not a public API.

From `dotnet/`:

```bash
dotnet test --solution CopilotSdk.slnx
```

A small set of live tests (`LiveChatClientTests`) calls a real runtime and is skipped unless
`COPILOT_LIVE_TESTS=1` is set:

```bash
COPILOT_LIVE_TESTS=1 dotnet test --solution CopilotSdk.slnx
```
