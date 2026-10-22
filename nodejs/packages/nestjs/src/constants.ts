/** Injection token for the core `Copilot` instance. */
export const COPILOT = Symbol.for("@copilot-sdk-integrations/nestjs:Copilot");
/** Injection token for `CopilotClientAccessor`: `() => Promise<ClientLike>` (starts a lazy runtime). */
export const COPILOT_CLIENT = Symbol.for("@copilot-sdk-integrations/nestjs:CopilotClient");
/** Optional provider: a `CopilotClientCustomizer` (or an array of them) adjusting raw SDK client options. */
export const COPILOT_CLIENT_CUSTOMIZER = Symbol.for("@copilot-sdk-integrations/nestjs:ClientCustomizer");
/** Optional provider: a `CopilotSessionCustomizer` (or an array of them) adjusting raw SDK session config. */
export const COPILOT_SESSION_CUSTOMIZER = Symbol.for("@copilot-sdk-integrations/nestjs:SessionCustomizer");
/** Optional provider: the default permission policy for agents that don't declare one. */
export const COPILOT_PERMISSION_POLICY = Symbol.for("@copilot-sdk-integrations/nestjs:PermissionPolicy");

/**
 * Optional provider: a `CopilotContextBridge` (or an array of them) contributing ambient context
 * (e.g. from `nestjs-cls`) to agent calls. `CopilotClsModule` from `@copilot-sdk-integrations/nestjs/cls` registers one.
 */
export const COPILOT_CONTEXT_BRIDGE = Symbol.for("@copilot-sdk-integrations/nestjs:ContextBridge");
/** Optional provider: `CopilotInfoOptions` for `CopilotInfoController`. */
export const COPILOT_INFO_OPTIONS = Symbol.for("@copilot-sdk-integrations/nestjs:InfoOptions");

/** Injection token of a functional agent registered with `CopilotModule.forFeature([defineAgent(...)])`. */
export function getAgentToken(name: string): string {
  return `CopilotAgent:${name}`;
}

export const AGENT_METADATA = "copilot:agent";
export const AGENT_TOOLS_METADATA = "copilot:agent:tools";
export const USER_MESSAGE_METADATA = "copilot:user-message";
export const PARAMS_METADATA = "copilot:params";
export const RETURNS_METADATA = "copilot:returns";
export const TOOL_METADATA = "copilot:tool";
export const TOOL_INTERCEPTORS_METADATA = "copilot:tool:interceptors";
export const HOOK_METADATA = "copilot:hook";
export const EVENT_LISTENER_METADATA = "copilot:event-listener";
