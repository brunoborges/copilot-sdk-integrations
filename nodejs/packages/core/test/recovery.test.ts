import { describe, expect, it, vi } from "vitest";
import { CopilotConfigurationError } from "../src/errors.js";
import {
  DEFAULT_ERROR_NOTE,
  DEFAULT_PERMISSION_NOTE,
  classifySdkError,
  classifyToolResult,
  composeHooks,
  errorRecoveryHooks,
} from "../src/governance/index.js";

const inv = { sessionId: "s1" };
const base = { sessionId: "s1", timestamp: new Date(), workingDirectory: "/tmp", toolArgs: {} };
const ok = (toolName: string, text: string, sessionId = "s1") => ({
  ...base,
  sessionId,
  toolName,
  toolResult: { textResultForLlm: text, resultType: "success" as const },
});
const failed = (toolName: string, error: string, sessionId = "s1") => ({ ...base, sessionId, toolName, error });

describe("errorRecoveryHooks", () => {
  it("classifies tool results by phrase", () => {
    expect(classifyToolResult("open: Permission Denied")).toBe("permission_denied");
    expect(classifyToolResult("bash: rg: command not found")).toBe("shell_error");
    expect(classifyToolResult("done\n<exited with exit code 0>")).toBe("normal");
    expect(classifyToolResult(undefined)).toBe("normal");
    expect(classifyToolResult("Not Authorized", { permission: ["not authorized"] })).toBe("permission_denied");
  });

  it("adds a continuation note without changing the result", async () => {
    const hooks = errorRecoveryHooks();
    expect(await hooks.onPostToolUse!(ok("bash", "No such file or directory"), inv)).toEqual({
      additionalContext: DEFAULT_ERROR_NOTE,
    });
    expect(await hooks.onPostToolUse!(ok("view", "EACCES"), inv)).toEqual({ additionalContext: DEFAULT_PERMISSION_NOTE });
    expect(await hooks.onPostToolUse!(ok("bash", "hello"), inv)).toBeUndefined();
    expect(await hooks.onPostToolUseFailure!(failed("lookup", "Order not found"), inv)).toEqual({
      additionalContext: DEFAULT_ERROR_NOTE,
    });
  });

  it("caps consecutive failures per session and tool", async () => {
    const hooks = errorRecoveryHooks({ maxConsecutiveFailures: 2 });
    const fail = (tool = "bash", session = "s1") => hooks.onPostToolUseFailure!(failed(tool, "boom", session), inv);
    expect(await fail()).toBeDefined();
    expect(await fail()).toBeDefined();
    expect(await fail()).toBeUndefined();
    expect(await fail("view")).toBeDefined();
    expect(await fail("bash", "s2")).toBeDefined();
    await hooks.onPostToolUse!(ok("bash", "fine"), inv);
    expect(await fail()).toBeDefined();
    await fail();
    expect(await fail()).toBeUndefined();
    await hooks.onSessionEnd!({ ...base, reason: "complete" }, inv);
    expect(await fail()).toBeDefined();
  });

  it("does not cap when maxConsecutiveFailures is 0", async () => {
    const hooks = errorRecoveryHooks({ maxConsecutiveFailures: 0 });
    for (let i = 0; i < 10; i++) {
      expect(await hooks.onPostToolUseFailure!(failed("bash", "boom"), inv)).toBeDefined();
    }
  });

  it("retries transient SDK errors and skips the rest", async () => {
    const onFailure = vi.fn();
    const hooks = errorRecoveryHooks({ retryCount: 3, onFailure });
    const err = (error: string, recoverable = false) => ({ ...base, error, errorContext: "model_call" as const, recoverable });
    expect(classifySdkError("Request timed out")).toBe("transient");
    expect(classifySdkError("HTTP 401 Unauthorized")).toBe("client_error");
    expect(classifySdkError("weird", true)).toBe("transient");
    expect(classifySdkError("weird")).toBe("non_recoverable");
    expect(await hooks.onErrorOccurred!(err("429 Too Many Requests"), inv)).toEqual({ errorHandling: "retry", retryCount: 3 });
    expect(await hooks.onErrorOccurred!(err("400 bad request"), inv)).toEqual({
      errorHandling: "skip",
      userNotification: "Error occurred — continuing investigation.",
    });
    expect(onFailure).toHaveBeenCalledWith(expect.objectContaining({ category: "client_error" }));
    expect(await errorRecoveryHooks({ unrecoverableHandling: null }).onErrorOccurred!(err("boom"), inv)).toBeUndefined();
    expect(
      await errorRecoveryHooks({ unrecoverableHandling: "abort", userNotification: null }).onErrorOccurred!(err("boom"), inv),
    ).toEqual({ errorHandling: "abort" });
  });

  it("composes with other hooks", async () => {
    const hooks = composeHooks(
      { onPostToolUseFailure: () => ({ additionalContext: "audit" }) },
      errorRecoveryHooks(),
    )!;
    expect(await hooks.onPostToolUseFailure!(failed("bash", "boom"), inv)).toEqual({
      additionalContext: `audit\n\n${DEFAULT_ERROR_NOTE}`,
    });
  });

  it("validates options", () => {
    expect(() => errorRecoveryHooks({ maxConsecutiveFailures: -1 })).toThrow(CopilotConfigurationError);
    expect(() => errorRecoveryHooks({ retryCount: 0 })).toThrow(CopilotConfigurationError);
    expect(() => errorRecoveryHooks({ errorNote: " " })).toThrow(CopilotConfigurationError);
    expect(() => errorRecoveryHooks({ unrecoverableHandling: "explode" as never })).toThrow(CopilotConfigurationError);
  });
});
