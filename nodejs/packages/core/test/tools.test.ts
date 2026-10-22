import { describe, expect, it, vi } from "vitest";
import { z } from "zod";
import { jsonSchema, tool } from "ai";
import { defineTool, type Tool, type ToolInvocation, type ToolResultObject } from "@github/copilot-sdk";
import { runWithCopilotContext } from "../src/context.js";
import { CopilotConfigurationError } from "../src/errors.js";
import {
  defineCopilotTool,
  isStructuredSchema,
  toJsonSchema,
  toSdkTools,
  validateToolName,
} from "../src/tools/index.js";
import type { CopilotContext, ToolCallInfo } from "../src/types.js";

function invocation(overrides: Partial<ToolInvocation> = {}): ToolInvocation {
  return { sessionId: "s1", toolCallId: "call-1", toolName: "t", arguments: {}, ...overrides };
}

async function call(tool: Tool, args: unknown, inv: Partial<ToolInvocation> = {}) {
  expect(tool.handler).toBeTypeOf("function");
  return tool.handler!(args, invocation({ toolName: tool.name, arguments: args, ...inv }));
}

function only(inputs: Parameters<typeof toSdkTools>[0], options?: Parameters<typeof toSdkTools>[1]) {
  const converted = toSdkTools(inputs, options);
  expect(converted.tools).toHaveLength(1);
  return converted.tools[0]!;
}

describe("toJsonSchema", () => {
  it("returns an empty object schema for undefined", () => {
    expect(toJsonSchema(undefined)).toEqual({ type: "object", properties: {} });
  });

  it("converts Zod 4 schemas and strips $schema", () => {
    const schema = toJsonSchema(z.object({ city: z.string().describe("City"), days: z.number().int().optional() }));
    expect(schema).not.toHaveProperty("$schema");
    expect(schema).toMatchObject({
      type: "object",
      properties: { city: { type: "string", description: "City" }, days: { type: "integer" } },
      required: ["city"],
    });
  });

  it("uses the input view so fields with defaults are optional", () => {
    const schema = toJsonSchema(z.object({ unit: z.string().default("c"), city: z.string() }));
    expect(schema.required).toEqual(["city"]);
  });

  it("passes raw JSON Schema through, stripping $schema", () => {
    const raw = { $schema: "x", type: "object", properties: { a: { type: "string" } } };
    expect(toJsonSchema(raw)).toEqual({ type: "object", properties: { a: { type: "string" } } });
    expect(raw).toHaveProperty("$schema");
  });

  it("uses toJSONSchema() on Zod-like schemas without _zod", () => {
    const schema = { parse: (v: unknown) => v, toJSONSchema: () => ({ $schema: "y", type: "string" }) };
    expect(toJsonSchema(schema)).toEqual({ type: "string" });
  });

  it("rejects parse-only schemas without a JSON Schema representation", () => {
    expect(() => toJsonSchema({ parse: (v: unknown) => v })).toThrow(CopilotConfigurationError);
  });

  it("detects structured schemas", () => {
    expect(isStructuredSchema(z.string())).toBe(true);
    expect(isStructuredSchema({ parse: () => 1 })).toBe(true);
    expect(isStructuredSchema({ type: "object" })).toBe(false);
    expect(isStructuredSchema(null)).toBe(false);
    expect(isStructuredSchema("x")).toBe(false);
  });
});

describe("validateToolName / defineCopilotTool", () => {
  it.each(["a", "get_weather", "get-weather", "A1", "x".repeat(64)])("accepts %s", (name) => {
    expect(() => validateToolName(name)).not.toThrow();
  });

  it.each(["", "has space", "dot.name", "slash/name", "x".repeat(65), "émoji"])("rejects %j", (name) => {
    expect(() => validateToolName(name)).toThrow(CopilotConfigurationError);
  });

  it("rejects non-strings", () => {
    expect(() => validateToolName(undefined as unknown as string)).toThrow(CopilotConfigurationError);
  });

  it("returns the definition unchanged", () => {
    const def = { name: "echo", description: "Echo", execute: () => "x" };
    expect(defineCopilotTool(def)).toBe(def);
  });

  it("validates name and execute", () => {
    expect(() => defineCopilotTool({ name: "bad name", description: "", execute: () => 1 })).toThrow(
      CopilotConfigurationError,
    );
    expect(() => defineCopilotTool({ name: "ok", description: "" } as never)).toThrow(CopilotConfigurationError);
  });
});

