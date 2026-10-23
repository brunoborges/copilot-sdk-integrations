import { z } from "zod";

const milliseconds = (fallback: number, maximum = 120_000) =>
  z.coerce.number().int().min(1000).max(maximum).default(fallback);

const schema = z.object({
  COPILOT_MODEL: z.string().trim().min(1).max(200).optional(),
  COPILOT_RESPONSE_TIMEOUT_MS: milliseconds(120_000, 600_000),
  COPILOT_MCP_TIMEOUT_MS: milliseconds(30_000),
  PLAYWRIGHT_HEADLESS: z.enum(["true", "false"]).default("true"),
  PLAYWRIGHT_CHANNEL: z.enum(["chromium", "chrome", "msedge"]).default("chromium"),
  PLAYWRIGHT_LAUNCH_TIMEOUT_MS: milliseconds(30_000),
  PLAYWRIGHT_NAVIGATION_TIMEOUT_MS: milliseconds(30_000),
  PLAYWRIGHT_SNAPSHOT_TIMEOUT_MS: milliseconds(10_000),
});

/** Deliberately no generic SDK/browser options, permissions, paths, or tool names. */
export function loadWorkshopConfig(environment: NodeJS.ProcessEnv = process.env) {
  const parsed = schema.safeParse(environment);
  if (!parsed.success) {
    throw new Error(`Invalid workshop configuration: ${parsed.error.issues.map((issue) =>
      `${issue.path.join(".")}: ${issue.message}`).join("; ")}`);
  }
  return {
    model: parsed.data.COPILOT_MODEL,
    responseTimeout: parsed.data.COPILOT_RESPONSE_TIMEOUT_MS,
    mcpTimeout: parsed.data.COPILOT_MCP_TIMEOUT_MS,
    browser: {
      headless: parsed.data.PLAYWRIGHT_HEADLESS === "true",
      channel: parsed.data.PLAYWRIGHT_CHANNEL,
      launchTimeout: parsed.data.PLAYWRIGHT_LAUNCH_TIMEOUT_MS,
      navigationTimeout: parsed.data.PLAYWRIGHT_NAVIGATION_TIMEOUT_MS,
      snapshotTimeout: parsed.data.PLAYWRIGHT_SNAPSHOT_TIMEOUT_MS,
    },
  };
}
