import type {
  JSONObject,
  LanguageModelV4,
  LanguageModelV4CallOptions,
  LanguageModelV4Content,
  LanguageModelV4FinishReason,
  LanguageModelV4GenerateResult,
  LanguageModelV4StreamPart,
  LanguageModelV4StreamResult,
  LanguageModelV4Usage,
  ProviderV4,
  SharedV4Warning,
} from "@ai-sdk/provider";
import { NoSuchModelError, ToolLoopAgent, UnsupportedFunctionalityError, type ToolLoopAgentSettings, type UIMessageChunk } from "ai";
import type {
  AgentHandle,
  Copilot,
  CopilotAgentDefinition,
  CopilotAgentEvent,
  CopilotContext,
  SendOptions,
  StructuredSchema,
  ToolInput,
} from "@copilot-sdk-integrations/agent-core";
import { toCopilotPrompt } from "./prompt.js";
import { UIChunkMapper, type CopilotUsage } from "./ui-stream.js";

/** Per-model settings. */
export interface CopilotModelSettings {
  /** Agent definition backing the model. Default: `{ name: "ai-sdk" }` (deny-all permissions, no tools). */
  agent?: CopilotAgentDefinition;
  /**
   * Default conversation key. With a conversation, Copilot keeps the history server-side and only
   * the latest user messages of the AI SDK prompt are sent. Override per call with
   * `providerOptions: { copilot: { conversationId } }`.
   */
  conversationId?: string;
  /** Principal for session-id derivation and context. Override per call with `providerOptions.copilot.principal`. */
  principal?: string;
  /** Extra Copilot tools (core tool inputs, including AI SDK `tool()` records) for every call. */
  tools?: ToolInput[];
  /** Merged into the core request context. */
  context?: Partial<CopilotContext>;
  /** Per-turn timeout in ms. */
  timeout?: number;
  /**
   * Answers "ask-user" permissions (including AI SDK tools with `needsApproval`).
   * Default: deny immediately.
   */
  onPermissionRequest?: SendOptions["onPermissionRequest"];
}

export interface CopilotProviderSettings extends CopilotModelSettings {
  copilot: Copilot;
}

/** Per-call options read from `providerOptions.copilot`. */
export interface CopilotProviderOptions {
  conversationId?: string;
  principal?: string;
}

export interface CopilotProvider extends ProviderV4 {
  /** Same as `languageModel`. */
  (modelId?: string, settings?: CopilotModelSettings): LanguageModelV4;
  /**
   * A language model backed by the provider's agent. `modelId` overrides the agent's model;
   * `"default"` (or omitted) keeps it.
   */
  languageModel(modelId?: string, settings?: CopilotModelSettings): LanguageModelV4;
  /** A language model backed by `definition` (its own `model`). */
  agent(definition: CopilotAgentDefinition, settings?: Omit<CopilotModelSettings, "agent">): LanguageModelV4;
}

const DEFAULT_AGENT: CopilotAgentDefinition = { name: "ai-sdk" };
const UNSUPPORTED_SETTINGS = [
  "maxOutputTokens",
  "temperature",
  "stopSequences",
  "topP",
  "topK",
  "presencePenalty",
  "frequencyPenalty",
  "seed",
] as const;

/**
 * An AI SDK `LanguageModelV4` that runs Copilot agent turns.
 *
 * Copilot executes tools itself (agent tools, MCP servers, built-ins); they are reported as
 * provider-executed dynamic `tool-call`/`tool-result` parts. Tools passed to `generateText` /
 * `streamText` are rejected: give them to the agent definition (or `settings.tools`) instead.
 */
export class CopilotLanguageModel implements LanguageModelV4 {
  readonly specificationVersion = "v4" as const;
  readonly provider = "copilot";
  readonly modelId: string;
  readonly supportedUrls: Record<string, RegExp[]> = {};

  constructor(
    private readonly copilot: Copilot,
    modelId: string,
    private readonly settings: CopilotModelSettings = {},
  ) {
    this.modelId = modelId;
  }

