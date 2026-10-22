import { CopilotConfigurationError } from "@copilot-sdk-integrations/core";
import { z } from "zod";
import type { CopilotModuleOptions } from "./interfaces.js";

const fn = z.custom<(...args: any[]) => any>((value) => typeof value === "function", { message: "must be a function" });
const object = z.custom<Record<string, unknown>>((value) => typeof value === "object" && value !== null && !Array.isArray(value), {
  message: "must be an object",
});
const nonBlank = z.string().trim().min(1, { message: "must be a non-blank string" });
const ms = z.number().positive();
const nonNegativeInt = z.number().int().nonnegative();
const store = z.custom((value) => typeof value === "object" && value !== null && typeof (value as any).acquireLease === "function", {
  message: "must be a SessionStore",
});
const logger = z.custom(
  (value) =>
    typeof value === "object" && value !== null && ["debug", "info", "warn", "error"].every((m) => typeof (value as any)[m] === "function"),
  { message: "must implement debug/info/warn/error" },
);
const reasoningEffort = z.enum(["low", "medium", "high", "xhigh"]);
const stringList = z.array(nonBlank);

const agentOverrides = z.strictObject({
  description: z.string().optional(),
  instructions: z.union([z.string(), object]).optional(),
  model: nonBlank.optional(),
  reasoningEffort: reasoningEffort.optional(),
  provider: z.union([nonBlank, object]).optional(),
  mcpServers: z.union([stringList, z.record(z.string(), object)]).optional(),
  availableTools: stringList.optional(),
  excludedTools: stringList.optional(),
  builtInTools: z.boolean().optional(),
});

const optionsSchema = z
  .strictObject({
    cliPath: nonBlank.optional(),
    cliUrl: nonBlank.optional(),
    startup: z.enum(["lazy", "eager"]).optional(),
    githubToken: z.string().optional(),
    useLoggedInUser: z.boolean().optional(),
    baseDirectory: nonBlank.optional(),
    logLevel: z.enum(["none", "error", "warning", "info", "debug", "all"]).optional(),
    limits: z
      .strictObject({
        maxConcurrentTurns: z.number().int().positive().optional(),
        maxQueuedTurns: nonNegativeInt.optional(),
        conversationWaitMs: nonNegativeInt.optional(),
      })
      .optional(),
    responseTimeout: ms.optional(),
    approvalWindow: ms.optional(),
    restart: z
      .strictObject({
        maxAttempts: z.union([nonNegativeInt, z.literal(Number.POSITIVE_INFINITY)]).optional(),
        backoffMs: ms.optional(),
        maxBackoffMs: ms.optional(),
        checkIntervalMs: ms.optional(),
      })
      .optional(),
    shutdownGracePeriod: z.number().nonnegative().optional(),
    store: store.optional(),
    hooks: z.record(z.string(), fn).optional(),
    onEvent: fn.optional(),
    tokenResolver: fn.optional(),
    configureClient: fn.optional(),
    clientFactory: fn.optional(),
    copilotFactory: fn.optional(),
    logger: logger.optional(),
    providers: z.record(nonBlank, object).optional(),
    mcpServers: z.record(nonBlank, object).optional(),
    agents: z.record(z.string(), agentOverrides).optional(),
    context: z
      .union([
        z.literal(false),
        z.strictObject({
          principal: fn.optional(),
          headers: stringList.optional(),
          conversationId: fn.optional(),
          attributes: fn.optional(),
          abortOnDisconnect: z.boolean().optional(),
        }),
      ])
      .optional(),
    exceptionFilter: z.boolean().optional(),
    templatesDir: nonBlank.optional(),
    eventEmitter: z.union([z.boolean(), z.strictObject({ prefix: nonBlank.optional() })]).optional(),
  })
  .superRefine((value, ctx) => {
    if (value.cliPath !== undefined && value.cliUrl !== undefined) {
      ctx.addIssue({ code: "custom", path: ["cliUrl"], message: "cliUrl and cliPath are mutually exclusive" });
    }
    for (const [name, agent] of Object.entries(value.agents ?? {})) {
      if (agent.availableTools !== undefined && agent.excludedTools !== undefined) {
        ctx.addIssue({
          code: "custom",
          path: ["agents", name, "availableTools"],
          message: "availableTools and excludedTools are mutually exclusive",
        });
      }
    }
  });

/** Validates module options at bootstrap; errors name the offending key. */
export function validateModuleOptions(options: CopilotModuleOptions | undefined): CopilotModuleOptions {
  const input = options ?? {};
  // Drop explicit `undefined`s (common with ConfigService lookups) so they don't override defaults.
  const compacted = Object.fromEntries(Object.entries(input).filter(([, v]) => v !== undefined));
  const result = optionsSchema.safeParse(compacted);
  if (!result.success) {
    const details = result.error.issues
      .map((issue) => `${issue.path.length > 0 ? issue.path.join(".") : "(root)"}: ${issue.message}`)
      .join("; ");
    throw new CopilotConfigurationError(`Invalid CopilotModule options: ${details}`);
  }
  // Return the original objects (zod clones records), only without undefined keys.
  return compacted as CopilotModuleOptions;
}
