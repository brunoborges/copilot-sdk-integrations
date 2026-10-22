using CopilotSdk.Extensions.Hosting;
using GitHub.Copilot;
using Microsoft.Extensions.AI;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Options;

namespace CopilotSdk.Extensions.Hosting.Tests;

public sealed class ChatClientTests
{
    private static readonly AsyncLocal<string?> s_user = new();
    private static readonly TimeSpan s_wait = TimeSpan.FromSeconds(10);

    private sealed class Fixture : IAsyncDisposable
    {
        public Fixture(Action<CopilotOptions>? configure)
        {
            var services = new ServiceCollection();
            services.AddSingleton<IConfiguration>(new ConfigurationBuilder().Build());
            services.AddCopilot(configure);
            services.AddSingleton<ICopilotSessionGateway>(Gateway);
            Services = services.BuildServiceProvider();
            Chat = ActivatorUtilities.CreateInstance<CopilotChatClient>(Services, Options.DefaultName);
        }

        public FakeSessionGateway Gateway { get; } = new();

        public ServiceProvider Services { get; }

        public CopilotChatClient Chat { get; }

        public FakeSession Session => Assert.Single(Gateway.Sessions);

        public SessionConfigBase Config => Assert.Single(Gateway.Calls).Config;

        public ValueTask DisposeAsync() => Services.DisposeAsync();
    }

    private static Fixture Create(Action<CopilotOptions>? configure = null) => new(configure);

    [Fact]
    public async Task NoConversationId_CreatesSession_AndReturnsItsId()
    {
        await using var f = Create();
        f.Gateway.Setup = s => s.OnSendAndWait = (session, _, _) =>
        {
            session.Emit(FakeEvents.Usage(3, 4));
            session.Emit(FakeEvents.Usage(100, 100, agentId: "sub-agent"));
            session.Emit(FakeEvents.Usage(1, 2));
            return Task.FromResult<AssistantMessageEvent?>(FakeEvents.Assistant("Hello!"));
        };

        var response = await f.Chat.GetResponseAsync("hi", cancellationToken: TestContext.Current.CancellationToken);

        var call = Assert.Single(f.Gateway.Calls);
        Assert.Null(call.ResumedId);
        Assert.IsType<SessionConfig>(call.Config);
        Assert.Equal("hi", Assert.Single(f.Session.Sent).Prompt);
        Assert.Equal("Hello!", response.Text);
        Assert.Equal("session-1", response.ConversationId);
        Assert.Equal("assistant-1", response.ResponseId);
        Assert.Equal("test-model", response.ModelId);
        Assert.Equal(ChatFinishReason.Stop, response.FinishReason);
        Assert.Equal(4, response.Usage!.InputTokenCount);
        Assert.Equal(6, response.Usage.OutputTokenCount);
        Assert.Equal(10, response.Usage.TotalTokenCount);
        Assert.True(f.Session.Disposed);
        Assert.Equal(0, f.Session.AbortCount);
    }

    [Fact]
    public async Task ConversationId_ResumesSession_AndSendsOnlyTheLastUserMessage()
    {
        await using var f = Create();
        List<ChatMessage> history =
        [
            new(ChatRole.User, "first"),
            new(ChatRole.Assistant, "answer"),
            new(ChatRole.User, "second"),
        ];

        var response = await f.Chat.GetResponseAsync(history, new ChatOptions { ConversationId = "conv-42" }, TestContext.Current.CancellationToken);

        var call = Assert.Single(f.Gateway.Calls);
        Assert.Equal("conv-42", call.ResumedId);
        Assert.IsType<ResumeSessionConfig>(call.Config);
        Assert.Equal("second", Assert.Single(f.Session.Sent).Prompt);
        Assert.Equal("conv-42", response.ConversationId);
        Assert.True(f.Session.Disposed);
        Assert.Equal(0, f.Services.GetRequiredService<CopilotConversationLocks>().Count);
    }

