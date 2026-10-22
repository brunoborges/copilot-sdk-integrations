using System.ComponentModel;
using CopilotSdk.Extensions.Hosting;
using GitHub.Copilot;
using GitHub.Copilot.Rpc;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Options;

namespace CopilotSdk.Extensions.Hosting.Tests;

public sealed class PermissionsTests
{
    private static ServiceProvider Build(Action<CopilotOptions>? configure = null)
    {
        var services = new ServiceCollection();
        services.AddSingleton<IConfiguration>(new ConfigurationBuilder().Build());
        services.AddCopilot(o =>
        {
            o.McpServers["github"] = new CopilotMcpServerOptions { Url = "https://example.com/mcp", Tools = ["list_issues"] };
            o.McpServers["docs"] = new CopilotMcpServerOptions { Url = "https://example.com/docs", Tools = ["*"] };
            o.McpServers["none"] = new CopilotMcpServerOptions { Url = "https://example.com/none" };
            configure?.Invoke(o);
        }).WithTools<AdminTools>();
        return services.BuildServiceProvider(new ServiceProviderOptions { ValidateScopes = true, ValidateOnBuild = true });
    }

    private static Func<PermissionRequest, PermissionInvocation, Task<PermissionDecision>> GetHandler(IServiceProvider sp, string agentName = "")
    {
        var config = new SessionConfig();
        foreach (var configurator in sp.GetServices<ICopilotSessionConfigurator>())
        {
            configurator.Configure(agentName, config, sp);
        }

        return config.OnPermissionRequest!;
    }

    private static PermissionInvocation Invocation(string sessionId = "s1") => new() { SessionId = sessionId };

    private static PermissionRequestCustomTool CustomTool(string name) => new() { ToolName = name, ToolDescription = "d" };

    private static PermissionRequestMcp Mcp(string server, string tool) => new() { ServerName = server, ToolName = tool, ToolTitle = tool, ReadOnly = true };

    [Fact]
    public async Task AppTool_IsApproved()
    {
        using var sp = Build();

        var decision = await GetHandler(sp)(CustomTool("list_users"), Invocation());

        Assert.IsType<PermissionDecisionApproveOnce>(decision);
    }

    [Fact]
    public async Task UnknownCustomTool_IsRejected()
    {
        using var sp = Build();

        var decision = await GetHandler(sp)(CustomTool("other"), Invocation());

        Assert.IsType<PermissionDecisionReject>(decision);
    }

    [Fact]
    public async Task RequiresApproval_WithoutOnApproval_IsRejected()
    {
        using var sp = Build();

        var decision = await GetHandler(sp)(CustomTool("delete_user"), Invocation());

        Assert.Equal(CopilotPermissionsSessionConfigurator.NotApprovedFeedback, Assert.IsType<PermissionDecisionReject>(decision).Feedback);
    }

    [Theory]
    [InlineData(true)]
    [InlineData(false)]
    public async Task RequiresApproval_UsesOnApproval(bool approve)
    {
        PermissionRequest? seen = null;
        using var sp = Build(o => o.OnApproval = call =>
        {
            seen = call.Request;
            Assert.Equal("delete_user", call.ToolName);
            Assert.NotNull(call.Services);
            return ValueTask.FromResult(approve);
        });
        var request = CustomTool("delete_user");

        var decision = await GetHandler(sp)(request, Invocation());

        Assert.Same(request, seen);
        if (approve)
        {
            Assert.IsType<PermissionDecisionApproveOnce>(decision);
        }
        else
        {
            Assert.IsType<PermissionDecisionReject>(decision);
        }
    }

    [Fact]
    public async Task RequiresApproval_RunsInTurnContext()
    {
        var user = new AsyncLocal<string?>();
        string? seenUser = null;
        IServiceProvider? seenServices = null;
        using var sp = Build(o => o.OnApproval = call =>
        {
            seenUser = user.Value;
            seenServices = call.Services;
            return ValueTask.FromResult(true);
        });
        using var turnServices = new ServiceCollection().BuildServiceProvider();
        ExecutionContext captured;
        user.Value = "alice";
        try
        {
            captured = ExecutionContext.Capture()!;
        }
        finally
        {
            user.Value = null;
        }

        sp.GetRequiredService<CopilotTurnContextRegistry>().Set("s1", new TurnContext(captured, turnServices, Options.DefaultName, CancellationToken.None));

        var decision = await GetHandler(sp)(CustomTool("delete_user"), Invocation("s1"));

        Assert.IsType<PermissionDecisionApproveOnce>(decision);
        Assert.Equal("alice", seenUser);
        Assert.Same(turnServices, seenServices);
    }

