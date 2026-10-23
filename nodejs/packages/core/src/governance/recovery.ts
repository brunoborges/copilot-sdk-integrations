import type { SessionHooks } from "@github/copilot-sdk";
import { CopilotConfigurationError } from "../errors.js";

export type ToolResultCategory = "permission_denied" | "shell_error" | "tool_failure" | "normal";
export type SdkErrorCategory = "transient" | "client_error" | "non_recoverable";

export const DEFAULT_PERMISSION_PHRASES: readonly string[] = [
  "permission denied",
  "access denied",
  "not permitted",
  "operation not allowed",
  "eacces",
  "eperm",
  "403 forbidden",
];
// "exit code" from the cookbook recipe is left out: successful shell output often reports "exit code 0".
export const DEFAULT_SHELL_ERROR_PHRASES: readonly string[] = [
  "command not found",
  "no such file or directory",
  "errno",
  "traceback",
  "non-zero exit",
];
export const DEFAULT_TRANSIENT_ERROR_PHRASES: readonly string[] = [
  "timeout",
  "timed out",
  "429",
  "rate limit",
  "502",
  "503",
  "504",
  "econnreset",
  "temporarily unavailable",
];
export const DEFAULT_CLIENT_ERROR_PHRASES: readonly string[] = ["400", "401", "403", "404", "422"];
export const DEFAULT_PERMISSION_NOTE =
  "[SYSTEM NOTE: Permission was denied for this specific action. Continue using alternative approaches.]";
export const DEFAULT_ERROR_NOTE =
  "[SYSTEM NOTE: This command encountered an error. This does NOT mean you should stop. " +
  "Retry with different arguments, try a different tool, or move on.]";
export const DEFAULT_USER_NOTIFICATION = "Error occurred — continuing investigation.";

const MAX_TRACKED = 10_000;

export interface ErrorRecoveryOptions {
  /** Case-insensitive phrases that mark a tool result as "permission denied". */
  permissionPhrases?: readonly string[];
  /** Case-insensitive phrases that mark a tool result as a shell error. */
  shellErrorPhrases?: readonly string[];
  /** Phrases that make an SDK error transient (retried). */
  transientErrorPhrases?: readonly string[];
  /** Phrases that make an SDK error a client error (not retried). */
  clientErrorPhrases?: readonly string[];
  permissionNote?: string;
  errorNote?: string;
  /** Stop adding notes after this many consecutive failures of one tool in a session. `0` = no cap. Default 3. */
  maxConsecutiveFailures?: number;
  /** Retries requested for transient SDK errors. Default 2. */
  retryCount?: number;
  /** Handling of non-transient SDK errors; `null` leaves the SDK default. Default `"skip"`. */
  unrecoverableHandling?: "skip" | "abort" | null;
  /** Shown to the user for non-transient SDK errors; `null` for none. */
  userNotification?: string | null;
  /** Called for every classified failure, e.g. to log or count categories. */
  onFailure?: (event: { sessionId: string; toolName?: string; category: ToolResultCategory | SdkErrorCategory; message: string }) => void;
}

function lower(phrases: readonly string[]): string[] {
  return phrases.map((phrase) => phrase.trim().toLowerCase()).filter((phrase) => phrase.length > 0);
}

function contains(text: string, phrases: readonly string[]): boolean {
  return phrases.some((phrase) => text.includes(phrase));
}

/** Classifies tool output text. Returns `"normal"` when no phrase matches. */
export function classifyToolResult(
  text: string | undefined | null,
  phrases: { permission?: readonly string[]; shellError?: readonly string[] } = {},
): ToolResultCategory {
  const value = (text ?? "").toLowerCase();
  if (contains(value, lower(phrases.permission ?? DEFAULT_PERMISSION_PHRASES))) return "permission_denied";
  if (contains(value, lower(phrases.shellError ?? DEFAULT_SHELL_ERROR_PHRASES))) return "shell_error";
  return "normal";
}

/** Classifies an SDK error message. Unmatched errors are transient only when the SDK marks them recoverable. */
export function classifySdkError(
  error: string | undefined | null,
  recoverable = false,
  phrases: { transient?: readonly string[]; clientError?: readonly string[] } = {},
): SdkErrorCategory {
  const value = (error ?? "").toLowerCase();
  if (contains(value, lower(phrases.transient ?? DEFAULT_TRANSIENT_ERROR_PHRASES))) return "transient";
  if (contains(value, lower(phrases.clientError ?? DEFAULT_CLIENT_ERROR_PHRASES))) return "client_error";
  return recoverable ? "transient" : "non_recoverable";
}

