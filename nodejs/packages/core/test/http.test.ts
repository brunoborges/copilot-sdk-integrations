import { describe, expect, it } from "vitest";
import {
  CLIENT_CLOSED_REQUEST,
  CopilotAbortedError,
  CopilotAdmissionRejectedError,
  CopilotConfigurationError,
  CopilotConversationBusyError,
  CopilotConversationExistsError,
  CopilotConversationNotFoundError,
  CopilotIntegrationError,
  CopilotOutputValidationError,
  CopilotRuntimeUnavailableError,
  CopilotSessionError,
  CopilotShuttingDownError,
  CopilotTimeoutError,
  copilotErrorCode,
  copilotErrorToHttp,
} from "../src/index.js";

const make = <T>(ctor: new (...args: any[]) => T, ...args: unknown[]): T => new ctor(...args);

describe("copilotErrorToHttp", () => {
  it("maps admission rejection to 429 with Retry-After (rounded up)", () => {
    const error = make(CopilotAdmissionRejectedError, "busy", 1.2);
    expect(error).toBeInstanceOf(CopilotAdmissionRejectedError);
    const mapped = copilotErrorToHttp(error);
    expect(mapped.status).toBe(429);
    expect(mapped.headers["Retry-After"]).toBe(String(Math.ceil(error.retryAfterSeconds)));
  });

  it.each([
    [CopilotConversationBusyError, 409],
    [CopilotConversationExistsError, 409],
    [CopilotConversationNotFoundError, 404],
    [CopilotTimeoutError, 504],
    [CopilotAbortedError, CLIENT_CLOSED_REQUEST],
    [CopilotRuntimeUnavailableError, 503],
    [CopilotShuttingDownError, 503],
    [CopilotSessionError, 502],
    [CopilotOutputValidationError, 502],
    [CopilotConfigurationError, 500],
    [CopilotIntegrationError, 500],
  ] as const)("maps %o to %i", (ctor, status) => {
    const error = make(ctor as new (...args: any[]) => CopilotIntegrationError, "x");
    expect(copilotErrorToHttp(error)).toEqual({ status, headers: {} });
  });
});

describe("copilotErrorCode", () => {
  it("derives an upper snake case code from the class name", () => {
    expect(copilotErrorCode(make(CopilotAdmissionRejectedError, "busy", 1))).toBe("COPILOT_ADMISSION_REJECTED");
    expect(copilotErrorCode(make(CopilotTimeoutError, "slow"))).toBe("COPILOT_TIMEOUT");
    expect(copilotErrorCode(new Error("x"))).toBe("ERROR");
    const named = new Error("x");
    named.name = "MCPServerError";
    expect(copilotErrorCode(named)).toBe("MCP_SERVER");
  });
});