  async doGenerate(options: LanguageModelV4CallOptions): Promise<LanguageModelV4GenerateResult> {
    const { stream } = await this.doStream(options);
    const content: LanguageModelV4Content[] = [];
    const open = new Map<string, { type: "text" | "reasoning"; text: string }>();
    let warnings: SharedV4Warning[] = [];
    let finishReason: LanguageModelV4FinishReason = { unified: "other", raw: undefined };
    let usage: LanguageModelV4Usage = toUsage({});
    let providerMetadata: LanguageModelV4GenerateResult["providerMetadata"];
    let modelId: string | undefined;
    let responseId: string | undefined;
    const reader = stream.getReader();
    for (;;) {
      const { value: part, done } = await reader.read();
      if (done) break;
      switch (part.type) {
        case "stream-start":
          warnings = part.warnings;
          break;
        case "response-metadata":
          responseId = part.id;
          modelId = part.modelId;
          break;
        case "text-start":
        case "reasoning-start": {
          const entry = { type: part.type === "text-start" ? ("text" as const) : ("reasoning" as const), text: "" };
          open.set(part.id, entry);
          content.push(entry as LanguageModelV4Content);
          break;
        }
        case "text-delta":
        case "reasoning-delta": {
          const entry = open.get(part.id);
          if (entry) entry.text += part.delta;
          break;
        }
        case "text-end":
        case "reasoning-end":
          open.delete(part.id);
          break;
        case "tool-call":
        case "tool-result":
          content.push(part);
          break;
        case "error":
          reader.releaseLock();
          throw part.error;
        case "finish":
          finishReason = part.finishReason;
          usage = part.usage;
          providerMetadata = part.providerMetadata;
          break;
      }
    }
    const result: LanguageModelV4GenerateResult = { content, finishReason, usage, warnings };
    if (providerMetadata) result.providerMetadata = providerMetadata;
    if (responseId !== undefined || modelId !== undefined) {
      result.response = {};
      if (responseId !== undefined) result.response.id = responseId;
      if (modelId !== undefined) result.response.modelId = modelId;
    }
    return result;
  }

  async doStream(options: LanguageModelV4CallOptions): Promise<LanguageModelV4StreamResult> {
    const call = this.prepare(options);
    const controller = new AbortController();
    const signal = options.abortSignal ? AbortSignal.any([options.abortSignal, controller.signal]) : controller.signal;
    const send: Omit<SendOptions, "schema"> = { prompt: call.prompt, signal };
    if (call.conversationId !== undefined) send.conversationId = call.conversationId;
    if (this.settings.tools !== undefined) send.tools = this.settings.tools;
    if (call.context !== undefined) send.context = call.context;
    if (this.settings.timeout !== undefined) send.timeout = this.settings.timeout;

    if (call.schema) {
      return { stream: structuredStream(call.handle, { ...send, schema: call.schema }, call.warnings) };
    }

    const iterator = call.handle.stream(send)[Symbol.asyncIterator]();
    const mapper = new UIChunkMapper({ dynamicTools: true });
    const toolNames = new Map<string, string>();
    const onPermission = this.settings.onPermissionRequest;
    let finished = false;
    const stream = new ReadableStream<LanguageModelV4StreamPart>({
      start(c) {
        c.enqueue({ type: "stream-start", warnings: call.warnings });
      },
      async pull(c) {
        for (;;) {
          const { value: event, done } = await iterator.next();
          if (done) {
            finished = true;
            for (const chunk of mapper.finalize()) enqueueAll(c, toStreamParts(chunk, toolNames));
            const failed = mapper.error !== undefined;
            const finish: LanguageModelV4StreamPart = {
              type: "finish",
              finishReason: { unified: failed ? "error" : "stop", raw: undefined },
              usage: toUsage(mapper.usage, mapper.model),
            };
            if (mapper.sessionId !== undefined) finish.providerMetadata = { copilot: { sessionId: mapper.sessionId } };
            c.enqueue(finish);
            c.close();
            return;
          }
          const parts = handleEvent(event);
          enqueueAll(c, parts);
          if (parts.length > 0) return;
        }
      },
      async cancel() {
        if (finished) return;
        controller.abort();
        await iterator.return?.();
      },
    });

    const modelId = call.model;
    function handleEvent(event: CopilotAgentEvent): LanguageModelV4StreamPart[] {
      switch (event.type) {
        case "permission-request": {
          const respond = event.respond;
          if (!onPermission) {
            respond({ approved: false });
          } else {
            Promise.resolve()
              .then(() => onPermission(event.request))
              .then(respond, () => respond({ approved: false }));
          }
          return [];
        }
        case "permission-resolved":
          return [];
        case "turn-start": {
          mapper.map(event);
          const meta: LanguageModelV4StreamPart = { type: "response-metadata", id: event.sessionId };
          if (modelId !== undefined) meta.modelId = modelId;
          return [meta];
        }
        case "error": {
          const parts = mapper.map(event).chunks.flatMap((chunk) => toStreamParts(chunk, toolNames));
          return [...parts, { type: "error", error: event.error }];
        }
        default:
          return mapper.map(event).chunks.flatMap((chunk) => toStreamParts(chunk, toolNames));
      }
    }

    return { stream, request: { body: { prompt: call.prompt, conversationId: call.conversationId } } };
  }

