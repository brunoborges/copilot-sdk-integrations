import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import {
  CopilotConfigurationError,
  composeHooks,
  defineAgent,
  getCopilotContext,
  runWithCopilotContext,
  type AgentHandle,
  type ConversationMode,
  type CopilotAgentDefinition,
  type CopilotAgentEvent,
  type CopilotContext,
  type PermissionPolicy,
  type PermissionResponse,
  type StructuredSchema,
  type ToolInput,
} from "@copilot-sdk-integrations/core";
import { Inject, Injectable, type OnModuleInit, type Type } from "@nestjs/common";
import { ModuleRef } from "@nestjs/core";
import { Observable } from "rxjs";
import { COPILOT_PERMISSION_POLICY, COPILOT_SESSION_CUSTOMIZER, AGENT_METADATA, AGENT_TOOLS_METADATA, PARAMS_METADATA, RETURNS_METADATA, USER_MESSAGE_METADATA } from "./constants.js";
import { CopilotClientManager } from "./copilot-client.manager.js";
import { CopilotExplorer, resolveOptional } from "./copilot.explorer.js";
import type {
  AgentMethodResult,
  CopilotAgentOptions,
  ParamMetadata,
  UserMessageMetadata,
} from "./decorators/agent.decorators.js";
import type {
  CopilotPermissionPolicy,
  CopilotSessionCustomizer,
  CopilotToolSource,
  MCPServerConfig,
  ProviderConfig,
} from "./interfaces.js";
import { observeAgentStream } from "./streaming.js";

const PLACEHOLDER = /\{\{\s*([a-zA-Z_$][\w$.-]*)\s*\}\}/g;

interface MethodPlan {
  name: string;
  template?: string;
  messageIndex?: number;
  conversationIdIndex?: number;
  signalIndex?: number;
  vars: { name: string; index: number }[];
  schema?: StructuredSchema<unknown>;
  result: AgentMethodResult;
  tools: CopilotToolSource[];
}

interface AgentEntry {
  name: string;
  agentClass?: CopilotAgentClass;
  options: CopilotAgentOptions;
  featureTools: CopilotToolSource[];
  plans: Map<string, MethodPlan>;
  built?: { definition: CopilotAgentDefinition; handle: AgentHandle };
}

interface InvokeOverrides {
  conversationId?: string;
  mode?: ConversationMode;
  principal?: string;
}

interface PendingPermission {
  conversationId?: string;
  respond(response: PermissionResponse): void;
}

/**
 * A conversation-bound agent: methods omit the `@ConversationId()` argument (typed assuming it is
 * the first parameter).
 */
export type CopilotBoundAgent<T> = {
  [K in keyof T as T[K] extends (...args: any[]) => any ? K : never]: T[K] extends (id: string, ...rest: infer R) => infer Ret
    ? (...rest: R) => Ret
    : T[K];
};

/** An agent class (abstract classes allowed). */
export type CopilotAgentClass<T = unknown> = abstract new (...args: any[]) => T;

export interface BindOptions {
  /** Default `"resume-only"`: a missing conversation raises `CopilotConversationNotFoundError`. */
  mode?: ConversationMode;
  /** Explicit principal (wins over the request context). */
  principal?: string;
}

/** Registry of declarative and functional agents: implementations, bound instances, and permission responses. */
@Injectable()
export class CopilotAgents implements OnModuleInit {
  private readonly byClass = new Map<CopilotAgentClass, AgentEntry>();
  private readonly byName = new Map<string, AgentEntry>();
  private readonly pending = new Map<string, PendingPermission>();

  constructor(
    @Inject(CopilotClientManager) private readonly manager: CopilotClientManager,
    @Inject(CopilotExplorer) private readonly explorer: CopilotExplorer,
    @Inject(ModuleRef) private readonly moduleRef: ModuleRef,
  ) {}

  /** Validates every registered agent (tools, filters, providers) at bootstrap. */
  onModuleInit(): void {
    for (const entry of this.byName.values()) this.build(entry);
  }

