using Aspire.Hosting;
using Aspire.Hosting.ApplicationModel;
using GitHub.Copilot;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Diagnostics.HealthChecks;
using static CopilotSdk.Aspire.Hosting.Tests.TestHelpers;

namespace CopilotSdk.Aspire.Hosting.Tests;

public class RuntimeResourceTests
{
    [Fact]
    public void AddCopilot_AddsExecutableWithTcpEndpointAndHealthCheck()
    {
        var builder = CreateBuilder();

        var copilot = builder.AddCopilot("copilot", port: 4321);

        var runtime = copilot.Resource;
        Assert.Same(runtime, Assert.Single(builder.Resources.OfType<CopilotRuntimeResource>()));
        Assert.Null(runtime.InnerResource);
        Assert.False(runtime.IsExisting);
        Assert.Equal(builder.AppHostDirectory, runtime.WorkingDirectory);

        var endpoint = Assert.Single(runtime.Annotations.OfType<EndpointAnnotation>());
        Assert.Equal("tcp", endpoint.Name);
        Assert.Equal("tcp", endpoint.UriScheme);
        Assert.Equal(4321, endpoint.Port);
        Assert.Null(endpoint.TargetPort);

        Assert.Contains(runtime.Annotations.OfType<HealthCheckAnnotation>(), h => h.Key == "copilot_copilot_check");
        Assert.Equal("copilot-connection-token", runtime.ConnectionTokenParameter.Name);
        Assert.DoesNotContain(builder.Resources, r => r.Name == "copilot-github-token");
    }

    [Fact]
    public void AddCopilot_UsesBundledRuntimeFromOutput()
    {
        var builder = CreateBuilder();

        var runtime = builder.AddCopilot("copilot").Resource;

        // GitHub.Copilot.SDK build targets flow to this project and copy the runtime to its output.
        var bundled = CopilotRuntimeLocator.GetBundledRuntimePath(AppContext.BaseDirectory);
        Assert.NotNull(bundled);
        Assert.True(File.Exists(bundled));
        if (Environment.GetEnvironmentVariable("COPILOT_CLI_PATH") is not { Length: > 0 })
        {
            Assert.Equal(bundled, runtime.Command);
        }
    }

    [Fact]
    public void Locator_PrefersCliPathVariable_ThenFallsBackToPath()
    {
        Assert.Equal("/opt/custom/copilot", CopilotRuntimeLocator.Resolve(AppContext.BaseDirectory, n => n == "COPILOT_CLI_PATH" ? "/opt/custom/copilot" : null));

        var empty = Path.Combine(AppContext.BaseDirectory, "no-runtime-here");
        var expected = OperatingSystem.IsWindows() ? "copilot.exe" : "copilot";
        Assert.Equal(expected, CopilotRuntimeLocator.Resolve(empty, _ => null));
    }

    [Fact]
    public async Task Executable_ArgumentsBindLoopbackAndAllocatedPort()
    {
        var builder = CreateBuilder();
        var runtime = builder.AddCopilot("copilot").WithLogLevel(CopilotLogLevel.Debug).Resource;
        Allocate(runtime, "tcp", port: 5000, targetPort: 5001);

        var args = await GetArgumentsAsync(runtime);

        Assert.Equal(["--headless", "--no-auto-update", "--log-level", "debug", "--host", "127.0.0.1", "--port", "5001"], args);
    }

    [Fact]
    public async Task Executable_EnvironmentHasTokenHomeAndTelemetry()
    {
        var builder = CreateBuilder();
        builder.Configuration["Parameters:copilot-connection-token"] = "conn-secret";
        builder.Configuration["Parameters:gh"] = "gh-secret";
        var gh = builder.AddParameter("gh", secret: true);
        var runtime = builder.AddCopilot("copilot").WithGitHubToken(gh).Resource;

        var env = await GetEnvironmentAsync(runtime);

        Assert.Equal("gh-secret", env["COPILOT_GITHUB_TOKEN"]);
        Assert.False(string.IsNullOrEmpty(env["COPILOT_CONNECTION_TOKEN"]));
        Assert.Equal("true", env["COPILOT_OTEL_ENABLED"]);
        Assert.Equal(OtlpHttpEndpoint, env["OTEL_EXPORTER_OTLP_ENDPOINT"]);
        Assert.Equal("http/protobuf", env["OTEL_EXPORTER_OTLP_PROTOCOL"]);
        Assert.Equal("false", env["OTEL_INSTRUMENTATION_GENAI_CAPTURE_MESSAGE_CONTENT"]);

        var home = env["COPILOT_HOME"];
        Assert.Contains("copilot-aspire", home, StringComparison.Ordinal);
        Assert.EndsWith(Path.DirectorySeparatorChar + "copilot", home, StringComparison.Ordinal);
    }

