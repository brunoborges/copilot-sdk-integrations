using System.Reflection;
using System.Runtime.InteropServices;

namespace CopilotSdk.Aspire.Hosting;

/// <summary>
/// Finds the runtime executable for executable mode.
/// </summary>
internal static class CopilotRuntimeLocator
{
    internal const string CliPathVariable = "COPILOT_CLI_PATH";

    /// <summary>
    /// Resolution order: <c>COPILOT_CLI_PATH</c>, the <c>copilot-runtime</c> that <c>GitHub.Copilot.SDK</c> copies to
    /// <c>runtimes/{rid}/native</c> in the AppHost output, then <c>copilot</c> on <c>PATH</c>.
    /// </summary>
    public static string Resolve(string baseDirectory, Func<string, string?> getEnvironmentVariable)
    {
        if (getEnvironmentVariable(CliPathVariable) is { Length: > 0 } explicitPath)
        {
            return explicitPath;
        }

        if (GetBundledRuntimePath(baseDirectory) is { } bundled)
        {
            return bundled;
        }

        return OperatingSystem.IsWindows() ? "copilot.exe" : "copilot";
    }

    public static string? GetBundledRuntimePath(string baseDirectory)
    {
        if (GetPortableRid() is not { } rid)
        {
            return null;
        }

        var path = Path.Combine(baseDirectory, "runtimes", rid, "native", OperatingSystem.IsWindows() ? "copilot-runtime.exe" : "copilot-runtime");
        if (!File.Exists(path) || !File.Exists(Path.Combine(Path.GetDirectoryName(path)!, "runtime.node")))
        {
            return null;
        }

        EnsureExecutable(path);
        return path;
    }

    /// <summary>The Copilot CLI version <c>GitHub.Copilot.SDK</c> pins, used for the container image.</summary>
    public static string CliVersion { get; } =
        typeof(CopilotRuntimeLocator).Assembly.GetCustomAttributes<AssemblyMetadataAttribute>()
            .FirstOrDefault(a => a.Key == "CopilotCliVersion")?.Value is { Length: > 0 } version
            ? version
            : throw new InvalidOperationException("CopilotCliVersion assembly metadata is missing.");

    private static string? GetPortableRid()
    {
        string os;
        if (OperatingSystem.IsWindows())
        {
            os = "win";
        }
        else if (OperatingSystem.IsLinux())
        {
            os = RuntimeInformation.RuntimeIdentifier.StartsWith("linux-musl-", StringComparison.Ordinal) ? "linux-musl" : "linux";
        }
        else if (OperatingSystem.IsMacOS())
        {
            os = "osx";
        }
        else
        {
            return null;
        }

        var arch = RuntimeInformation.OSArchitecture switch
        {
            Architecture.X64 => "x64",
            Architecture.Arm64 => "arm64",
            _ => null,
        };

        return arch is null ? null : $"{os}-{arch}";
    }

    private static void EnsureExecutable(string path)
    {
        if (OperatingSystem.IsWindows())
        {
            return;
        }

        const UnixFileMode executeBits = UnixFileMode.UserExecute | UnixFileMode.GroupExecute | UnixFileMode.OtherExecute;
        try
        {
            var mode = File.GetUnixFileMode(path);
            if ((mode & executeBits) == 0)
            {
                File.SetUnixFileMode(path, mode | executeBits);
            }
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            // DCP reports a clear error if the file is not executable.
        }
    }
}
