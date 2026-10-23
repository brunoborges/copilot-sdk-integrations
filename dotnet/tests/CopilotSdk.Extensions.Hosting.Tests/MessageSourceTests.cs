using CopilotSdk.Extensions.Hosting;
using GitHub.Copilot;
using Microsoft.Extensions.AI;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Options;

namespace CopilotSdk.Extensions.Hosting.Tests;

public sealed class MessageSourceTests
{
    private static ServiceProvider Build(FakeSessionGateway gateway, Action<IServiceCollection>? configure = null)
    {
        var services = new ServiceCollection();
        services.AddSingleton<IConfiguration>(new ConfigurationBuilder().Build());
        services.AddCopilot();
        configure?.Invoke(services);
        services.AddSingleton<ICopilotSessionGateway>(gateway);
        return services.BuildServiceProvider();
    }

    private static CopilotChatClient Chat(IServiceProvider services, string name = "") =>
        ActivatorUtilities.CreateInstance<CopilotChatClient>(services, name);

    private static string? SourceOf(FakeSession session) => Assert.Single(session.Sent).Source?.Value;

    [Fact]
    public async Task Omitted_ByDefault()
    {
        var gateway = new FakeSessionGateway();
        await using var services = Build(gateway);

        await Chat(services).GetResponseAsync("hi", cancellationToken: TestContext.Current.CancellationToken);

        Assert.Null(Assert.Single(gateway.Sessions).Sent[0].Source);
    }

    [Fact]
    public async Task ConfiguredSource_IsSent_AndScopeOverridesIt()
    {
        var gateway = new FakeSessionGateway();
        await using var services = Build(gateway, s => s.AddCopilot("reports", o => o.MessageSource = "system"));
        var chat = Chat(services, "reports");
        var ct = TestContext.Current.CancellationToken;

        await chat.GetResponseAsync("a", cancellationToken: ct);
        using (CopilotMessageSources.BeginScope(CopilotMessageSources.Agent("incident-bot")))
        {
            await chat.GetResponseAsync("b", cancellationToken: ct);
            using (CopilotMessageSources.BeginScope(MessageSource.User))
            {
                await chat.GetResponseAsync("c", cancellationToken: ct);
            }

            Assert.Equal("agent-incident-bot", CopilotMessageSources.Current?.Value);
        }

        Assert.Null(CopilotMessageSources.Current);
        Assert.Equal(["system", "agent-incident-bot", "user"], gateway.Sessions.Select(SourceOf));
    }

    [Fact]
    public async Task AgentCalledFromAnotherAgentsTool_IsLabeledWithTheCallingAgent()
    {
        var gateway = new FakeSessionGateway();
        await using var services = Build(gateway, s => s.AddCopilot("helper"));
        var helper = Chat(services, "helper");
        var ct = TestContext.Current.CancellationToken;
        var askHelper = AIFunctionFactory.Create(
            async () => (await helper.GetResponseAsync("help", cancellationToken: ct)).Text, "ask_helper");
        gateway.Setup = session =>
        {
            if (session.Config.Tools is { Count: > 0 } tools)
            {
                session.OnSendAndWait = async (_, _, token) =>
                {
                    var tool = (AIFunction)tools.Single(t => t.Name == "ask_helper");
                    await tool.InvokeAsync(new AIFunctionArguments(), token);
                    return FakeEvents.Assistant("done");
                };
            }
        };

        await Chat(services).GetResponseAsync("delegate", new ChatOptions { Tools = [askHelper] }, ct);
        using (CopilotMessageSources.BeginScope(MessageSource.User))
        {
            // The outer scope labels the outer prompt only; the delegated call keeps the calling agent.
            await Chat(services).GetResponseAsync("delegate", new ChatOptions { Tools = [askHelper] }, ct);
            Assert.Equal("user", CopilotMessageSources.Current?.Value);
        }

        await helper.GetResponseAsync("direct", cancellationToken: ct);

        var sent = gateway.Sessions.SelectMany(s => s.Sent).Select(m => (m.Prompt, m.Source?.Value));
        Assert.Equal(
            [("delegate", null), ("help", "agent-default"), ("delegate", "user"), ("help", "agent-default"), ("direct", null)],
            sent);
    }

    [Theory]
    [InlineData("user", "user")]
    [InlineData("system", "system")]
    [InlineData("agent-incident.bot_1", "agent-incident.bot_1")]
    [InlineData("", null)]
    [InlineData(null, null)]
    public void Parse_AcceptsSupportedValues(string? value, string? expected) =>
        Assert.Equal(expected, CopilotMessageSources.Parse(value)?.Value);

    [Theory]
    [InlineData("admin")]
    [InlineData("USER")]
    [InlineData(" system")]
    [InlineData("agent-")]
    [InlineData("agent-bad id")]
    [InlineData("agent--x")]
    [InlineData("agent-x\n")]
    [InlineData("command-x")]
    public void Parse_RejectsUnsupportedValues(string value) =>
        Assert.Throws<ArgumentException>(() => CopilotMessageSources.Parse(value));

    [Fact]
    public void AgentIds_AreValidated()
    {
        Assert.Throws<ArgumentException>(() => CopilotMessageSources.Agent("../etc"));
        Assert.Throws<ArgumentException>(() => CopilotMessageSources.Agent(new string('a', 129)));
        Assert.Throws<ArgumentException>(() => CopilotMessageSources.BeginScope(MessageSource.Agent("bad id")));
        Assert.Equal("agent-" + new string('a', 128), CopilotMessageSources.Agent(new string('a', 128)).Value);
        Assert.Null(CopilotMessageSources.Current);
    }

    [Fact]
    public async Task InvalidConfiguredSource_FailsValidation()
    {
        await using var services = Build(new FakeSessionGateway(), s => s.AddCopilot("bad", o => o.MessageSource = "admin"));

        var error = Assert.Throws<OptionsValidationException>(
            () => services.GetRequiredService<IOptionsMonitor<CopilotOptions>>().Get("bad"));
        Assert.Contains("Copilot:Agents:bad:MessageSource", error.Message);
    }
}
