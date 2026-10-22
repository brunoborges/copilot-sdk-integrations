import {
  CopilotAbortedError,
  CopilotAdmissionRejectedError,
  CopilotConversationBusyError,
  CopilotConversationExistsError,
  CopilotConversationNotFoundError,
  CopilotIntegrationError,
  CopilotOutputValidationError,
  CopilotRuntimeUnavailableError,
  CopilotSessionError,
  CopilotShuttingDownError,
  CopilotTimeoutError,
} from "./errors.js";

/** Status code used for turns cancelled by the client ("Client Closed Request"). */
export const CLIENT_CLOSED_REQUEST = 499;

export interface CopilotHttpError {
  status: number;
  headers: Record<string, string>;
}

/**
 * Maps a core error to an HTTP status (and headers such as `Retry-After`):
 *
 * | Error | Status |
 * | --- | --- |
 * | `CopilotAdmissionRejectedError` | 429 + `Retry-After` |
 * | `CopilotConversationBusyError`, `CopilotConversationExistsError` | 409 |
 * | `CopilotConversationNotFoundError` | 404 |
 * | `CopilotTimeoutError` | 504 |
 * | `CopilotAbortedError` | 499 |
 * | `CopilotRuntimeUnavailableError`, `CopilotShuttingDownError` | 503 |
 * | `CopilotSessionError`, `CopilotOutputValidationError` | 502 |
 * | other | 500 |
 */
export function copilotErrorToHttp(error: CopilotIntegrationError): CopilotHttpError {
  if (error instanceof CopilotAdmissionRejectedError) {
    return { status: 429, headers: { "Retry-After": String(Math.max(0, Math.ceil(error.retryAfterSeconds))) } };
  }
  if (error instanceof CopilotConversationBusyError || error instanceof CopilotConversationExistsError) {
    return { status: 409, headers: {} };
  }
  if (error instanceof CopilotConversationNotFoundError) return { status: 404, headers: {} };
  if (error instanceof CopilotTimeoutError) return { status: 504, headers: {} };
  if (error instanceof CopilotAbortedError) return { status: CLIENT_CLOSED_REQUEST, headers: {} };
  if (error instanceof CopilotRuntimeUnavailableError || error instanceof CopilotShuttingDownError) {
    return { status: 503, headers: {} };
  }
  if (error instanceof CopilotSessionError || error instanceof CopilotOutputValidationError) {
    return { status: 502, headers: {} };
  }
  return { status: 500, headers: {} };
}

/**
 * A stable, machine-readable code for an error: the class name in upper snake case without the
 * `Error` suffix (`CopilotAdmissionRejectedError` -> `COPILOT_ADMISSION_REJECTED`).
 */
export function copilotErrorCode(error: Error): string {
  const name = (error.name || error.constructor?.name || "Error").replace(/Error$/, "") || "ERROR";
  return name
    .replace(/([a-z0-9])([A-Z])/g, "$1_$2")
    .replace(/([A-Z])([A-Z][a-z])/g, "$1_$2")
    .toUpperCase();
}