describe("toSdkTools: CopilotToolDefinition", () => {
  it("returns empty results for undefined or empty input", () => {
    for (const input of [undefined, []]) {
      const converted = toSdkTools(input);
      expect(converted.tools).toEqual([]);
      expect(converted.customToolNames.size).toBe(0);
      expect(converted.approvalRequired.size).toBe(0);
    }
  });

  it("converts a Zod-typed tool with JSON Schema parameters and parsed input", async () => {
    const execute = vi.fn((input: { city: string; unit: string }) => `${input.city}:${input.unit}`);
    const t = only([
      defineCopilotTool({
        name: "weather",
        description: "Weather",
        input: z.object({ city: z.string(), unit: z.string().default("c") }),
        execute,
        skipPermission: true,
        overridesBuiltInTool: false,
      }),
    ]);
    expect(t.name).toBe("weather");
    expect(t.description).toBe("Weather");
    expect(t.skipPermission).toBe(true);
    expect(t.overridesBuiltInTool).toBe(false);
    expect(t.parameters).toMatchObject({ type: "object", required: ["city"] });
    expect(t.parameters).not.toHaveProperty("$schema");
    expect(await call(t, { city: "Lisbon" })).toBe("Lisbon:c");
    expect(execute.mock.calls[0]![0]).toEqual({ city: "Lisbon", unit: "c" });
  });

  it("returns a failure result on validation errors without calling execute", async () => {
    const execute = vi.fn();
    const t = only([{ name: "weather", description: "", input: z.object({ city: z.string() }), execute }]);
    const result = (await call(t, { city: 42 })) as ToolResultObject;
    expect(result.resultType).toBe("failure");
    expect(result.textResultForLlm).toContain('Invalid arguments for tool "weather"');
    expect(result.error).toContain("city");
    expect(execute).not.toHaveBeenCalled();
  });

  it("supports async Zod refinements", async () => {
    const schema = z.object({ n: z.number() }).refine(async (v) => v.n > 0, "must be positive");
    const t = only([{ name: "pos", description: "", input: schema, execute: (v: { n: number }) => v.n }]);
    expect(await call(t, { n: 2 })).toBe("2");
    expect(((await call(t, { n: -1 })) as ToolResultObject).error).toContain("must be positive");
  });

  it("passes raw JSON Schema input through without validation", async () => {
    const raw = { type: "object", properties: { q: { type: "string" } } };
    const t = only([{ name: "search", description: "", input: raw, execute: (v: unknown) => v }]);
    expect(t.parameters).toEqual(raw);
    expect(await call(t, { q: 1 })).toBe('{"q":1}');
  });

  it("defaults parameters to an empty object schema", () => {
    const t = only([{ name: "ping", description: "", execute: () => "pong" }]);
    expect(t.parameters).toEqual({ type: "object", properties: {} });
  });

  it("converts results", async () => {
    const cases: Array<[unknown, unknown]> = [
      ["text", "text"],
      [undefined, ""],
      [null, ""],
      [{ a: 1 }, '{"a":1}'],
      [[1, 2], "[1,2]"],
      [42, "42"],
      [false, "false"],
      [Symbol("s"), "Symbol(s)"],
      [10n, "10"],
    ];
    for (const [value, expected] of cases) {
      const t = only([{ name: "r", description: "", execute: async () => value }]);
      expect(await call(t, {})).toBe(expected);
    }
  });

  it("passes ToolResultObjects through", async () => {
    const value: ToolResultObject = { textResultForLlm: "denied!", resultType: "denied" };
    const t = only([{ name: "r", description: "", execute: () => value }]);
    expect(await call(t, {})).toBe(value);
  });

  it("does not treat objects with an unknown resultType as ToolResultObjects", async () => {
    const value = { textResultForLlm: "x", resultType: "weird" };
    const t = only([{ name: "r", description: "", execute: () => value }]);
    expect(await call(t, {})).toBe(JSON.stringify(value));
  });

  it("takes the last value of async iterable results", async () => {
    async function* gen() {
      yield "partial";
      yield { done: true };
    }
    const t = only([{ name: "r", description: "", execute: () => gen() }]);
    expect(await call(t, {})).toBe('{"done":true}');
  });

  it("turns thrown errors (sync and async) into failure results", async () => {
    const sync = only([{ name: "boom", description: "", execute: () => { throw new Error("kaput"); } }]);
    const asyncTool = only([{ name: "boom", description: "", execute: async () => Promise.reject("nope") }]);
    const a = (await call(sync, {})) as ToolResultObject;
    expect(a).toEqual({ resultType: "failure", error: "kaput", textResultForLlm: 'Tool "boom" failed: kaput' });
    const b = (await call(asyncTool, {})) as ToolResultObject;
    expect(b.resultType).toBe("failure");
    expect(b.error).toBe("nope");
  });

  it("builds ToolCallInfo from the invocation and the ambient context", async () => {
    let seen: ToolCallInfo | undefined;
    let ambient: CopilotContext | undefined;
    const t = only([
      {
        name: "ctx",
        description: "",
        execute: async (_: unknown, info: ToolCallInfo) => {
          seen = info;
          ambient = (await import("../src/context.js")).getCopilotContext();
          return "ok";
        },
      },
    ]);
    const controller = new AbortController();
    await runWithCopilotContext({ principal: "alice", attributes: { tenant: "t1" } }, () =>
      call(t, {}, { toolCallId: "c9", sessionId: "sess", signal: controller.signal }),
    );
    expect(seen).toMatchObject({ toolCallId: "c9", toolName: "ctx", sessionId: "sess", signal: controller.signal });
    expect(seen!.context).toMatchObject({ principal: "alice", attributes: { tenant: "t1" } });
    expect(ambient).toMatchObject({ principal: "alice", attributes: { tenant: "t1" } });
  });

  it("prefers getContext() and makes it ambient inside execute", async () => {
    let ambient: CopilotContext | undefined;
    const { getCopilotContext } = await import("../src/context.js");
    const t = only(
      [
        {
          name: "ctx",
          description: "",
          execute: (_: unknown, info: ToolCallInfo) => {
            ambient = getCopilotContext();
            return info.context.principal;
          },
        },
      ],
      { getContext: () => ({ principal: "bob", attributes: {} }) },
    );
    expect(await runWithCopilotContext({ principal: "alice", attributes: {} }, () => call(t, {}))).toBe("bob");
    expect(ambient?.principal).toBe("bob");
  });

  it("falls back to an empty context outside any scope", async () => {
    let seen: ToolCallInfo | undefined;
    const t = only([{ name: "ctx", description: "", execute: (_: unknown, info: ToolCallInfo) => { seen = info; } }]);
    await call(t, {});
    expect(seen!.context).toEqual({ attributes: {} });
  });

  it("falls back to an empty context when getContext returns undefined and there is no scope", async () => {
    let seen: ToolCallInfo | undefined;
    const t = only([{ name: "ctx", description: "", execute: (_: unknown, info: ToolCallInfo) => { seen = info; } }], {
      getContext: () => undefined,
    });
    await call(t, {});
    expect(seen!.context).toEqual({ attributes: {} });
  });
});

