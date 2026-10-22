using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Options;

namespace CopilotSdk.Extensions.Hosting.Tests;

public sealed class ValidationTests
{
    private static ValidateOptionsResult Validate(Action<CopilotOptions> configure, string name = "")
    {
        var options = new CopilotOptions();
        configure(options);
        return new CopilotOptionsValidator().Validate(name, options);
    }

    private static void AssertFails(ValidateOptionsResult result, string expected)
    {
        Assert.True(result.Failed, "Expected validation to fail.");
        Assert.Contains(result.Failures!, f => f.Contains(expected, StringComparison.Ordinal));
    }

    [Fact]
    public void Defaults_AreValid() => Assert.True(Validate(_ => { }).Succeeded);

    [Fact]
    public void ResponseTimeout_MustBePositive() =>
        AssertFails(Validate(o => o.ResponseTimeout = TimeSpan.Zero), "Copilot:ResponseTimeout must be positive");

    [Fact]
    public void NamedAgent_MessagesUseAgentKey() =>
        AssertFails(Validate(o => o.ResponseTimeout = TimeSpan.Zero, "triage"), "Copilot:Agents:triage:ResponseTimeout");

    [Fact]
    public void MaxConcurrentTurns_MustBePositive() =>
        AssertFails(Validate(o => o.MaxConcurrentTurns = 0), "Copilot:MaxConcurrentTurns must be positive");

    [Theory]
    [InlineData("tcp://localhost:5000")]
    [InlineData("tcp://127.0.0.1:5000")]
    [InlineData("tcp://[::1]:5000")]
    [InlineData("localhost:5000")]
    [InlineData("5000")]
    [InlineData("http://localhost:5000")]
    public void LoopbackEndpoint_DoesNotNeedToken(string endpoint) =>
        Assert.True(Validate(o => o.Endpoint = endpoint).Succeeded);

    [Theory]
    [InlineData("tcp://runtime:5000")]
    [InlineData("runtime.internal:5000")]
    [InlineData("https://runtime.example.com")]
    public void RemoteEndpoint_RequiresConnectionToken(string endpoint)
    {
        AssertFails(Validate(o => o.Endpoint = endpoint), "Copilot:ConnectionToken is required");
        Assert.True(Validate(o => { o.Endpoint = endpoint; o.ConnectionToken = "t"; }).Succeeded);
    }

    [Theory]
    [InlineData("tcp://runtime")]
    [InlineData("runtime")]
    [InlineData("0")]
    [InlineData("70000")]
    [InlineData("ftp://runtime:21")]
    [InlineData("tcp://runtime:5000/path")]
    public void InvalidEndpoint_Fails(string endpoint) =>
        AssertFails(Validate(o => o.Endpoint = endpoint), "Copilot:Endpoint");

    [Fact]
    public void GitHubToken_WithEndpoint_Fails() =>
        AssertFails(
            Validate(o => { o.Endpoint = "localhost:5000"; o.GitHubToken = "ghp"; }),
            "Copilot:GitHubToken cannot be combined with Copilot:Endpoint");

    [Fact]
    public void GitHubToken_WithTokenProvider_IsAllowed() =>
        Assert.True(Validate(o => { o.GitHubToken = "ghp"; o.GitHubTokenProvider = (_, _) => Task.FromResult<string?>(null); }).Succeeded);

    [Fact]
    public void ClientSettings_AreNotValidatedOnNamedAgents() =>
        Assert.True(Validate(o => o.Endpoint = "tcp://runtime:5000", "triage").Succeeded);

    [Fact]
    public void Provider_RequiresBaseUrl() =>
        AssertFails(Validate(o => o.Provider = new() { Type = "openai" }), "Copilot:Provider:BaseUrl is required");

    [Fact]
    public void HttpMcpServer_RequiresUrl() =>
        AssertFails(Validate(o => o.McpServers["docs"] = new() { Type = "http" }), "Copilot:McpServers:docs:Url");

    [Fact]
    public void StdioMcpServer_RequiresCommand() =>
        AssertFails(Validate(o => o.McpServers["fs"] = new() { Type = "stdio" }), "Copilot:McpServers:fs:Command is required");

    [Fact]
    public void McpServer_WithoutUrlOrCommand_Fails() =>
        AssertFails(Validate(o => o.McpServers["x"] = new()), "Copilot:McpServers:x needs a Url");

    [Fact]
    public void McpServer_UnknownType_Fails() =>
        AssertFails(Validate(o => o.McpServers["x"] = new() { Type = "ws", Url = "https://x" }), "Copilot:McpServers:x:Type 'ws' is not supported");

    [Fact]
    public void McpServer_Timeout_MustBePositive() =>
        AssertFails(Validate(o => o.McpServers["x"] = new() { Url = "https://x", Timeout = 0 }), "Copilot:McpServers:x:Timeout");

    [Fact]
    public async Task ValidateOnStart_FailsHostStart()
    {
        var builder = Host.CreateEmptyApplicationBuilder(new());
        builder.Configuration.AddInMemoryCollection(new Dictionary<string, string?> { ["Copilot:Endpoint"] = "tcp://runtime:5000" });
        builder.Services.AddCopilot();
        using var host = builder.Build();

        var ex = await Assert.ThrowsAsync<OptionsValidationException>(() => host.StartAsync(TestContext.Current.CancellationToken));
        Assert.Contains("Copilot:ConnectionToken is required", ex.Message);
    }
}
