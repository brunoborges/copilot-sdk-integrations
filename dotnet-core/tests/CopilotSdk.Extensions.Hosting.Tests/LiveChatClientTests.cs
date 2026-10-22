using GitHub.Copilot;
using Microsoft.Extensions.AI;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.DependencyInjection;

namespace CopilotSdk.Extensions.Hosting.Tests;

/// <summary>
/// Runs against a real Copilot runtime with the logged-in user. Opt in with <c>COPILOT_LIVE_TESTS=1</c>.
/// </summary>
public sealed class LiveChatClientTests
{
    private static bool Enabled => Environment.GetEnvironmentVariable("COPILOT_LIVE_TESTS") == "1";

    [Fact]
    public async Task GetResponseAsync_AgainstRealRuntime()
    {
        Assert.SkipUnless(Enabled, "Set COPILOT_LIVE_TESTS=1 to run live Copilot tests.");
        var ct = TestContext.Current.CancellationToken;

        var services = new ServiceCollection();
        services.AddLogging();
        services.AddSingleton<IConfiguration>(new ConfigurationBuilder().AddEnvironmentVariables().Build());
        services.AddCopilot(o =>
        {
            o.Instructions = "You are a terse test assistant.";
            o.ConfigureClient(c => c.UseLoggedInUser ??= true);
        });
        await using var sp = services.BuildServiceProvider();
        var chat = sp.GetRequiredService<IChatClient>();
        var provider = sp.GetRequiredService<ICopilotClientProvider>();

        try
        {
            var first = await chat.GetResponseAsync("Reply with exactly the word: pong", cancellationToken: ct);
            Assert.Contains("pong", first.Text, StringComparison.OrdinalIgnoreCase);
            Assert.False(string.IsNullOrEmpty(first.ConversationId));

            var second = await chat.GetResponseAsync(
                "What word did you reply with last time? Reply with that word only.",
                new ChatOptions { ConversationId = first.ConversationId },
                ct);
            Assert.Contains("pong", second.Text, StringComparison.OrdinalIgnoreCase);
            Assert.Equal(first.ConversationId, second.ConversationId);

            var streamed = await chat.GetStreamingResponseAsync("Reply with exactly the word: ping", cancellationToken: ct).ToListAsync(ct);
            Assert.Contains("ping", streamed.ToChatResponse().Text, StringComparison.OrdinalIgnoreCase);
        }
        finally
        {
            using var shutdown = new CancellationTokenSource(TimeSpan.FromSeconds(10));
            await provider.StopAsync(shutdown.Token);
        }
    }
}