    [Fact]
    public async Task Executable_HomeDirectoryAndCaptureOverrides()
    {
        var builder = CreateBuilder();
        var runtime = builder.AddCopilot("copilot")
            .WithHomeDirectory("data/copilot-home")
            .WithMessageContentCapture()
            .Resource;

        var env = await GetEnvironmentAsync(runtime);

        Assert.Equal(Path.GetFullPath("data/copilot-home", builder.AppHostDirectory), env["COPILOT_HOME"]);
        Assert.Equal("true", env["OTEL_INSTRUMENTATION_GENAI_CAPTURE_MESSAGE_CONTENT"]);
        Assert.False(env.ContainsKey("COPILOT_GITHUB_TOKEN"));
    }

    [Fact]
    public async Task Executable_DataBindMountBecomesHome()
    {
        var builder = CreateBuilder();
        var runtime = builder.AddCopilot("copilot").WithDataBindMount(".copilot-data").Resource;

        var env = await GetEnvironmentAsync(runtime);

        Assert.Equal(Path.GetFullPath(".copilot-data", builder.AppHostDirectory), env["COPILOT_HOME"]);
    }

    [Fact]
    public void WithCliPath_SetsCommand()
    {
        var builder = CreateBuilder();

        var runtime = builder.AddCopilot("copilot").WithCliPath("tools/copilot").Resource;

        Assert.Equal(Path.GetFullPath("tools/copilot", builder.AppHostDirectory), runtime.Command);
    }

    [Fact]
    public async Task ConnectionString_UsesEndpointAndToken()
    {
        var builder = CreateBuilder();
        builder.Configuration["Parameters:token"] = "s3cret";
        var token = builder.AddParameter("token", secret: true);
        var runtime = builder.AddCopilot("copilot").WithConnectionToken(token).Resource;
        Allocate(runtime, "tcp", port: 5000);

        var connectionString = await runtime.ConnectionStringExpression.GetValueAsync(TestContext.Current.CancellationToken);

        Assert.Equal("Endpoint=tcp://localhost:5000;ConnectionToken=s3cret", connectionString);
        Assert.Equal("Endpoint=tcp://{copilot.bindings.tcp.host}:{copilot.bindings.tcp.port};ConnectionToken={token.value}", runtime.ConnectionStringExpression.ValueExpression);
    }

    [Fact]
    public async Task ConnectionProperties_ExposeHostPortEndpointAndToken()
    {
        var builder = CreateBuilder();
        builder.Configuration["Parameters:token"] = "s3cret";
        var runtime = builder.AddCopilot("copilot").WithConnectionToken(builder.AddParameter("token", secret: true)).Resource;
        Allocate(runtime, "tcp", port: 5000);

        var properties = ((IResourceWithConnectionString)runtime).GetConnectionProperties().ToDictionary(p => p.Key, p => p.Value);

        Assert.Equal("localhost", await properties["Host"].GetValueAsync(TestContext.Current.CancellationToken));
        Assert.Equal("5000", await properties["Port"].GetValueAsync(TestContext.Current.CancellationToken));
        Assert.Equal("tcp://localhost:5000", await properties["Endpoint"].GetValueAsync(TestContext.Current.CancellationToken));
        Assert.Equal("s3cret", await properties["ConnectionToken"].GetValueAsync(TestContext.Current.CancellationToken));
    }

    [Fact]
    public async Task HealthCheck_IsUnhealthyWhenRuntimeIsNotReachable()
    {
        var builder = CreateBuilder();
        builder.Configuration["Parameters:copilot-connection-token"] = "s3cret";
        var runtime = builder.AddCopilot("copilot").Resource;

        await using var app = builder.Build();
        var check = app.Services.GetRequiredKeyedService<CopilotRuntimeHealthCheck>("copilot_copilot_check");

        // Not allocated yet.
        var before = await check.CheckHealthAsync(new HealthCheckContext(), TestContext.Current.CancellationToken);
        Assert.Equal(HealthStatus.Unhealthy, before.Status);

        // Allocated, but nothing listens on the port.
        Allocate(runtime, "tcp", port: GetFreePort());
        var after = await check.CheckHealthAsync(new HealthCheckContext(), TestContext.Current.CancellationToken);
        Assert.Equal(HealthStatus.Unhealthy, after.Status);
    }

    [Fact]
    public void RuntimeConnectionString_ParsesTcpEndpoint()
    {
        Assert.True(CopilotRuntimeConnectionString.TryParse("Endpoint=tcp://localhost:5000;ConnectionToken=abc", out var url, out var token));
        Assert.Equal("localhost:5000", url);
        Assert.Equal("abc", token);

        Assert.True(CopilotRuntimeConnectionString.TryParse("Endpoint=https://copilot.example.com", out url, out token));
        Assert.Equal("https://copilot.example.com", url);
        Assert.Null(token);

        Assert.False(CopilotRuntimeConnectionString.TryParse("ConnectionToken=abc", out _, out _));
        Assert.False(CopilotRuntimeConnectionString.TryParse(null, out _, out _));
    }

    private static int GetFreePort()
    {
        using var listener = new System.Net.Sockets.TcpListener(System.Net.IPAddress.Loopback, 0);
        listener.Start();
        return ((System.Net.IPEndPoint)listener.LocalEndpoint).Port;
    }
}