    [Fact]
    public async Task HistoryWithoutConversationId_IsRejected()
    {
        await using var f = Create();
        List<ChatMessage> history = [new(ChatRole.User, "first"), new(ChatRole.Assistant, "answer"), new(ChatRole.User, "second")];

        var ex = await Assert.ThrowsAsync<InvalidOperationException>(() =>
            f.Chat.GetResponseAsync(history, cancellationToken: TestContext.Current.CancellationToken));

        Assert.Contains("ConversationId", ex.Message, StringComparison.Ordinal);
        Assert.Empty(f.Gateway.Calls);
    }

    [Fact]
    public async Task NoUserMessage_IsRejected()
    {
        await using var f = Create();

        await Assert.ThrowsAsync<ArgumentException>(() =>
            f.Chat.GetResponseAsync([new ChatMessage(ChatRole.System, "x")], cancellationToken: TestContext.Current.CancellationToken));
        Assert.Empty(f.Gateway.Calls);
    }

    [Fact]
    public async Task SystemMessagesAndInstructions_AreAppendedForNewSessions()
    {
        await using var f = Create(o => o.Instructions = "Agent instructions.");
        List<ChatMessage> messages = [new(ChatRole.System, "Answer in French."), new(ChatRole.User, "hi")];

        await f.Chat.GetResponseAsync(messages, new ChatOptions { Instructions = "Be brief." }, TestContext.Current.CancellationToken);

        var system = f.Config.SystemMessage!;
        Assert.Equal(SystemMessageMode.Append, system.Mode);
        Assert.Equal("Agent instructions.\n\nAnswer in French.\n\nBe brief.", system.Content);
    }

    [Fact]
    public async Task SystemMessages_AreIgnoredForResumedSessions()
    {
        await using var f = Create(o => o.Instructions = "Agent instructions.");
        List<ChatMessage> messages = [new(ChatRole.System, "Answer in French."), new(ChatRole.User, "hi")];

        await f.Chat.GetResponseAsync(messages, new ChatOptions { ConversationId = "c", Instructions = "Be brief." }, TestContext.Current.CancellationToken);

        Assert.DoesNotContain("French", f.Config.SystemMessage?.Content ?? "", StringComparison.Ordinal);
        Assert.DoesNotContain("brief", f.Config.SystemMessage?.Content ?? "", StringComparison.Ordinal);
    }

    [Fact]
    public async Task ChatOptions_MapToSessionConfig()
    {
        await using var f = Create(o => o.Model = "configured-model");
        var tool = AIFunctionFactory.Create(() => "ok", "per_call_tool");

        await f.Chat.GetResponseAsync("hi", new ChatOptions
        {
            ModelId = "override-model",
            Reasoning = new ReasoningOptions { Effort = ReasoningEffort.ExtraHigh },
            Tools = [tool],
        }, TestContext.Current.CancellationToken);

        var config = f.Config;
        Assert.Equal("override-model", config.Model);
        Assert.Equal("xhigh", config.ReasoningEffort);
        var added = Assert.Single(config.Tools!, t => t.Name == "per_call_tool");
        Assert.Equal(true, added.AdditionalProperties["skip_permission"]);
    }

    [Fact]
    public async Task DataContent_IsSentAsBlobAttachment()
    {
        await using var f = Create();
        var message = new ChatMessage(ChatRole.User, [new TextContent("describe"), new DataContent(new byte[] { 1, 2, 3 }, "image/png")]);

        await f.Chat.GetResponseAsync([message], cancellationToken: TestContext.Current.CancellationToken);

        var sent = Assert.Single(f.Session.Sent);
        Assert.Equal("describe", sent.Prompt);
        var blob = Assert.IsType<AttachmentBlob>(Assert.Single(sent.Attachments!));
        Assert.Equal("image/png", blob.MimeType);
        Assert.Equal(Convert.ToBase64String(new byte[] { 1, 2, 3 }), blob.Data);
    }