  /** Creates the implementation of a `@CopilotAgent` class (used by `CopilotModule.forFeature`). */
  implement<T>(agentClass: CopilotAgentClass<T>, featureTools: CopilotToolSource[] = []): T {
    const entry = this.registerClass(agentClass, featureTools);
    const self = this;
    const Base = agentClass as unknown as new () => object;
    const Impl = class extends Base {};
    Object.defineProperty(Impl, "name", { value: `${agentClass.name}$Copilot` });
    for (const plan of entry.plans.values()) {
      Object.defineProperty(Impl.prototype, plan.name, {
        configurable: true,
        writable: true,
        value: function (...args: unknown[]) {
          return self.invoke(entry, plan, args, {});
        },
      });
    }
    return new Impl() as T;
  }

  /** Registers a functional agent; returns a handle that builds lazily (tool providers resolve after instantiation). */
  register(definition: CopilotAgentDefinition, featureTools: CopilotToolSource[] = []): AgentHandle {
    if (!definition || typeof definition.name !== "string") {
      throw new CopilotConfigurationError("forFeature() accepts @CopilotAgent classes or defineAgent() definitions");
    }
    const entry: AgentEntry = { name: definition.name, options: definition as CopilotAgentOptions, featureTools, plans: new Map() };
    this.addEntry(entry);
    const handle = () => this.build(entry).handle;
    return {
      get definition() {
        return handle().definition;
      },
      send: ((options: Parameters<AgentHandle["send"]>[0]) => handle().send(options)) as AgentHandle["send"],
      stream: (options) => handle().stream(options),
      conversation: (conversationId, options) => handle().conversation(conversationId, options),
      deleteConversation: (conversationId, options) => handle().deleteConversation(conversationId, options),
    };
  }

  /** The core handle of a registered agent (class or name). */
  handle(agent: CopilotAgentClass | string): AgentHandle {
    return this.build(this.entry(agent)).handle;
  }

  /** The final core definition of a registered agent (after overrides). */
  definition(agent: CopilotAgentClass | string): CopilotAgentDefinition {
    return this.build(this.entry(agent)).definition;
  }

  /** Names of all registered agents. */
  names(): string[] {
    return [...this.byName.keys()];
  }

  /** A conversation-bound instance (resume-only by default). */
  bind<T>(agentClass: CopilotAgentClass<T>, conversationId: string, options: BindOptions = {}): CopilotBoundAgent<T> {
    if (typeof conversationId !== "string" || conversationId.trim() === "") {
      throw new TypeError("conversationId must be a non-blank string");
    }
    const entry = this.entry(agentClass);
    const overrides: InvokeOverrides = { conversationId, mode: options.mode ?? "resume-only" };
    if (options.principal !== undefined) overrides.principal = options.principal;
    const bound: Record<string, (...args: unknown[]) => unknown> = {};
    for (const plan of entry.plans.values()) {
      bound[plan.name] = (...args: unknown[]) => {
        const full = [...args];
        if (plan.conversationIdIndex !== undefined) full.splice(plan.conversationIdIndex, 0, conversationId);
        return this.invoke(entry, plan, full, overrides);
      };
    }
    return bound as CopilotBoundAgent<T>;
  }

  /** A conversation-bound instance that creates the conversation if it doesn't exist. */
  create<T>(agentClass: CopilotAgentClass<T>, conversationId: string, options: Omit<BindOptions, "mode"> = {}): CopilotBoundAgent<T> {
    return this.bind(agentClass, conversationId, { ...options, mode: "create-or-resume" });
  }

  /** Deletes a conversation's session. */
  delete(agent: CopilotAgentClass | string, conversationId: string, options: { principal?: string } = {}): Promise<void> {
    return this.handle(agent).deleteConversation(conversationId, options);
  }

