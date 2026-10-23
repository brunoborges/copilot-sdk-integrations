import { CopilotConfigurationError } from "./errors.js";
import { validateToolFilters } from "./governance/index.js";
import { parseMessageSource } from "./message-source.js";
import { toSdkTools } from "./tools/index.js";
import type { CopilotAgentDefinition } from "./types.js";

/** Agent names are part of the session identity. */
export const AGENT_NAME_PATTERN = /^[a-zA-Z0-9][a-zA-Z0-9._-]{0,63}$/;

const validated = new WeakSet<CopilotAgentDefinition>();

/**
 * Validates an agent definition and returns a frozen copy.
 *
 * Fails fast with `CopilotConfigurationError` on an invalid name, malformed instructions, invalid or
 * duplicate tools, and conflicting or self-hiding tool filters.
 */
export function defineAgent(def: CopilotAgentDefinition): CopilotAgentDefinition {
  if (validated.has(def)) return def;
  if (!def || typeof def !== "object") {
    throw new CopilotConfigurationError("Agent definition must be an object");
  }
  const name = def.name;
  if (typeof name !== "string" || !AGENT_NAME_PATTERN.test(name)) {
    throw new CopilotConfigurationError(
      `Invalid agent name ${JSON.stringify(name)}: agent names must match ${AGENT_NAME_PATTERN.source}`,
    );
  }
  const where = `Agent "${name}"`;
  validateInstructions(where, def.instructions);
  if (def.model !== undefined && (typeof def.model !== "string" || def.model.trim() === "")) {
    throw new CopilotConfigurationError(`${where}: model must be a non-blank string`);
  }
  if (def.reasoningEffort !== undefined && !["low", "medium", "high", "xhigh"].includes(def.reasoningEffort)) {
    throw new CopilotConfigurationError(`${where}: invalid reasoningEffort ${JSON.stringify(def.reasoningEffort)}`);
  }
  if (def.tools !== undefined && !Array.isArray(def.tools)) {
    throw new CopilotConfigurationError(`${where}: tools must be an array`);
  }
  for (const key of ["permissions", "configureSession"] as const) {
    if (def[key] !== undefined && typeof def[key] !== "function") {
      throw new CopilotConfigurationError(`${where}: ${key} must be a function`);
    }
  }
  if (def.hooks !== undefined && (def.hooks === null || typeof def.hooks !== "object")) {
    throw new CopilotConfigurationError(`${where}: hooks must be an object`);
  }
  if (def.mcpServers !== undefined && (def.mcpServers === null || typeof def.mcpServers !== "object")) {
    throw new CopilotConfigurationError(`${where}: mcpServers must be an object keyed by server name`);
  }
  try {
    parseMessageSource(def.messageSource);
  } catch (error) {
    throw new CopilotConfigurationError(`${where}: messageSource: ${messageOf(error)}`, { cause: error });
  }
  if (def.availableTools !== undefined && def.excludedTools !== undefined) {
    throw new CopilotConfigurationError(`${where}: availableTools and excludedTools are mutually exclusive`);
  }
  validateToolSearch(where, def.toolSearch);

  let customToolNames: Set<string>;
  try {
    customToolNames = toSdkTools(def.tools ?? []).customToolNames;
  } catch (error) {
    if (error instanceof CopilotConfigurationError) {
      throw new CopilotConfigurationError(`${where}: ${error.message}`, { cause: error });
    }
    throw error;
  }
  validateToolFilters(def, { customToolNames });

  const copy: CopilotAgentDefinition = { ...def };
  if (def.messageSource === ("" as unknown)) delete copy.messageSource;
  if (def.tools) copy.tools = Object.freeze([...def.tools]) as CopilotAgentDefinition["tools"];
  if (def.availableTools) copy.availableTools = Object.freeze([...def.availableTools]) as string[];
  if (def.excludedTools) copy.excludedTools = Object.freeze([...def.excludedTools]) as string[];
  if (def.toolSearch) copy.toolSearch = Object.freeze({ ...def.toolSearch });
  const frozen = Object.freeze(copy);
  validated.add(frozen);
  return frozen;
}

function validateToolSearch(where: string, toolSearch: unknown): void {
  if (toolSearch === undefined) return;
  if (toolSearch === null || typeof toolSearch !== "object" || Array.isArray(toolSearch)) {
    throw new CopilotConfigurationError(`${where}: toolSearch must be an object`);
  }
  const { enabled, deferThreshold } = toolSearch as { enabled?: unknown; deferThreshold?: unknown };
  if (enabled !== undefined && typeof enabled !== "boolean") {
    throw new CopilotConfigurationError(`${where}: toolSearch.enabled must be a boolean`);
  }
  if (deferThreshold !== undefined && (typeof deferThreshold !== "number" || !Number.isInteger(deferThreshold) || deferThreshold < 1)) {
    throw new CopilotConfigurationError(`${where}: toolSearch.deferThreshold must be an integer >= 1`);
  }
  if (enabled === false && deferThreshold !== undefined) {
    throw new CopilotConfigurationError(`${where}: toolSearch.deferThreshold has no effect when toolSearch.enabled is false`);
  }
}

function validateInstructions(where: string, instructions: unknown): void {
  if (instructions === undefined || typeof instructions === "string") return;
  if (!instructions || typeof instructions !== "object") {
    throw new CopilotConfigurationError(`${where}: instructions must be a string or { mode, content }`);
  }
  const { mode, content } = instructions as { mode?: unknown; content?: unknown };
  if (mode !== undefined && mode !== "append" && mode !== "replace" && mode !== "customize") {
    throw new CopilotConfigurationError(
      `${where}: instructions.mode must be "append", "replace", or "customize" (got ${JSON.stringify(mode)})`,
    );
  }
  if (content !== undefined && typeof content !== "string") {
    throw new CopilotConfigurationError(`${where}: instructions.content must be a string`);
  }
  if (mode === "replace" && (typeof content !== "string" || content.trim() === "")) {
    throw new CopilotConfigurationError(`${where}: instructions with mode "replace" require non-blank content`);
  }
}

function messageOf(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}