    public sealed record Triage(string Category, int Priority);

    [Fact]
    public async Task GetResponseAsyncOfT_PassesResponseSchema_AndParsesResult()
    {
        await using var f = Create();
        f.Gateway.Setup = s => s.OnSendAndWait = (_, message, _) =>
            Task.FromResult<AssistantMessageEvent?>(FakeEvents.Assistant(
                message.ResponseSchema is null ? "no schema" : """{"category":"billing","priority":2}"""));

        var response = await f.Chat.GetResponseAsync<Triage>("classify", cancellationToken: TestContext.Current.CancellationToken);

        Assert.NotNull(Assert.Single(f.Session.Sent).ResponseSchema);
        Assert.Equal(new Triage("billing", 2), response.Result);
    }

    [Fact]
    public async Task ResponseSchema_IsMadeStrict()
    {
        await using var f = Create();
        f.Gateway.Setup = s => s.OnSendAndWait = (_, _, _) =>
            Task.FromResult<AssistantMessageEvent?>(FakeEvents.Assistant("""{"category":"billing","priority":2}"""));

        await f.Chat.GetResponseAsync<Triage>("classify", cancellationToken: TestContext.Current.CancellationToken);

        var schema = System.Text.Json.JsonSerializer.SerializeToElement(Assert.Single(f.Session.Sent).ResponseSchema);
        Assert.False(schema.GetProperty("additionalProperties").GetBoolean());
        Assert.Equal(2, schema.GetProperty("required").GetArrayLength());
    }

    [Fact]
    public async Task TrailingUserMessages_AreCombinedIntoOnePrompt()
    {
        await using var f = Create();

        await f.Chat.GetResponseAsync(
            [new(ChatRole.User, "first"), new(ChatRole.User, "second")],
            cancellationToken: TestContext.Current.CancellationToken);

        Assert.Equal("first\n\nsecond", Assert.Single(f.Session.Sent).Prompt);
    }

    [Fact]
    public async Task SameConversation_IsSerialized()
    {
        await using var f = Create();
        var release = new TaskCompletionSource();
        var active = 0;
        var maxActive = 0;
        f.Gateway.Setup = s => s.OnSendAndWait = async (_, _, ct) =>
        {
            var now = Interlocked.Increment(ref active);
            InterlockedMax(ref maxActive, now);
            await release.Task.WaitAsync(ct);
            Interlocked.Decrement(ref active);
            return FakeEvents.Assistant("done");
        };
        var options = new ChatOptions { ConversationId = "conv" };
        var ct = TestContext.Current.CancellationToken;

        var first = f.Chat.GetResponseAsync("one", options, ct);
        var second = f.Chat.GetResponseAsync("two", options, ct);
        await WaitUntil(() => Volatile.Read(ref active) == 1);
        await Task.Delay(100, ct);

        Assert.Single(f.Gateway.Calls);
        release.SetResult();
        await Task.WhenAll(first, second).WaitAsync(s_wait, ct);

        Assert.Equal(1, maxActive);
        Assert.Equal(2, f.Gateway.Calls.Count);
        Assert.Equal(0, f.Services.GetRequiredService<CopilotConversationLocks>().Count);
    }

    [Fact]
    public async Task DifferentConversations_RunConcurrently()
    {
        await using var f = Create();
        var bothEntered = new TaskCompletionSource();
        var entered = 0;
        f.Gateway.Setup = s => s.OnSendAndWait = async (_, _, ct) =>
        {
            if (Interlocked.Increment(ref entered) == 2)
            {
                bothEntered.SetResult();
            }

            await bothEntered.Task.WaitAsync(ct);
            return FakeEvents.Assistant("done");
        };
        var ct = TestContext.Current.CancellationToken;

        await Task.WhenAll(
            f.Chat.GetResponseAsync("one", new ChatOptions { ConversationId = "a" }, ct),
            f.Chat.GetResponseAsync("two", new ChatOptions { ConversationId = "b" }, ct)).WaitAsync(s_wait, ct);
    }