  /**
   * Resolves an "ask-user" permission emitted on a streaming call (`permission-request` event).
   * Returns false when no such request is pending (already resolved, expired, or another conversation).
   */
  respondToPermission(conversationId: string | undefined, requestId: string, decision: boolean | PermissionResponse): boolean {
    const pending = this.pending.get(requestId);
    if (!pending || (pending.conversationId !== undefined && pending.conversationId !== conversationId)) return false;
    this.pending.delete(requestId);
    pending.respond(typeof decision === "boolean" ? { approved: decision } : decision);
    return true;
  }

  // -------------------------------------------------------------------------

  private entry(agent: CopilotAgentClass | string): AgentEntry {
    const entry = typeof agent === "string" ? this.byName.get(agent) : this.byClass.get(agent);
    if (!entry) {
      const label = typeof agent === "string" ? `"${agent}"` : agent?.name;
      throw new CopilotConfigurationError(`Agent ${label} is not registered: add it to CopilotModule.forFeature([...])`);
    }
    return entry;
  }

  private addEntry(entry: AgentEntry): void {
    const existing = this.byName.get(entry.name);
    if (existing) {
      throw new CopilotConfigurationError(`Duplicate agent name "${entry.name}"`);
    }
    this.byName.set(entry.name, entry);
    if (entry.agentClass) this.byClass.set(entry.agentClass, entry);
  }

  private registerClass(agentClass: CopilotAgentClass, featureTools: CopilotToolSource[]): AgentEntry {
    const existing = this.byClass.get(agentClass);
    if (existing) {
      existing.featureTools.push(...featureTools.filter((t) => !existing.featureTools.includes(t)));
      return existing;
    }
    const options: CopilotAgentOptions | undefined = Reflect.getMetadata(AGENT_METADATA, agentClass);
    if (!options) {
      throw new CopilotConfigurationError(`${agentClass.name} is not decorated with @CopilotAgent`);
    }
    const entry: AgentEntry = {
      name: options.name ?? agentClass.name,
      agentClass,
      options,
      featureTools: [...featureTools],
      plans: planMethods(agentClass),
    };
    this.addEntry(entry);
    return entry;
  }

  private build(entry: AgentEntry): { definition: CopilotAgentDefinition; handle: AgentHandle } {
    if (entry.built) return entry.built;
    const where = `Agent "${entry.name}"`;
    const { tools: optionTools, provider, mcpServers, permissions, hooks, configureSession, name: _name, ...rest } = entry.options;
    const overrides = this.manager.options.agents?.[entry.name] ?? {};
    const classTools: CopilotToolSource[] = entry.agentClass ? (Reflect.getMetadata(AGENT_TOOLS_METADATA, entry.agentClass) ?? []) : [];

    let tools: ToolInput[];
    try {
      tools = this.explorer.toTools([...(optionTools ?? []), ...classTools, ...entry.featureTools]);
    } catch (error) {
      throw prefixed(where, error);
    }

    const definition: CopilotAgentDefinition = { ...rest, name: entry.name, tools };
    for (const key of ["description", "instructions", "model", "reasoningEffort", "builtInTools"] as const) {
      if (overrides[key] !== undefined) (definition as unknown as Record<string, unknown>)[key] = overrides[key];
    }
    if (overrides.availableTools !== undefined || overrides.excludedTools !== undefined) {
      definition.availableTools = overrides.availableTools;
      definition.excludedTools = overrides.excludedTools;
    }
    const resolvedProvider = this.resolveProvider(where, overrides.provider ?? provider);
    if (resolvedProvider) definition.provider = resolvedProvider;
    const resolvedMcp = this.resolveMcpServers(where, overrides.mcpServers ?? mcpServers);
    if (resolvedMcp) definition.mcpServers = resolvedMcp;
    const policy = this.resolvePolicy(where, permissions) ?? this.resolvePolicy(where, resolveOptional(this.moduleRef, COPILOT_PERMISSION_POLICY));
    if (policy) definition.permissions = policy;
    const composedHooks = composeHooks(this.explorer.hooksFor(entry.name, entry.agentClass), hooks);
    if (composedHooks) definition.hooks = composedHooks;

    const customizers = [resolveOptional<CopilotSessionCustomizer | CopilotSessionCustomizer[]>(this.moduleRef, COPILOT_SESSION_CUSTOMIZER) ?? []].flat();
    let finalDefinition: CopilotAgentDefinition | undefined;
    if (configureSession || customizers.length > 0) {
      definition.configureSession = (config, context) => {
        let result = configureSession ? configureSession(config, context) : config;
        for (const customize of customizers) result = customize(result, context, finalDefinition!);
        return result;
      };
    }
    finalDefinition = defineAgent(definition);
    entry.built = { definition: finalDefinition, handle: this.manager.copilot.agent(finalDefinition) };
    return entry.built;
  }

