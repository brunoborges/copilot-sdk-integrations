import type { ProviderConfig } from "@github/copilot-sdk";
import { toSdkTools } from "./tools/index.js";
import type { CopilotAgentDefinition, CopilotCapacity, CopilotClientState } from "./types.js";

/** Read-only description of an agent. Never includes provider keys or MCP headers/env. */
export interface CopilotAgentInfo {
  name: string;
  description?: string;
  model?: string;
  reasoningEffort?: string;
  /** Named provider (`providers.<name>`), else the inline provider's `type` (or `"custom"`). */
  provider?: string;
  builtInTools: boolean;
  tools: string[];
  mcpServers: string[];
  availableTools?: string[];
  excludedTools?: string[];
  toolSearch?: { enabled?: boolean; deferThreshold?: number };
  instructions?: unknown;
}

/** Info document served by the framework info endpoints. */
export interface CopilotInfo {
  state: CopilotClientState;
  /** Runtime version, when the runtime is running. */
  version?: string;
  /** Admission capacity; `activeTurns` counts in-flight turns (active sessions). */
  capacity: CopilotCapacity;
  agents: CopilotAgentInfo[];
}

export interface DescribeAgentOptions {
  /** Named providers; an agent whose `provider` is one of them is reported by name. */
  providers?: Record<string, ProviderConfig>;
  /** Include the agent's instructions. Default: false (they may contain sensitive prompt engineering). */
  includeInstructions?: boolean;
}

/** Describes an agent definition for an info endpoint (no secrets). */
export function describeAgent(def: CopilotAgentDefinition, options: DescribeAgentOptions = {}): CopilotAgentInfo {
  const info: CopilotAgentInfo = {
    name: def.name,
    builtInTools: def.builtInTools === true,
    tools: [...toSdkTools(def.tools ?? []).customToolNames].sort(),
    mcpServers: Object.keys(def.mcpServers ?? {}),
  };
  if (def.description !== undefined) info.description = def.description;
  if (def.model !== undefined) info.model = def.model;
  if (def.reasoningEffort !== undefined) info.reasoningEffort = def.reasoningEffort;
  if (def.provider !== undefined) {
    const named = Object.entries(options.providers ?? {}).find(([, config]) => config === def.provider)?.[0];
    info.provider = named ?? (def.provider as { type?: string }).type ?? "custom";
  }
  if (def.availableTools !== undefined) info.availableTools = [...def.availableTools];
  if (def.excludedTools !== undefined) info.excludedTools = [...def.excludedTools];
  if (def.toolSearch !== undefined) info.toolSearch = { ...def.toolSearch };
  if (options.includeInstructions && def.instructions !== undefined) info.instructions = def.instructions;
  return info;
}