    [Fact]
    public async Task Cancellation_AbortsTurn_AndThrowsOperationCanceled()
    {
        await using var f = Create();
        f.Gateway.Setup = s => s.OnSendAndWait = async (_, _, ct) =>
        {
            await Task.Delay(Timeout.Infinite, ct);
            return null;
        };
        using var cts = CancellationTokenSource.CreateLinkedTokenSource(TestContext.Current.CancellationToken);
        cts.CancelAfter(100);

        var ex = await Assert.ThrowsAnyAsync<OperationCanceledException>(() => f.Chat.GetResponseAsync("hi", cancellationToken: cts.Token));

        Assert.Equal(cts.Token, ex.CancellationToken);
        Assert.Equal(1, f.Session.AbortCount);
        Assert.True(f.Session.Disposed);
    }

    [Fact]
    public async Task ResponseTimeout_AbortsTurn_AndThrowsTimeout()
    {
        await using var f = Create(o => o.ResponseTimeout = TimeSpan.FromMilliseconds(100));
        f.Gateway.Setup = s => s.OnSendAndWait = async (_, _, ct) =>
        {
            await Task.Delay(Timeout.Infinite, ct);
            return null;
        };

        await Assert.ThrowsAsync<TimeoutException>(() => f.Chat.GetResponseAsync("hi", cancellationToken: TestContext.Current.CancellationToken));

        Assert.Equal(1, f.Session.AbortCount);
        Assert.True(f.Session.Disposed);
    }

    [Fact]
    public async Task MaxConcurrentTurns_QueuesThenRejectsWhenQueueIsFull()
    {
        await using var f = Create(o => o.MaxConcurrentTurns = 1);
        var release = new TaskCompletionSource();
        f.Gateway.Setup = s => s.OnSendAndWait = async (_, _, ct) =>
        {
            await release.Task.WaitAsync(ct);
            return FakeEvents.Assistant("done");
        };
        var ct = TestContext.Current.CancellationToken;

        var accepted = Enumerable.Range(0, 1 + CopilotTurnLimiter.QueueFactor)
            .Select(i => f.Chat.GetResponseAsync($"m{i}", cancellationToken: ct))
            .ToList();
        var rejected = await Assert.ThrowsAsync<InvalidOperationException>(() => f.Chat.GetResponseAsync("overflow", cancellationToken: ct));
        Assert.Contains("MaxConcurrentTurns", rejected.Message, StringComparison.Ordinal);
        Assert.Single(f.Gateway.Calls);

        release.SetResult();
        await Task.WhenAll(accepted).WaitAsync(s_wait, ct);
        Assert.Equal(1 + CopilotTurnLimiter.QueueFactor, f.Gateway.Calls.Count);
    }

    [Fact]
    public async Task GitHubTokenProvider_ReturningNull_FailsTheCall()
    {
        await using var f = Create(o => o.GitHubTokenProvider = (_, _) => Task.FromResult<string?>(null));

        var ex = await Assert.ThrowsAsync<InvalidOperationException>(() => f.Chat.GetResponseAsync("hi", cancellationToken: TestContext.Current.CancellationToken));

        Assert.Contains("GitHubTokenProvider", ex.Message, StringComparison.Ordinal);
        Assert.Empty(f.Gateway.Calls);
    }

