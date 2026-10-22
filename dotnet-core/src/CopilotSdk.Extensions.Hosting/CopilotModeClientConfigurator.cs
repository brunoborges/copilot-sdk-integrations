using System.Reflection;
using System.Security.Cryptography;
using System.Text;
using GitHub.Copilot;
using Microsoft.Extensions.Hosting;

namespace CopilotSdk.Extensions.Hosting;

/// <summary>
/// Runs the client in <see cref="CopilotClientMode.Empty"/>, so the runtime offers no built-in tools and none of the
/// CLI's ambient behavior (custom instructions, plugins, skills, host environment context).
/// </summary>
/// <remarks>
/// Empty mode requires a runtime home (<see cref="CopilotClientOptions.BaseDirectory"/>, <c>COPILOT_HOME</c>) when the
/// SDK starts the runtime. The default is a stable per-application directory under the local application data folder,
/// so sessions survive restarts without mixing with the user's Copilot CLI state. Apps can change
/// <see cref="CopilotClientOptions.Mode"/> or <see cref="CopilotClientOptions.BaseDirectory"/> with
/// <see cref="CopilotOptions.ConfigureClient"/>, which runs after this configurator. With an <see cref="CopilotOptions.Endpoint"/>,
/// the runtime owns its own home and no directory is set.
/// </remarks>
internal sealed class CopilotModeClientConfigurator(IHostEnvironment? environment = null) : ICopilotClientConfigurator
{
    public void Configure(CopilotClientOptions options, CopilotOptions copilotOptions)
    {
        options.Mode = CopilotClientMode.Empty;

        if (copilotOptions.Endpoint is null && string.IsNullOrEmpty(options.BaseDirectory) && options.SessionFs is null)
        {
            options.BaseDirectory = Directory.CreateDirectory(GetDefaultBaseDirectory(environment)).FullName;
        }
    }

    internal static string GetDefaultBaseDirectory(IHostEnvironment? environment)
    {
        var root = Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData, Environment.SpecialFolderOption.DoNotVerify);
        if (string.IsNullOrEmpty(root))
        {
            root = Path.GetTempPath();
        }

        var applicationName = environment?.ApplicationName;
        if (string.IsNullOrEmpty(applicationName))
        {
            applicationName = Assembly.GetEntryAssembly()?.GetName().Name ?? "app";
        }

        var contentRoot = environment?.ContentRootPath ?? AppContext.BaseDirectory;
        var hash = Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes($"{applicationName}|{contentRoot}")))[..12].ToLowerInvariant();

        var safeName = new string([.. applicationName.Select(c => char.IsAsciiLetterOrDigit(c) || c is '-' or '_' or '.' ? c : '_')]);
        return Path.Combine(root, "copilot-sdk", $"{safeName}-{hash}");
    }
}
