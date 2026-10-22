using System.ComponentModel;
using System.Text.Json;
using CopilotSdk.Extensions.Hosting;
using GitHub.Copilot;
using Microsoft.Extensions.AI;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;
using Microsoft.Extensions.Options;

namespace CopilotSdk.Extensions.Hosting.Tests;

public sealed class ToolsTests
{
    private static ServiceProvider Build(Action<IServiceCollection> configure)
    {
        var services = new ServiceCollection();
        services.AddSingleton<IConfiguration>(new ConfigurationBuilder().Build());
        configure(services);
        return services.BuildServiceProvider(new ServiceProviderOptions { ValidateScopes = true, ValidateOnBuild = true });
    }

    private static AIFunction GetTool(IServiceProvider sp, string name, string agentName = "") =>
        sp.GetRequiredService<CopilotToolCatalog>().GetTools(agentName).Functions.Single(f => f.Name == name);

    private static AIFunctionArguments Args(string? sessionId = null, Dictionary<string, object?>? values = null)
    {
        var arguments = new AIFunctionArguments(values ?? []);
        if (sessionId is not null)
        {
            arguments.Context = new Dictionary<object, object?> { [typeof(ToolInvocation)] = new ToolInvocation { SessionId = sessionId } };
        }

        return arguments;
    }

    private static string? Text(object? result) => result switch
    {
        JsonElement { ValueKind: JsonValueKind.String } e => e.GetString(),
        JsonElement e => e.GetRawText(),
        _ => result?.ToString(),
    };

    [Fact]
    public void WithTools_DiscoversDescribedMethods_WithSnakeCaseNames()
    {
        using var sp = Build(s => s.AddCopilot().WithTools<OrderTools>());

        var tools = sp.GetRequiredService<CopilotToolCatalog>().GetTools(Options.DefaultName).Functions;

        Assert.Equal(["lookup_order", "get_static_info", "custom-name", "fail", "cancel_order", "who_am_i"], tools.Select(t => t.Name));
        Assert.Equal("Looks up an order.", tools[0].Description);
    }

    [Fact]
    public void Schema_ExcludesServicesInvocationAndCancellationToken()
    {
        using var sp = Build(s =>
        {
            s.AddScoped<ScopedCounter>();
            s.AddCopilot().WithTools<OrderTools>();
        });

        var schema = GetTool(sp, "lookup_order").JsonSchema.GetRawText();

        Assert.Contains("\"orderId\"", schema, StringComparison.Ordinal);
        Assert.Contains("The order id.", schema, StringComparison.Ordinal);
        Assert.DoesNotContain("counter", schema, StringComparison.OrdinalIgnoreCase);
        Assert.DoesNotContain("invocation", schema, StringComparison.OrdinalIgnoreCase);
        Assert.DoesNotContain("cancellationToken", schema, StringComparison.OrdinalIgnoreCase);
    }

    [Fact]
    public async Task EachCall_UsesNewScope()
    {
        using var sp = Build(s =>
        {
            s.AddScoped<ScopedCounter>();
            s.AddCopilot().WithTools<OrderTools>();
        });
        var tool = GetTool(sp, "lookup_order");

        var first = Text(await tool.InvokeAsync(Args("s1", new() { ["orderId"] = "1" }), TestContext.Current.CancellationToken));
        var second = Text(await tool.InvokeAsync(Args("s1", new() { ["orderId"] = "1" }), TestContext.Current.CancellationToken));

        Assert.NotNull(first);
        Assert.StartsWith("1:", first, StringComparison.Ordinal);
        Assert.NotEqual(first, second);
        Assert.Equal(2, OrderTools.Disposed(first, second));
    }

    [Fact]
    public async Task StaticMethod_IsInvoked()
    {
        using var sp = Build(s => s.AddCopilot().WithTools<OrderTools>());

        var result = await GetTool(sp, "get_static_info").InvokeAsync(Args(), TestContext.Current.CancellationToken);

        Assert.Equal("static", Text(result));
    }