    [Fact]
    public async Task GitHubTokenProvider_SuppliesSessionToken_AndRefreshesInCallerContext()
    {
        var calls = 0;
        await using var f = Create(o =>
        {
            o.GitHubToken = "service-token";
            o.GitHubTokenProvider = (_, _) => Task.FromResult<string?>($"{s_user.Value}-{Interlocked.Increment(ref calls)}");
        });
        s_user.Value = "alice";

        await f.Chat.GetResponseAsync("hi", cancellationToken: TestContext.Current.CancellationToken);

        var config = f.Config;
        Assert.Null(config.GitHubToken);
        var provider = config.GitHubTokenProvider!;
        var initial = await provider(new GitHubTokenProviderArgs { Host = "https://github.com", Reason = GitHubTokenRequestReason.Initial });
        Assert.Equal("alice-1", initial.Token!.AccessToken);
        Assert.Equal(CopilotChatClient.TokenExpiresInSeconds, initial.Token.ExpiresIn);

        Task<GitHubTokenProviderResult> refreshTask;
        using (ExecutionContext.SuppressFlow())
        {
            refreshTask = Task.Run(() => provider(new GitHubTokenProviderArgs { Host = "https://github.com", Reason = GitHubTokenRequestReason.Refresh }));
        }

        Assert.Equal("alice-2", (await refreshTask).Token!.AccessToken);
    }

    [Fact]
    public async Task TurnContext_IsPublishedDuringTheTurn_WithCallerContext()
    {
        await using var f = Create();
        var registry = f.Services.GetRequiredService<CopilotTurnContextRegistry>();
        string? observedUser = null;
        string? toolResult = null;
        f.Gateway.Setup = s => s.OnSendAndWait = async (session, _, ct) =>
        {
            Assert.True(registry.TryGet(session.SessionId, out var context));
            Assert.Equal(Options.DefaultName, context.AgentName);
            Assert.NotNull(context.Services.GetService<CopilotTurnContextRegistry>());

            var tool = (AIFunction)session.Config.Tools!.Single(t => t.Name == "whoami");
            Task<object?> toolTask;
            using (ExecutionContext.SuppressFlow())
            {
                toolTask = Task.Run(async () =>
                {
                    ExecutionContext.Run(context.ExecutionContext!, _ => observedUser = s_user.Value, null);
                    return await tool.InvokeAsync(new AIFunctionArguments(), ct);
                }, ct);
            }

            toolResult = (await toolTask)?.ToString();
            return FakeEvents.Assistant("done");
        };
        s_user.Value = "bob";

        await f.Chat.GetResponseAsync("hi", new ChatOptions { Tools = [AIFunctionFactory.Create(() => s_user.Value ?? "nobody", "whoami")] },
            TestContext.Current.CancellationToken);

        Assert.Equal("bob", observedUser);
        Assert.Equal("bob", toolResult);
        Assert.False(registry.TryGet(f.Session.SessionId, out _));
    }

    [Fact]
    public async Task GetService_ReturnsMetadata()
    {
        await using var f = Create(o => o.Model = "gpt-test");

        var metadata = f.Chat.GetService<ChatClientMetadata>();

        Assert.Equal(CopilotChatClient.ProviderName, metadata!.ProviderName);
        Assert.Equal("gpt-test", metadata.DefaultModelId);
        Assert.Null(f.Chat.GetService<CopilotSession>());
        Assert.Same(f.Chat, f.Chat.GetService<CopilotChatClient>());
    }

    [Fact]
    public async Task Streaming_YieldsDeltas_UntilIdle()
    {
        await using var f = Create();
        f.Gateway.Setup = s => s.OnSend = (session, _, _) =>
        {
            session.Emit(FakeEvents.User("user-1"));
            session.Emit(FakeEvents.Delta("Hel"));
            session.Emit(FakeEvents.Delta("ignored", agentId: "sub-agent"));
            session.Emit(FakeEvents.Delta("lo"));
            session.Emit(FakeEvents.Assistant("Hello", originatingMessageId: "user-1"));
            session.Emit(FakeEvents.Usage(5, 2));
            session.Emit(FakeEvents.Idle());
            return Task.FromResult("user-1");
        };

        var updates = await f.Chat.GetStreamingResponseAsync("hi", cancellationToken: TestContext.Current.CancellationToken)
            .ToListAsync(TestContext.Current.CancellationToken);

        Assert.True(f.Config.Streaming);
        Assert.All(updates, u => Assert.Equal("session-1", u.ConversationId));
        var response = updates.ToChatResponse();
        Assert.Equal("Hello", response.Text);
        Assert.Equal(ChatFinishReason.Stop, updates[^1].FinishReason);
        Assert.Equal(5, response.Usage!.InputTokenCount);
        Assert.Equal(0, f.Session.AbortCount);
        Assert.True(f.Session.Disposed);
    }

