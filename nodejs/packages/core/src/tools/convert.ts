import { prettifyError } from "zod";
import type { Tool, ToolInvocation, ToolResultObject } from "@github/copilot-sdk";
import { getCopilotContext, runWithCopilotContext } from "../context.js";
import { CopilotConfigurationError } from "../errors.js";
import type { AiSdkToolLike, CopilotContext, CopilotToolDefinition, ToolCallInfo, ToolInput } from "../types.js";
import { isObjectLike, isStructuredSchema, messageOf, toJsonSchema } from "./schema.js";

/**
 * Tool name rule. The SDK's `ToolSet` accepts `^[a-zA-Z0-9_-]+$`; model providers additionally cap
 * function names at 64 characters, so names are validated against both.
 */
const TOOL_NAME = /^[a-zA-Z0-9_-]{1,64}$/;

/** Decides whether a tool call needs human approval (from AI SDK `needsApproval`). */
export type ApprovalRequirement =
  | boolean
  | ((input: unknown, options?: { toolCallId?: string }) => boolean | Promise<boolean>);

export interface ConvertedTools {
  tools: Tool[];
  customToolNames: Set<string>;
  approvalRequired: Map<string, ApprovalRequirement>;
}

export interface ToSdkToolsOptions {
  /** Context for tool calls. Defaults to the AsyncLocalStorage context, then `{ attributes: {} }`. */
  getContext?: () => CopilotContext | undefined;
}

/** Throws `CopilotConfigurationError` unless `name` is a valid tool name. */
export function validateToolName(name: string): void {
  if (typeof name !== "string" || !TOOL_NAME.test(name)) {
    throw new CopilotConfigurationError(
      `Invalid tool name ${JSON.stringify(name)}: tool names must match ${TOOL_NAME.source}`,
    );
  }
}

/** Identity helper that validates a native tool definition and preserves its input type. */
export function defineCopilotTool<T>(def: CopilotToolDefinition<T>): CopilotToolDefinition<T> {
  if (!isObjectLike(def)) {
    throw new CopilotConfigurationError("Tool definition must be an object");
  }
  validateToolName(def.name);
  if (typeof def.execute !== "function") {
    throw new CopilotConfigurationError(`Tool "${def.name}" must have an execute function`);
  }
  if (def.description !== undefined && typeof def.description !== "string") {
    throw new CopilotConfigurationError(`Tool "${def.name}" description must be a string`);
  }
  return def;
}

/**
 * Converts every accepted tool shape into SDK `Tool`s.
 *
 * - `CopilotToolDefinition` (`name` + `execute`): wrapped with argument validation, context, and result conversion.
 * - SDK `Tool` (`name`, optional `handler`): passed through unchanged.
 * - Record of AI SDK `tool()`s: each entry becomes an SDK tool named by its key; entries without
 *   `execute` become declaration-only tools (no handler); `needsApproval` is reported in `approvalRequired`.
 *
 * Tool handlers never throw: validation failures and execution errors become `resultType: "failure"`
 * results so the model can see the error and retry.
 */
export function toSdkTools(inputs: ToolInput[] | undefined, options: ToSdkToolsOptions = {}): ConvertedTools {
  const result: ConvertedTools = { tools: [], customToolNames: new Set(), approvalRequired: new Map() };
  const add = (tool: Tool) => {
    validateToolName(tool.name);
    if (result.customToolNames.has(tool.name)) {
      throw new CopilotConfigurationError(`Duplicate tool name "${tool.name}"`);
    }
    result.customToolNames.add(tool.name);
    result.tools.push(tool);
  };
  const resolveContext = () => options.getContext?.() ?? getCopilotContext() ?? { attributes: {} };

  for (const input of inputs ?? []) {
    if (!isObjectLike(input) || Array.isArray(input)) {
      throw new CopilotConfigurationError(`Invalid tool: expected an object, got ${describe(input)}`);
    }
    const candidate = input as Record<string, unknown>;
    if (typeof candidate.name === "string") {
      if (typeof candidate.execute === "function") {
        add(fromCopilotTool(defineCopilotTool(input as CopilotToolDefinition), resolveContext));
      } else if (candidate.handler === undefined || typeof candidate.handler === "function") {
        add(input as Tool);
      } else {
        throw new CopilotConfigurationError(`Tool "${candidate.name}" has a non-function handler`);
      }
      continue;
    }
    for (const [name, aiTool] of Object.entries(input as Record<string, AiSdkToolLike>)) {
      validateToolName(name);
      add(fromAiSdkTool(name, aiTool, resolveContext));
      if (aiTool.needsApproval !== undefined && aiTool.needsApproval !== false) {
        const needsApproval = aiTool.needsApproval;
        result.approvalRequired.set(
          name,
          typeof needsApproval === "function"
            ? async (value, opts) =>
                await needsApproval(value, { toolCallId: opts?.toolCallId ?? "", messages: [], context: resolveContext() })
            : needsApproval,
        );
      }
    }
  }
  return result;
}

