using System.Text.Json;
using CopilotSdk.Extensions.Hosting;
using GitHub.Copilot;
using GitHub.Copilot.Rpc;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Configuration;

namespace Workshop;

public static class Agents
{
    public static string CuratorInstructions => Prompts.Read("curator");
    public static string ReviewerInstructions => Prompts.Read("reviewer");
    public static string ResearchInstructions => Prompts.Read("researcher");

    public static IServiceCollection AddWorkshop(this IServiceCollection services, RunState run, string mode,
        IConfiguration configuration)
    {
        services.AddSingleton(run);
        var agent = services.AddCopilot(mode, options =>
        {
            if (mode == "accessibility")
            {
                var browser = configuration["Workshop:Browser"]
                    ?? throw new InvalidOperationException("Workshop:Browser must be configured.");
                var headless = configuration.GetValue<bool>("Workshop:Headless");
                List<string> args = ["-y", "@playwright/mcp@0.0.78", $"--browser={browser}", "--isolated"];
                if (headless) args.Add("--headless");
                args.AddRange(["--output-dir", run.OutputDirectory, "--output-mode", "file"]);
                options.McpServers["playwright"] = new CopilotMcpServerOptions
                {
                    Command = "npx", Args = args,
                    WorkingDirectory = run.OutputDirectory,
                    Tools = ["browser_navigate"]
                };
            }
            options.ConfigureSession(config => ConfigureSession(config, run, mode));
        });
        if (mode == "museum")
        {
            agent.WithTool((Func<object>)run.ApprovedFactLookup, "approved_fact_lookup", "Read bounded educator-approved facts.");
            if (run.Research is not null)
                agent.WithTool((Func<object>)run.ApprovedWikipediaFactLookup,
                    "approved_wikipedia_fact_lookup", "Read the accepted cited Wikipedia research snapshot.");
        }
        else if (mode == "accessibility")
        {
            agent.WithTool((Func<string>)run.ReadLatestAccessibilitySnapshot,
                "read_latest_accessibility_snapshot", "Read this run's bounded Playwright accessibility tree.");
            agent.WithTool((Func<string, object>)run.AccessibilityRuleLookup,
                "accessibility_rule_lookup", "Look up an issue or criterion in the local WCAG catalog.");
        }
        else throw new ArgumentException("Unknown workshop agent.");
        return services;
    }

    public static IServiceCollection AddResearch(this IServiceCollection services)
    {
        services.AddCopilot("research", options =>
        {
            options.McpServers["wikipedia"] = new CopilotMcpServerOptions
            {
                Command = "npx", Args = ["-y", "wikipedia-mcp@1.0.3"], Tools = ["search", "readArticle"]
            };
            options.ConfigureSession(config =>
            {
                config.SystemMessage = new SystemMessageConfig { Mode = SystemMessageMode.Replace, Content = ResearchInstructions };
                config.AvailableTools = ["wikipedia-search", "wikipedia-readArticle"];
                config.OnPermissionRequest = (request, _) => Task.FromResult(
                    ResearchAllowed(request) ? PermissionDecision.ApproveOnce() : PermissionDecision.Reject("Only Wikipedia search/read is allowed."));
            });
        });
        return services;
    }

    public static void ConfigureSession(SessionConfigBase config, RunState run, string mode)
    {
        config.SystemMessage = new SystemMessageConfig
        {
            Mode = SystemMessageMode.Replace, Content = mode == "museum" ? CuratorInstructions : ReviewerInstructions
        };
        config.AvailableTools = mode == "museum"
            ? run.Research is null ? ["approved_fact_lookup"] : ["approved_fact_lookup", "approved_wikipedia_fact_lookup"]
            : ["playwright-browser_navigate", "read_latest_accessibility_snapshot", "accessibility_rule_lookup"];
        config.OnPermissionRequest = (request, _) => Task.FromResult(
            Allowed(request, run, mode) ? PermissionDecision.ApproveOnce() :
                PermissionDecision.Reject("Only the configured read-only tools and exact target navigation are allowed."));
    }

    public static bool ResearchAllowed(PermissionRequest request) =>
        request is PermissionRequestMcp { ServerName: "wikipedia" } mcp &&
        mcp.ToolName is "search" or "readArticle" or "wikipedia-search" or "wikipedia-readArticle";

    public static bool Allowed(PermissionRequest request, RunState run, string mode)
    {
        if (request is PermissionRequestCustomTool tool)
            return mode == "museum"
                ? tool.ToolName == "approved_fact_lookup" || (run.Research is not null && tool.ToolName == "approved_wikipedia_fact_lookup")
                : tool.ToolName is "read_latest_accessibility_snapshot" or "accessibility_rule_lookup";
        if (mode != "accessibility" || run.Target is null ||
            request is not PermissionRequestMcp { ServerName: "playwright" } navigation ||
            navigation.ToolName is not ("browser_navigate" or "playwright-browser_navigate"))
            return false;
        var args = JsonSerializer.SerializeToElement(navigation.Args, Contracts.Json);
        return args.ValueKind == JsonValueKind.Object &&
            args.TryGetProperty("url", out var url) && url.ValueKind == JsonValueKind.String &&
            Contracts.SameTarget(url.GetString()!, run.Target);
    }
}
