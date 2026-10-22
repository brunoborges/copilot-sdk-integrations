import { runWithCopilotContext, type CopilotContext } from "@copilot-sdk-integrations/core";
import { Inject, Injectable, type CallHandler, type ExecutionContext, type NestInterceptor } from "@nestjs/common";
import { Observable } from "rxjs";
import { COPILOT_MODULE_OPTIONS } from "./copilot.module-definition.js";
import type { CopilotContextOptions, CopilotModuleOptions } from "./interfaces.js";

/** Default principal: `request.user` as a string, or its `id`/`sub`/`username`/`login`/`email`. */
export function defaultPrincipal(request: { user?: unknown } | undefined): string | undefined {
  const user = request?.user;
  if (typeof user === "string") return user || undefined;
  if (!user || typeof user !== "object") return undefined;
  for (const key of ["id", "sub", "username", "login", "email"]) {
    const value = (user as Record<string, unknown>)[key];
    if (typeof value === "string" && value !== "") return value;
    if (typeof value === "number") return String(value);
  }
  return undefined;
}

/** Builds the `CopilotContext` of an HTTP request (principal, conversation id, attributes). */
export function copilotContextFromRequest(request: any, options: CopilotContextOptions = {}): Partial<CopilotContext> {
  const context: Partial<CopilotContext> = {};
  const principal = options.principal ? options.principal(request) : defaultPrincipal(request);
  if (principal !== undefined) context.principal = principal;
  const conversationId = options.conversationId?.(request);
  if (conversationId !== undefined) context.conversationId = conversationId;
  const attributes: Record<string, unknown> = {};
  if (request?.user !== undefined) attributes.user = request.user;
  if (options.headers?.length) {
    const headers: Record<string, string> = {};
    for (const name of options.headers) {
      const value = request?.headers?.[name.toLowerCase()];
      if (value !== undefined) headers[name.toLowerCase()] = Array.isArray(value) ? value.join(", ") : String(value);
    }
    attributes.headers = headers;
  }
  Object.assign(attributes, options.attributes?.(request) ?? {});
  context.attributes = attributes;
  return context;
}

/**
 * Global interceptor (registered by `CopilotModule`) that runs each HTTP handler inside a
 * `CopilotContext` so agents, tools, policies and hooks see the request's principal and attributes.
 * When the client disconnects, the context signal is aborted and in-flight turns are cancelled.
 */
@Injectable()
export class CopilotContextInterceptor implements NestInterceptor {
  private readonly options: CopilotContextOptions | false;

  constructor(@Inject(COPILOT_MODULE_OPTIONS) moduleOptions: CopilotModuleOptions) {
    this.options = moduleOptions.context ?? {};
  }

  intercept(executionContext: ExecutionContext, next: CallHandler): Observable<unknown> {
    if (this.options === false || executionContext.getType() !== "http") return next.handle();
    const http = executionContext.switchToHttp();
    const request = http.getRequest();
    const response = http.getResponse();
    const context = copilotContextFromRequest(request, this.options);

    if (this.options.abortOnDisconnect !== false) {
      const raw = response?.raw ?? response;
      if (raw && typeof raw.once === "function") {
        const controller = new AbortController();
        const onClose = () => {
          if (!raw.writableEnded) controller.abort();
        };
        raw.once("close", onClose);
        context.signal = controller.signal;
      }
    }

    return new Observable((subscriber) => runWithCopilotContext(context, () => next.handle().subscribe(subscriber)));
  }
}
