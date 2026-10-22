import type { Type } from "@nestjs/common";
import type {
  ClientLike,
  Copilot,
  CopilotAgentDefinition,
  CopilotContext,
  CopilotOptions,
  PermissionDecision,
  PermissionPolicy,
  PermissionPolicyContext,
  PermissionRequest,
  ToolCallInfo,
  ToolInput,
} from "@copilot-sdk-integrations/core";

// SDK types, derived from core so this package needs no direct `@github/copilot-sdk` dependency.
export type SessionConfig = Parameters<NonNullable<CopilotAgentDefinition["configureSession"]>>[0];
export type CopilotClientOptions = Parameters<NonNullable<CopilotOptions["configureClient"]>>[0];
export type ProviderConfig = NonNullable<CopilotAgentDefinition["provider"]>;
export type MCPServerConfig = NonNullable<CopilotAgentDefinition["mcpServers"]>[string];
export type SessionHooks = NonNullable<CopilotAgentDefinition["hooks"]>;

/** Lazily starts the runtime and returns the underlying client. */
export type CopilotClientAccessor = () => Promise<ClientLike>;

/** Adjusts the raw SDK client options (after `configureClient`). */
export type CopilotClientCustomizer = (options: CopilotClientOptions) => CopilotClientOptions;

/** Adjusts the raw SDK session config of every agent (after the agent's own `configureSession`). */
export type CopilotSessionCustomizer = (
  config: SessionConfig,
  context: CopilotContext,
  agent: CopilotAgentDefinition,
) => SessionConfig;

/** An injectable permission policy, usable as `permissions: MyPolicy` or as `COPILOT_PERMISSION_POLICY`. */
export interface CopilotPermissionPolicy {
  evaluate(request: PermissionRequest, context: PermissionPolicyContext): PermissionDecision | Promise<PermissionDecision>;
}

/** Tool invocation details passed as the second argument of `@CopilotTool` methods. */
export type CopilotToolInvocation = ToolCallInfo;

/** Anything accepted in an agent's `tools`: core tool inputs, or provider classes with `@CopilotTool` methods. */
export type CopilotToolSource = ToolInput | Type<unknown>;

/** Per-agent overrides from configuration (`agents.<name>`); they win over decorator values. */
export interface CopilotAgentOverrides {
  description?: string;
  instructions?: CopilotAgentDefinition["instructions"];
  model?: string;
  reasoningEffort?: CopilotAgentDefinition["reasoningEffort"];
  /** A named provider from `providers`, or an inline BYOK provider config. */
  provider?: string | ProviderConfig;
  /** Names from `mcpServers`, or inline server configs. */
  mcpServers?: string[] | Record<string, MCPServerConfig>;
  availableTools?: string[];
  excludedTools?: string[];
  builtInTools?: boolean;
}

/** How the request context (`CopilotContext`) is captured from HTTP requests. */
export interface CopilotContextOptions {
  /** Derive the principal. Default: `request.user` (a string, or its `id`/`sub`/`username`/`login`/`email`). */
  principal?: (request: any) => string | undefined;
  /** Request headers copied into `attributes.headers` (lower-case names). */
  headers?: string[];
  /**
   * Derive the request's conversation id, stored in `CopilotContext.conversationId` for tools,
   * policies, hooks, and listeners. Informational only: agent calls use the `@ConversationId()`
   * argument (or a bound instance), and calls without one run in an ephemeral session.
   */
  conversationId?: (request: any) => string | undefined;
  /** Extra attributes merged into `attributes`. */
  attributes?: (request: any) => Record<string, unknown>;
  /** Abort turns started by the request when the client disconnects. Default: true. */
  abortOnDisconnect?: boolean;
}

/**
 * Contributes ambient context to agent calls from another context store (e.g. `nestjs-cls`), for
 * HTTP and non-HTTP entry points alike. Register it as `COPILOT_CONTEXT_BRIDGE` (a bridge or an array).
 */
export interface CopilotContextBridge {
  /**
   * Called synchronously when an agent method or handle is invoked. The result is merged over the
   * ambient context (e.g. the one set by `CopilotContextInterceptor`); explicit `context` send options still win.
   */
  capture(): Partial<CopilotContext> | undefined;
  /** Runs a tool, hook, event listener, or permission policy provider with the captured state restored. */
  restore?<R>(context: CopilotContext | undefined, fn: () => R): R;
}

/** Re-emission of Copilot events on `@nestjs/event-emitter`'s `EventEmitter2`. */
export interface CopilotEventEmitterOptions {
  /** Event name prefix: events are emitted as `<prefix>.<event.type>`. Default `"copilot"`. */
  prefix?: string;
}

/** What a custom `copilotFactory` returns. */
export interface CopilotInstance {
  copilot: Copilot;
  stop?(options?: { gracePeriodMs?: number }): Promise<void>;
}

/** `CopilotModule.forRoot()` options: the core `CopilotOptions` plus NestJS-specific settings. */
export interface CopilotModuleOptions extends CopilotOptions {
  /** Named BYOK providers, selected per agent with `provider: "name"`. */
  providers?: Record<string, ProviderConfig>;
  /** Named MCP servers, selected per agent with `mcpServers: ["name"]`. */
  mcpServers?: Record<string, MCPServerConfig>;
  /** Per-agent overrides keyed by agent name. */
  agents?: Record<string, CopilotAgentOverrides>;
  /** How long shutdown waits for in-flight turns before aborting them, in ms. Default 30 000. */
  shutdownGracePeriod?: number;
  /** Request context capture. `false` disables the global `CopilotContextInterceptor`. */
  context?: CopilotContextOptions | false;
  /**
   * Base directory for relative `@UserMessageFrom()` template paths. Default: `process.cwd()`.
   * Absolute paths and `file:` URLs (`new URL("./prompt.md", import.meta.url)`) are used as is.
   */
  templatesDir?: string;
  /**
   * Re-emit every Copilot event on the application's `EventEmitter2` (`@nestjs/event-emitter`, which
   * must be installed and `EventEmitterModule.forRoot()` imported) as `copilot.<event.type>`, with
   * `(event, context)` as arguments. Default: false.
   */
  eventEmitter?: boolean | CopilotEventEmitterOptions;
  /** Register `CopilotExceptionFilter` globally. Default: true. */
  exceptionFilter?: boolean;
  /** Advanced: create the core `Copilot` yourself (the testing module uses this). */
  copilotFactory?: (options: CopilotOptions) => CopilotInstance;
}