/**
 * Opt-in hooks that keep an agent working when tools fail, based on the Copilot SDK cookbook's
 * error-recovery recipe:
 *
 * - `onPostToolUse` / `onPostToolUseFailure`: classify the output by phrase lists and add an
 *   `additionalContext` note telling the model to keep going (the result is unchanged). After
 *   `maxConsecutiveFailures` consecutive failures of the same tool in a session the note stops;
 *   a successful result resets the count.
 * - `onErrorOccurred`: retry transient errors (timeouts, rate limits, 5xx); `unrecoverableHandling` otherwise.
 * - `onSessionEnd`: forget the session's counts.
 *
 * Combine with other hooks via `composeHooks` (or the runtime/agent `hooks` options, which compose).
 */
export function errorRecoveryHooks(options: ErrorRecoveryOptions = {}): SessionHooks {
  const max = options.maxConsecutiveFailures ?? 3;
  const retryCount = options.retryCount ?? 2;
  const unrecoverable = options.unrecoverableHandling === undefined ? "skip" : options.unrecoverableHandling;
  const notification = options.userNotification === undefined ? DEFAULT_USER_NOTIFICATION : options.userNotification;
  const permissionNote = options.permissionNote ?? DEFAULT_PERMISSION_NOTE;
  const errorNote = options.errorNote ?? DEFAULT_ERROR_NOTE;
  if (!Number.isInteger(max) || max < 0) {
    throw new CopilotConfigurationError("maxConsecutiveFailures must be a non-negative integer");
  }
  if (!Number.isInteger(retryCount) || retryCount < 1) {
    throw new CopilotConfigurationError("retryCount must be a positive integer");
  }
  if (unrecoverable !== null && unrecoverable !== "skip" && unrecoverable !== "abort") {
    throw new CopilotConfigurationError('unrecoverableHandling must be "skip", "abort" or null');
  }
  if (!permissionNote.trim() || !errorNote.trim()) {
    throw new CopilotConfigurationError("notes must not be blank");
  }
  const toolPhrases = {
    permission: options.permissionPhrases ?? DEFAULT_PERMISSION_PHRASES,
    shellError: options.shellErrorPhrases ?? DEFAULT_SHELL_ERROR_PHRASES,
  };
  const errorPhrases = {
    transient: options.transientErrorPhrases ?? DEFAULT_TRANSIENT_ERROR_PHRASES,
    clientError: options.clientErrorPhrases ?? DEFAULT_CLIENT_ERROR_PHRASES,
  };
  const failures = new Map<string, number>();
  const key = (sessionId: string, toolName: string) => `${sessionId}\u0000${toolName}`;
  const report = (event: Parameters<NonNullable<ErrorRecoveryOptions["onFailure"]>>[0]) => {
    try {
      options.onFailure?.(event);
    } catch {
      // a failing listener must not break the hook
    }
  };

  const note = (sessionId: string, toolName: string, category: ToolResultCategory, message: string) => {
    if (failures.size >= MAX_TRACKED) failures.clear();
    const count = (failures.get(key(sessionId, toolName)) ?? 0) + 1;
    failures.set(key(sessionId, toolName), count);
    report({ sessionId, toolName, category, message });
    if (max > 0 && count > max) return undefined;
    return { additionalContext: category === "permission_denied" ? permissionNote : errorNote };
  };

  return {
    onPostToolUse: (input, invocation) => {
      const sessionId = input.sessionId ?? invocation.sessionId;
      const text = input.toolResult?.textResultForLlm ?? "";
      const category = classifyToolResult(text, toolPhrases);
      if (category === "normal") {
        failures.delete(key(sessionId, input.toolName));
        return undefined;
      }
      return note(sessionId, input.toolName, category, text);
    },
    onPostToolUseFailure: (input, invocation) => {
      const sessionId = input.sessionId ?? invocation.sessionId;
      const category = classifyToolResult(input.error, toolPhrases);
      return note(sessionId, input.toolName, category === "normal" ? "tool_failure" : category, input.error ?? "");
    },
    onErrorOccurred: (input, invocation) => {
      const category = classifySdkError(input.error, input.recoverable, errorPhrases);
      report({ sessionId: input.sessionId ?? invocation.sessionId, category, message: input.error ?? "" });
      if (category === "transient") return { errorHandling: "retry", retryCount };
      if (unrecoverable === null) return undefined;
      return notification ? { errorHandling: unrecoverable, userNotification: notification } : { errorHandling: unrecoverable };
    },
    onSessionEnd: (input, invocation) => {
      const prefix = `${input.sessionId ?? invocation.sessionId}\u0000`;
      for (const k of [...failures.keys()]) {
        if (k.startsWith(prefix)) failures.delete(k);
      }
      return undefined;
    },
  };
}
