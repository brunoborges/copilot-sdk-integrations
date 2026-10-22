using GitHub.Copilot;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Diagnostics.HealthChecks;
using Microsoft.Extensions.Options;

namespace CopilotSdk.Extensions.Hosting.Tests;

public sealed class HealthCheckTests : IAsyncDisposable
{
    private readonly CopilotClient _client = new(new CopilotClientOptions { Connection = RuntimeConnection.ForUri("localhost:1") });

    public ValueTask DisposeAsync() => _client.DisposeAsync();

    private sealed class FakeProvider(CopilotClient client, bool started) : ICopilotClientProvider
    {
        public CopilotClient Client => started ? client : throw new InvalidOperationException("Must not create the client.");

        public bool IsStarted => started;

        public ValueTask<CopilotClient> GetClientAsync(CancellationToken cancellationToken) =>
            throw new InvalidOperationException("Must not start the client.");
    }

    private static HealthCheckContext Context(HealthStatus failureStatus = HealthStatus.Unhealthy) => new()
    {
        Registration = new HealthCheckRegistration("copilot", _ => null!, failureStatus, null),
    };

    private CopilotHealthCheck Create(
        bool started,
        Func<Task>? ping = null,
        GetAuthStatusResponse? auth = null,
        Action<CopilotOptions>? configure = null)
    {
        var options = new CopilotOptions();
        configure?.Invoke(options);
        return new CopilotHealthCheck(
            new FakeProvider(_client, started),
            new StaticOptionsMonitor(options),
            (_, _) => ping?.Invoke() ?? Task.CompletedTask,
            (_, _) => Task.FromResult(auth ?? new GetAuthStatusResponse { IsAuthenticated = true }));
    }

    [Fact]
    public async Task NotStarted_IsHealthy_WithoutStartingClient()
    {
        var result = await Create(started: false, ping: () => throw new InvalidOperationException())
            .CheckHealthAsync(Context(), TestContext.Current.CancellationToken);

        Assert.Equal(HealthStatus.Healthy, result.Status);
        Assert.Contains("not started", result.Description);
    }

    [Fact]
    public async Task PingFailure_ReportsFailureStatus()
    {
        var result = await Create(started: true, ping: () => throw new IOException("down"))
            .CheckHealthAsync(Context(HealthStatus.Degraded), TestContext.Current.CancellationToken);

        Assert.Equal(HealthStatus.Degraded, result.Status);
        Assert.IsType<IOException>(result.Exception);
    }

    [Fact]
    public async Task Authenticated_IsHealthy_WithData()
    {
        var result = await Create(
                started: true,
                auth: new GetAuthStatusResponse { IsAuthenticated = true, Login = "octocat", AuthType = "token", Host = "github.com" })
            .CheckHealthAsync(Context(), TestContext.Current.CancellationToken);

        Assert.Equal(HealthStatus.Healthy, result.Status);
        Assert.Equal("octocat", result.Data["login"]);
        Assert.Equal("token", result.Data["authType"]);
        Assert.Equal("github.com", result.Data["host"]);
    }

    [Fact]
    public async Task NotAuthenticated_IsDegraded()
    {
        var result = await Create(started: true, auth: new GetAuthStatusResponse { IsAuthenticated = false, StatusMessage = "no token" })
            .CheckHealthAsync(Context(), TestContext.Current.CancellationToken);

        Assert.Equal(HealthStatus.Degraded, result.Status);
        Assert.Contains("no token", result.Description);
    }

    [Fact]
    public async Task NotAuthenticated_WithByok_IsHealthy()
    {
        var result = await Create(
                started: true,
                auth: new GetAuthStatusResponse { IsAuthenticated = false },
                configure: o => o.Provider = new ProviderConfig { BaseUrl = "https://models" })
            .CheckHealthAsync(Context(), TestContext.Current.CancellationToken);

        Assert.Equal(HealthStatus.Healthy, result.Status);
    }

    [Fact]
    public async Task Cancellation_Propagates()
    {
        using var cts = new CancellationTokenSource();
        await cts.CancelAsync();
        var check = new CopilotHealthCheck(
            new FakeProvider(_client, started: true),
            new StaticOptionsMonitor(new CopilotOptions()),
            (_, ct) => Task.FromCanceled(ct),
            (_, _) => Task.FromResult(new GetAuthStatusResponse()));

        await Assert.ThrowsAnyAsync<OperationCanceledException>(() => check.CheckHealthAsync(Context(), cts.Token));
    }

    [Fact]
    public async Task AddCopilot_RegistersHealthCheck_ThatDoesNotStartClient()
    {
        await using var sp = TestHost.Build(s =>
        {
            s.AddLogging();
            s.AddCopilot();
            s.AddHealthChecks().AddCopilot(tags: ["ready"]);
        });

        var report = await sp.GetRequiredService<HealthCheckService>().CheckHealthAsync(TestContext.Current.CancellationToken);

        Assert.Equal(HealthStatus.Healthy, report.Entries["copilot"].Status);
        Assert.Equal(["ready"], report.Entries["copilot"].Tags);
        Assert.False(sp.GetRequiredService<ICopilotClientProvider>().IsStarted);
    }

    private sealed class StaticOptionsMonitor(CopilotOptions options) : IOptionsMonitor<CopilotOptions>
    {
        public CopilotOptions CurrentValue => options;

        public CopilotOptions Get(string? name) => options;

        public IDisposable? OnChange(Action<CopilotOptions, string?> listener) => null;
    }
}