describe("toSdkTools: SDK tools", () => {
  it("passes SDK tools through unchanged and records their names", () => {
    const sdk = defineTool("lookup", { description: "Lookup", parameters: z.object({ id: z.string() }), handler: () => "x" });
    const declaration: Tool = { name: "client_side", description: "no handler" };
    const converted = toSdkTools([sdk, declaration]);
    expect(converted.tools[0]).toBe(sdk);
    expect(converted.tools[1]).toBe(declaration);
    expect([...converted.customToolNames]).toEqual(["lookup", "client_side"]);
  });

  it("rejects an SDK tool with a non-function handler", () => {
    expect(() => toSdkTools([{ name: "x", handler: "nope" } as never])).toThrow(CopilotConfigurationError);
  });

  it("validates SDK tool names", () => {
    expect(() => toSdkTools([{ name: "bad.name" }])).toThrow(CopilotConfigurationError);
  });
});

describe("toSdkTools: AI SDK tool records", () => {
  it("converts Zod-typed tools and calls execute with AI SDK options", async () => {
    const execute = vi.fn(async ({ city }: { city: string }) => ({ temp: 20, city }));
    const converted = toSdkTools([
      { weather: tool({ description: "Weather", inputSchema: z.object({ city: z.string() }), execute }) },
    ]);
    const t = converted.tools[0]!;
    expect(t.name).toBe("weather");
    expect(t.description).toBe("Weather");
    expect(t.parameters).toMatchObject({ type: "object", properties: { city: { type: "string" } } });
    const controller = new AbortController();
    expect(await call(t, { city: "Porto" }, { toolCallId: "c1", signal: controller.signal })).toBe(
      '{"temp":20,"city":"Porto"}',
    );
    expect(execute).toHaveBeenCalledWith(
      { city: "Porto" },
      expect.objectContaining({ toolCallId: "c1", abortSignal: controller.signal, messages: [] }),
    );
    expect(converted.customToolNames).toEqual(new Set(["weather"]));
  });

  it("returns a failure result when Zod validation fails", async () => {
    const t = only([{ w: tool({ inputSchema: z.object({ city: z.string() }), execute: async () => "x" }) }]);
    expect(((await call(t, {})) as ToolResultObject).resultType).toBe("failure");
  });

  it("supports jsonSchema() wrappers with validate", async () => {
    const t = only([
      {
        add: tool({
          description: "Add",
          inputSchema: jsonSchema<{ a: number; b: number }>(
            { type: "object", properties: { a: { type: "number" }, b: { type: "number" } } },
            {
              validate: (value) => {
                const v = value as { a: unknown; b: unknown };
                return typeof v.a === "number" && typeof v.b === "number"
                  ? { success: true, value: { a: v.a, b: v.b } }
                  : { success: false, error: new Error("a and b must be numbers") };
              },
            },
          ),
          execute: async ({ a, b }) => a + b,
        }),
      },
    ]);
    expect(t.parameters).toEqual({ type: "object", properties: { a: { type: "number" }, b: { type: "number" } } });
    expect(await call(t, { a: 1, b: 2, extra: true })).toBe("3");
    const failure = (await call(t, { a: "1" })) as ToolResultObject;
    expect(failure.resultType).toBe("failure");
    expect(failure.error).toBe("a and b must be numbers");
  });

  it("supports jsonSchema() wrappers without validate", async () => {
    const t = only([
      { echo: tool({ inputSchema: jsonSchema({ type: "object", properties: {} }), execute: async (v: unknown) => v }) },
    ]);
    expect(await call(t, { any: 1 })).toBe('{"any":1}');
  });

  it("supports lazily-defined jsonSchema() wrappers", () => {
    const t = only([{ lazy: tool({ inputSchema: jsonSchema(() => ({ type: "object", properties: { x: { type: "string" } } })) }) }]);
    expect(t.parameters).toEqual({ type: "object", properties: { x: { type: "string" } } });
  });

  it("rejects asynchronous JSON Schemas", () => {
    expect(() =>
      toSdkTools([{ a: tool({ inputSchema: jsonSchema(Promise.resolve({ type: "object" })) }) }]),
    ).toThrow(CopilotConfigurationError);
  });

  it("accepts raw JSON Schema objects and missing schemas", async () => {
    const converted = toSdkTools([
      {
        raw: { inputSchema: { type: "object", properties: { q: { type: "string" } } }, execute: (v: unknown) => v },
        none: { description: "No input", execute: () => "ok" },
      },
    ]);
    expect(converted.tools[0]!.parameters).toEqual({ type: "object", properties: { q: { type: "string" } } });
    expect(converted.tools[1]!.parameters).toEqual({ type: "object", properties: {} });
    expect(await call(converted.tools[1]!, {})).toBe("ok");
  });

  it("supports Standard Schema validators", async () => {
    const standard = {
      "~standard": {
        version: 1,
        vendor: "custom",
        validate: (value: unknown) =>
          typeof (value as { n?: unknown }).n === "number"
            ? { value }
            : { issues: [{ message: "n must be a number" }] },
        jsonSchema: { input: () => ({ $schema: "draft-07", type: "object", properties: { n: { type: "number" } } }) },
      },
    };
    const t = only([{ s: { inputSchema: standard, execute: (v: { n: number }) => v.n * 2 } }]);
    expect(t.parameters).toEqual({ type: "object", properties: { n: { type: "number" } } });
    expect(await call(t, { n: 2 })).toBe("4");
    expect(((await call(t, { n: "x" })) as ToolResultObject).error).toBe("n must be a number");
  });

  it("creates declaration-only tools when execute is missing", () => {
    const t = only([{ confirm: tool({ description: "Ask the user", inputSchema: z.object({ question: z.string() }) }) }]);
    expect(t.name).toBe("confirm");
    expect(t.handler).toBeUndefined();
    expect(t.parameters).toMatchObject({ properties: { question: { type: "string" } } });
  });

  it("records needsApproval", async () => {
    const needsApproval = vi.fn(async (input: { amount: number }) => input.amount > 100);
    const converted = toSdkTools([
      {
        always: tool({ inputSchema: z.object({}), needsApproval: true, execute: async () => "" }),
        never: tool({ inputSchema: z.object({}), needsApproval: false, execute: async () => "" }),
        unset: tool({ inputSchema: z.object({}), execute: async () => "" }),
        dynamic: tool({ inputSchema: z.object({ amount: z.number() }), needsApproval, execute: async () => "" }),
      },
    ]);
    expect(converted.approvalRequired.get("always")).toBe(true);
    expect(converted.approvalRequired.has("never")).toBe(false);
    expect(converted.approvalRequired.has("unset")).toBe(false);
    const fn = converted.approvalRequired.get("dynamic") as (i: unknown, o?: { toolCallId?: string }) => Promise<boolean>;
    expect(await fn({ amount: 500 }, { toolCallId: "c7" })).toBe(true);
    expect(await fn({ amount: 5 })).toBe(false);
    expect(needsApproval).toHaveBeenCalledWith({ amount: 500 }, { toolCallId: "c7", messages: [], context: { attributes: {} } });
    expect(needsApproval).toHaveBeenLastCalledWith({ amount: 5 }, { toolCallId: "", messages: [], context: { attributes: {} } });
  });

  it("propagates the context into AI SDK tools", async () => {
    let options: Record<string, unknown> | undefined;
    const t = only([
      {
        ctx: {
          execute: (_: unknown, opts: Record<string, unknown>) => {
            options = opts;
          },
        },
      },
    ]);
    await runWithCopilotContext({ conversationId: "c1", attributes: {} }, () => call(t, {}));
    expect(options!.context).toMatchObject({ conversationId: "c1" });
  });

  it("turns AI SDK execute errors into failure results", async () => {
    const t = only([{ fail: tool({ inputSchema: z.object({}), execute: async (): Promise<string> => { throw new Error("db down"); } }) }]);
    const result = (await call(t, {})) as ToolResultObject;
    expect(result).toMatchObject({ resultType: "failure", error: "db down" });
  });

  it("allows a tool record keyed 'name' or 'execute'", () => {
    const converted = toSdkTools([{ name: tool({ inputSchema: z.object({}) }), execute: tool({ inputSchema: z.object({}) }) }]);
    expect([...converted.customToolNames]).toEqual(["name", "execute"]);
  });

  it("rejects invalid record entries", () => {
    expect(() => toSdkTools([{ "bad name": tool({ inputSchema: z.object({}) }) }])).toThrow(CopilotConfigurationError);
    expect(() => toSdkTools([{ x: null as never }])).toThrow(CopilotConfigurationError);
    expect(() => toSdkTools([{ x: { execute: "nope" } as never }])).toThrow(CopilotConfigurationError);
    expect(() => toSdkTools([{ x: { type: "provider", id: "openai.web_search" } as never }])).toThrow(
      CopilotConfigurationError,
    );
  });
});

