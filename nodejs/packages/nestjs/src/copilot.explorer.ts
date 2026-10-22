import { CopilotConfigurationError, composeHooks, getCopilotContext, type CopilotAgentEvent, type CopilotContext, type ToolCallInfo, type ToolInput, type CopilotToolDefinition } from "@copilot-sdk-integrations/core";
import { Inject, Injectable, Logger, Scope, type Type } from "@nestjs/common";
import { ContextIdFactory, DiscoveryService, MetadataScanner, ModuleRef } from "@nestjs/core";
import { COPILOT_CONTEXT_BRIDGE, EVENT_LISTENER_METADATA, HOOK_METADATA, TOOL_INTERCEPTORS_METADATA, TOOL_METADATA } from "./constants.js";
import type {
  CopilotEventType,
  CopilotHookMetadata,
  CopilotToolExecutionContext,
  CopilotToolInterceptor,
  CopilotToolInterceptorRef,
  CopilotToolOptions,
} from "./decorators/tool.decorators.js";
import type { CopilotContextBridge, CopilotToolSource, SessionHooks } from "./interfaces.js";

interface DiscoveredHook extends CopilotHookMetadata {
  handler: (...args: unknown[]) => unknown;
}

interface DiscoveredListener {
  types: CopilotEventType[];
  handler: (event: CopilotAgentEvent, context: CopilotContext) => unknown;
  name: string;
}

/** Looks up an optional provider anywhere in the application. */
export function resolveOptional<T>(moduleRef: ModuleRef, token: unknown): T | undefined {
  try {
    return moduleRef.get(token as string, { strict: false }) as T;
  } catch {
    return undefined;
  }
}

/** Discovers `@CopilotTool`, `@CopilotHook`, and `@OnCopilotEvent` provider methods. */
@Injectable()
export class CopilotExplorer {
  private readonly logger = new Logger("Copilot");
  private readonly toolCache = new Map<Type<unknown>, CopilotToolDefinition[]>();
  private hooks?: DiscoveredHook[];
  private listeners?: DiscoveredListener[];
  private bridges?: CopilotContextBridge[];
  /** One DI context per turn for request-scoped/transient tool providers. */
  private readonly turnContexts = new WeakMap<AbortSignal, ReturnType<typeof ContextIdFactory.create>>();

  constructor(
    @Inject(DiscoveryService) private readonly discovery: DiscoveryService,
    @Inject(MetadataScanner) private readonly scanner: MetadataScanner,
    @Inject(ModuleRef) private readonly moduleRef: ModuleRef,
  ) {}

  /** Ambient context contributed by `COPILOT_CONTEXT_BRIDGE` providers (merged in order), or `undefined`. */
  captureContext(): Partial<CopilotContext> | undefined {
    let result: Partial<CopilotContext> | undefined;
    for (const bridge of this.contextBridges()) {
      const captured = bridge.capture();
      if (!captured) continue;
      const defined = Object.fromEntries(Object.entries(captured).filter(([, v]) => v !== undefined)) as Partial<CopilotContext>;
      result = { ...result, ...defined, attributes: { ...(result?.attributes ?? {}), ...(captured.attributes ?? {}) } };
    }
    return result;
  }

  /** Runs `fn` with the state captured by `COPILOT_CONTEXT_BRIDGE` providers restored (e.g. the CLS store). */
  restoreContext<R>(context: CopilotContext | undefined, fn: () => R): R {
    const bridges = this.contextBridges().filter((b) => typeof b.restore === "function");
    return bridges.reduceRight<() => R>((next, bridge) => () => bridge.restore!(context, next), fn)();
  }

  private contextBridges(): CopilotContextBridge[] {
    this.bridges ??= [resolveOptional<CopilotContextBridge | CopilotContextBridge[]>(this.moduleRef, COPILOT_CONTEXT_BRIDGE) ?? []].flat();
    return this.bridges;
  }

  /** Converts tool sources (provider classes or core tool inputs) to core tool inputs. Classes are deduplicated. */
  toTools(sources: readonly CopilotToolSource[]): ToolInput[] {
    const seen = new Set<unknown>();
    const result: ToolInput[] = [];
    for (const source of sources) {
      if (seen.has(source)) continue;
      seen.add(source);
      if (typeof source === "function") result.push(...this.toolsOf(source as Type<unknown>));
      else result.push(source);
    }
    return result;
  }

