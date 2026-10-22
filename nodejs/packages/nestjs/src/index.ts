import "reflect-metadata";

export { CopilotModule, CopilotFeatureModule, type CopilotFeatureOptions } from "./copilot.module.js";
export { COPILOT_MODULE_OPTIONS, type CopilotModuleAsyncOptions, type CopilotModuleRootOptions } from "./copilot.module-definition.js";
export {
  COPILOT,
  COPILOT_CLIENT,
  COPILOT_CLIENT_CUSTOMIZER,
  COPILOT_SESSION_CUSTOMIZER,
  COPILOT_PERMISSION_POLICY,
  getAgentToken,
} from "./constants.js";
export type {
  CopilotModuleOptions,
  CopilotContextOptions,
  CopilotAgentOverrides,
  CopilotClientAccessor,
  CopilotClientCustomizer,
  CopilotSessionCustomizer,
  CopilotPermissionPolicy,
  CopilotToolInvocation,
  CopilotToolSource,
  CopilotInstance,
  SessionConfig,
  CopilotClientOptions,
  ProviderConfig,
  MCPServerConfig,
  SessionHooks,
} from "./interfaces.js";
export {
  CopilotAgent,
  UserMessage,
  UserMessageFrom,
  Message,
  P,
  ConversationId,
  Signal,
  Returns,
  Tools,
  implementedByCopilot,
  type CopilotAgentOptions,
  type UserMessageOptions,
  type AgentMethodResult,
} from "./decorators/agent.decorators.js";
export {
  CopilotTool,
  UseToolInterceptors,
  CopilotHook,
  OnCopilotEvent,
  InjectCopilot,
  InjectCopilotClient,
  InjectAgent,
  type CopilotToolOptions,
  type CopilotToolExecutionContext,
  type CopilotToolInterceptor,
  type CopilotToolInterceptorRef,
  type CopilotHookType,
  type CopilotHookOptions,
  type CopilotEventType,
} from "./decorators/tool.decorators.js";
export { CopilotAgents, type CopilotBoundAgent, type BindOptions, type CopilotAgentClass } from "./copilot-agents.service.js";
export { CopilotClientManager, nestCopilotLogger } from "./copilot-client.manager.js";
export { CopilotExplorer } from "./copilot.explorer.js";
export { CopilotContextInterceptor, copilotContextFromRequest, defaultPrincipal } from "./copilot-context.interceptor.js";
export {
  CopilotExceptionFilter,
  copilotErrorToHttp,
  CLIENT_CLOSED_REQUEST,
  type CopilotHttpError,
} from "./copilot-exception.filter.js";
export { CopilotHealthIndicator, type CopilotHealthResult } from "./copilot.health.js";
export { CopilotTokenResolver, PassportGitHubTokenResolver } from "./token-resolver.js";
export { observeAgentStream, fromAsyncIterable, serializeAgentEvent, toMessageEvent, toSseEvents } from "./streaming.js";
export { validateModuleOptions } from "./options.js";
