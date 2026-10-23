import { CopilotIntegrationError, copilotErrorToHttp } from "@copilot-sdk-integrations/agent-core";
import { Catch, HttpStatus, Inject, Logger, Optional, type ArgumentsHost, type ExceptionFilter } from "@nestjs/common";
import { HttpAdapterHost } from "@nestjs/core";
import { throwError, type Observable } from "rxjs";

export { copilotErrorToHttp, CLIENT_CLOSED_REQUEST, type CopilotHttpError } from "@copilot-sdk-integrations/agent-core";

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
