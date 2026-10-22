using GitHub.Copilot;
using Microsoft.Extensions.DependencyInjection;

namespace CopilotSdk.Extensions.Hosting.Tests;

public sealed class GitHubTokenTests
{
    private static CopilotClientOptions ConfigureClient(Dictionary<string, string?> settings)
    {
        using var sp = TestHost.Build(s => s.AddCopilot(), settings);
        var options = new CopilotClientOptions();
        new CopilotClientOptionsConfigurator(sp).Configure(options, sp.GetOptions());
        return options;
    }

    [Fact]
    public void ConfiguredToken_WinsOverEnvironment()
    {
        var options = ConfigureClient(new() { ["Copilot:GitHubToken"] = "configured", ["GH_TOKEN"] = "env" });

        Assert.Equal("configured", options.GitHubToken);
    }

    [Theory]
    [InlineData("COPILOT_GITHUB_TOKEN")]
    [InlineData("GH_TOKEN")]
    [InlineData("GITHUB_TOKEN")]
    public void EnvironmentToken_IsUsed_WhenNotConfigured(string key)
    {
        var options = ConfigureClient(new() { [key] = "env-token" });

        Assert.Equal("env-token", options.GitHubToken);
    }

    [Fact]
    public void EnvironmentTokens_FollowPrecedence()
    {
        var options = ConfigureClient(new()
        {
            ["GITHUB_TOKEN"] = "github",
            ["GH_TOKEN"] = "gh",
            ["COPILOT_GITHUB_TOKEN"] = "copilot",
        });

        Assert.Equal("copilot", options.GitHubToken);
    }

    [Fact]
    public void NoToken_LeavesSignedInUser()
    {
        var options = ConfigureClient([]);

        Assert.Null(options.GitHubToken);
    }

    [Fact]
    public void EnvironmentToken_IsIgnored_WithEndpoint()
    {
        var options = ConfigureClient(new() { ["Copilot:Endpoint"] = "localhost:3000", ["GH_TOKEN"] = "env" });

        Assert.Null(options.GitHubToken);
    }
}
