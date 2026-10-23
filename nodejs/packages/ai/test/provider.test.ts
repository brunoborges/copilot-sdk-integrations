import { describe, expect, it } from "vitest";
import { generateText, jsonSchema, Output, streamText, tool, type ModelMessage } from "ai";
import { defineAgent, permissions } from "@copilot-sdk-integrations/agent-core";
import { copilot as copilotProvider, createCopilotAgent, createCopilotProvider, toCopilotPrompt } from "../src/index.js";
import { setup } from "./helpers.js";

(globalThis as { AI_SDK_LOG_WARNINGS?: boolean }).AI_SDK_LOG_WARNINGS = false;

const lookupOrder = tool({
  description: "Look up an order",
  inputSchema: jsonSchema<{ id: string }>({ type: "object", properties: { id: { type: "string" } }, required: ["id"] }),
  execute: async ({ id }) => ({ id, status: "shipped" }),
});

describe("copilot provider", () => {
  it("generateText returns text, usage, and the session id", async () => {
    const { copilot, runtime } = setup({
      script: (rt) => rt.onPrompt(/.*/).reply("libuv is an I/O library.").usage({ inputTokens: 12, outputTokens: 6, model: "gpt-test" }),
    });
    const provider = createCopilotProvider({ copilot });
    const result = await generateText({ model: provider(), prompt: "What is libuv?" });
    expect(result.text).toBe("libuv is an I/O library.");
    expect(result.finishReason).toBe("stop");
    expect(result.usage).toMatchObject({ inputTokens: 12, outputTokens: 6, totalTokens: 18 });
    expect(result.providerMetadata?.copilot?.sessionId).toEqual(expect.any(String));
    expect(runtime.prompts).toEqual(["What is libuv?"]);
  });

  it("streamText streams text deltas", async () => {
    const { copilot } = setup({ script: (rt) => rt.onPrompt(/.*/).streamReply(["Hel", "lo"]) });
    const result = streamText({ model: copilotProvider({ copilot }).languageModel(), prompt: "Hi" });
    const deltas: string[] = [];
    for await (const delta of result.textStream) deltas.push(delta);
    expect(deltas.join("")).toBe("Hello");
    expect(await result.text).toBe("Hello");
    expect(await result.finishReason).toBe("stop");
  });

  it("reports Copilot tool calls as provider-executed dynamic tool calls", async () => {
    const { copilot } = setup({ script: (rt) => rt.onPrompt(/.*/).reasoning("Let me check").callTool("lookupOrder", { id: "42" }).thenReply("Shipped.") });
    const provider = createCopilotProvider({
      copilot,
      agent: defineAgent({ name: "support", tools: [{ lookupOrder }], permissions: permissions.allowAll() }),
    });
    const result = streamText({ model: provider(), prompt: "Where is order 42?" });
    const parts: { type: string }[] = [];
    for await (const part of result.fullStream) parts.push(part);
    const kinds = parts.map((p) => p.type);
    expect(kinds).toEqual(expect.arrayContaining(["reasoning-delta", "tool-call", "tool-result", "text-delta", "finish"]));
    expect(parts.find((p) => p.type === "tool-call")).toMatchObject({ toolName: "lookupOrder", input: { id: "42" }, providerExecuted: true, dynamic: true });
    expect(parts.find((p) => p.type === "tool-result")).toMatchObject({ toolName: "lookupOrder", providerExecuted: true });
    expect(await result.text).toBe("Shipped.");
    expect(await result.reasoningText).toBe("Let me check");

    const generated = await generateText({ model: provider(), prompt: "Where is order 42?" });
    expect(generated.text).toBe("Shipped.");
    expect(generated.toolCalls).toMatchObject([{ toolName: "lookupOrder", providerExecuted: true }]);
    expect(generated.toolResults).toHaveLength(1);
  });

  it("answers ask-user permissions with onPermissionRequest (deny by default)", async () => {
    const { copilot, runtime } = setup({ script: (rt) => rt.onPrompt(/.*/).callTool("lookupOrder", { id: "1" }).thenReply("done") });
    const agent = defineAgent({ name: "support", tools: [{ lookupOrder }], permissions: permissions.askUser() });
    await generateText({ model: createCopilotProvider({ copilot, agent })(), prompt: "x" });
    expect(runtime.toolCalls.at(-1)).toMatchObject({ approved: false });
    const approve = createCopilotProvider({ copilot, agent, onPermissionRequest: () => ({ approved: true }) });
    await generateText({ model: approve(), prompt: "x" });
    expect(runtime.toolCalls.at(-1)).toMatchObject({ approved: true });
  });

  it("keeps history server-side with a conversationId and sends only the new user message", async () => {
    const { copilot, runtime } = setup({ script: (rt) => rt.onPrompt(/.*/).reply("ok") });
    const model = createCopilotProvider({ copilot })();
    const messages: ModelMessage[] = [
      { role: "user", content: "first" },
      { role: "assistant", content: "ok" },
      { role: "user", content: "second" },
    ];
    await generateText({ model, messages: messages.slice(0, 1), providerOptions: { copilot: { conversationId: "conv-1" } } });
    await generateText({ model, messages, providerOptions: { copilot: { conversationId: "conv-1" } } });
    expect(runtime.prompts).toEqual(["first", "second"]);
    expect(runtime.sessionIds).toHaveLength(1);
  });

  it("sends a transcript for stateless multi-message prompts", async () => {
    const { copilot, runtime } = setup({ script: (rt) => rt.onPrompt(/.*/).reply("ok") });
    await generateText({
      model: createCopilotProvider({ copilot })(),
      messages: [
        { role: "user", content: "first" },
        { role: "assistant", content: "reply" },
        { role: "user", content: "second" },
      ],
    });
    expect(runtime.prompts[0]).toContain("User: first");
    expect(runtime.prompts[0]).toContain("Assistant: reply");
    expect(runtime.prompts[0]).toMatch(/second$/);
  });

  it("merges system messages into the agent instructions and applies the model id", async () => {
    const { copilot, runtime } = setup({ script: (rt) => rt.onPrompt(/.*/).reply("ok") });
    const provider = createCopilotProvider({ copilot, agent: defineAgent({ name: "bot", instructions: "Base." }) });
    const result = await generateText({ model: provider("gpt-other", { conversationId: "sys-1" }), system: "Be brief.", prompt: "x" });
    const config = runtime.sessionConfig(result.providerMetadata!.copilot!.sessionId as string) as {
      model?: string;
      systemMessage?: { content?: string };
    };
    expect(config.model).toBe("gpt-other");
    expect(JSON.stringify(config.systemMessage)).toContain("Base.\\n\\nBe brief.");
  });

  it("warns about unsupported settings", async () => {
    const { copilot } = setup({ script: (rt) => rt.onPrompt(/.*/).reply("ok") });
    const result = await generateText({ model: createCopilotProvider({ copilot })(), prompt: "x", temperature: 0.2, seed: 1 });
    expect(result.warnings).toEqual(
      expect.arrayContaining([expect.objectContaining({ feature: "temperature" }), expect.objectContaining({ feature: "seed" })]),
    );
  });

  it("rejects AI SDK call-level tools", async () => {
    const { copilot } = setup();
    await expect(generateText({ model: createCopilotProvider({ copilot })(), prompt: "x", tools: { lookupOrder } })).rejects.toThrow(
      /agent definition/,
    );
  });

  it("supports structured output", async () => {
    const { copilot } = setup({ script: (rt) => rt.onPrompt(/.*/).replyJson({ term: "DI", definition: "Dependency injection" }) });
    const result = await generateText({
      model: createCopilotProvider({ copilot })(),
      prompt: "Define DI",
      output: Output.object({
        schema: jsonSchema<{ term: string; definition: string }>({
          type: "object",
          properties: { term: { type: "string" }, definition: { type: "string" } },
          required: ["term", "definition"],
        }),
      }),
    });
    expect(result.output).toEqual({ term: "DI", definition: "Dependency injection" });
  });

  it("surfaces failures as errors", async () => {
    const { copilot } = setup({ script: (rt) => rt.onPrompt(/.*/).fail("model exploded") });
    await expect(generateText({ model: createCopilotProvider({ copilot })(), prompt: "x", maxRetries: 0 })).rejects.toThrow(/model exploded/);
  });

  it("has no embedding or image models", () => {
    const { copilot } = setup();
    const provider = createCopilotProvider({ copilot });
    expect(() => provider.embeddingModel("x")).toThrow(/no embedding models/);
    expect(() => provider.imageModel("x")).toThrow(/no image models/);
    expect(provider.agent(defineAgent({ name: "other" })).provider).toBe("copilot");
  });
});