    [Theory]
    [InlineData(50, true)]
    [InlineData(400, false)]
    public async Task OnApproval_CanReadTypedArguments(decimal amount, bool expectApproved)
    {
        using var sp = Build(o => o.OnApproval = call => ValueTask.FromResult(call.GetArgument<decimal>("Amount") < 100));
        var request = new PermissionRequestCustomTool
        {
            ToolName = "delete_user",
            ToolDescription = "d",
            Args = System.Text.Json.JsonSerializer.SerializeToElement(new { orderId = "1002", amount }),
        };

        var decision = await GetHandler(sp)(request, Invocation());

        if (expectApproved)
        {
            Assert.IsType<PermissionDecisionApproveOnce>(decision);
        }
        else
        {
            Assert.IsType<PermissionDecisionReject>(decision);
        }
    }

    [Fact]
    public async Task OnApprovalThrows_IsRejected()
    {
        using var sp = Build(o => o.OnApproval = _ => throw new InvalidOperationException("boom"));

        var decision = await GetHandler(sp)(CustomTool("delete_user"), Invocation());

        Assert.IsType<PermissionDecisionReject>(decision);
    }

    [Theory]
    [InlineData("github", "list_issues", true)]
    [InlineData("github", "github-list_issues", true)]
    [InlineData("GitHub", "list_issues", true)]
    [InlineData("github", "delete_repo", false)]
    [InlineData("docs", "anything", true)]
    [InlineData("none", "anything", false)]
    [InlineData("unknown", "list_issues", false)]
    public async Task Mcp_AllowedOnlyForListedTools(string server, string tool, bool allowed)
    {
        using var sp = Build();

        var decision = await GetHandler(sp)(Mcp(server, tool), Invocation());

        Assert.Equal(allowed, decision is PermissionDecisionApproveOnce);
    }

    [Fact]
    public async Task OtherKinds_AreRejected()
    {
        using var sp = Build();
        var handler = GetHandler(sp);

        var url = await handler(new PermissionRequestUrl { Intention = "i", Url = "https://example.com" }, Invocation());
        var read = await handler(new PermissionRequestRead { Intention = "i", Path = "/etc/passwd" }, Invocation());

        Assert.Equal(CopilotPermissionsSessionConfigurator.DeniedFeedback, Assert.IsType<PermissionDecisionReject>(url).Feedback);
        Assert.IsType<PermissionDecisionReject>(read);
    }

    [Fact]
    public void OtherAgent_DoesNotSeeDefaultAgentTools()
    {
        using var sp = Build();

        var catalog = sp.GetRequiredService<CopilotToolCatalog>();

        Assert.Empty(catalog.GetTools("other").Functions);
    }

    [Fact]
    public async Task AppConfigureSession_OverridesHandler()
    {
        Func<PermissionRequest, PermissionInvocation, Task<PermissionDecision>> custom = (_, _) => Task.FromResult(PermissionDecision.ApproveOnce());
        using var sp = Build(o => o.ConfigureSession(c => c.OnPermissionRequest = custom));
        var config = new SessionConfig();
        foreach (var configurator in sp.GetServices<ICopilotSessionConfigurator>())
        {
            configurator.Configure(Options.DefaultName, config, sp);
        }

        foreach (var configure in sp.GetRequiredService<IOptionsMonitor<CopilotOptions>>().Get(Options.DefaultName).SessionConfigurations)
        {
            configure(config);
        }

        Assert.Same(custom, config.OnPermissionRequest);
        Assert.IsType<PermissionDecisionApproveOnce>(await config.OnPermissionRequest!(new PermissionRequestRead { Intention = "i", Path = "p" }, Invocation()));
    }

    public sealed class AdminTools
    {
        [Description("Lists users.")]
        public string[] ListUsers() => ["a"];

        [Description("Deletes a user.")]
        [RequiresApproval]
        public string DeleteUser(string id) => id;
    }
}
