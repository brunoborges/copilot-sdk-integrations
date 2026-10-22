import type { SessionConfig } from "@github/copilot-sdk";
import { CopilotConfigurationError } from "../errors.js";
import type { CopilotAgentDefinition } from "../types.js";

/** Excludes every built-in runtime tool (SDK `ToolSet` syntax). */
export const ALL_BUILT_IN_TOOLS = "builtin:*";

/** SDK ToolSet syntax: a bare name, or `builtin:`/`custom:`/`mcp:` followed by a name or `*`. */
const FILTER_ENTRY = /^(?:(builtin|custom|mcp):(\*|[a-zA-Z0-9_-]+)|([a-zA-Z0-9_-]+))$/;

interface ParsedEntry {
  source?: "builtin" | "custom" | "mcp";
  name: string;
}

function parse(entry: unknown, agent: string, list: string): ParsedEntry {
  const match = typeof entry === "string" ? FILTER_ENTRY.exec(entry) : null;
  if (!match) {
    throw new CopilotConfigurationError(
      `Copilot agent "${agent}": invalid ${list} entry ${JSON.stringify(entry)}; use a tool name or ` +
        `"builtin:<name>", "custom:<name>", "mcp:<name>" (or "*" as the name)`,
    );
  }
  return match[1] ? { source: match[1] as ParsedEntry["source"], name: match[2]! } : { name: match[3]! };
}

/**
 * Validates an agent's tool filters at definition time. Fails when:
 * - an entry is not valid SDK `ToolSet` syntax;
 * - `availableTools` and a non-empty `excludedTools` are both set (Spring parity: the integration
 *   rejects ambiguous configurations rather than relying on runtime precedence);
 * - `availableTools` names `builtin:` tools while `builtInTools` is not `true` (built-ins are excluded);
 * - `availableTools` hides every one of the agent's own tools (no `custom:*`, `custom:<name>`, or bare
 *   name matching one of them);
 * - `availableTools` hides every MCP tool while the agent has `mcpServers` (no `mcp:` entry and no bare
 *   `<server>-<tool>` name for a configured server).
 *
 * The integration never widens an explicit allow-list.
 */
export function validateToolFilters(
  def: CopilotAgentDefinition,
  converted: { customToolNames: ReadonlySet<string> },
): void {
  const agent = def.name;
  const { availableTools, excludedTools } = def;
  if (availableTools !== undefined && !Array.isArray(availableTools)) {
    throw new CopilotConfigurationError(`Copilot agent "${agent}": availableTools must be an array of strings`);
  }
  if (excludedTools !== undefined && !Array.isArray(excludedTools)) {
    throw new CopilotConfigurationError(`Copilot agent "${agent}": excludedTools must be an array of strings`);
  }
  const available = availableTools?.map((entry) => parse(entry, agent, "availableTools"));
  excludedTools?.forEach((entry) => parse(entry, agent, "excludedTools"));

  if (available === undefined) return;

  if (excludedTools !== undefined && excludedTools.length > 0) {
    throw new CopilotConfigurationError(
      `Copilot agent "${agent}": availableTools and excludedTools are mutually exclusive; ` +
        `express the allow-list only`,
    );
  }
  if (def.builtInTools !== true && available.some((entry) => entry.source === "builtin")) {
    throw new CopilotConfigurationError(
      `Copilot agent "${agent}": availableTools lists built-in tools but builtInTools is not enabled; ` +
        `set builtInTools: true`,
    );
  }
  const customTools = [...converted.customToolNames];
  if (customTools.length > 0) {
    const visible = customTools.some((tool) =>
      available.some(
        (entry) =>
          (entry.source === "custom" && (entry.name === "*" || entry.name === tool)) ||
          (entry.source === undefined && entry.name === tool),
      ),
    );
    if (!visible) {
      throw new CopilotConfigurationError(
        `Copilot agent "${agent}": availableTools hides every tool declared by the agent; add "custom:*" ` +
          `or specific "custom:<tool>" entries, or remove the agent's tools`,
      );
    }
  }
  const servers = Object.keys(def.mcpServers ?? {});
  if (servers.length > 0) {
    const visible = available.some(
      (entry) =>
        entry.source === "mcp" ||
        (entry.source === undefined && servers.some((server) => entry.name.startsWith(`${server}-`))),
    );
    if (!visible) {
      throw new CopilotConfigurationError(
        `Copilot agent "${agent}": availableTools hides every MCP server tool; add "mcp:*" or specific ` +
          `"mcp:<server>-<tool>" entries, or remove the agent's mcpServers`,
      );
    }
  }
}

/**
 * Translates the agent's tool filters into SDK `SessionConfig` fields:
 *
 * | Agent definition                          | SessionConfig                                   |
 * | ----------------------------------------- | ----------------------------------------------- |
 * | `availableTools: [...]`                   | `availableTools: [...]` (copied verbatim)       |
 * | `excludedTools: [...]`                    | `excludedTools: [...]`                          |
 * | `builtInTools` false/unset (default)      | `"builtin:*"` appended to `excludedTools`       |
 * | `builtInTools: true`                      | nothing added (runtime defaults)                |
 *
 * The SDK composes the lists (a tool is enabled when it matches `availableTools`, or that is unset, and
 * does not match `excludedTools`), so excluding `builtin:*` hides every built-in tool while keeping
 * custom and MCP tools, with or without an allow-list. Fields are omitted when empty/unset.
 */
export function buildToolFilterConfig(
  def: CopilotAgentDefinition,
): Pick<SessionConfig, "availableTools" | "excludedTools"> {
  const config: Pick<SessionConfig, "availableTools" | "excludedTools"> = {};
  if (def.availableTools !== undefined) {
    config.availableTools = [...def.availableTools];
  }
  const excluded = [...(def.excludedTools ?? [])];
  if (def.builtInTools !== true && !excluded.includes(ALL_BUILT_IN_TOOLS)) {
    excluded.push(ALL_BUILT_IN_TOOLS);
  }
  if (excluded.length > 0) {
    config.excludedTools = excluded;
  }
  return config;
}
