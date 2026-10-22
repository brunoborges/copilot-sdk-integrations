/** Base class for all integration errors. */
export class CopilotIntegrationError extends Error {
  constructor(message: string, options?: { cause?: unknown }) {
    super(message, options);
    this.name = new.target.name;
  }
}

/** Invalid agent, tool, or client configuration. Thrown at definition/bootstrap time. */
export class CopilotConfigurationError extends CopilotIntegrationError {}

/** Admission queue full; maps to HTTP 429. */
export class CopilotAdmissionRejectedError extends CopilotIntegrationError {
  constructor(message = "Copilot is at capacity; try again later", readonly retryAfterSeconds = 1) {
    super(message);
  }
}

/** A conversation was busy for longer than the configured wait. Maps to HTTP 409. */
export class CopilotConversationBusyError extends CopilotIntegrationError {
  constructor(readonly conversationId: string) {
    super(`Conversation "${conversationId}" is busy`);
  }
}

/** Resume-only conversation does not exist. Maps to HTTP 404. */
export class CopilotConversationNotFoundError extends CopilotIntegrationError {
  constructor(readonly conversationId: string) {
    super(`Conversation "${conversationId}" does not exist`);
  }
}

/** Create-only conversation already exists. Maps to HTTP 409. */
export class CopilotConversationExistsError extends CopilotIntegrationError {
  constructor(readonly conversationId: string) {
    super(`Conversation "${conversationId}" already exists`);
  }
}

/** The turn exceeded its timeout and was aborted. Maps to HTTP 504. */
export class CopilotTimeoutError extends CopilotIntegrationError {
  constructor(readonly timeoutMs: number) {
    super(`Copilot turn timed out after ${timeoutMs} ms`);
  }
}

/** The turn was cancelled by the caller. */
export class CopilotAbortedError extends CopilotIntegrationError {
  constructor(message = "Copilot turn was aborted") {
    super(message);
  }
}

/** The runtime could not be started or crashed. Maps to HTTP 503. */
export class CopilotRuntimeUnavailableError extends CopilotIntegrationError {}

/** The client is stopping or stopped and no longer admits turns. Maps to HTTP 503. */
export class CopilotShuttingDownError extends CopilotIntegrationError {
  constructor() {
    super("Copilot is shutting down");
  }
}

/** The runtime reported an error for the turn (session.error). Maps to HTTP 502. */
export class CopilotSessionError extends CopilotIntegrationError {
  constructor(message: string, readonly errorType?: string) {
    super(message);
  }
}

/** A structured result failed schema validation. */
export class CopilotOutputValidationError extends CopilotIntegrationError {}
