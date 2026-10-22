import type { SessionHooks } from "@github/copilot-sdk";
import { messageOf } from "../tools/schema.js";

type HookOutput<K extends keyof SessionHooks> = Exclude<Awaited<ReturnType<NonNullable<SessionHooks[K]>>>, void>;
type AgentStopHookOutput = HookOutput<"onAgentStop">;
type ErrorOccurredHookOutput = HookOutput<"onErrorOccurred">;
type PostToolUseHookOutput = HookOutput<"onPostToolUse">;
type PreMcpToolCallHookOutput = HookOutput<"onPreMcpToolCall">;
type PreToolUseHookOutput = HookOutput<"onPreToolUse">;
type SessionEndHookOutput = HookOutput<"onSessionEnd">;
type SessionStartHookOutput = HookOutput<"onSessionStart">;
type SubagentStopHookOutput = HookOutput<"onSubagentStop">;
type UserPromptSubmittedHookOutput = HookOutput<"onUserPromptSubmitted">;
type UserPromptTransformedHookOutput = HookOutput<"onUserPromptTransformed">;

type AnyHandler = (input: any, invocation: { sessionId: string }) => unknown;
type Combiner = (handlers: AnyHandler[]) => AnyHandler;

/**
 * Composes several `SessionHooks` into one. Handlers run sequentially in argument order (pass global
 * hooks first, then agent hooks). Semantics mirror the Spring Boot starter:
 *
 * - `onPreToolUse`: the first `"deny"` wins and stops the chain; otherwise `"ask"` beats `"allow"`.
 *   `modifiedArgs` are passed to later handlers (as `toolArgs`) and returned. A throwing handler
 *   produces `"deny"` (fail closed). The reason comes from the deciding handler.
 * - `onUserPromptSubmitted`: the first `"block"` wins and stops the chain; `modifiedPrompt` is passed to
 *   later handlers. A throwing handler blocks the prompt (fail closed).
 * - `onUserPromptTransformed`: `modifiedTransformedPrompt` is passed on; the last one wins.
 * - `onPostToolUse`: `modifiedResult` is passed to later handlers (as `toolResult`); the last one wins.
 * - `onPreMcpToolCall`: `metaToUse` is passed on (as `_meta`; `null` omits it); the last defined one wins.
 * - `onAgentStop`: the first `"block"` wins and stops the chain.
 * - `onSubagentStop`: the first `"block"` with a non-empty reason wins and stops the chain; otherwise
 *   `modifiedResponse` is passed on (as `response`) and the last one wins.
 * - `onSessionStart`: `modifiedConfig` objects are shallow-merged in order.
 * - `onSessionEnd`: `suppressOutput` is OR-ed; the last `sessionSummary`/`cleanupActions` win.
 * - `onErrorOccurred`: the most conservative `errorHandling` wins (`abort` > `skip` > `retry`);
 *   the last `retryCount`/`userNotification` win.
 * - Everywhere: `additionalContext` values are joined with blank lines; `suppressOutput` is set if any
 *   handler sets it. Handlers returning nothing have no opinion; if none returns anything, the composed
 *   handler returns `undefined`.
 * - Exceptions from handlers other than the fail-closed ones above are swallowed (the handler is
 *   treated as returning nothing).
 * - Unknown (future) hook keys run in order and the last defined output wins.
 *
 * Returns `undefined` when no hooks are given.
 */