    [Fact]
    public async Task Tool_RunsInCallersExecutionContext_WithTurnServices()
    {
        using var sp = Build(s => s.AddCopilot().WithTools<OrderTools>());
        var turns = sp.GetRequiredService<CopilotTurnContextRegistry>();

        ExecutionContext captured;
        OrderTools.CurrentUser.Value = "alice";
        try
        {
            captured = ExecutionContext.Capture()!;
        }
        finally
        {
            OrderTools.CurrentUser.Value = null;
        }

        turns.Set("s1", new TurnContext(captured, sp, Options.DefaultName, CancellationToken.None));
        try
        {
            var result = await GetTool(sp, "who_am_i").InvokeAsync(Args("s1"), TestContext.Current.CancellationToken);
            Assert.Equal("alice", Text(result));
            Assert.Null(OrderTools.CurrentUser.Value);
        }
        finally
        {
            turns.Remove("s1");
        }
    }

    [Fact]
    public async Task Tool_TurnCancellation_IsObserved()
    {
        using var sp = Build(s => s.AddCopilot().WithTools<OrderTools>());
        var turns = sp.GetRequiredService<CopilotTurnContextRegistry>();
        using var cts = new CancellationTokenSource();
        await cts.CancelAsync();
        turns.Set("s1", new TurnContext(null, sp, Options.DefaultName, cts.Token));

        await Assert.ThrowsAnyAsync<OperationCanceledException>(
            async () => await GetTool(sp, "cancel_order").InvokeAsync(Args("s1"), TestContext.Current.CancellationToken));
    }

    [Fact]
    public async Task Exception_BecomesGenericFailure_AndIsLogged()
    {
        var logger = new ListLoggerProvider();
        using var sp = Build(s =>
        {
            s.AddLogging(b => b.AddProvider(logger));
            s.AddCopilot().WithTools<OrderTools>();
        });

        var result = await GetTool(sp, "fail").InvokeAsync(Args("s1"), TestContext.Current.CancellationToken);

        var content = Assert.IsType<ToolResultAIContent>(result);
        Assert.Equal("failure", content.Result.ResultType);
        Assert.Equal("Tool 'fail' failed.", content.Result.TextResultForLlm);
        Assert.DoesNotContain("secret", content.Result.Error ?? string.Empty, StringComparison.Ordinal);
        var entry = Assert.Single(logger.Entries, e => e.Category == CopilotToolFactory.LoggerCategory);
        Assert.Equal(LogLevel.Error, entry.Level);
        Assert.IsType<InvalidOperationException>(entry.Exception);
    }

    [Fact]
    public void SkipPermission_SetForAppTools_ExceptRequiresApproval()
    {
        using var sp = Build(s => s.AddCopilot().WithTools<OrderTools>());

        var lookup = GetTool(sp, "lookup_order");
        var cancel = GetTool(sp, "cancel_order");

        Assert.Equal(true, lookup.AdditionalProperties["skip_permission"]);
        Assert.False(cancel.AdditionalProperties.ContainsKey("skip_permission"));
        Assert.Contains("cancel_order", sp.GetRequiredService<CopilotToolCatalog>().GetTools("").ApprovalRequired);
    }

    [Fact]
    public async Task WithTool_Delegate_InjectsServices()
    {
        using var sp = Build(s =>
        {
            s.AddScoped<ScopedCounter>();
            s.AddCopilot().WithTool(
                (ScopedCounter counter, [Description("A value.")] int value) => $"{value}:{counter.Id}",
                "echo",
                "Echoes a value.");
        });
        var tool = GetTool(sp, "echo");

        var result = Text(await tool.InvokeAsync(Args(values: new() { ["value"] = 7 }), TestContext.Current.CancellationToken));

        Assert.Equal("Echoes a value.", tool.Description);
        Assert.DoesNotContain("counter", tool.JsonSchema.GetRawText(), StringComparison.OrdinalIgnoreCase);
        Assert.StartsWith("7:", result, StringComparison.Ordinal);
        Assert.Equal(true, tool.AdditionalProperties["skip_permission"]);
    }

