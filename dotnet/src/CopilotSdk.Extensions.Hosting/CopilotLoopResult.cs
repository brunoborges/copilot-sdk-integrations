using Microsoft.Extensions.AI;

namespace CopilotSdk.Extensions.Hosting;

/// <summary>Why a <see cref="CopilotLoopRunner"/> run ended.</summary>
public enum CopilotLoopStopReason
{
    /// <summary><see cref="CopilotLoopOptions.StopWhen"/> matched a successful iteration.</summary>
    StopCondition,

    /// <summary><see cref="CopilotLoopOptions.Check"/> returned <see langword="false"/>.</summary>
    Check,

    /// <summary><see cref="CopilotLoopOptions.MaxIterations"/> iterations ran.</summary>
    MaxIterations,

    /// <summary>An iteration failed or timed out and <see cref="CopilotLoopOptions.ContinueOnFailure"/> is off.</summary>
    Failed,

    /// <summary>The cancellation token was cancelled. An interrupted iteration is not recorded.</summary>
    Cancelled,
}

/// <summary>One iteration of a <see cref="CopilotLoopRunner"/> run.</summary>
/// <param name="Number">The 1-based iteration number.</param>
/// <param name="Prompt">The prompt that was sent.</param>
/// <param name="Response">The agent's response, or <see langword="null"/> when the iteration failed or used a custom step.</param>
/// <param name="Text">The response text.</param>
/// <param name="Exception">Why the iteration failed, or <see langword="null"/>.</param>
/// <param name="Duration">How long the iteration took.</param>
public sealed record CopilotLoopIteration(
    int Number,
    string Prompt,
    ChatResponse? Response,
    string? Text,
    Exception? Exception,
    TimeSpan Duration)
{
    /// <summary>Whether the iteration completed without an exception.</summary>
    public bool Succeeded => Exception is null;
}

/// <summary>The outcome of a <see cref="CopilotLoopRunner"/> run.</summary>
/// <param name="StopReason">Why the loop ended.</param>
/// <param name="Iterations">Every completed iteration, in order.</param>
public sealed record CopilotLoopResult(CopilotLoopStopReason StopReason, IReadOnlyList<CopilotLoopIteration> Iterations)
{
    /// <summary>The last completed iteration, if any.</summary>
    public CopilotLoopIteration? Last => Iterations.Count == 0 ? null : Iterations[^1];
}
