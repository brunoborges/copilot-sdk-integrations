import { AsyncLocalStorage } from "node:async_hooks";
import type { MessageSource } from "@github/copilot-sdk";
import { CopilotConfigurationError } from "./errors.js";

export type { MessageSource };

/** Agent identifiers allowed in `agent-<id>` sources: 1-128 letters, digits, `.`, `_` or `-`, starting alphanumeric. */
export const AGENT_ID_PATTERN = /^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$/;

interface Scope {
  /** Explicit source opened with `withMessageSource`. */
  source?: MessageSource;
  /** Agent whose tool is running; nested agent calls are labeled `agent-<callingAgent>`. */
  callingAgent?: string;
}

const storage = new AsyncLocalStorage<Scope>();

/** An `agent-<id>` source. Throws `CopilotConfigurationError` for invalid identifiers. */
export function agentMessageSource(id: string): MessageSource {
  if (typeof id !== "string" || !AGENT_ID_PATTERN.test(id)) {
    throw new CopilotConfigurationError(
      `Invalid agent id ${JSON.stringify(id)}: use 1-128 letters, digits, ".", "_" or "-", starting with a letter or digit`,
    );
  }
  return `agent-${id}`;
}

/** Validates `"user"`, `"system"`, or `"agent-<id>"`; `undefined` and `""` mean "not set". */
export function parseMessageSource(value: unknown): MessageSource | undefined {
  if (value === undefined || value === "") return undefined;
  if (value === "user" || value === "system") return value;
  if (typeof value === "string" && value.startsWith("agent-")) return agentMessageSource(value.slice("agent-".length));
  throw new CopilotConfigurationError(
    `Invalid message source ${JSON.stringify(value)}: use "user", "system", or "agent-<id>"`,
  );
}

/**
 * Labels every agent turn started inside `fn` (including across `await`) with `source`.
 *
 * The source is caller-supplied provenance, not an authenticated identity: never authorize from it.
 * Inside a tool, only a scope opened by the tool itself applies; the outer caller's scope does not
 * flow into tools.
 */
export function withMessageSource<T>(source: MessageSource, fn: () => T): T {
  const parsed = parseMessageSource(source);
  if (parsed === undefined) throw new CopilotConfigurationError("withMessageSource needs a source");
  return storage.run({ source: parsed }, fn);
}

/** The source of the innermost `withMessageSource` scope that applies here, if any. */
export function getMessageSource(): MessageSource | undefined {
  return storage.getStore()?.source;
}

/** @internal Runs a tool handler of `agentName`, suspending the caller's scope. */
export function runAsToolOf<T>(agentName: string | undefined, fn: () => T): T {
  return storage.run(agentName === undefined ? {} : { callingAgent: agentName }, fn);
}

/** @internal Scope, then delegation (`agent-<calling agent>`), then the agent's configured source. */
export function resolveMessageSource(configured: MessageSource | undefined): MessageSource | undefined {
  const scope = storage.getStore();
  if (scope?.source) return scope.source;
  if (scope?.callingAgent !== undefined && AGENT_ID_PATTERN.test(scope.callingAgent)) return `agent-${scope.callingAgent}`;
  return configured;
}