    [Fact]
    public void WithTool_NamedMethod_UsesSnakeCaseName()
    {
        using var sp = Build(s => s.AddCopilot().WithTool(GetWeatherAsync));

        Assert.Equal("get_weather", Assert.Single(sp.GetRequiredService<CopilotToolCatalog>().GetTools("").Functions).Name);
    }

    [Fact]
    public void WithTool_LambdaWithoutName_Throws()
    {
        var services = new ServiceCollection();

        Assert.Throws<ArgumentException>(() => services.AddCopilot().WithTool(() => "x"));
    }

    [Fact]
    public void WithTools_TypeWithoutTools_Throws()
    {
        var services = new ServiceCollection();

        Assert.Throws<ArgumentException>(() => services.AddCopilot().WithTools<ScopedCounter>());
    }

    [Fact]
    public void DuplicateToolNames_Throw()
    {
        using var sp = Build(s => s.AddCopilot().WithTools<OrderTools>().WithTool(() => "x", "lookup_order"));

        Assert.Throws<InvalidOperationException>(() => sp.GetRequiredService<CopilotToolCatalog>().GetTools(""));
    }

    [Fact]
    public void ToolsConfigurator_AddsTools_AndAvailableTools()
    {
        using var sp = Build(s =>
        {
            s.AddCopilot(o => o.McpServers["github"] = new CopilotMcpServerOptions { Url = "https://example.com/mcp", Tools = ["list_issues"] })
                .WithTools<OrderTools>();
            s.AddCopilot("plain");
        });
        var configurator = sp.GetServices<ICopilotSessionConfigurator>().OfType<CopilotToolsSessionConfigurator>().Single();

        var config = new SessionConfig();
        configurator.Configure(Options.DefaultName, config, sp);
        var plain = new SessionConfig();
        configurator.Configure("plain", plain, sp);

        Assert.Equal(6, config.Tools!.Count);
        Assert.Equal(
            ["custom:lookup_order", "custom:get_static_info", "custom:custom-name", "custom:fail", "custom:cancel_order", "custom:who_am_i", "mcp:*"],
            config.AvailableTools);
        // Named agents inherit the default agent's MCP servers, but not its tools.
        Assert.Null(plain.Tools);
        Assert.Equal(["mcp:*"], plain.AvailableTools);
    }

    [Fact]
    public void ToolsConfigurator_McpServerWithoutTools_IsNotAllowed()
    {
        using var sp = Build(s => s.AddCopilot(o => o.McpServers["github"] = new CopilotMcpServerOptions { Url = "https://example.com/mcp" }));
        var configurator = sp.GetServices<ICopilotSessionConfigurator>().OfType<CopilotToolsSessionConfigurator>().Single();

        var config = new SessionConfig();
        configurator.Configure(Options.DefaultName, config, sp);

        Assert.Empty(config.AvailableTools!);
    }

    [Fact]
    public void ModeConfigurator_SetsEmptyMode_AndPerAppBaseDirectory()
    {
        var environment = new TestEnvironment { ApplicationName = "My App", ContentRootPath = "/srv/app" };
        var expected = CopilotModeClientConfigurator.GetDefaultBaseDirectory(environment);
        var options = new CopilotClientOptions();

        Assert.Equal(expected, CopilotModeClientConfigurator.GetDefaultBaseDirectory(environment));
        Assert.Contains("copilot-sdk", expected, StringComparison.Ordinal);
        Assert.StartsWith("My_App-", Path.GetFileName(expected), StringComparison.Ordinal);
        Assert.NotEqual(expected, CopilotModeClientConfigurator.GetDefaultBaseDirectory(new TestEnvironment { ApplicationName = "My App", ContentRootPath = "/srv/other" }));

        new CopilotModeClientConfigurator(environment).Configure(options, new CopilotOptions { Endpoint = "http://localhost:1234" });
        Assert.Equal(CopilotClientMode.Empty, options.Mode);
        Assert.Null(options.BaseDirectory);
    }