  private prepare(options: LanguageModelV4CallOptions) {
    const warnings: SharedV4Warning[] = [];
    for (const setting of UNSUPPORTED_SETTINGS) {
      if (options[setting] !== undefined) warnings.push({ type: "unsupported", feature: setting });
    }
    if (options.tools && options.tools.length > 0) {
      if (options.toolChoice?.type === "none") {
        warnings.push({ type: "unsupported", feature: "tools", details: "AI SDK tools are ignored by the Copilot provider." });
      } else {
        throw new UnsupportedFunctionalityError({
          functionality: "AI SDK tools",
          message:
            "The Copilot provider runs tools inside the Copilot runtime. Pass AI SDK tool() objects to the agent " +
            "definition (defineAgent({ tools: [{ name: tool(...) }] })) or the model settings (tools), not to generateText/streamText.",
        });
      }
    }

    const copilotOptions = (options.providerOptions?.copilot ?? {}) as JSONObject;
    const conversationId = stringOption(copilotOptions.conversationId) ?? this.settings.conversationId;
    const principal = stringOption(copilotOptions.principal) ?? this.settings.principal;

    const converted = toCopilotPrompt(options.prompt, conversationId !== undefined ? "latest" : "transcript");
    warnings.push(...converted.warnings);
    let prompt = converted.prompt;

    let definition: CopilotAgentDefinition = { ...(this.settings.agent ?? DEFAULT_AGENT) };
    if (this.modelId !== "" && this.modelId !== "default") definition.model = this.modelId;
    const effort = mapReasoning(options.reasoning, warnings);
    if (effort !== undefined) definition.reasoningEffort = effort;
    if (converted.system !== undefined) {
      const merged = withSystem(definition, converted.system);
      if (merged) definition = merged;
      else prompt = `${converted.system}\n\n${prompt}`;
    }

    let schema: StructuredSchema<unknown> | undefined;
    if (options.responseFormat?.type === "json") {
      const jsonSchema = options.responseFormat.schema;
      if (jsonSchema) {
        schema = { parse: (value: unknown) => value, toJSONSchema: () => jsonSchema as Record<string, unknown> };
      } else {
        warnings.push({ type: "compatibility", feature: "responseFormat", details: "JSON without a schema is requested through the prompt." });
        prompt = `${prompt}\n\nRespond with a single JSON value only.`;
      }
    }

    let context = this.settings.context;
    if (principal !== undefined) context = { ...context, principal };

    return {
      handle: this.copilot.agent(definition),
      prompt,
      conversationId,
      context,
      schema,
      warnings,
      model: definition.model,
    };
  }
}

