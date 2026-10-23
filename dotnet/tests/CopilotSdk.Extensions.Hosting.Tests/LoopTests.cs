using GitHub.Copilot;
using Microsoft.Extensions.AI;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.DependencyInjection;

namespace CopilotSdk.Extensions.Hosting.Tests;

public sealed class LoopTests
{
    private static ServiceProvider Build(FakeSessionGateway gateway)
    {
        var services = new ServiceCollection();
        services.AddSingleton<IConfiguration>(new ConfigurationBuilder().Build());
        services.AddCopilot();
        services.AddCopilot("builder");
        services.AddSingleton<ICopilotSessionGateway>(gateway);
        return services.BuildServiceProvider();
    }

    [Fact]
    public async Task EachIteration_UsesAFreshSession()
    {
        var gateway = new FakeSessionGateway();
        var replies = new Queue<string>(["one", "two", "DONE", "never"]);
        gateway.Setup = s => s.OnSendAndWait = (_, _, _) =>
            Task.FromResult<AssistantMessageEvent?>(FakeEvents.Assistant(replies.Dequeue()));
        await using var services = Build(gateway);
        var seen = new List<int>();

        var result = await services.GetRequiredService<CopilotLoopRunner>().RunAsync(
            new CopilotLoopOptions
            {
                Prompt = "Do the next task",
                MaxIterations = 5,
                StopWhen = i => i.Text == "DONE",
                OnIteration = i => seen.Add(i.Number),
            },
            TestContext.Current.CancellationToken);

        Assert.Equal(CopilotLoopStopReason.StopCondition, result.StopReason);
        Assert.Equal(["one", "two", "DONE"], result.Iterations.Select(i => i.Text));
        Assert.Equal([1, 2, 3], seen);
        Assert.Equal(3, gateway.Sessions.Count);
        Assert.All(gateway.Calls, c => Assert.Null(c.ResumedId));
        Assert.All(gateway.Sessions, s => Assert.True(s.Disposed));
        Assert.Equal(3, result.Iterations.Select(i => i.Response!.ConversationId).Distinct().Count());
    }

    [Fact]
    public async Task NamedAgents_GetAKeyedRunner()
    {
        await using var services = Build(new FakeSessionGateway());

        Assert.NotNull(services.GetRequiredKeyedService<CopilotLoopRunner>("builder"));
        Assert.NotSame(services.GetRequiredService<CopilotLoopRunner>(), services.GetRequiredKeyedService<CopilotLoopRunner>("builder"));
    }

    [Fact]
    public async Task Check_RunsOnlyBetweenIterations()
    {
        var checkedIterations = new List<int>();
        var result = await CopilotLoopRunner.RunAsync(
            (n, _, _) => Task.FromResult<string?>($"out {n}"),
            new CopilotLoopOptions
            {
                PromptProvider = (n, _) => ValueTask.FromResult($"prompt {n}"),
                MaxIterations = 3,
                Check = (i, _) =>
                {
                    checkedIterations.Add(i.Number);
                    return ValueTask.FromResult(true);
                },
            },
            TestContext.Current.CancellationToken);

        Assert.Equal(CopilotLoopStopReason.MaxIterations, result.StopReason);
        Assert.Equal([1, 2], checkedIterations);
        Assert.Equal(["prompt 1", "prompt 2", "prompt 3"], result.Iterations.Select(i => i.Prompt));

        result = await CopilotLoopRunner.RunAsync(
            (_, _, _) => Task.FromResult<string?>("ok"),
            new CopilotLoopOptions { Prompt = "go", MaxIterations = 3, Check = (_, _) => ValueTask.FromResult(false) },
            TestContext.Current.CancellationToken);
        Assert.Equal(CopilotLoopStopReason.Check, result.StopReason);
        Assert.Single(result.Iterations);
    }

    [Fact]
    public async Task Failure_StopsUnlessContinueOnFailure()
    {
        Task<string?> Step(int n, string _, CancellationToken __) =>
            n == 1 ? throw new InvalidOperationException("boom") : Task.FromResult<string?>("ok");

        var result = await CopilotLoopRunner.RunAsync(Step, new() { Prompt = "go", MaxIterations = 3 }, TestContext.Current.CancellationToken);
        Assert.Equal(CopilotLoopStopReason.Failed, result.StopReason);
        Assert.IsType<InvalidOperationException>(result.Last!.Exception);

        result = await CopilotLoopRunner.RunAsync(Step, new() { Prompt = "go", MaxIterations = 3, ContinueOnFailure = true }, TestContext.Current.CancellationToken);
        Assert.Equal(CopilotLoopStopReason.MaxIterations, result.StopReason);
        Assert.Equal([false, true, true], result.Iterations.Select(i => i.Succeeded));
    }

    [Fact]
    public async Task IterationTimeout_FailsTheIteration()
    {
        var result = await CopilotLoopRunner.RunAsync(
            async (n, _, ct) =>
            {
                if (n == 1)
                {
                    await Task.Delay(Timeout.Infinite, ct);
                }

                return "ok";
            },
            new() { Prompt = "go", MaxIterations = 2, IterationTimeout = TimeSpan.FromMilliseconds(50), ContinueOnFailure = true },
            TestContext.Current.CancellationToken);

        Assert.IsType<TimeoutException>(result.Iterations[0].Exception);
        Assert.Equal("ok", result.Iterations[1].Text);
    }

    [Fact]
    public async Task Cancellation_StopsWithoutRecordingTheInterruptedIteration()
    {
        using var cts = new CancellationTokenSource();
        var result = await CopilotLoopRunner.RunAsync(
            async (n, _, ct) =>
            {
                if (n == 2)
                {
                    await cts.CancelAsync();
                    await Task.Delay(Timeout.Infinite, ct);
                }

                return "ok";
            },
            new() { Prompt = "go", MaxIterations = 5 },
            cts.Token);

        Assert.Equal(CopilotLoopStopReason.Cancelled, result.StopReason);
        Assert.Single(result.Iterations);
    }

    [Fact]
    public async Task ListenerErrors_AreIgnored()
    {
        var result = await CopilotLoopRunner.RunAsync(
            (_, _, _) => Task.FromResult<string?>("ok"),
            new() { Prompt = "go", MaxIterations = 2, OnIteration = _ => throw new InvalidOperationException() },
            TestContext.Current.CancellationToken);

        Assert.Equal(CopilotLoopStopReason.MaxIterations, result.StopReason);
    }

    [Fact]
    public async Task InvalidOptions_Throw()
    {
        static Task<string?> Step(int n, string p, CancellationToken ct) => Task.FromResult<string?>("ok");
        var ct = TestContext.Current.CancellationToken;

        await Assert.ThrowsAsync<ArgumentOutOfRangeException>(() => CopilotLoopRunner.RunAsync(Step, new() { Prompt = "go", MaxIterations = 0 }, ct));
        await Assert.ThrowsAsync<ArgumentOutOfRangeException>(() => CopilotLoopRunner.RunAsync(Step, new() { Prompt = "go", MaxIterations = 1, IterationTimeout = TimeSpan.Zero }, ct));
        await Assert.ThrowsAsync<ArgumentException>(() => CopilotLoopRunner.RunAsync(Step, new() { MaxIterations = 1 }, ct));
    }
}
