import "reflect-metadata";
import type { CopilotContext } from "@copilot-sdk-integrations/core";
import { Module, type DynamicModule } from "@nestjs/common";
import { ClsService } from "nestjs-cls";
import { COPILOT_CONTEXT_BRIDGE } from "../constants.js";
import { defaultPrincipal } from "../copilot-context.interceptor.js";
import type { CopilotContextBridge } from "../interfaces.js";

/** Attribute key (a symbol, so it is never serialized) holding the CLS store captured for a turn. */
export const CLS_STORE_ATTRIBUTE = Symbol.for("@copilot-sdk-integrations/nestjs:cls-store");

export interface CopilotClsOptions {
  /**
   * Maps the active CLS store to Copilot context (principal, attributes, ...). Default: `principal`
   * from the `"principal"` key, or derived from the `"user"` key like `request.user`; `conversationId`
   * from the `"conversationId"` key; and `attributes.user` from the `"user"` key.
   */
  context?: (cls: ClsService) => Partial<CopilotContext> | undefined;
  /** Re-enter the captured CLS store around tools, hooks, event listeners, and policy providers. Default: true. */
  propagate?: boolean;
}

/** Default CLS mapping (see `CopilotClsOptions.context`). */
export function defaultClsContext(cls: ClsService): Partial<CopilotContext> {
  const context: Partial<CopilotContext> = {};
  const user = cls.get("user");
  const principal = cls.get("principal");
  const resolved = typeof principal === "string" && principal !== "" ? principal : defaultPrincipal({ user });
  if (resolved !== undefined) context.principal = resolved;
  const conversationId = cls.get("conversationId");
  if (typeof conversationId === "string" && conversationId !== "") context.conversationId = conversationId;
  context.attributes = user !== undefined ? { user } : {};
  return context;
}

/** `CopilotContextBridge` backed by `nestjs-cls`. */
export class ClsCopilotContextBridge implements CopilotContextBridge {
  constructor(
    private readonly cls: ClsService,
    private readonly options: CopilotClsOptions = {},
  ) {}

  capture(): Partial<CopilotContext> | undefined {
    if (!this.cls.isActive()) return undefined;
    const mapped = (this.options.context ?? defaultClsContext)(this.cls) ?? {};
    const attributes: Record<string | symbol, unknown> = { ...(mapped.attributes ?? {}) };
    if (this.options.propagate !== false) attributes[CLS_STORE_ATTRIBUTE] = this.cls.get();
    return { ...mapped, attributes: attributes as Record<string, unknown> };
  }

  restore<R>(context: CopilotContext | undefined, fn: () => R): R {
    const store = (context?.attributes as Record<symbol, unknown> | undefined)?.[CLS_STORE_ATTRIBUTE];
    if (!store || this.options.propagate === false) return fn();
    if (this.cls.isActive() && this.cls.get() === store) return fn();
    return this.cls.runWith(store as never, fn);
  }
}

/**
 * Integrates `nestjs-cls` with `CopilotModule` (import from `@copilot-sdk-integrations/nestjs/cls`).
 * When an agent is called inside a CLS context (HTTP middleware, guards, interceptors, or
 * `cls.run()` in queue consumers, cron jobs, and other non-HTTP entry points), the principal and
 * attributes are read from the CLS store, and the store is re-entered around `@CopilotTool`,
 * `@CopilotHook`, and `@OnCopilotEvent` methods and policy providers.
 *
 * ```ts
 * imports: [
 *   ClsModule.forRoot({ global: true, middleware: { mount: true } }),
 *   CopilotModule.forRoot({}),
 *   CopilotClsModule.forRoot({ context: (cls) => ({ principal: cls.get("userId"), attributes: { tenant: cls.get("tenant") } }) }),
 * ]
 * ```
 *
 * `ClsService` must be resolvable: register `ClsModule.forRoot({ global: true })`.
 */
@Module({})
export class CopilotClsModule {
  static forRoot(options: CopilotClsOptions = {}): DynamicModule {
    return {
      module: CopilotClsModule,
      global: true,
      providers: [
        {
          provide: COPILOT_CONTEXT_BRIDGE,
          useFactory: (cls: ClsService) => new ClsCopilotContextBridge(cls, options),
          inject: [ClsService],
        },
      ],
      exports: [COPILOT_CONTEXT_BRIDGE],
    };
  }
}
