import { CopilotConfigurationError, type Copilot, type CopilotOptions } from "@copilot-sdk-integrations/core";
import { z } from "zod";
import type { CopilotContextOptions, CopilotHealthRouteOptions, CopilotInfoRouteOptions, CopilotPluginOptions } from "./types.js";

export const DEFAULT_SHUTDOWN_GRACE_PERIOD_MS = 30_000;
export const DEFAULT_HEALTH_PATH = "/health/copilot";
export const DEFAULT_INFO_PATH = "/copilot/info";

/** Whether `value` is an existing core `Copilot` (rather than `CopilotOptions`). */
export function isCopilot(value: unknown): value is Copilot {
  if (!value || typeof value !== "object") return false;
  const candidate = value as Record<string, unknown>;
  return ["agent", "start", "stop", "client", "capacity", "health"].every((key) => typeof candidate[key] === "function");
}

const fn = z.custom<(...args: any[]) => unknown>((value) => typeof value === "function", { message: "must be a function" });
const path = z.string().regex(/^\//, { message: 'must start with "/"' });
const record = z.custom<Record<string, unknown>>((value) => !!value && typeof value === "object" && !Array.isArray(value), {
  message: "must be an object",
});
const agentDefinition = z.custom<object>(
  (value) => !!value && typeof value === "object" && typeof (value as { name?: unknown }).name === "string",
  { message: "must be an agent definition (defineAgent)" },
);

const schema = z
  .object({
    client: record.optional(),
    startup: z.enum(["lazy", "eager"]).optional(),
    agents: z.array(agentDefinition).optional(),
    agentOverrides: z.record(z.string(), record).optional(),
    context: z
      .union([
        z.literal(false),
        z
          .object({
            principal: fn.optional(),
            headers: z.array(z.string().min(1)).optional(),
            attributes: fn.optional(),
            abortOnDisconnect: z.boolean().optional(),
            hook: z.enum(["onRequest", "preValidation", "preHandler"]).optional(),
          })
          .strict(),
      ])
      .optional(),
    errorHandler: z.boolean().optional(),
    health: z.union([z.boolean(), z.object({ path: path.optional() }).strict()]).optional(),
    info: z
      .union([
        z.boolean(),
        z
          .object({
            path: path.optional(),
            onRequest: z.union([fn, z.array(fn)]).optional(),
            includeInstructions: z.boolean().optional(),
          })
          .strict(),
      ])
      .optional(),
    shutdownGracePeriod: z.number().int().nonnegative().optional(),
    stopOnClose: z.boolean().optional(),
    // Fastify's own register options.
    prefix: z.string().optional(),
    logLevel: z.string().optional(),
    logSerializers: z.unknown().optional(),
  })
  .strict();

export interface ResolvedPluginOptions {
  client: CopilotOptions | Copilot;
  startup: "lazy" | "eager";
  agents: CopilotPluginOptions["agents"] & object;
  agentOverrides: NonNullable<CopilotPluginOptions["agentOverrides"]>;
  context: CopilotContextOptions | false;
  errorHandler: boolean;
  health: Required<CopilotHealthRouteOptions> | false;
  info: (CopilotInfoRouteOptions & { path: string }) | false;
  shutdownGracePeriod: number;
  stopOnClose: boolean | undefined;
  prefix: string | undefined;
}

/** Validates plugin options; errors name the offending key. */
export function resolvePluginOptions(options: CopilotPluginOptions | undefined): ResolvedPluginOptions {
  const result = schema.safeParse(options ?? {});
  if (!result.success) {
    const details = result.error.issues
      .map((issue) => {
        const key = issue.path.length > 0 ? issue.path.join(".") : undefined;
        if (issue.code === "unrecognized_keys") return `unknown option${issue.keys.length > 1 ? "s" : ""} ${issue.keys.map((k) => JSON.stringify([...issue.path, k].join("."))).join(", ")}`;
        return key ? `"${key}" ${issue.message}` : issue.message;
      })
      .join("; ");
    throw new CopilotConfigurationError(`Invalid copilotPlugin options: ${details}`);
  }
  const opts = (options ?? {}) as CopilotPluginOptions & { prefix?: string };
  const health = opts.health === undefined || opts.health === false ? false : { path: (opts.health === true ? undefined : opts.health.path) ?? DEFAULT_HEALTH_PATH };
  const info =
    opts.info === undefined || opts.info === false
      ? false
      : { ...(opts.info === true ? {} : opts.info), path: (opts.info === true ? undefined : opts.info.path) ?? DEFAULT_INFO_PATH };
  return {
    client: opts.client ?? {},
    startup: opts.startup ?? "lazy",
    agents: opts.agents ?? [],
    agentOverrides: opts.agentOverrides ?? {},
    context: opts.context === false ? false : (opts.context ?? {}),
    errorHandler: opts.errorHandler ?? true,
    health,
    info,
    shutdownGracePeriod: opts.shutdownGracePeriod ?? DEFAULT_SHUTDOWN_GRACE_PERIOD_MS,
    stopOnClose: opts.stopOnClose,
    prefix: opts.prefix,
  };
}
