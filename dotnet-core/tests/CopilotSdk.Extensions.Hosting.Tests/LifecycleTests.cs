using CopilotSdk.Extensions.Hosting;
using GitHub.Copilot;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Options;

namespace CopilotSdk.Extensions.Hosting.Tests;

public sealed class LifecycleTests
{
    private static CopilotClient NewClient() => new(new CopilotClientOptions { Connection = RuntimeConnection.ForUri("localhost:1") });

    private static CopilotClientProvider NewProvider(
        Func<CopilotClient, CancellationToken, Task>? start = null,
        Func<CopilotClient, Task>? stop = null,
        Func<CopilotClient, Task>? forceStop = null) =>
        new(NewClient)
        {
            StartClientAsync = start ?? ((_, _) => Task.CompletedTask),
            StopClientAsync = stop ?? (_ => Task.CompletedTask),
            ForceStopClientAsync = forceStop ?? (_ => Task.CompletedTask),
        };

    [Fact]
    public async Task Provider_StartsOnce_UnderConcurrency()
    {
        var starts = 0;
        await using var provider = NewProvider(start: async (_, _) =>
        {
            Interlocked.Increment(ref starts);
            await Task.Delay(50);
        });
        Assert.False(provider.IsStarted);

        var clients = await Task.WhenAll(Enumerable.Range(0, 10).Select(_ => provider.GetClientAsync(CancellationToken.None).AsTask()));

        Assert.Equal(1, starts);
        Assert.True(provider.IsStarted);
        Assert.All(clients, c => Assert.Same(provider.Client, c));
    }

    [Fact]
    public async Task Provider_FailedStart_IsRetried()
    {
        var attempts = 0;
        await using var provider = NewProvider(start: (_, _) =>
            Interlocked.Increment(ref attempts) == 1 ? Task.FromException(new IOException("no runtime")) : Task.CompletedTask);

        await Assert.ThrowsAsync<IOException>(() => provider.GetClientAsync(CancellationToken.None).AsTask());
        Assert.False(provider.IsStarted);

        await provider.GetClientAsync(CancellationToken.None);
        Assert.True(provider.IsStarted);
        Assert.Equal(2, attempts);
    }

    [Fact]
    public async Task Provider_Stop_WhenNotStarted_DoesNothing()
    {
        var stops = 0;
        await using var provider = NewProvider(stop: _ => { stops++; return Task.CompletedTask; });

        await provider.StopAsync(CancellationToken.None);

        Assert.Equal(0, stops);
    }

    [Fact]
    public async Task Provider_Stop_IsGraceful()
    {
        var stops = 0;
        var forceStops = 0;
        await using var provider = NewProvider(
            stop: _ => { stops++; return Task.CompletedTask; },
            forceStop: _ => { forceStops++; return Task.CompletedTask; });
        await provider.GetClientAsync(CancellationToken.None);

        await provider.StopAsync(CancellationToken.None);

        Assert.Equal(1, stops);
        Assert.Equal(0, forceStops);
        Assert.False(provider.IsStarted);
    }

    [Fact]
    public async Task Provider_Stop_ForceStopsWhenShutdownTimesOut()
    {
        var forceStops = 0;
        await using var provider = NewProvider(
            stop: _ => Task.Delay(Timeout.Infinite),
            forceStop: _ => { forceStops++; return Task.CompletedTask; });
        await provider.GetClientAsync(CancellationToken.None);
        using var shutdown = new CancellationTokenSource(100);

        await provider.StopAsync(shutdown.Token);

        Assert.Equal(1, forceStops);
        Assert.False(provider.IsStarted);
    }

    [Fact]
    public async Task Provider_Stop_ForceStopsWhenGracefulStopFails()
    {
        var forceStops = 0;
        await using var provider = NewProvider(
            stop: _ => Task.FromException(new IOException("broken pipe")),
            forceStop: _ => { forceStops++; return Task.FromException(new IOException("still broken")); });
        await provider.GetClientAsync(CancellationToken.None);

        await provider.StopAsync(CancellationToken.None);

        Assert.Equal(1, forceStops);
    }

    [Fact]
    public async Task Provider_Dispose_WithoutClient_DoesNotCreateIt()
    {
        var created = 0;
        var provider = new CopilotClientProvider(() => { created++; return NewClient(); });

        await provider.DisposeAsync();
        await provider.DisposeAsync();

        Assert.Equal(0, created);
    }

