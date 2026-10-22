using CopilotSdk.Extensions.Hosting;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.Options;

namespace CopilotSdk.Aspire;

/// <summary>
/// Fails at startup when an agent registered through Aspire has neither an endpoint nor a connection string.
/// </summary>
internal sealed class CopilotConnectionStringValidator(string optionsName, string connectionName, IConfiguration configuration)
    : IValidateOptions<CopilotOptions>
{
    public ValidateOptionsResult Validate(string? name, CopilotOptions options)
    {
        if ((name ?? Microsoft.Extensions.Options.Options.DefaultName) != optionsName || options.Endpoint is not null)
        {
            return ValidateOptionsResult.Skip;
        }

        var effectiveName = string.IsNullOrWhiteSpace(options.ConnectionName) ? connectionName : options.ConnectionName;
        if (string.IsNullOrWhiteSpace(configuration.GetConnectionString(effectiveName)))
        {
            return ValidateOptionsResult.Fail(
                $"ConnectionStrings:{effectiveName} not found. Did you call WithReference in the AppHost? " +
                $"For example: builder.AddProject<Projects.Api>(\"api\").WithReference(copilot).");
        }

        return ValidateOptionsResult.Success;
    }
}