  private resolveProvider(where: string, provider: string | ProviderConfig | undefined): ProviderConfig | undefined {
    if (typeof provider !== "string") return provider;
    const resolved = this.manager.options.providers?.[provider];
    if (!resolved) throw new CopilotConfigurationError(`${where}: unknown provider "${provider}" (configure it in providers)`);
    return resolved;
  }

  private resolveMcpServers(
    where: string,
    servers: string[] | Record<string, MCPServerConfig> | undefined,
  ): Record<string, MCPServerConfig> | undefined {
    if (!Array.isArray(servers)) return servers;
    const result: Record<string, MCPServerConfig> = {};
    for (const name of servers) {
      const config = this.manager.options.mcpServers?.[name];
      if (!config) throw new CopilotConfigurationError(`${where}: unknown MCP server "${name}" (configure it in mcpServers)`);
      result[name] = config;
    }
    return result;
  }

  private resolvePolicy(where: string, policy: unknown): PermissionPolicy | undefined {
    if (policy === undefined || policy === null) return undefined;
    if (typeof policy === "function" && typeof (policy as { prototype?: { evaluate?: unknown } }).prototype?.evaluate === "function") {
      const instance = resolveOptional<CopilotPermissionPolicy>(this.moduleRef, policy);
      if (!instance) {
        throw new CopilotConfigurationError(`${where}: permission policy ${(policy as Type).name} is not registered as a provider`);
      }
      return (request, context) => instance.evaluate(request, context);
    }
    if (typeof policy === "function") return policy as PermissionPolicy;
    if (typeof (policy as CopilotPermissionPolicy).evaluate === "function") {
      const instance = policy as CopilotPermissionPolicy;
      return (request, context) => instance.evaluate(request, context);
    }
    throw new CopilotConfigurationError(`${where}: permissions must be a policy function or a CopilotPermissionPolicy`);
  }