    [Theory]
    [InlineData(true)]
    [InlineData(false)]
    public async Task Lifetime_StartsOnlyWithEagerStart(bool eagerStart)
    {
        var provider = new RecordingProvider();
        var lifetime = new CopilotClientLifetime(provider, Options(o => o.EagerStart = eagerStart));

        await lifetime.StartAsync(CancellationToken.None);
        await lifetime.StopAsync(CancellationToken.None);

        Assert.Equal(eagerStart ? 1 : 0, provider.Starts);
        Assert.Equal(1, provider.Stops);
    }

    [Fact]
    public void AddCopilot_RegistersChatClientDependencies()
    {
        var services = new ServiceCollection();
        services.AddSingleton<IConfiguration>(new ConfigurationBuilder().Build());
        services.AddCopilot(o => o.MaxConcurrentTurns = 2);
        using var sp = services.BuildServiceProvider(new ServiceProviderOptions { ValidateOnBuild = true, ValidateScopes = true });

        Assert.IsType<CopilotSessionGateway>(sp.GetRequiredService<ICopilotSessionGateway>());
        Assert.NotNull(sp.GetRequiredService<CopilotTurnLimiter>());
        Assert.NotNull(sp.GetRequiredService<CopilotConversationLocks>());
        Assert.NotNull(sp.GetRequiredService<CopilotTurnContextRegistry>());
        Assert.False(sp.GetRequiredService<ICopilotClientProvider>().IsStarted);
    }

    [Fact]
    public async Task TurnLimiter_RejectsWhenQueueIsFull()
    {
        using var limiter = new CopilotTurnLimiter(2);
        var held = new[] { await limiter.AcquireAsync(CancellationToken.None), await limiter.AcquireAsync(CancellationToken.None) };
        var queued = Enumerable.Range(0, 2 * CopilotTurnLimiter.QueueFactor).Select(_ => limiter.AcquireAsync(CancellationToken.None).AsTask()).ToList();

        await Assert.ThrowsAsync<InvalidOperationException>(() => limiter.AcquireAsync(CancellationToken.None).AsTask());
        Assert.All(queued, t => Assert.False(t.IsCompleted));

        foreach (var slot in held)
        {
            slot.Dispose();
        }

        var next = await queued[0].WaitAsync(TimeSpan.FromSeconds(5), TestContext.Current.CancellationToken);
        next.Dispose();
    }

    [Fact]
    public async Task TurnLimiter_WithoutCap_NeverBlocks()
    {
        using var limiter = new CopilotTurnLimiter(maxConcurrentTurns: null);

        for (var i = 0; i < 100; i++)
        {
            _ = await limiter.AcquireAsync(CancellationToken.None);
        }
    }

    [Fact]
    public async Task ConversationLocks_CanceledWaiter_IsCleanedUp()
    {
        var locks = new CopilotConversationLocks();
        var held = await locks.AcquireAsync("c", CancellationToken.None);
        using var cts = new CancellationTokenSource(50);

        await Assert.ThrowsAnyAsync<OperationCanceledException>(() => locks.AcquireAsync("c", cts.Token).AsTask());
        held.Dispose();

        Assert.Equal(0, locks.Count);
    }

    private static IOptionsMonitor<CopilotOptions> Options(Action<CopilotOptions> configure)
    {
        var services = new ServiceCollection();
        services.AddOptions<CopilotOptions>().Configure(configure);
        return services.BuildServiceProvider().GetRequiredService<IOptionsMonitor<CopilotOptions>>();
    }

    private sealed class RecordingProvider : ICopilotClientProvider
    {
        public int Starts { get; private set; }

        public int Stops { get; private set; }

        public CopilotClient Client => throw new InvalidOperationException("Not used.");

        public bool IsStarted => Starts > 0;

        public ValueTask<CopilotClient> GetClientAsync(CancellationToken cancellationToken)
        {
            Starts++;
            return ValueTask.FromResult<CopilotClient>(null!);
        }

        public Task StopAsync(CancellationToken cancellationToken)
        {
            Stops++;
            return Task.CompletedTask;
        }
    }
}