    [Fact]
    public async Task Streaming_WithoutDeltas_YieldsWholeMessage()
    {
        await using var f = Create();
        f.Gateway.Setup = s => s.OnSend = (session, _, _) =>
        {
            session.Emit(FakeEvents.User("user-1"));
            session.Emit(FakeEvents.Assistant("Whole reply"));
            session.Emit(FakeEvents.Idle());
            return Task.FromResult("user-1");
        };

        var updates = await f.Chat.GetStreamingResponseAsync("hi", cancellationToken: TestContext.Current.CancellationToken)
            .ToListAsync(TestContext.Current.CancellationToken);

        Assert.Equal("Whole reply", string.Concat(updates.Select(u => u.Text)));
    }

    [Fact]
    public async Task Streaming_SessionError_Throws()
    {
        await using var f = Create();
        f.Gateway.Setup = s => s.OnSend = (session, _, _) =>
        {
            session.Emit(FakeEvents.User("user-1"));
            session.Emit(FakeEvents.Error("boom"));
            return Task.FromResult("user-1");
        };

        var ex = await Assert.ThrowsAsync<InvalidOperationException>(async () =>
            await f.Chat.GetStreamingResponseAsync("hi", cancellationToken: TestContext.Current.CancellationToken)
                .ToListAsync(TestContext.Current.CancellationToken));

        Assert.Contains("boom", ex.Message, StringComparison.Ordinal);
        Assert.True(f.Session.Disposed);
    }

    [Fact]
    public async Task Streaming_Cancellation_AbortsTurn()
    {
        await using var f = Create();
        f.Gateway.Setup = s => s.OnSend = (session, _, _) =>
        {
            session.Emit(FakeEvents.User("user-1"));
            return Task.FromResult("user-1");
        };
        using var cts = CancellationTokenSource.CreateLinkedTokenSource(TestContext.Current.CancellationToken);
        cts.CancelAfter(100);

        await Assert.ThrowsAnyAsync<OperationCanceledException>(async () =>
            await f.Chat.GetStreamingResponseAsync("hi", cancellationToken: cts.Token).ToListAsync(CancellationToken.None));

        Assert.Equal(1, f.Session.AbortCount);
        Assert.True(f.Session.Disposed);
    }

    [Fact]
    public async Task Streaming_AbandonedEnumeration_AbortsTurn()
    {
        await using var f = Create();
        f.Gateway.Setup = s => s.OnSend = (session, _, _) =>
        {
            session.Emit(FakeEvents.User("user-1"));
            session.Emit(FakeEvents.Delta("partial"));
            return Task.FromResult("user-1");
        };

        await foreach (var update in f.Chat.GetStreamingResponseAsync("hi", cancellationToken: TestContext.Current.CancellationToken))
        {
            Assert.Equal("partial", update.Text);
            break;
        }

        Assert.Equal(1, f.Session.AbortCount);
        Assert.True(f.Session.Disposed);
    }

    private static void InterlockedMax(ref int target, int value)
    {
        int current;
        while ((current = Volatile.Read(ref target)) < value && Interlocked.CompareExchange(ref target, value, current) != current)
        {
        }
    }

    private static async Task WaitUntil(Func<bool> condition)
    {
        var deadline = DateTime.UtcNow + s_wait;
        while (!condition())
        {
            Assert.True(DateTime.UtcNow < deadline, "Timed out waiting for condition.");
            await Task.Delay(10);
        }
    }
}