  private invoke(entry: AgentEntry, plan: MethodPlan, args: unknown[], overrides: InvokeOverrides): unknown {
    let prompt: string;
    let tools: ToolInput[] | undefined;
    let handle: AgentHandle;
    try {
      prompt = render(entry, plan, args);
      tools = plan.tools.length > 0 ? this.explorer.toTools(plan.tools) : undefined;
      handle = this.build(entry).handle;
    } catch (error) {
      if (plan.result === "promise") return Promise.reject(error);
      if (plan.result === "observable") return new Observable((subscriber) => subscriber.error(error));
      throw error;
    }
    const conversationId =
      overrides.conversationId ?? (plan.conversationIdIndex !== undefined ? (args[plan.conversationIdIndex] as string | undefined) : undefined);
    const captured = getCopilotContext();
    const signals = [plan.signalIndex !== undefined ? args[plan.signalIndex] : undefined, captured?.signal].filter(
      (s): s is AbortSignal => s instanceof AbortSignal,
    );
    const options = (extra?: AbortSignal) => {
      const all = extra ? [...signals, extra] : signals;
      const result: { signal?: AbortSignal; tools?: ToolInput[] } = {};
      if (all.length > 0) result.signal = all.length === 1 ? all[0] : AbortSignal.any(all);
      if (tools) result.tools = tools;
      return result;
    };
    const bound =
      overrides.mode !== undefined && conversationId !== undefined
        ? handle.conversation(conversationId, {
            mode: overrides.mode,
            ...(overrides.principal !== undefined ? { principal: overrides.principal } : {}),
          })
        : undefined;

    if (plan.result === "promise") {
      const sendOptions = { ...options(), ...(plan.schema ? { schema: plan.schema } : {}) };
      if (bound) return bound.send(prompt, sendOptions as Parameters<typeof bound.send>[1]);
      return handle.send({ ...sendOptions, prompt, ...(conversationId !== undefined ? { conversationId } : {}) } as Parameters<AgentHandle["send"]>[0]);
    }

    const start = (extra?: AbortSignal) => {
      const streamOptions = options(extra);
      const iterable = bound
        ? bound.stream(prompt, streamOptions)
        : handle.stream({ ...streamOptions, prompt, ...(conversationId !== undefined ? { conversationId } : {}) });
      return this.track(iterable, conversationId);
    };
    const inContext = <R>(fn: () => R): R => (captured ? runWithCopilotContext(captured, fn) : fn());

    if (plan.result === "observable") {
      const events = observeAgentStream(start);
      return new Observable<CopilotAgentEvent>((subscriber) => inContext(() => events.subscribe(subscriber)));
    }
    return {
      [Symbol.asyncIterator](): AsyncIterator<CopilotAgentEvent> {
        const iterator = inContext(() => start()[Symbol.asyncIterator]());
        return {
          next: () => inContext(() => iterator.next()),
          return: (value?: unknown) => inContext(() => iterator.return!(value)),
          throw: (error?: unknown) => inContext(() => iterator.throw!(error)),
        };
      },
    } satisfies AsyncIterable<CopilotAgentEvent>;
  }

  /** Tracks pending "ask-user" permissions so `respondToPermission` can resolve them. */
  private track(iterable: AsyncIterable<CopilotAgentEvent>, conversationId: string | undefined): AsyncGenerator<CopilotAgentEvent> {
    const pending = this.pending;
    return (async function* () {
      const seen: string[] = [];
      try {
        for await (const event of iterable) {
          if (event.type === "permission-request") {
            pending.set(event.requestId, { conversationId, respond: event.respond });
            seen.push(event.requestId);
          } else if (event.type === "permission-resolved") {
            pending.delete(event.requestId);
          }
          yield event;
        }
      } finally {
        for (const id of seen) pending.delete(id);
      }
    })();
  }
}

function prefixed(where: string, error: unknown): unknown {
  if (error instanceof CopilotConfigurationError && !error.message.startsWith(where)) {
    return new CopilotConfigurationError(`${where}: ${error.message}`, { cause: error });
  }
  return error;
}

/** Reads and validates `@UserMessage` metadata of every method (templates, parameters, schemas). */
function planMethods(agentClass: CopilotAgentClass): Map<string, MethodPlan> {
  const plans = new Map<string, MethodPlan>();
  const seen = new Set<string>();
  for (let proto = agentClass.prototype as object | null; proto && proto !== Object.prototype; proto = Object.getPrototypeOf(proto)) {
    for (const name of Object.getOwnPropertyNames(proto)) {
      if (name === "constructor" || seen.has(name)) continue;
      seen.add(name);
      const metadata: UserMessageMetadata | undefined = Reflect.getMetadata(USER_MESSAGE_METADATA, proto, name);
      if (!metadata) continue;
      plans.set(name, planMethod(agentClass, proto, name, metadata));
    }
  }
  return plans;
}