describe("toSdkTools: validation across inputs", () => {
  it("rejects duplicate names across shapes", () => {
    const def = { name: "dup", description: "", execute: () => "" };
    expect(() => toSdkTools([def, { dup: tool({ inputSchema: z.object({}) }) }])).toThrow(/Duplicate tool name "dup"/);
    expect(() => toSdkTools([def, defineTool("dup", { handler: () => "" })])).toThrow(CopilotConfigurationError);
    expect(() => toSdkTools([def, def])).toThrow(CopilotConfigurationError);
  });

  it("rejects invalid names on native tools", () => {
    expect(() => toSdkTools([{ name: "has space", description: "", execute: () => "" }])).toThrow(
      CopilotConfigurationError,
    );
  });

  it("rejects non-object inputs", () => {
    for (const bad of [null, "tool", 42, [1]]) {
      expect(() => toSdkTools([bad as never])).toThrow(CopilotConfigurationError);
    }
  });

  it("collects names from all shapes in order", () => {
    const converted = toSdkTools([
      { name: "a", description: "", execute: () => "" },
      defineTool("b", { handler: () => "" }),
      { c: tool({ inputSchema: z.object({}) }), d: tool({ inputSchema: z.object({}), execute: async () => "" }) },
    ]);
    expect([...converted.customToolNames]).toEqual(["a", "b", "c", "d"]);
    expect(converted.tools.map((t) => t.name)).toEqual(["a", "b", "c", "d"]);
  });
});
