namespace CopilotSdk.Extensions.Hosting;

/// <summary>
/// Settings for one <see cref="CopilotLoopRunner"/> run: the prompt, how many fresh-session iterations to
/// allow, and when to stop early.
/// </summary>
public sealed class CopilotLoopOptions
{
    /// <summary>The prompt sent on every iteration. Ignored when <see cref="PromptProvider"/> is set.</summary>
    public string? Prompt { get; set; }

    /// <summary>
    /// Produces the prompt for an iteration (1-based), for example by re-reading <c>PROMPT.md</c>.
    /// Takes precedence over <see cref="Prompt"/>.
    /// </summary>
    public Func<int, CancellationToken, ValueTask<string>>? PromptProvider { get; set; }

    /// <summary>The most iterations to run. Must be at least 1.</summary>
    public required int MaxIterations { get; set; }

    /// <summary>Optional limit for each iteration; a timed-out iteration fails with <see cref="TimeoutException"/>.</summary>
    public TimeSpan? IterationTimeout { get; set; }

    /// <summary>
    /// Runs between iterations (never after the last one), for example the build or test suite. Returning
    /// <see langword="false"/> stops the loop with <see cref="CopilotLoopStopReason.Check"/>.
    /// </summary>
    public Func<CopilotLoopIteration, CancellationToken, ValueTask<bool>>? Check { get; set; }

    /// <summary>
    /// Evaluated after each successful iteration; returning <see langword="true"/> stops the loop with
    /// <see cref="CopilotLoopStopReason.StopCondition"/>.
    /// </summary>
    public Func<CopilotLoopIteration, bool>? StopWhen { get; set; }

    /// <summary>Called after every iteration. Exceptions it throws are ignored.</summary>
    public Action<CopilotLoopIteration>? OnIteration { get; set; }

    /// <summary>Keep going after a failed or timed-out iteration instead of stopping with <see cref="CopilotLoopStopReason.Failed"/>.</summary>
    public bool ContinueOnFailure { get; set; }
}
