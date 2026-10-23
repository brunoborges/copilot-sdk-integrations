using GitHub.Copilot;
using Microsoft.Extensions.AI;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Hosting;

namespace CopilotSdk.Extensions.Hosting.Tests;

public sealed class SessionStoreTests
{
    private static readonly DateTimeOffset Now = new(2026, 1, 15, 12, 0, 0, TimeSpan.Zero);

    private static SessionMetadata Session(string id, TimeSpan age, bool remote = false) =>
        new() { SessionId = id, StartTime = Now - age, ModifiedTime = Now - age, IsRemote = remote };

    [Fact]
    public async Task List_ExcludesRemote_NewestFirst()
    {
        var storage = new FakeSessionStorage(Session("old", TimeSpan.FromDays(2)), Session("new", TimeSpan.FromHours(1)), Session("remote", TimeSpan.Zero, remote: true));
        var store = new CopilotSessionStore(storage, new CopilotConversationLocks());

        var sessions = await store.ListAsync(TestContext.Current.CancellationToken);

        Assert.Equal(["new", "old"], sessions.Select(s => s.SessionId));
    }

    [Fact]
    public async Task GetMessages_MapsRootUserAndAssistantMessages()
    {
        var storage = new FakeSessionStorage(Session("s1", TimeSpan.Zero));
        storage.Events["s1"] =
        [
            new UserMessageEvent { Data = new UserMessageData { Content = "hi", MessageId = "u1" } },
            FakeEvents.Assistant(string.Empty, "a0"),
            FakeEvents.Assistant("sub-agent", "a1", agentId: "sub"),
            FakeEvents.Assistant("hello", "a2"),
            FakeEvents.Idle(),
        ];
        var store = new CopilotSessionStore(storage, new CopilotConversationLocks());

        var messages = await store.GetMessagesAsync("s1", TestContext.Current.CancellationToken);

        Assert.NotNull(messages);
        Assert.Collection(
            messages,
            m => { Assert.Equal(ChatRole.User, m.Role); Assert.Equal("hi", m.Text); Assert.Equal("u1", m.MessageId); },
            m => { Assert.Equal(ChatRole.Assistant, m.Role); Assert.Equal("hello", m.Text); Assert.IsType<AssistantMessageEvent>(m.RawRepresentation); });
    }

    [Fact]
    public async Task GetMessages_Missing_ReturnsNull()
    {
        var store = new CopilotSessionStore(new FakeSessionStorage(), new CopilotConversationLocks());
        Assert.Null(await store.GetMessagesAsync("missing", TestContext.Current.CancellationToken));
    }

    [Fact]
    public async Task Delete_ReturnsWhetherDeleted()
    {
        var storage = new FakeSessionStorage(Session("s1", TimeSpan.Zero));
        var store = new CopilotSessionStore(storage, new CopilotConversationLocks());

        Assert.True(await store.DeleteAsync("s1", TestContext.Current.CancellationToken));
        Assert.False(await store.DeleteAsync("s1", TestContext.Current.CancellationToken));
        Assert.Equal(["s1"], storage.Deleted);
    }

    [Fact]
    public async Task Delete_WaitsForTurnInFlight()
    {
        var ct = TestContext.Current.CancellationToken;
        var storage = new FakeSessionStorage(Session("s1", TimeSpan.Zero));
        var locks = new CopilotConversationLocks();
        var store = new CopilotSessionStore(storage, locks);

        var turn = await locks.AcquireAsync("s1", ct);
        var delete = store.DeleteAsync("s1", ct);
        await Task.Delay(50, ct);
        Assert.False(delete.IsCompleted);

        turn.Dispose();
        Assert.True(await delete);
        Assert.Equal(0, locks.Count);
    }

    [Fact]
    public void TryAcquire_FailsWhileHeld_AndReleases()
    {
        var locks = new CopilotConversationLocks();
        using (var held = locks.TryAcquire("s1"))
        {
            Assert.NotNull(held);
            Assert.Null(locks.TryAcquire("s1"));
        }

        Assert.Equal(0, locks.Count);
        using var again = locks.TryAcquire("s1");
        Assert.NotNull(again);
    }

    [Fact]
    public async Task Sweep_DeletesOnlyExpiredIdleLocalSessions()
    {
        var ct = TestContext.Current.CancellationToken;
        var storage = new FakeSessionStorage(
            Session("expired", TimeSpan.FromDays(2)),
            Session("busy", TimeSpan.FromDays(2)),
            Session("fresh", TimeSpan.FromHours(1)),
            Session("remote", TimeSpan.FromDays(2), remote: true));
        var locks = new CopilotConversationLocks();
        var service = CreateCleanup(storage, locks);

        using var turn = await locks.AcquireAsync("busy", ct);
        var deleted = await service.SweepAsync(TimeSpan.FromDays(1), ct);

        Assert.Equal(1, deleted);
        Assert.Equal(["expired"], storage.Deleted);
    }

