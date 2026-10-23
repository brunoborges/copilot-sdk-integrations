using System.Diagnostics;
using Microsoft.Extensions.AI;

namespace CopilotSdk.Extensions.Hosting;

/// <summary>
/// Runs the same prompt repeatedly, each iteration in a fresh Copilot session (the "Ralph loop" pattern).
/// State lives on disk (for example <c>IMPLEMENTATION_PLAN.md</c> in the agent's working directory), not in
/// model context; tests or builds run by the agent, or by <see cref="CopilotLoopOptions.Check"/>, are the
/// quality gate.
/// </summary>
/// <remarks>
/// Every iteration is an ordinary <see cref="IChatClient.GetResponseAsync"/> call without a conversation ID, so
/// the agent's permission policy, tools, hooks, and telemetry apply unchanged; nothing is approved implicitly.
/// <c>AddCopilot</c> registers a runner for the default agent and a keyed one for each named agent.
/// </remarks>
public sealed class CopilotLoopRunner
{
    private readonly IChatClient _client;

    /// <summary>Creates a runner for <paramref name="client"/>.</summary>
    public CopilotLoopRunner(IChatClient client)
    {
        ArgumentNullException.ThrowIfNull(client);
        _client = client;
    }

    /// <summary>Runs the loop against this runner's agent.</summary>
    public Task<CopilotLoopResult> RunAsync(CopilotLoopOptions options, CancellationToken cancellationToken = default) =>
        RunCoreAsync(
            async (_, prompt, ct) =>
            {
                var response = await _client.GetResponseAsync(prompt, cancellationToken: ct).ConfigureAwait(false);
                return (response, response.Text);
            },
            options,
            cancellationToken);

    /// <summary>
    /// Runs the loop with a custom <paramref name="step"/> (iteration number, prompt, token → output). The step
    /// must start a fresh session on every call.
    /// </summary>
    public static Task<CopilotLoopResult> RunAsync(
        Func<int, string, CancellationToken, Task<string?>> step,
        CopilotLoopOptions options,
        CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(step);
        return RunCoreAsync(
            async (n, prompt, ct) => ((ChatResponse?)null, await step(n, prompt, ct).ConfigureAwait(false)),
            options,
            cancellationToken);
    }

    private static async Task<CopilotLoopResult> RunCoreAsync(
        Func<int, string, CancellationToken, Task<(ChatResponse? Response, string? Text)>> step,
        CopilotLoopOptions options,
        CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(options);
        if (options.MaxIterations < 1)
        {
            throw new ArgumentOutOfRangeException(nameof(options), options.MaxIterations, "MaxIterations must be at least 1.");
        }

        if (options.IterationTimeout is { } t && t <= TimeSpan.Zero)
        {
            throw new ArgumentOutOfRangeException(nameof(options), t, "IterationTimeout must be positive.");
        }

        if (options.PromptProvider is null && string.IsNullOrEmpty(options.Prompt))
        {
            throw new ArgumentException("Set Prompt or PromptProvider.", nameof(options));
        }

        var iterations = new List<CopilotLoopIteration>();
        for (var number = 1; number <= options.MaxIterations; number++)
        {
            if (cancellationToken.IsCancellationRequested)
            {
                return new(CopilotLoopStopReason.Cancelled, iterations);
            }

            string prompt;
            try
            {
                prompt = options.PromptProvider is { } provider
                    ? await provider(number, cancellationToken).ConfigureAwait(false)
                    : options.Prompt!;
            }
            catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
            {
                return new(CopilotLoopStopReason.Cancelled, iterations);
            }

            var iteration = await RunIterationAsync(step, number, prompt, options.IterationTimeout, cancellationToken).ConfigureAwait(false);
            if (iteration is null)
            {
                return new(CopilotLoopStopReason.Cancelled, iterations);
            }

            iterations.Add(iteration);
            try
            {
                options.OnIteration?.Invoke(iteration);
            }
            catch (Exception)
            {
                // Listeners must not change the outcome.
            }

            if (!iteration.Succeeded && !options.ContinueOnFailure)
            {
                return new(CopilotLoopStopReason.Failed, iterations);
            }

            if (iteration.Succeeded && options.StopWhen?.Invoke(iteration) == true)
            {
                return new(CopilotLoopStopReason.StopCondition, iterations);
            }

            if (number < options.MaxIterations && options.Check is { } check)
            {
                bool proceed;
                try
                {
                    proceed = await check(iteration, cancellationToken).ConfigureAwait(false);
                }
                catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
                {
                    return new(CopilotLoopStopReason.Cancelled, iterations);
                }

                if (!proceed)
                {
                    return new(CopilotLoopStopReason.Check, iterations);
                }
            }
        }

        return new(CopilotLoopStopReason.MaxIterations, iterations);
    }

    private static async Task<CopilotLoopIteration?> RunIterationAsync(
        Func<int, string, CancellationToken, Task<(ChatResponse? Response, string? Text)>> step,
        int number,
        string prompt,
        TimeSpan? timeout,
        CancellationToken cancellationToken)
    {
        using var cts = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        if (timeout is { } limit)
        {
            cts.CancelAfter(limit);
        }

        var started = Stopwatch.GetTimestamp();
        try
        {
            var (response, text) = await step(number, prompt, cts.Token).ConfigureAwait(false);
            return new(number, prompt, response, text, null, Stopwatch.GetElapsedTime(started));
        }
        catch (Exception) when (cancellationToken.IsCancellationRequested)
        {
            return null;
        }
        catch (OperationCanceledException) when (cts.IsCancellationRequested)
        {
            return new(number, prompt, null, null, new TimeoutException($"Iteration {number} timed out after {timeout}."), Stopwatch.GetElapsedTime(started));
        }
        catch (Exception ex)
        {
            return new(number, prompt, null, null, ex, Stopwatch.GetElapsedTime(started));
        }
    }
}