describe("createCopilotAgent", () => {
  it("is an AI SDK agent backed by a Copilot agent", async () => {
    const { copilot } = setup({ script: (rt) => rt.onPrompt(/.*/).reply("Agent reply") });
    const agent = createCopilotAgent({ copilot, agent: defineAgent({ name: "advisor" }) });
    expect(agent.id).toBe("advisor");
    const result = await agent.generate({ prompt: "Hello" });
    expect(result.text).toBe("Agent reply");
    const streamed = await agent.stream({ prompt: "Hello" });
    expect(await streamed.text).toBe("Agent reply");
  });
});

describe("toCopilotPrompt", () => {
  it("replaces files with placeholders and warns", () => {
    const result = toCopilotPrompt(
      [
        { role: "system", content: "sys" },
        { role: "user", content: [{ type: "text", text: "see" }, { type: "file", mediaType: "image/png", data: { type: "url", url: new URL("https://x/y.png") } } as never] },
      ],
      "latest",
    );
    expect(result.system).toBe("sys");
    expect(result.prompt).toContain("see\n[file (image/png) omitted]");
    expect(result.warnings).toMatchObject([{ type: "unsupported", feature: "file parts" }]);
  });

  it("throws without user text", () => {
    expect(() => toCopilotPrompt([{ role: "system", content: "sys" }], "latest")).toThrow(/no user text/);
  });
});