    [Fact]
    public async Task Sweep_SkipsWhenClientNotStarted()
    {
        var storage = new FakeSessionStorage(Session("expired", TimeSpan.FromDays(2))) { IsStarted = false };
        var service = CreateCleanup(storage, new CopilotConversationLocks());

        Assert.Equal(0, await service.SweepAsync(TimeSpan.FromDays(1), TestContext.Current.CancellationToken));
        Assert.Equal(0, storage.ListCalls);
    }

    [Fact]
    public async Task Sweep_ContinuesAfterDeleteFailure()
    {
        var storage = new FakeSessionStorage(Session("a", TimeSpan.FromDays(2)), Session("b", TimeSpan.FromDays(2)));
        storage.FailDelete.Add("a");
        var service = CreateCleanup(storage, new CopilotConversationLocks());

        Assert.Equal(1, await service.SweepAsync(TimeSpan.FromDays(1), TestContext.Current.CancellationToken));
        Assert.Equal(["b"], storage.Deleted);
    }

    [Theory]
    [InlineData(10, 60)]
    [InlineData(1800, 900)]
    [InlineData(86400, 3600)]
    public void Interval_IsHalfRetention_Clamped(int retentionSeconds, int expectedSeconds) =>
        Assert.Equal(TimeSpan.FromSeconds(expectedSeconds), CopilotSessionCleanupService.GetInterval(TimeSpan.FromSeconds(retentionSeconds)));

    [Fact]
    public void AddCopilot_RegistersStoreAndCleanupService()
    {
        using var sp = TestHost.Build(s => s.AddCopilot());

        Assert.IsType<CopilotSessionStore>(sp.GetRequiredService<ICopilotSessionStore>());
        Assert.Contains(sp.GetServices<IHostedService>(), s => s is CopilotSessionCleanupService);
    }

    [Fact]
    public void SessionRetention_BindsFromConfiguration()
    {
        using var sp = TestHost.Build(s => s.AddCopilot(), new() { ["Copilot:SessionRetention"] = "7.00:00:00" });
        Assert.Equal(TimeSpan.FromDays(7), sp.GetOptions().SessionRetention);
    }

    private static CopilotSessionCleanupService CreateCleanup(FakeSessionStorage storage, CopilotConversationLocks locks)
    {
        var services = new ServiceCollection();
        services.AddSingleton<TimeProvider>(new FixedTimeProvider(Now));
        services.AddCopilot();
        var sp = services.BuildServiceProvider();
        return new CopilotSessionCleanupService(storage, locks, sp.GetRequiredService<Microsoft.Extensions.Options.IOptionsMonitor<CopilotOptions>>(), sp);
    }

    private sealed class FixedTimeProvider(DateTimeOffset now) : TimeProvider
    {
        public override DateTimeOffset GetUtcNow() => now;
    }
}

internal sealed class FakeSessionStorage(params SessionMetadata[] sessions) : ICopilotSessionStorage
{
    private readonly Dictionary<string, SessionMetadata> _sessions = sessions.ToDictionary(s => s.SessionId);

    public bool IsStarted { get; set; } = true;

    public Dictionary<string, IReadOnlyList<SessionEvent>> Events { get; } = [];

    public List<string> Deleted { get; } = [];

    public HashSet<string> FailDelete { get; } = [];

    public int ListCalls { get; private set; }

    public Task<IList<SessionMetadata>> ListAsync(CancellationToken cancellationToken)
    {
        ListCalls++;
        return Task.FromResult<IList<SessionMetadata>>(_sessions.Values.ToList());
    }

    public Task<SessionMetadata?> GetAsync(string sessionId, CancellationToken cancellationToken) =>
        Task.FromResult(_sessions.GetValueOrDefault(sessionId));

    public Task<IReadOnlyList<SessionEvent>> ReadEventsAsync(string sessionId, CancellationToken cancellationToken) =>
        Task.FromResult(Events.GetValueOrDefault(sessionId) ?? []);

    public Task DeleteAsync(string sessionId, CancellationToken cancellationToken)
    {
        if (FailDelete.Contains(sessionId))
        {
            throw new InvalidOperationException("delete failed");
        }

        _sessions.Remove(sessionId);
        Deleted.Add(sessionId);
        return Task.CompletedTask;
    }
}
