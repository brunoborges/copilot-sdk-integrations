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
} from "@copilot-sdk-integrations/core";
import { Catch, HttpStatus, Inject, Logger, Optional, type ArgumentsHost, type ExceptionFilter } from "@nestjs/common";
import { HttpAdapterHost } from "@nestjs/core";
import { throwError, type Observable } from "rxjs";

/** Status code used for turns cancelled by the client ("Client Closed Request"). */
export const CLIENT_CLOSED_REQUEST = 499;

export interface CopilotHttpError {
  status: number;
  headers: Record<string, string>;
}

/** Maps a core error to an HTTP status (and headers such as `Retry-After`). */
export function copilotErrorToHttp(error: CopilotIntegrationError): CopilotHttpError {
  if (error instanceof CopilotAdmissionRejectedError) {
    return { status: HttpStatus.TOO_MANY_REQUESTS, headers: { "Retry-After": String(Math.max(0, Math.ceil(error.retryAfterSeconds))) } };
  }
  if (error instanceof CopilotConversationBusyError || error instanceof CopilotConversationExistsError) {
    return { status: HttpStatus.CONFLICT, headers: {} };
  }
  if (error instanceof CopilotConversationNotFoundError) return { status: HttpStatus.NOT_FOUND, headers: {} };
  if (error instanceof CopilotTimeoutError) return { status: HttpStatus.GATEWAY_TIMEOUT, headers: {} };
  if (error instanceof CopilotAbortedError) return { status: CLIENT_CLOSED_REQUEST, headers: {} };
  if (error instanceof CopilotRuntimeUnavailableError || error instanceof CopilotShuttingDownError) {
    return { status: HttpStatus.SERVICE_UNAVAILABLE, headers: {} };
  }
  if (error instanceof CopilotSessionError || error instanceof CopilotOutputValidationError) {
    return { status: HttpStatus.BAD_GATEWAY, headers: {} };
  }
  return { status: HttpStatus.INTERNAL_SERVER_ERROR, headers: {} };
}

/**
 * Maps `CopilotIntegrationError`s to HTTP responses (registered globally by `CopilotModule` unless
 * `exceptionFilter: false`): 429 + Retry-After, 409, 404, 504, 499, 503, 502, otherwise 500.
 * 5xx bodies of configuration/internal errors use a generic message.
 */
@Catch(CopilotIntegrationError)
export class CopilotExceptionFilter implements ExceptionFilter<CopilotIntegrationError> {
  private readonly logger = new Logger("CopilotExceptionFilter");

  constructor(@Optional() @Inject(HttpAdapterHost) private readonly adapterHost?: HttpAdapterHost) {}

  catch(exception: CopilotIntegrationError, host: ArgumentsHost): void | Observable<never> {
    if (host.getType() !== "http") return throwError(() => exception);
    const { status, headers } = copilotErrorToHttp(exception);
    const ctx = host.switchToHttp();
    const response = ctx.getResponse();
    const message = status === HttpStatus.INTERNAL_SERVER_ERROR ? "Internal server error" : exception.message;
    if (status >= 500) this.logger.error(`${exception.name}: ${exception.message}`, exception.stack);
    const body = { statusCode: status, error: exception.name, message };

    const adapter = this.adapterHost?.httpAdapter;
    if (adapter && typeof adapter.isHeadersSent === "function" && adapter.isHeadersSent(response)) {
      adapter.end(response);
      return;
    }
    if (adapter) {
      for (const [name, value] of Object.entries(headers)) adapter.setHeader(response, name, value);
      adapter.reply(response, body, status);
      return;
    }
    for (const [name, value] of Object.entries(headers)) response.setHeader?.(name, value);
    response.status(status).json(body);
  }
}

/** Placeholder filter used when `exceptionFilter: false` (catches nothing). */
class NeverThrown {}
@Catch(NeverThrown)
export class DisabledCopilotExceptionFilter implements ExceptionFilter {
  catch(exception: unknown): never {
    throw exception;
  }
}
