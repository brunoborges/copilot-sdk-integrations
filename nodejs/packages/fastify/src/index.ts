export { copilotPlugin, PLUGIN_NAME, SSE_HEADERS } from "./plugin.js";
export { copilotErrorHandler, copilotErrorBody, type CopilotErrorBody } from "./errors.js";
export { copilotHealthCheck } from "./routes.js";
export { copilotContextFromRequest, defaultPrincipal } from "./context.js";
export { fastifyCopilotLogger } from "./logger.js";
export { DEFAULT_HEALTH_PATH, DEFAULT_INFO_PATH, DEFAULT_SHUTDOWN_GRACE_PERIOD_MS } from "./options.js";
export type {
  CopilotAgentName,
  CopilotAgentRegistry,
  CopilotContextOptions,
  CopilotEventsOptions,
  CopilotHealthRouteOptions,
  CopilotInfoRouteOptions,
  CopilotPluginOptions,
  FastifyCopilot,
  RequestCopilot,
} from "./types.js";
export {
  copilotErrorToHttp,
  copilotErrorCode,
  CLIENT_CLOSED_REQUEST,
  type CopilotHttpError,
} from "@copilot-sdk-integrations/agent-core";
