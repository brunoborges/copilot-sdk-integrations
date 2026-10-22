import { STATUS_CODES } from "node:http";
import {
  CLIENT_CLOSED_REQUEST,
  CopilotIntegrationError,
  copilotErrorCode,
  copilotErrorToHttp,
} from "@copilot-sdk-integrations/core";
import type { FastifyError, FastifyReply, FastifyRequest } from "fastify";

/** Fastify-shaped error body sent for Copilot errors. */
export interface CopilotErrorBody {
  statusCode: number;
  code: string;
  error: string;
  message: string;
}

/** Builds the Fastify-shaped body for a Copilot error (generic message for 500). */
export function copilotErrorBody(error: CopilotIntegrationError, status: number): CopilotErrorBody {
  return {
    statusCode: status,
    code: copilotErrorCode(error),
    error: STATUS_CODES[status] ?? (status === CLIENT_CLOSED_REQUEST ? "Client Closed Request" : "Error"),
    message: status === 500 ? "Internal Server Error" : error.message,
  };
}

/**
 * Error handler that maps Copilot errors to HTTP (`copilotErrorToHttp`) and rethrows anything
 * else to the parent (Fastify's default) handler. Use it from an app's own error handler:
 *
 * ```ts
 * app.setErrorHandler((error, request, reply) => {
 *   if (error instanceof CopilotIntegrationError) return copilotErrorHandler(error, request, reply);
 *   ...
 * });
 * ```
 */
export function copilotErrorHandler(error: FastifyError | Error, request: FastifyRequest, reply: FastifyReply): FastifyReply {
  if (!(error instanceof CopilotIntegrationError)) throw error;
  const { status, headers } = copilotErrorToHttp(error);
  if (status === CLIENT_CLOSED_REQUEST) {
    request.log.debug({ err: error }, "Copilot turn aborted by the client");
    if (reply.raw.destroyed || request.raw.socket?.destroyed) return reply.code(status).send();
  } else if (status >= 500) {
    request.log.error({ err: error }, "Copilot request failed");
  } else {
    request.log.info({ err: error }, "Copilot request rejected");
  }
  return reply.code(status).headers(headers).send(copilotErrorBody(error, status));
}
