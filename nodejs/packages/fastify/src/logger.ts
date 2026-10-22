import type { CopilotLogger } from "@copilot-sdk-integrations/core";
import type { FastifyBaseLogger } from "fastify";

/** Adapts a Fastify (pino) logger to the core `CopilotLogger` interface. */
export function fastifyCopilotLogger(log: FastifyBaseLogger): CopilotLogger {
  const level =
    (method: "debug" | "info" | "warn" | "error") =>
    (message: string, meta?: Record<string, unknown>) => {
      if (meta) log[method](meta, message);
      else log[method](message);
    };
  return { debug: level("debug"), info: level("info"), warn: level("warn"), error: level("error") };
}
