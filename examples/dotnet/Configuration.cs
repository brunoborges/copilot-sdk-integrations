using Microsoft.Extensions.Configuration;

namespace Workshop;

public static class Prompts
{
    public static string Read(string name)
    {
        using var stream = typeof(Prompts).Assembly.GetManifestResourceStream($"Workshop.Prompts.{name}.txt")
            ?? throw new InvalidOperationException($"Missing embedded prompt resource: {name}.");
        using var reader = new StreamReader(stream);
        var content = reader.ReadToEnd();
        return !string.IsNullOrWhiteSpace(content) ? content :
            throw new InvalidDataException($"Empty prompt resource: {name}.");
    }
}

public static class WorkshopConfiguration
{
    public static void AddDefaults(ConfigurationManager configuration)
    {
        configuration.AddJsonFile(Path.Combine(AppContext.BaseDirectory, "appsettings.json"), optional: false)
            .AddJsonFile(Path.Combine(AppContext.BaseDirectory, "appsettings.Local.json"), optional: true)
            .AddEnvironmentVariables();
    }
}