export function composeHooks(...hooks: (SessionHooks | undefined)[]): SessionHooks | undefined {
  const present = hooks.filter((hook): hook is SessionHooks => hook !== undefined && hook !== null);
  if (present.length === 0) return undefined;
  const keys = new Set<string>();
  for (const hook of present) {
    for (const [key, value] of Object.entries(hook)) {
      if (typeof value === "function") keys.add(key);
    }
  }
  const composed: Record<string, AnyHandler> = {};
  for (const key of keys) {
    const handlers = present
      .map((hook) => (hook as Record<string, unknown>)[key])
      .filter((handler): handler is AnyHandler => typeof handler === "function");
    composed[key] = (COMBINERS[key] ?? lastWins)(handlers);
  }
  return composed as SessionHooks;
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

async function attempt<T>(handler: AnyHandler, input: unknown, invocation: { sessionId: string }) {
  try {
    return { ok: true as const, value: (await handler(input, invocation)) as T | undefined | void };
  } catch (error) {
    return { ok: false as const, error };
  }
}

class Outputs {
  private readonly contexts: string[] = [];
  suppress = false;
  any = false;

  note(output: { additionalContext?: string; suppressOutput?: boolean }): void {
    this.any = true;
    if (typeof output.additionalContext === "string" && output.additionalContext !== "") {
      this.contexts.push(output.additionalContext);
    }
    if (output.suppressOutput === true) this.suppress = true;
  }

  apply<T extends object>(target: T): T {
    const out = target as T & { additionalContext?: string; suppressOutput?: boolean };
    if (this.contexts.length > 0) out.additionalContext = this.contexts.join("\n\n");
    if (this.suppress) out.suppressOutput = true;
    return out;
  }
}

function isObject<T>(value: T | undefined | void): value is T & object {
  return typeof value === "object" && value !== null;
}

const lastWins: Combiner = (handlers) => async (input, invocation) => {
  let result: unknown;
  for (const handler of handlers) {
    const outcome = await attempt<unknown>(handler, input, invocation);
    if (outcome.ok && outcome.value !== undefined) result = outcome.value;
  }
  return result;
};

/** Combiner for hooks whose only output is `additionalContext` (and optionally `suppressOutput`). */
const contextOnly: Combiner = (handlers) => async (input, invocation) => {
  const outputs = new Outputs();
  for (const handler of handlers) {
    const outcome = await attempt<{ additionalContext?: string }>(handler, input, invocation);
    if (outcome.ok && isObject(outcome.value)) outputs.note(outcome.value);
  }
  return outputs.any ? outputs.apply({}) : undefined;
};

// ---------------------------------------------------------------------------
// Per-hook combiners
// ---------------------------------------------------------------------------

const preToolUse: Combiner = (handlers) => async (input, invocation) => {
  const outputs = new Outputs();
  let current = input;
  let modified = false;
  let decision: PreToolUseHookOutput["permissionDecision"];
  let reason: string | undefined;
  for (const handler of handlers) {
    const outcome = await attempt<PreToolUseHookOutput>(handler, current, invocation);
    if (!outcome.ok) {
      return outputs.apply<PreToolUseHookOutput>({
        permissionDecision: "deny",
        permissionDecisionReason: `Pre-tool-use hook failed: ${messageOf(outcome.error)}`,
      });
    }
    const output = outcome.value;
    if (!isObject(output)) continue;
    outputs.note(output);
    if ("modifiedArgs" in output && output.modifiedArgs !== undefined) {
      current = { ...current, toolArgs: output.modifiedArgs };
      modified = true;
    }
    if (output.permissionDecision === "deny") {
      const result: PreToolUseHookOutput = { permissionDecision: "deny" };
      if (output.permissionDecisionReason !== undefined) result.permissionDecisionReason = output.permissionDecisionReason;
      if (modified) result.modifiedArgs = current.toolArgs;
      return outputs.apply(result);
    }
    if (output.permissionDecision === "ask" && decision !== "ask") {
      decision = "ask";
      reason = output.permissionDecisionReason;
    } else if (output.permissionDecision === "allow" && decision === undefined) {
      decision = "allow";
      reason = output.permissionDecisionReason;
    }
  }
  if (!outputs.any) return undefined;
  const result: PreToolUseHookOutput = {};
  if (decision !== undefined) result.permissionDecision = decision;
  if (reason !== undefined) result.permissionDecisionReason = reason;
  if (modified) result.modifiedArgs = current.toolArgs;
  return outputs.apply(result);
};

const userPromptSubmitted: Combiner = (handlers) => async (input, invocation) => {
  const outputs = new Outputs();
  let current = input;
  let modified = false;
  for (const handler of handlers) {
    const outcome = await attempt<UserPromptSubmittedHookOutput>(handler, current, invocation);
    if (!outcome.ok) {
      return outputs.apply<UserPromptSubmittedHookOutput>({
        decision: "block",
        reason: `User-prompt hook failed: ${messageOf(outcome.error)}`,
      });
    }
    const output = outcome.value;
    if (!isObject(output)) continue;
    outputs.note(output);
    if (output.decision === "block") {
      const result: UserPromptSubmittedHookOutput = { decision: "block" };
      if (output.reason !== undefined) result.reason = output.reason;
      return outputs.apply(result);
    }
    if (typeof output.modifiedPrompt === "string") {
      current = { ...current, prompt: output.modifiedPrompt };
      modified = true;
    }
  }
  if (!outputs.any) return undefined;
  const result: UserPromptSubmittedHookOutput = {};
  if (modified) result.modifiedPrompt = current.prompt;
  return outputs.apply(result);
};

const userPromptTransformed: Combiner = (handlers) => async (input, invocation) => {
  let current = input;
  let result: UserPromptTransformedHookOutput | undefined;
  for (const handler of handlers) {
    const outcome = await attempt<UserPromptTransformedHookOutput>(handler, current, invocation);
    if (!outcome.ok || !isObject(outcome.value)) continue;
    result ??= {};
    if (typeof outcome.value.modifiedTransformedPrompt === "string") {
      current = { ...current, transformedPrompt: outcome.value.modifiedTransformedPrompt };
      result.modifiedTransformedPrompt = outcome.value.modifiedTransformedPrompt;
    }
  }
  return result;
};

const postToolUse: Combiner = (handlers) => async (input, invocation) => {
  const outputs = new Outputs();
  let current = input;
  let modified = false;
  for (const handler of handlers) {
    const outcome = await attempt<PostToolUseHookOutput>(handler, current, invocation);
    if (!outcome.ok || !isObject(outcome.value)) continue;
    outputs.note(outcome.value);
    if (outcome.value.modifiedResult !== undefined) {
      current = { ...current, toolResult: outcome.value.modifiedResult };
      modified = true;
    }
  }
  if (!outputs.any) return undefined;
  const result: PostToolUseHookOutput = {};
  if (modified) result.modifiedResult = current.toolResult;
  return outputs.apply(result);
};

const preMcpToolCall: Combiner = (handlers) => async (input, invocation) => {
  let current = input;
  let result: PreMcpToolCallHookOutput | undefined;
  for (const handler of handlers) {
    const outcome = await attempt<PreMcpToolCallHookOutput>(handler, current, invocation);
    if (!outcome.ok || !isObject(outcome.value)) continue;
    result ??= {};
    if ("metaToUse" in outcome.value && outcome.value.metaToUse !== undefined) {
      const meta = outcome.value.metaToUse;
      current = { ...current, _meta: meta ?? undefined };
      result.metaToUse = meta;
    }
  }
  return result;
};

const agentStop: Combiner = (handlers) => async (input, invocation) => {
  let result: AgentStopHookOutput | undefined;
  for (const handler of handlers) {
    const outcome = await attempt<AgentStopHookOutput>(handler, input, invocation);
    if (!outcome.ok || !isObject(outcome.value)) continue;
    if (outcome.value.decision === "block") return outcome.value;
    result ??= outcome.value;
  }
  return result;
};

const subagentStop: Combiner = (handlers) => async (input, invocation) => {
  let current = input;
  let result: SubagentStopHookOutput | undefined;
  for (const handler of handlers) {
    const outcome = await attempt<SubagentStopHookOutput>(handler, current, invocation);
    if (!outcome.ok || !isObject(outcome.value)) continue;
    const output = outcome.value;
    if (output.decision === "block" && typeof output.reason === "string" && output.reason !== "") {
      return { decision: "block", reason: output.reason };
    }
    result ??= {};
    if (typeof output.modifiedResponse === "string") {
      current = { ...current, response: output.modifiedResponse };
      result = { modifiedResponse: output.modifiedResponse };
    }
  }
  return result;
};

const sessionStart: Combiner = (handlers) => async (input, invocation) => {
  const outputs = new Outputs();
  let config: Record<string, unknown> | undefined;
  for (const handler of handlers) {
    const outcome = await attempt<SessionStartHookOutput>(handler, input, invocation);
    if (!outcome.ok || !isObject(outcome.value)) continue;
    outputs.note(outcome.value);
    if (isObject(outcome.value.modifiedConfig)) config = { ...config, ...outcome.value.modifiedConfig };
  }
  if (!outputs.any) return undefined;
  const result: SessionStartHookOutput = {};
  if (config) result.modifiedConfig = config;
  return outputs.apply(result);
};

const sessionEnd: Combiner = (handlers) => async (input, invocation) => {
  let result: SessionEndHookOutput | undefined;
  for (const handler of handlers) {
    const outcome = await attempt<SessionEndHookOutput>(handler, input, invocation);
    if (!outcome.ok || !isObject(outcome.value)) continue;
    const output = outcome.value;
    result ??= {};
    if (output.suppressOutput === true) result.suppressOutput = true;
    if (output.sessionSummary !== undefined) result.sessionSummary = output.sessionSummary;
    if (output.cleanupActions !== undefined) result.cleanupActions = output.cleanupActions;
  }
  return result;
};

const ERROR_HANDLING_RANK = { retry: 1, skip: 2, abort: 3 } as const;

const errorOccurred: Combiner = (handlers) => async (input, invocation) => {
  let result: ErrorOccurredHookOutput | undefined;
  for (const handler of handlers) {
    const outcome = await attempt<ErrorOccurredHookOutput>(handler, input, invocation);
    if (!outcome.ok || !isObject(outcome.value)) continue;
    const output = outcome.value;
    result ??= {};
    if (output.suppressOutput === true) result.suppressOutput = true;
    if (
      output.errorHandling !== undefined &&
      (result.errorHandling === undefined ||
        ERROR_HANDLING_RANK[output.errorHandling] > ERROR_HANDLING_RANK[result.errorHandling])
    ) {
      result.errorHandling = output.errorHandling;
    }
    if (output.retryCount !== undefined) result.retryCount = output.retryCount;
    if (output.userNotification !== undefined) result.userNotification = output.userNotification;
  }
  return result;
};

const COMBINERS: Record<string, Combiner> = {
  onPreToolUse: preToolUse,
  onPreMcpToolCall: preMcpToolCall,
  onPostToolUse: postToolUse,
  onPostToolUseFailure: contextOnly,
  onUserPromptSubmitted: userPromptSubmitted,
  onUserPromptTransformed: userPromptTransformed,
  onSessionStart: sessionStart,
  onSessionEnd: sessionEnd,
  onErrorOccurred: errorOccurred,
  onAgentStop: agentStop,
  onSubagentStart: contextOnly,
  onSubagentStop: subagentStop,
} satisfies Record<keyof Required<SessionHooks>, Combiner>;
