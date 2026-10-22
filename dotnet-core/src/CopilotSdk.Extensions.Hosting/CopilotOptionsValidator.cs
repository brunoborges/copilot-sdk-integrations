using Microsoft.Extensions.Options;

namespace CopilotSdk.Extensions.Hosting;

/// <summary>
/// Validates <see cref="CopilotOptions"/> at startup.
/// </summary>
internal sealed class CopilotOptionsValidator : IValidateOptions<CopilotOptions>
{
    public ValidateOptionsResult Validate(string? name, CopilotOptions options)
    {
        var failures = new List<string>();

        if (options.ResponseTimeout <= TimeSpan.Zero)
        {
            failures.Add($"{nameof(CopilotOptions.ResponseTimeout)} must be positive.");
        }

        if (options.MaxConcurrentTurns is <= 0)
        {
            failures.Add($"{nameof(CopilotOptions.MaxConcurrentTurns)} must be positive when set.");
        }

        // TODO(config agent): a non-loopback Endpoint requires ConnectionToken; Endpoint must parse; MCP server shapes.

        return failures.Count == 0 ? ValidateOptionsResult.Success : ValidateOptionsResult.Fail(failures);
    }
}