/**
 * Creates the Copilot AI SDK provider.
 *
 * ```ts
 * const copilotProvider = createCopilotProvider({ copilot, agent: advisor });
 * const { text } = await generateText({ model: copilotProvider("gpt-5"), prompt: "What is libuv?" });
 * ```
 */
export function createCopilotProvider(settings: CopilotProviderSettings): CopilotProvider {
  const { copilot: instance, ...defaults } = settings;
  const languageModel = (modelId = "default", modelSettings: CopilotModelSettings = {}) =>
    new CopilotLanguageModel(instance, modelId, { ...defaults, ...modelSettings });
  const provider = ((modelId?: string, modelSettings?: CopilotModelSettings) => languageModel(modelId, modelSettings)) as unknown as CopilotProvider;
  Object.assign(provider, {
    specificationVersion: "v4" as const,
    languageModel,
    agent: (definition: CopilotAgentDefinition, modelSettings: Omit<CopilotModelSettings, "agent"> = {}) =>
      languageModel("default", { ...modelSettings, agent: definition }),
    embeddingModel(modelId: string): never {
      throw new NoSuchModelError({ modelId, modelType: "embeddingModel", message: "The Copilot provider has no embedding models" });
    },
    imageModel(modelId: string): never {
      throw new NoSuchModelError({ modelId, modelType: "imageModel", message: "The Copilot provider has no image models" });
    },
  });
  return provider;
}

/** The `copilot()` provider factory named in the design; alias of `createCopilotProvider`. */
export const copilot = createCopilotProvider;

export type CopilotAgentSettings = CopilotProviderSettings &
  Omit<ToolLoopAgentSettings, "model" | "tools" | "toolChoice"> & {
    /** Model id override for the agent definition. */
    modelId?: string;
  };

/**
 * An AI SDK `Agent` (a `ToolLoopAgent`) backed by a Copilot agent, for `createAgentUIStreamResponse`
 * and other AI SDK agent APIs. Copilot runs the tool loop itself, so each call is one model step.
 */
export function createCopilotAgent(settings: CopilotAgentSettings): ToolLoopAgent {
  const { copilot: instance, agent, conversationId, principal, tools, context, timeout, onPermissionRequest, modelId, ...agentSettings } =
    settings;
  const modelSettings: CopilotModelSettings = {};
  if (agent !== undefined) modelSettings.agent = agent;
  if (conversationId !== undefined) modelSettings.conversationId = conversationId;
  if (principal !== undefined) modelSettings.principal = principal;
  if (tools !== undefined) modelSettings.tools = tools;
  if (context !== undefined) modelSettings.context = context;
  if (timeout !== undefined) modelSettings.timeout = timeout;
  if (onPermissionRequest !== undefined) modelSettings.onPermissionRequest = onPermissionRequest;
  const loopSettings: ToolLoopAgentSettings = {
    id: agent?.name ?? DEFAULT_AGENT.name,
    ...agentSettings,
    model: new CopilotLanguageModel(instance, modelId ?? "default", modelSettings),
  };
  return new ToolLoopAgent(loopSettings);
}

function structuredStream(
  handle: AgentHandle,
  send: SendOptions<unknown> & { schema: StructuredSchema<unknown> },
  warnings: SharedV4Warning[],
): ReadableStream<LanguageModelV4StreamPart> {
  return new ReadableStream<LanguageModelV4StreamPart>({
    async start(c) {
      c.enqueue({ type: "stream-start", warnings });
      try {
        const value = await handle.send(send);
        const text = JSON.stringify(value);
        c.enqueue({ type: "text-start", id: "0" });
        c.enqueue({ type: "text-delta", id: "0", delta: text });
        c.enqueue({ type: "text-end", id: "0" });
        c.enqueue({ type: "finish", finishReason: { unified: "stop", raw: undefined }, usage: toUsage({}) });
      } catch (error) {
        c.enqueue({ type: "error", error });
        c.enqueue({ type: "finish", finishReason: { unified: "error", raw: undefined }, usage: toUsage({}) });
      }
      c.close();
    },
  });
}