  /** The `@CopilotTool` methods of a provider class, bound to its DI-managed instance. */
  toolsOf(providerClass: Type<unknown>): CopilotToolDefinition[] {
    const cached = this.toolCache.get(providerClass);
    if (cached) return cached;

    const prototype = providerClass.prototype as object;
    const methods = this.scanner
      .getAllMethodNames(prototype)
      .filter((name) => Reflect.getMetadata(TOOL_METADATA, prototype, name) !== undefined);
    if (methods.length === 0) {
      throw new CopilotConfigurationError(`${providerClass.name} has no @CopilotTool methods`);
    }

    let scope: Scope | undefined;
    try {
      scope = this.moduleRef.introspect(providerClass).scope;
    } catch {
      throw new CopilotConfigurationError(
        `Tool provider ${providerClass.name} is not registered: add it to the providers of a module`,
      );
    }
    const scoped = scope === Scope.REQUEST || scope === Scope.TRANSIENT;
    const staticInstance = scoped ? undefined : (this.moduleRef.get(providerClass, { strict: false }) as object);
    const classInterceptors: CopilotToolInterceptorRef[] = Reflect.getMetadata(TOOL_INTERCEPTORS_METADATA, providerClass) ?? [];

    const tools = methods.map((methodName): CopilotToolDefinition => {
      const options: CopilotToolOptions = Reflect.getMetadata(TOOL_METADATA, prototype, methodName);
      const name = options.name ?? methodName;
      const interceptors = [
        ...classInterceptors,
        ...((Reflect.getMetadata(TOOL_INTERCEPTORS_METADATA, prototype, methodName) as CopilotToolInterceptorRef[]) ?? []),
      ].map((ref) => this.resolveInterceptor(ref));
      const definition: CopilotToolDefinition = {
        name,
        description: options.description,
        execute: (input: unknown, invocation: ToolCallInfo) => this.restoreContext(invocation.context, async () => {
          const instance = staticInstance ?? (await this.resolveScoped(providerClass, invocation));
          const call = () => Promise.resolve((instance as Record<string, (...a: unknown[]) => unknown>)[methodName](input, invocation));
          if (interceptors.length === 0) return call();
          const context: CopilotToolExecutionContext = { toolName: name, input, invocation, instance, methodName, providerClass };
          const run = (index: number): Promise<unknown> =>
            index >= interceptors.length ? call() : Promise.resolve(interceptors[index].intercept(context, () => run(index + 1)));
          return run(0);
        }),
      };
      if (options.input !== undefined) definition.input = options.input;
      if (options.skipPermission !== undefined) definition.skipPermission = options.skipPermission;
      if (options.overridesBuiltInTool !== undefined) definition.overridesBuiltInTool = options.overridesBuiltInTool;
      return definition;
    });
    this.toolCache.set(providerClass, tools);
    return tools;
  }

  private async resolveScoped(providerClass: Type<unknown>, invocation: ToolCallInfo): Promise<object> {
    const signal = invocation.context.signal ?? invocation.signal;
    let contextId = signal ? this.turnContexts.get(signal) : undefined;
    if (!contextId) {
      contextId = ContextIdFactory.create();
      this.moduleRef.registerRequestByContextId({ copilot: invocation.context }, contextId);
      if (signal) this.turnContexts.set(signal, contextId);
    }
    return (await this.moduleRef.resolve(providerClass, contextId, { strict: false })) as object;
  }

  private resolveInterceptor(ref: CopilotToolInterceptorRef): CopilotToolInterceptor {
    if (typeof ref !== "function") return ref;
    return resolveOptional<CopilotToolInterceptor>(this.moduleRef, ref) ?? new ref();
  }

  /** Discovered hooks that apply to an agent, composed (global hooks first). */
  hooksFor(agentName: string, agentClass?: abstract new (...args: any[]) => unknown): SessionHooks | undefined {
    const applicable = this.discoverHooks().filter(
      (hook) => !hook.agents || hook.agents.some((a) => a === agentName || (agentClass !== undefined && a === agentClass)),
    );
    if (applicable.length === 0) return undefined;
    const ordered = [...applicable.filter((h) => !h.agents), ...applicable.filter((h) => h.agents)];
    return composeHooks(
      ...ordered.map((hook) => ({ [`on${hook.type[0].toUpperCase()}${hook.type.slice(1)}`]: hook.handler }) as SessionHooks),
    );
  }

  /** Delivers an event to `@OnCopilotEvent` listeners. Failures are logged and never propagated. */
  dispatchEvent(event: CopilotAgentEvent, context: CopilotContext): void {
    for (const listener of this.discoverListeners()) {
      if (!listener.types.includes("*") && !listener.types.includes(event.type)) continue;
      try {
        const result = this.restoreContext(context, () => listener.handler(event, context));
        if (result && typeof (result as Promise<unknown>).then === "function") {
          (result as Promise<unknown>).catch((error) => this.listenerFailed(listener.name, error));
        }
      } catch (error) {
        this.listenerFailed(listener.name, error);
      }
    }
  }

  private listenerFailed(name: string, error: unknown): void {
    this.logger.warn(`Copilot event listener ${name} failed: ${error instanceof Error ? error.message : String(error)}`);
  }

  private discoverHooks(): DiscoveredHook[] {
    if (!this.hooks) {
      const hooks: DiscoveredHook[] = [];
      this.scanProviders((instance, prototype, method) => {
        const metadata: CopilotHookMetadata[] | undefined = Reflect.getMetadata(HOOK_METADATA, prototype, method);
        for (const hook of metadata ?? []) {
          hooks.push({
            ...hook,
            handler: (...args) =>
              this.restoreContext(getCopilotContext(), () => (instance as Record<string, (...a: unknown[]) => unknown>)[method](...args)),
          });
        }
      });
      this.hooks = hooks;
    }
    return this.hooks;
  }

  private discoverListeners(): DiscoveredListener[] {
    if (!this.listeners) {
      const listeners: DiscoveredListener[] = [];
      this.scanProviders((instance, prototype, method) => {
        const types: CopilotEventType[] | undefined = Reflect.getMetadata(EVENT_LISTENER_METADATA, prototype, method);
        if (!types) return;
        listeners.push({
          types,
          name: `${instance.constructor?.name ?? "provider"}.${method}`,
          handler: (event, context) => (instance as Record<string, (...a: unknown[]) => unknown>)[method](event, context),
        });
      });
      this.listeners = listeners;
    }
    return this.listeners;
  }

  private scanProviders(visit: (instance: object, prototype: object, method: string) => void): void {
    const seen = new Set<object>();
    for (const wrapper of this.discovery.getProviders()) {
      const instance = wrapper.instance as unknown;
      if (!instance || typeof instance !== "object" || !wrapper.isDependencyTreeStatic() || seen.has(instance)) continue;
      seen.add(instance);
      const prototype = Object.getPrototypeOf(instance) as object | null;
      if (!prototype || prototype === Object.prototype) continue;
      for (const method of this.scanner.getAllMethodNames(prototype)) visit(instance, prototype, method);
    }
  }
}