/**
 * AI SDK 7 allows `description` to be a function of the tool context. The Copilot SDK needs a
 * static string at session creation, so it is resolved once with the context current at conversion.
 */
function resolveDescription(
  description: AiSdkToolLike["description"],
  resolveContext: () => CopilotContext,
): string | undefined {
  if (typeof description !== "function") return description;
  try {
    const value = description({ context: resolveContext() });
    return typeof value === "string" ? value : undefined;
  } catch {
    return undefined;
  }
}

// ---------------------------------------------------------------------------
// Converters
// ---------------------------------------------------------------------------

type Validation = { ok: true; value: unknown } | { ok: false; message: string };
type Validator = (value: unknown) => Promise<Validation>;

interface ResolvedSchema {
  parameters: Record<string, unknown>;
  validate?: Validator;
}

function fromCopilotTool(def: CopilotToolDefinition, resolveContext: () => CopilotContext): Tool {
  const schema = resolveStructured(def.input, def.name);
  const tool: Tool = {
    name: def.name,
    description: def.description,
    parameters: schema.parameters,
    handler: createHandler(def.name, schema.validate, resolveContext, (value, info) => def.execute(value, info)),
  };
  if (def.skipPermission !== undefined) tool.skipPermission = def.skipPermission;
  if (def.overridesBuiltInTool !== undefined) tool.overridesBuiltInTool = def.overridesBuiltInTool;
  return tool;
}

function fromAiSdkTool(name: string, aiTool: AiSdkToolLike, resolveContext: () => CopilotContext): Tool {
  if (!isObjectLike(aiTool) || Array.isArray(aiTool)) {
    throw new CopilotConfigurationError(`AI SDK tool "${name}" must be an object, got ${describe(aiTool)}`);
  }
  const type = (aiTool as { type?: unknown }).type;
  if (type === "provider" || type === "provider-defined") {
    throw new CopilotConfigurationError(
      `AI SDK tool "${name}" is provider-defined and cannot run as a Copilot tool`,
    );
  }
  if (aiTool.execute !== undefined && typeof aiTool.execute !== "function") {
    throw new CopilotConfigurationError(`AI SDK tool "${name}" has a non-function execute`);
  }
  const schema = resolveAiSdkSchema(aiTool.inputSchema, name);
  const tool: Tool = { name, parameters: schema.parameters };
  const description = resolveDescription(aiTool.description, resolveContext);
  if (description !== undefined) tool.description = description;
  const execute = aiTool.execute;
  if (execute) {
    tool.handler = createHandler(name, schema.validate, resolveContext, (value, info) => {
      const callOptions = {
        toolCallId: info.toolCallId,
        abortSignal: info.signal,
        messages: [] as unknown[],
        context: info.context,
      };
      return execute(value, callOptions);
    });
  }
  return tool;
}

function createHandler(
  toolName: string,
  validate: Validator | undefined,
  resolveContext: () => CopilotContext,
  execute: (value: unknown, info: ToolCallInfo) => unknown,
): NonNullable<Tool["handler"]> {
  return async (args: unknown, invocation: ToolInvocation) => {
    const validation = validate ? await validate(args) : ({ ok: true, value: args } as const);
    if (!validation.ok) {
      return failure(`Invalid arguments for tool "${toolName}": ${validation.message}`, validation.message);
    }
    const context = resolveContext();
    const info: ToolCallInfo = {
      toolCallId: invocation.toolCallId,
      toolName: invocation.toolName ?? toolName,
      sessionId: invocation.sessionId,
      signal: invocation.signal,
      context,
    };
    try {
      const raw = await runWithCopilotContext(context, () => execute(validation.value, info));
      return await toToolResult(raw);
    } catch (error) {
      const message = messageOf(error);
      return failure(`Tool "${toolName}" failed: ${message}`, message);
    }
  };
}

function resolveStructured(
  schema: CopilotToolDefinition["input"],
  toolName: string,
): ResolvedSchema {
  if (schema !== undefined && isStructuredSchema(schema)) {
    return { parameters: convert(schema, toolName), validate: parseValidator(schema) };
  }
  return { parameters: convert(schema, toolName) };
}

