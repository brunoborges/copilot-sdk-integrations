import { CopilotModule, type CopilotModuleOptions } from "@copilot-sdk-integrations/nestjs";
import { ConfigService } from "@nestjs/config";

/**
 * Builds `CopilotModule` options from the environment:
 * - `GITHUB_TOKEN` (or the logged-in Copilot CLI user), or a BYOK provider (`BYOK_BASE_URL`, `BYOK_API_KEY`, `BYOK_TYPE`, `COPILOT_MODEL`);
 * - `COPILOT_CLI_URL` to use an existing runtime server; `COPILOT_STARTUP=eager` to fail fast at boot.
 */
export function copilotOptions(config: ConfigService): CopilotModuleOptions {
  const githubToken = config.get<string>("GITHUB_TOKEN");
  const cliUrl = config.get<string>("COPILOT_CLI_URL");
  const byokBaseUrl = config.get<string>("BYOK_BASE_URL");
  const model = config.get<string>("COPILOT_MODEL");
  const byokType = config.get<"openai" | "azure" | "anthropic">("BYOK_TYPE", "openai");
  const byokApiKey = config.get<string>("BYOK_API_KEY");

  return {
    startup: config.get<"lazy" | "eager">("COPILOT_STARTUP", "lazy"),
    ...(githubToken ? { githubToken } : {}),
    ...(cliUrl ? { cliUrl } : {}),
    limits: { maxConcurrentTurns: 8, maxQueuedTurns: 32 },
    shutdownGracePeriod: 10_000,
    ...(byokBaseUrl ? { providers: { byok: { type: byokType, baseUrl: byokBaseUrl, ...(byokApiKey ? { apiKey: byokApiKey } : {}) } } } : {}),
    // Per-agent overrides beat the decorator values.
    agents: {
      "node-advisor": {
        ...(model ? { model } : {}),
        ...(byokBaseUrl ? { provider: "byok" } : {}),
      },
    },
    context: { headers: ["x-request-id"] },
  };
}

/** The root Copilot module. Tests swap it with `overrideModule(AppCopilotModule).useModule(CopilotTestingModule.forRoot(...))`. */
export const AppCopilotModule = CopilotModule.forRootAsync({ inject: [ConfigService], useFactory: copilotOptions });