    [Fact]
    public void ModeConfigurator_AppConfigureClient_Wins()
    {
        var baseDirectory = Path.Combine(AppContext.BaseDirectory, "copilot-home-test");
        var copilotOptions = new CopilotOptions();
        copilotOptions.ConfigureClient(o =>
        {
            o.Mode = CopilotClientMode.CopilotCli;
            o.BaseDirectory = baseDirectory;
        });

        var options = CopilotClientProvider.CreateClientOptions([new CopilotModeClientConfigurator()], copilotOptions);

        Assert.Equal(CopilotClientMode.CopilotCli, options.Mode);
        Assert.Equal(baseDirectory, options.BaseDirectory);
    }

    [Fact]
    public void AddCopilot_RegistersModeConfigurator()
    {
        using var sp = Build(s => s.AddCopilot());

        Assert.Single(sp.GetServices<ICopilotClientConfigurator>().OfType<CopilotModeClientConfigurator>());
    }

    [Description("Gets the weather.")]
    private static Task<string> GetWeatherAsync(string city) => Task.FromResult($"sunny in {city}");

    public sealed class ScopedCounter : IDisposable
    {
        private static int s_next;

        public int Id { get; } = Interlocked.Increment(ref s_next);

        public bool IsDisposed { get; private set; }

        public void Dispose()
        {
            IsDisposed = true;
            OrderTools.DisposedIds[Id] = true;
        }
    }

    public sealed class OrderTools(IServiceProvider services)
    {
        public static readonly AsyncLocal<string?> CurrentUser = new();
        public static readonly System.Collections.Concurrent.ConcurrentDictionary<int, bool> DisposedIds = new();

        public static int Disposed(params string?[] results) =>
            results.Count(r => r is not null && DisposedIds.ContainsKey(int.Parse(r.Split(':')[1], System.Globalization.CultureInfo.InvariantCulture)));

        [Description("Looks up an order.")]
        public Task<string> LookupOrderAsync(
            [Description("The order id.")] string orderId,
            ScopedCounter counter,
            ToolInvocation invocation,
            CancellationToken cancellationToken)
        {
            Assert.NotNull(services);
            Assert.Same(counter, services.GetRequiredService<ScopedCounter>());
            Assert.Equal("s1", invocation.SessionId);
            return Task.FromResult($"{orderId}:{counter.Id}");
        }

        [Description("Static info.")]
        public static string GetStaticInfo() => "static";

        [Description("Has a custom name.")]
        [DisplayName("custom-name")]
        public string Renamed() => "renamed";

        [Description("Always fails.")]
        public string Fail() => throw new InvalidOperationException("secret detail");

        [Description("Cancels an order.")]
        [RequiresApproval]
        public Task<string> CancelOrderAsync(CancellationToken cancellationToken)
        {
            cancellationToken.ThrowIfCancellationRequested();
            return Task.FromResult("cancelled");
        }

        [Description("Returns the current user.")]
        public async Task<string?> WhoAmIAsync()
        {
            await Task.Yield();
            return CurrentUser.Value;
        }

        public string NotATool() => "no";
    }

    private sealed class TestEnvironment : IHostEnvironment
    {
        public string EnvironmentName { get; set; } = "Test";

        public string ApplicationName { get; set; } = "app";

        public string ContentRootPath { get; set; } = "/";

        public Microsoft.Extensions.FileProviders.IFileProvider ContentRootFileProvider { get; set; } = null!;
    }

    internal sealed class ListLoggerProvider : ILoggerProvider
    {
        public System.Collections.Concurrent.ConcurrentQueue<(string Category, LogLevel Level, Exception? Exception, string Message)> Entries { get; } = new();

        public ILogger CreateLogger(string categoryName) => new ListLogger(this, categoryName);

        public void Dispose()
        {
        }

        private sealed class ListLogger(ListLoggerProvider provider, string category) : ILogger
        {
            public IDisposable? BeginScope<TState>(TState state) where TState : notnull => null;

            public bool IsEnabled(LogLevel logLevel) => true;

            public void Log<TState>(LogLevel logLevel, EventId eventId, TState state, Exception? exception, Func<TState, Exception?, string> formatter) =>
                provider.Entries.Enqueue((category, logLevel, exception, formatter(state, exception)));
        }
    }
}
