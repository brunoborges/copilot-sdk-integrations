import type { SessionEvent } from "@github/copilot-sdk";

/** Matches a prompt: substring (string), regular expression, or predicate. */
export type PromptMatcher = string | RegExp | ((prompt: string) => boolean);

/** A `SessionEvent` without the envelope fields the runtime fills in. */
export type SessionEventInit = SessionEvent extends infer E
  ? E extends SessionEvent
    ? Omit<E, "id" | "timestamp" | "parentId">
    : never
  : never;

/** Token usage reported by a scripted `.usage(...)` step. */
export interface TestUsage {
  inputTokens?: number;
  outputTokens?: number;
  model?: string;
  cost?: number;
}

/** A tool call executed by the scripted runtime. */
export interface TestToolCall {
  sessionId: string;
  toolCallId: string;
  name: string;
  args: unknown;
  /** Raw value returned by the tool handler (before normalization). */
  result?: unknown;
  /** Failure message: tool missing, permission denied, hook denial, handler error, or abort. */
  error?: string;
  /** Whether the call was allowed to run (permission approved, `skipPermission`, or hook "allow"). */
  approved: boolean;
}

/** Information available to computed replies (`replyWith`) and `emit` factories. */
export interface TestTurn {
  sessionId: string;
  /** Prompt as seen by the model (after an `onUserPromptSubmitted` rewrite). */
  prompt: string;
  /** Tool calls made so far in this turn. */
  toolCalls: readonly TestToolCall[];
  /** Raw result of the most recent call to `name` in this turn. */
  toolResult(name: string): unknown;
}

export type ScriptStep =
  | { kind: "reply"; text: string | ((turn: TestTurn) => string) }
  | { kind: "stream"; chunks: string[] }
  | { kind: "reasoning"; text: string }
  | { kind: "tool"; name: string; args: unknown }
  | { kind: "fail"; message: string; errorType: string }
  | { kind: "delay"; ms: number }
  | { kind: "usage"; usage: TestUsage }
  | { kind: "emit"; event: (turn: TestTurn) => SessionEventInit };

export interface ScriptRule {
  matcher: PromptMatcher;
  steps: ScriptStep[];
  /** Remaining uses; `Infinity` by default. */
  remaining: number;
}

export function matches(matcher: PromptMatcher, prompt: string): boolean {
  if (typeof matcher === "string") return prompt.includes(matcher);
  if (matcher instanceof RegExp) {
    matcher.lastIndex = 0;
    return matcher.test(prompt);
  }
  return matcher(prompt);
}

/**
 * Builder returned by `runtime.onPrompt(...)`. Steps run in order when a prompt matches.
 * Call `onPrompt(...)` on the builder to register the next rule.
 */
export class PromptScript {
  /** @internal */
  constructor(
    private readonly rule: ScriptRule,
    private readonly register: (matcher: PromptMatcher) => PromptScript,
  ) {}

  /** Reply with fixed text (an `assistant.message`). */
  reply(text: string): this {
    return this.step({ kind: "reply", text });
  }

  /** Alias of `reply`; reads well after `callTool`. */
  thenReply(text: string): this {
    return this.reply(text);
  }

  /** Reply with text computed from the turn (prompt, tool results). */
  replyWith(fn: (turn: TestTurn) => string): this {
    return this.step({ kind: "reply", text: fn });
  }

  /** Emit one `assistant.message_delta` per chunk, then the concatenated `assistant.message`. */
  streamReply(chunks: string[]): this {
    return this.step({ kind: "stream", chunks: [...chunks] });
  }

  /** Reply with `JSON.stringify(value)`, as a structured (`responseSchema`) run would. */
  replyJson(value: unknown): this {
    return this.step({ kind: "reply", text: JSON.stringify(value) });
  }

  /** Emit `assistant.reasoning_delta` followed by `assistant.reasoning`. */
  reasoning(text: string): this {
    return this.step({ kind: "reasoning", text });
  }

  /** Call a tool registered on the session. Consecutive calls share one assistant message. */
  callTool(name: string, args: unknown = {}): this {
    return this.step({ kind: "tool", name, args });
  }

  /** Emit `session.error` and end the turn. */
  fail(message: string, errorType = "model_error"): this {
    return this.step({ kind: "fail", message, errorType });
  }

  /** Wait before the next step. `abort()` interrupts the wait. */
  delay(ms: number): this {
    return this.step({ kind: "delay", ms });
  }

  /** Emit `assistant.usage`. */
  usage(usage: TestUsage): this {
    return this.step({ kind: "usage", usage: { ...usage } });
  }

  /** Emit an arbitrary SDK event; `id`, `timestamp`, and `parentId` are filled in. */
  emit(event: SessionEventInit | ((turn: TestTurn) => SessionEventInit)): this {
    return this.step({ kind: "emit", event: typeof event === "function" ? event : () => event });
  }

  /** Use this rule at most `n` times. Default: unlimited. */
  times(n: number): this {
    if (!Number.isInteger(n) || n < 1) throw new RangeError(`times(n) requires a positive integer, got ${n}`);
    this.rule.remaining = n;
    return this;
  }

  /** Use this rule once. */
  once(): this {
    return this.times(1);
  }

  /** Register the next rule (same as `runtime.onPrompt`). */
  onPrompt(matcher: PromptMatcher): PromptScript {
    return this.register(matcher);
  }

  private step(step: ScriptStep): this {
    this.rule.steps.push(step);
    return this;
  }
}