function toStreamParts(chunk: UIMessageChunk, toolNames: Map<string, string>): LanguageModelV4StreamPart[] {
  switch (chunk.type) {
    case "text-start":
    case "text-delta":
    case "text-end":
    case "reasoning-start":
    case "reasoning-delta":
    case "reasoning-end":
      return [chunk];
    case "tool-input-available":
      toolNames.set(chunk.toolCallId, chunk.toolName);
      return [
        {
          type: "tool-call",
          toolCallId: chunk.toolCallId,
          toolName: chunk.toolName,
          input: JSON.stringify(chunk.input ?? {}),
          providerExecuted: true,
          dynamic: true,
        },
      ];
    case "tool-output-available":
      return [toolResult(chunk.toolCallId, toolNames, chunk.output ?? null, false)];
    case "tool-output-error":
      return [toolResult(chunk.toolCallId, toolNames, chunk.errorText, true)];
    case "tool-output-denied":
      return [toolResult(chunk.toolCallId, toolNames, "Permission denied", true)];
    default:
      return [];
  }
}

function toolResult(toolCallId: string, toolNames: Map<string, string>, result: unknown, isError: boolean): LanguageModelV4StreamPart {
  return {
    type: "tool-result",
    toolCallId,
    toolName: toolNames.get(toolCallId) ?? "unknown",
    result: (result ?? "") as NonNullable<JSONObject[string]>,
    dynamic: true,
    ...(isError ? { isError: true } : {}),
  };
}

function enqueueAll<T>(controller: ReadableStreamDefaultController<T>, parts: T[]): void {
  for (const part of parts) controller.enqueue(part);
}

function toUsage(usage: CopilotUsage, model?: string): LanguageModelV4Usage {
  const raw: JSONObject = {};
  if (usage.inputTokens !== undefined) raw.inputTokens = usage.inputTokens;
  if (usage.outputTokens !== undefined) raw.outputTokens = usage.outputTokens;
  if (model !== undefined) raw.model = model;
  return {
    inputTokens: { total: usage.inputTokens, noCache: undefined, cacheRead: undefined, cacheWrite: undefined },
    outputTokens: { total: usage.outputTokens, text: undefined, reasoning: undefined },
    ...(Object.keys(raw).length > 0 ? { raw } : {}),
  };
}

function stringOption(value: unknown): string | undefined {
  return typeof value === "string" && value !== "" ? value : undefined;
}

function mapReasoning(
  reasoning: LanguageModelV4CallOptions["reasoning"],
  warnings: SharedV4Warning[],
): CopilotAgentDefinition["reasoningEffort"] {
  switch (reasoning) {
    case undefined:
    case "provider-default":
      return undefined;
    case "low":
    case "medium":
    case "high":
    case "xhigh":
      return reasoning;
    case "minimal":
      warnings.push({ type: "compatibility", feature: "reasoning", details: "'minimal' is mapped to 'low'." });
      return "low";
    case "max":
      warnings.push({ type: "compatibility", feature: "reasoning", details: "'max' is mapped to 'xhigh'." });
      return "xhigh";
    default:
      warnings.push({ type: "unsupported", feature: "reasoning", details: `'${reasoning}' is not supported; the agent default is used.` });
      return undefined;
  }
}

function withSystem(definition: CopilotAgentDefinition, system: string): CopilotAgentDefinition | undefined {
  const instructions = definition.instructions;
  if (instructions === undefined) return { ...definition, instructions: system };
  if (typeof instructions === "string") return { ...definition, instructions: `${instructions}\n\n${system}` };
  const config = instructions as { mode?: string; content?: string };
  if ((config.mode === "append" || config.mode === "replace" || config.mode === undefined) && typeof config.content === "string") {
    return { ...definition, instructions: { ...instructions, content: `${config.content}\n\n${system}` } as CopilotAgentDefinition["instructions"] };
  }
  return undefined;
}