function planMethod(agentClass: CopilotAgentClass, proto: object, name: string, metadata: UserMessageMetadata): MethodPlan {
  const where = `${agentClass.name}.${name}()`;
  const fail = (message: string) => new CopilotConfigurationError(`${where}: ${message}`);
  const plan: MethodPlan = { name, vars: [], result: "promise", tools: Reflect.getMetadata(AGENT_TOOLS_METADATA, proto, name) ?? [] };

  const params: ParamMetadata[] = [...(Reflect.getMetadata(PARAMS_METADATA, proto, name) ?? [])].sort((a, b) => a.index - b.index);
  const indexes = new Set<number>();
  for (const p of params) {
    if (indexes.has(p.index)) throw fail(`parameter ${p.index} has more than one Copilot parameter decorator`);
    indexes.add(p.index);
    const single = (key: "messageIndex" | "conversationIdIndex" | "signalIndex", label: string) => {
      if (plan[key] !== undefined) throw fail(`only one ${label} parameter is allowed`);
      plan[key] = p.index;
    };
    if (p.kind === "message") single("messageIndex", "@Message()");
    else if (p.kind === "conversationId") single("conversationIdIndex", "@ConversationId()");
    else if (p.kind === "signal") single("signalIndex", "@Signal()");
    else {
      if (plan.vars.some((v) => v.name === p.name)) throw fail(`duplicate @P("${p.name}")`);
      plan.vars.push({ name: p.name!, index: p.index });
    }
  }

  let template = metadata.template;
  if (metadata.templateFile !== undefined) {
    try {
      template = readFileSync(resolve(process.cwd(), metadata.templateFile), "utf8");
    } catch (error) {
      throw fail(`cannot read template file "${metadata.templateFile}": ${(error as Error).message}`);
    }
  }
  if (template !== undefined) {
    if (template.trim() === "") throw fail("the prompt template is blank");
    plan.template = template;
    const placeholders = new Set([...template.matchAll(PLACEHOLDER)].map((m) => m[1]));
    const varNames = new Set(plan.vars.map((v) => v.name));
    if (plan.messageIndex !== undefined && varNames.has("message")) throw fail(`@P("message") conflicts with @Message()`);
    for (const placeholder of placeholders) {
      if (varNames.has(placeholder)) continue;
      if (placeholder === "message" && plan.messageIndex !== undefined) continue;
      throw fail(`unknown placeholder {{${placeholder}}}: bind it with @P("${placeholder}")`);
    }
    for (const v of plan.vars) {
      if (!placeholders.has(v.name)) throw fail(`@P("${v.name}") is not used in the template`);
    }
    if (plan.messageIndex !== undefined && !placeholders.has("message")) {
      throw fail("the @Message() argument must be referenced as {{message}} in the template");
    }
  } else {
    if (plan.messageIndex === undefined) throw fail("needs a @UserMessage(template) or a @Message() parameter");
    if (plan.vars.length > 0) throw fail("@P() parameters require a @UserMessage(template)");
  }

  plan.schema = Reflect.getMetadata(RETURNS_METADATA, proto, name);
  if (plan.schema !== undefined && typeof plan.schema?.parse !== "function") throw fail("@Returns() requires a Zod schema");
  const returnType = Reflect.getMetadata("design:returntype", proto, name) as { name?: string } | undefined;
  plan.result = metadata.stream ?? (returnType?.name === "Observable" ? "observable" : "promise");
  if (plan.result !== "promise" && plan.schema !== undefined) throw fail("@Returns() cannot be combined with a streaming result");
  return plan;
}

function render(entry: AgentEntry, plan: MethodPlan, args: unknown[]): string {
  if (plan.template === undefined) {
    const message = args[plan.messageIndex!];
    if (typeof message !== "string") {
      throw new TypeError(`${entry.agentClass?.name ?? entry.name}.${plan.name}(): the @Message() argument must be a string`);
    }
    return message;
  }
  const values = new Map(plan.vars.map((v) => [v.name, args[v.index]]));
  if (plan.messageIndex !== undefined) values.set("message", args[plan.messageIndex]);
  return plan.template.replace(PLACEHOLDER, (_, name: string) => stringify(values.get(name)));
}

function stringify(value: unknown): string {
  if (value === undefined || value === null) return "";
  if (typeof value === "string") return value;
  if (typeof value === "number" || typeof value === "boolean" || typeof value === "bigint") return String(value);
  return JSON.stringify(value);
}

export type { CopilotContext };