function resolveAiSdkSchema(schema: unknown, toolName: string, depth = 0): ResolvedSchema {
  if (schema === undefined || schema === null) {
    return { parameters: toJsonSchema(undefined) };
  }
  // `lazySchema(() => schema)` from the AI SDK.
  if (typeof schema === "function" && !("_zod" in schema)) {
    if (depth > 3) throw new CopilotConfigurationError(`AI SDK tool "${toolName}" has an unresolvable inputSchema`);
    return resolveAiSdkSchema((schema as () => unknown)(), toolName, depth + 1);
  }
  if (!isObjectLike(schema)) {
    throw new CopilotConfigurationError(`AI SDK tool "${toolName}" inputSchema must be an object`);
  }
  if ("_zod" in schema || isStructuredSchema(schema)) {
    return resolveStructured(schema as CopilotToolDefinition["input"], toolName);
  }
  // `jsonSchema(schema, { validate })` wrapper from the AI SDK.
  if ("jsonSchema" in schema && (Symbol.for("vercel.ai.schema") in schema || "validate" in schema)) {
    const wrapper = schema as {
      jsonSchema: unknown;
      validate?: (value: unknown) => unknown;
    };
    const json = wrapper.jsonSchema;
    if (isPromiseLike(json)) {
      throw new CopilotConfigurationError(
        `AI SDK tool "${toolName}" has an asynchronous JSON Schema, which is not supported`,
      );
    }
    const parameters = convert(json as Record<string, unknown>, toolName);
    const validate = wrapper.validate;
    if (typeof validate !== "function") return { parameters };
    return {
      parameters,
      validate: async (value) => {
        try {
          const outcome = (await validate(value)) as { success: boolean; value?: unknown; error?: unknown };
          return outcome?.success
            ? { ok: true, value: "value" in outcome ? outcome.value : value }
            : { ok: false, message: validationMessage(outcome?.error) };
        } catch (error) {
          return { ok: false, message: validationMessage(error) };
        }
      },
    };
  }
  // Standard Schema (Valibot, ArkType, ...).
  if ("~standard" in schema) {
    const standard = (schema as { "~standard": StandardSchemaProps })["~standard"];
    const toJson = standard.jsonSchema?.input;
    if (typeof toJson !== "function") {
      throw new CopilotConfigurationError(
        `AI SDK tool "${toolName}" uses a Standard Schema (${standard.vendor}) without JSON Schema support`,
      );
    }
    return {
      parameters: convert(toJson({ target: "draft-07" }), toolName),
      validate: async (value) => {
        try {
          const outcome = (await standard.validate(value)) as
            | { value: unknown; issues?: undefined }
            | { issues: ReadonlyArray<{ message: string }> };
          return outcome.issues
            ? { ok: false, message: outcome.issues.map((issue) => issue.message).join("; ") }
            : { ok: true, value: (outcome as { value: unknown }).value };
        } catch (error) {
          return { ok: false, message: validationMessage(error) };
        }
      },
    };
  }
  return { parameters: convert(schema as Record<string, unknown>, toolName) };
}

interface StandardSchemaProps {
  vendor: string;
  validate(value: unknown): { value: unknown } | { issues: ReadonlyArray<{ message: string }> } | Promise<unknown>;
  jsonSchema?: { input?: (options: { target: string }) => Record<string, unknown> };
}

function convert(schema: unknown, toolName: string): Record<string, unknown> {
  try {
    return toJsonSchema(schema as Record<string, unknown> | undefined);
  } catch (error) {
    throw new CopilotConfigurationError(`Tool "${toolName}": ${messageOf(error)}`, { cause: error });
  }
}

function parseValidator(schema: { parse(value: unknown): unknown; parseAsync?: unknown }): Validator {
  return async (value) => {
    try {
      const parsed =
        typeof schema.parseAsync === "function"
          ? await (schema.parseAsync as (v: unknown) => Promise<unknown>).call(schema, value)
          : schema.parse(value);
      return { ok: true, value: parsed };
    } catch (error) {
      return { ok: false, message: validationMessage(error) };
    }
  };
}

function validationMessage(error: unknown): string {
  if (isObjectLike(error) && Array.isArray((error as { issues?: unknown }).issues)) {
    try {
      return prettifyError(error as never);
    } catch {
      // fall through
    }
  }
  return error === undefined ? "validation failed" : messageOf(error);
}

// ---------------------------------------------------------------------------
// Results
// ---------------------------------------------------------------------------

const RESULT_TYPES = new Set(["success", "failure", "rejected", "denied", "timeout"]);

export function isToolResultObject(value: unknown): value is ToolResultObject {
  return (
    isObjectLike(value) &&
    typeof (value as ToolResultObject).textResultForLlm === "string" &&
    RESULT_TYPES.has((value as ToolResultObject).resultType)
  );
}

async function toToolResult(raw: unknown): Promise<string | ToolResultObject> {
  let value = raw;
  // Streaming AI SDK tools yield preliminary results; the last one is final.
  if (isObjectLike(value) && !isToolResultObject(value) && Symbol.asyncIterator in value) {
    let last: unknown;
    for await (const item of value as AsyncIterable<unknown>) last = item;
    value = last;
  }
  if (value === undefined || value === null) return "";
  if (typeof value === "string") return value;
  if (isToolResultObject(value)) return value;
  try {
    return JSON.stringify(value) ?? String(value);
  } catch {
    return String(value);
  }
}

function failure(text: string, error: string): ToolResultObject {
  return { textResultForLlm: text, resultType: "failure", error };
}

function isPromiseLike(value: unknown): value is PromiseLike<unknown> {
  return isObjectLike(value) && typeof (value as { then?: unknown }).then === "function";
}

function describe(value: unknown): string {
  if (value === null) return "null";
  if (Array.isArray(value)) return "array";
  return typeof value;
}
