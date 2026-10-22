import { describe, expect, it, vi } from "vitest";
import type { SessionHooks } from "@github/copilot-sdk";
import { CopilotConfigurationError } from "../src/errors.js";
import {
  buildToolFilterConfig,
  composeHooks,
  evaluatePermission,
  permissions,
  toPermissionResult,
  validateToolFilters,
} from "../src/governance/index.js";
import type {
  CopilotAgentDefinition,
  PermissionDecision,
  PermissionPolicy,
  PermissionPolicyContext,
  PermissionRequest,
} from "../src/types.js";

const agent: CopilotAgentDefinition = { name: "support" };
const ctx: PermissionPolicyContext = {
  agent,
  customToolNames: new Set(["lookup_order", "refund"]),
  context: { attributes: {} },
};

const req = (r: Record<string, unknown>) => r as unknown as PermissionRequest;
const shell = req({ kind: "shell", fullCommandText: "rm -rf /" });
const write = req({ kind: "write", fileName: "a.txt" });
const read = req({ kind: "read", path: "/a", intention: "look" });
const managedRead = req({ kind: "read", path: "/a", intention: "look", managedApprovalRequired: true });
const url = req({ kind: "url", url: "https://x" });
const mcpRo = req({ kind: "mcp", serverName: "docs", toolName: "search", toolTitle: "", readOnly: true });
const mcpRw = req({ kind: "mcp", serverName: "docs", toolName: "update", toolTitle: "", readOnly: false });
const mcpOther = req({ kind: "mcp", serverName: "github", toolName: "search", toolTitle: "", readOnly: false });
const custom = (toolName: string) => req({ kind: "custom-tool", toolName, toolDescription: "" });

const run = (policy: PermissionPolicy, request: PermissionRequest, c = ctx) => Promise.resolve(policy(request, c));

describe("permission policies", () => {
  it("denyAll denies everything", async () => {
    for (const r of [shell, read, mcpRo, custom("refund")]) expect(await run(permissions.denyAll(), r)).toBe("deny");
  });

  it("allowAll approves everything except managed requests", async () => {
    for (const r of [shell, write, read, url, mcpRw, custom("x")]) {
      expect(await run(permissions.allowAll(), r)).toBe("approve");
    }
    expect(await run(permissions.allowAll(), managedRead)).toBe("abstain");
  });

  it("allowReadOnly approves reads and read-only MCP calls", async () => {
    const p = permissions.allowReadOnly();
    expect(await run(p, read)).toBe("approve");
    expect(await run(p, mcpRo)).toBe("approve");
    expect(await run(p, mcpRw)).toBe("abstain");
    expect(await run(p, req({ kind: "mcp", serverName: "d", toolName: "t", readOnly: "true" }))).toBe("abstain");
    expect(await run(p, write)).toBe("abstain");
    expect(await run(p, shell)).toBe("abstain");
    expect(await run(p, managedRead)).toBe("abstain");
  });

  it("allowCustomTools approves only the agent's own tools", async () => {
    const p = permissions.allowCustomTools();
    expect(await run(p, custom("refund"))).toBe("approve");
    expect(await run(p, custom("lookup_order"))).toBe("approve");
    expect(await run(p, custom("other"))).toBe("abstain");
    expect(await run(p, req({ kind: "mcp", serverName: "docs", toolName: "refund", readOnly: true }))).toBe("abstain");
    expect(await run(p, req({ kind: "custom-tool" }))).toBe("abstain");
  });

  it("allowCustomTools narrows to the given names, still requiring agent ownership", async () => {
    const p = permissions.allowCustomTools(["refund", "foreign"]);
    expect(await run(p, custom("refund"))).toBe("approve");
    expect(await run(p, custom("lookup_order"))).toBe("abstain");
    expect(await run(p, custom("foreign"))).toBe("abstain");
  });

  it("allowTools approves custom-tool and MCP requests by tool name", async () => {
    const p = permissions.allowTools(["search", "refund"]);
    expect(await run(p, custom("refund"))).toBe("approve");
    expect(await run(p, mcpRo)).toBe("approve");
    expect(await run(p, mcpOther)).toBe("approve");
    expect(await run(p, mcpRw)).toBe("abstain");
    expect(await run(p, req({ kind: "hook", toolName: "search" }))).toBe("abstain");
    expect(await run(p, read)).toBe("abstain");
  });

  it("allowMcpServers approves MCP requests by server", async () => {
    const p = permissions.allowMcpServers(["docs"]);
    expect(await run(p, mcpRo)).toBe("approve");
    expect(await run(p, mcpRw)).toBe("approve");
    expect(await run(p, mcpOther)).toBe("abstain");
    expect(await run(p, custom("docs"))).toBe("abstain");
  });

  it("allowKinds approves listed kinds", async () => {
    const p = permissions.allowKinds(["read", "url"]);
    expect(await run(p, read)).toBe("approve");
    expect(await run(p, url)).toBe("approve");
    expect(await run(p, shell)).toBe("abstain");
    expect(await run(p, managedRead)).toBe("abstain");
  });

  it("rejects blank or invalid names at construction", () => {
    expect(() => permissions.allowKinds([""])).toThrow(CopilotConfigurationError);
    expect(() => permissions.allowTools([" "])).toThrow(CopilotConfigurationError);
    expect(() => permissions.allowMcpServers(undefined as never)).toThrow(CopilotConfigurationError);
    expect(() => permissions.allowCustomTools([1 as never])).toThrow(CopilotConfigurationError);
    expect(() => permissions.anyOf("x" as never)).toThrow(CopilotConfigurationError);
  });

  describe("askUser", () => {
    it("asks for everything without a policy", async () => {
      expect(await run(permissions.askUser(), shell)).toBe("ask-user");
    });

    it("keeps the inner policy's final decisions and asks otherwise", async () => {
      const p = permissions.askUser(permissions.anyOf(permissions.allowReadOnly(), (r) => (r.kind === "shell" ? "deny" : "abstain")));
      expect(await run(p, read)).toBe("approve");
      expect(await run(p, shell)).toBe("deny");
      expect(await run(p, write)).toBe("ask-user");
      expect(await run(p, managedRead)).toBe("ask-user");
    });

    it("denies when the inner policy throws", async () => {
      const p = permissions.askUser(() => {
        throw new Error("x");
      });
      expect(await run(p, read)).toBe("deny");
    });
  });

  describe("anyOf", () => {
    const fixed = (d: PermissionDecision): PermissionPolicy => () => d;

    it("returns the first approval and stops evaluating", async () => {
      const later = vi.fn(() => "deny" as const);
      const p = permissions.anyOf(fixed("abstain"), fixed("approve"), later);
      expect(await run(p, read)).toBe("approve");
      expect(later).not.toHaveBeenCalled();
    });

    it("approval wins over earlier deny and ask-user", async () => {
      expect(await run(permissions.anyOf(fixed("deny"), fixed("ask-user"), fixed("approve")), read)).toBe("approve");
    });

    it("treats throwing policies as deny and continues", async () => {
      const sync: PermissionPolicy = () => {
        throw new Error("sync");
      };
      const async: PermissionPolicy = async () => {
        throw new Error("async");
      };
      expect(await run(permissions.anyOf(sync, async, permissions.allowReadOnly()), read)).toBe("approve");
      expect(await run(permissions.anyOf(sync, fixed("abstain")), read)).toBe("deny");
    });

    it("prefers ask-user over deny when nothing approves", async () => {
      expect(await run(permissions.anyOf(fixed("deny"), fixed("ask-user"), fixed("abstain")), read)).toBe("ask-user");
    });

    it("denies when a policy denied and none asked/approved", async () => {
      expect(await run(permissions.anyOf(fixed("abstain"), fixed("deny")), read)).toBe("deny");
    });

    it("abstains when every policy abstains or there are none", async () => {
      expect(await run(permissions.anyOf(fixed("abstain")), read)).toBe("abstain");
      expect(await run(permissions.anyOf(), read)).toBe("abstain");
    });

    it("treats unknown decisions as deny", async () => {
      expect(await run(permissions.anyOf((() => "yes") as never), read)).toBe("deny");
    });

    it("composes the built-in policies like Spring's example", async () => {
      const p = permissions.anyOf(
        permissions.allowReadOnly(),
        permissions.allowMcpServers(["docs"]),
        permissions.allowTools(["lookup_order"]),
      );
      expect(await run(p, read)).toBe("approve");
      expect(await run(p, mcpRw)).toBe("approve");
      expect(await run(p, custom("lookup_order"))).toBe("approve");
      expect(await evaluatePermission(p, shell, ctx)).toBe("deny");
    });
  });

  describe("allOf", () => {
    const fixed = (d: PermissionDecision): PermissionPolicy => () => d;

    it("approves only when every policy approves", async () => {
      expect(await run(permissions.allOf(fixed("approve"), fixed("approve")), read)).toBe("approve");
      expect(await run(permissions.allOf(fixed("approve"), fixed("abstain")), read)).toBe("abstain");
      expect(await run(permissions.allOf(fixed("approve"), fixed("ask-user")), read)).toBe("ask-user");
      expect(await run(permissions.allOf(), read)).toBe("abstain");
    });

    it("first deny or throw wins and stops", async () => {
      const later = vi.fn(() => "approve" as const);
      expect(await run(permissions.allOf(fixed("ask-user"), fixed("deny"), later), read)).toBe("deny");
      expect(
        await run(
          permissions.allOf(() => {
            throw new Error();
          }, later),
          read,
        ),
      ).toBe("deny");
      expect(later).not.toHaveBeenCalled();
    });

    it("narrows policies (approve reads of agent tools only)", async () => {
      const p = permissions.allOf(permissions.allowCustomTools(), permissions.allowTools(["refund"]));
      expect(await run(p, custom("refund"))).toBe("approve");
      expect(await run(p, custom("lookup_order"))).toBe("abstain");
    });
  });
});

describe("evaluatePermission", () => {
  it("denies by default when no policy is configured", async () => {
    expect(await evaluatePermission(undefined, read, ctx)).toBe("deny");
  });

  it("maps abstain to deny", async () => {
    expect(await evaluatePermission(permissions.allowReadOnly(), shell, ctx)).toBe("deny");
  });

  it("passes approve and ask-user through", async () => {
    expect(await evaluatePermission(permissions.allowReadOnly(), read, ctx)).toBe("approve");
    expect(await evaluatePermission(permissions.askUser(), read, ctx)).toBe("ask-user");
  });

  it("denies when the policy throws or rejects", async () => {
    expect(await evaluatePermission(() => { throw new Error("x"); }, read, ctx)).toBe("deny");
    expect(await evaluatePermission(() => Promise.reject(new Error("x")), read, ctx)).toBe("deny");
  });

  it("denies unknown decisions", async () => {
    expect(await evaluatePermission((() => undefined) as never, read, ctx)).toBe("deny");
  });

  it("passes request and context to the policy", async () => {
    const policy = vi.fn(() => "approve" as const);
    await evaluatePermission(policy, read, ctx);
    expect(policy).toHaveBeenCalledWith(read, ctx);
  });
});

describe("toPermissionResult", () => {
  it("maps decisions to SDK results", () => {
    expect(toPermissionResult("approve")).toEqual({ kind: "approve-once" });
    expect(toPermissionResult("deny")).toEqual({ kind: "reject" });
    expect(toPermissionResult("deny", "not allowed")).toEqual({ kind: "reject", feedback: "not allowed" });
    expect(toPermissionResult("user-not-available")).toEqual({ kind: "user-not-available" });
  });
});

describe("validateToolFilters", () => {
  const none = { customToolNames: new Set<string>() };
  const tools = { customToolNames: new Set(["echo", "lookup"]) };
  const def = (d: Partial<CopilotAgentDefinition>): CopilotAgentDefinition => ({ name: "a", ...d });

  it("accepts definitions without filters", () => {
    expect(() => validateToolFilters(def({}), tools)).not.toThrow();
    expect(() => validateToolFilters(def({ excludedTools: ["bash", "mcp:*"] }), tools)).not.toThrow();
  });

  it("rejects availableTools together with excludedTools", () => {
    expect(() => validateToolFilters(def({ availableTools: ["custom:*"], excludedTools: ["bash"] }), tools)).toThrow(
      /mutually exclusive/,
    );
    expect(() => validateToolFilters(def({ availableTools: ["custom:*"], excludedTools: [] }), tools)).not.toThrow();
  });

  it("rejects invalid entries", () => {
    for (const entry of ["*", "custom:", "foo:bar", "has space", "custom:a.b", ""]) {
      expect(() => validateToolFilters(def({ excludedTools: [entry] }), none)).toThrow(CopilotConfigurationError);
      expect(() => validateToolFilters(def({ availableTools: [entry] }), none)).toThrow(CopilotConfigurationError);
    }
    expect(() => validateToolFilters(def({ availableTools: "custom:*" as never }), none)).toThrow(
      CopilotConfigurationError,
    );
  });

  it("requires the allow-list to expose at least one of the agent's tools", () => {
    expect(() => validateToolFilters(def({ availableTools: [] }), tools)).toThrow(/hides every tool/);
    expect(() => validateToolFilters(def({ availableTools: ["mcp:*"] }), tools)).toThrow(/hides every tool/);
    expect(() => validateToolFilters(def({ availableTools: ["custom:other"] }), tools)).toThrow(/hides every tool/);
    for (const entry of ["custom:*", "custom:echo", "lookup"]) {
      expect(() => validateToolFilters(def({ availableTools: [entry] }), tools)).not.toThrow();
    }
    expect(() => validateToolFilters(def({ availableTools: [] }), none)).not.toThrow();
  });

  it("requires the allow-list to expose MCP tools when mcpServers are configured", () => {
    const mcpServers = { docs: { command: "docs-mcp", args: [] } } as unknown as CopilotAgentDefinition["mcpServers"];
    expect(() => validateToolFilters(def({ mcpServers, availableTools: ["view_x"] }), none)).toThrow(/MCP server/);
    for (const entry of ["mcp:*", "mcp:docs-search", "docs-search"]) {
      expect(() => validateToolFilters(def({ mcpServers, availableTools: [entry] }), none)).not.toThrow();
    }
    expect(() => validateToolFilters(def({ mcpServers: {}, availableTools: [] }), none)).not.toThrow();
  });

  it("rejects builtin entries unless builtInTools is enabled", () => {
    expect(() => validateToolFilters(def({ availableTools: ["builtin:view"] }), none)).toThrow(/builtInTools/);
    expect(() => validateToolFilters(def({ availableTools: ["builtin:*"], builtInTools: false }), none)).toThrow(
      /builtInTools/,
    );
    expect(() => validateToolFilters(def({ availableTools: ["builtin:view"], builtInTools: true }), none)).not.toThrow();
  });
});

describe("buildToolFilterConfig", () => {
  const def = (d: Partial<CopilotAgentDefinition>): CopilotAgentDefinition => ({ name: "a", ...d });

  it("excludes built-in tools by default", () => {
    expect(buildToolFilterConfig(def({}))).toEqual({ excludedTools: ["builtin:*"] });
    expect(buildToolFilterConfig(def({ builtInTools: false }))).toEqual({ excludedTools: ["builtin:*"] });
  });

  it("leaves runtime defaults when built-in tools are enabled", () => {
    expect(buildToolFilterConfig(def({ builtInTools: true }))).toEqual({});
  });

  it("appends builtin:* to excludedTools once", () => {
    expect(buildToolFilterConfig(def({ excludedTools: ["mcp:docs-delete"] }))).toEqual({
      excludedTools: ["mcp:docs-delete", "builtin:*"],
    });
    expect(buildToolFilterConfig(def({ excludedTools: ["builtin:*"] }))).toEqual({ excludedTools: ["builtin:*"] });
    expect(buildToolFilterConfig(def({ excludedTools: ["bash"], builtInTools: true }))).toEqual({
      excludedTools: ["bash"],
    });
  });

  it("copies availableTools and still excludes built-ins by default", () => {
    const availableTools = ["custom:*", "mcp:*"];
    const config = buildToolFilterConfig(def({ availableTools }));
    expect(config).toEqual({ availableTools: ["custom:*", "mcp:*"], excludedTools: ["builtin:*"] });
    expect(config.availableTools).not.toBe(availableTools);
    expect(buildToolFilterConfig(def({ availableTools: ["builtin:view", "custom:*"], builtInTools: true }))).toEqual({
      availableTools: ["builtin:view", "custom:*"],
    });
    expect(buildToolFilterConfig(def({ availableTools: [], builtInTools: true }))).toEqual({ availableTools: [] });
  });
});

describe("composeHooks", () => {
  const inv = { sessionId: "s" };
  const base = { sessionId: "s", timestamp: new Date(0), workingDirectory: "/w" };
  const preInput = { ...base, toolName: "bash", toolArgs: { cmd: "ls" } };

  it("returns undefined when no hooks are given", () => {
    expect(composeHooks()).toBeUndefined();
    expect(composeHooks(undefined, undefined)).toBeUndefined();
  });

  it("only defines keys that some hook defines", () => {
    const composed = composeHooks({ onSessionStart: () => undefined }, undefined, {})!;
    expect(Object.keys(composed)).toEqual(["onSessionStart"]);
  });

  it("calls handlers in order and returns undefined when none has an opinion", async () => {
    const calls: string[] = [];
    const composed = composeHooks(
      { onPreToolUse: () => void calls.push("global") },
      { onPreToolUse: async () => void calls.push("agent") },
    )!;
    expect(await composed.onPreToolUse!(preInput, inv)).toBeUndefined();
    expect(calls).toEqual(["global", "agent"]);
  });

  describe("onPreToolUse", () => {
    it("first deny wins and stops the chain", async () => {
      const later = vi.fn();
      const composed = composeHooks(
        { onPreToolUse: () => ({ permissionDecision: "allow", additionalContext: "a" }) },
        { onPreToolUse: () => ({ permissionDecision: "deny", permissionDecisionReason: "no shell" }) },
        { onPreToolUse: later },
      )!;
      expect(await composed.onPreToolUse!(preInput, inv)).toEqual({
        permissionDecision: "deny",
        permissionDecisionReason: "no shell",
        additionalContext: "a",
      });
      expect(later).not.toHaveBeenCalled();
    });

    it("ask beats allow regardless of order", async () => {
      const composed = composeHooks(
        { onPreToolUse: () => ({ permissionDecision: "ask", permissionDecisionReason: "check" }) },
        { onPreToolUse: () => ({ permissionDecision: "allow", permissionDecisionReason: "fine" }) },
      )!;
      expect(await composed.onPreToolUse!(preInput, inv)).toEqual({
        permissionDecision: "ask",
        permissionDecisionReason: "check",
      });
      const reversed = composeHooks(
        { onPreToolUse: () => ({ permissionDecision: "allow" }) },
        { onPreToolUse: () => ({ permissionDecision: "ask" }) },
      )!;
      expect(await reversed.onPreToolUse!(preInput, inv)).toEqual({ permissionDecision: "ask" });
    });

    it("passes modified args to later hooks and returns them", async () => {
      const second = vi.fn((input: { toolArgs: unknown }) => ({ modifiedArgs: { ...(input.toolArgs as object), b: 2 } }));
      const composed = composeHooks(
        { onPreToolUse: () => ({ modifiedArgs: { cmd: "pwd" } }) },
        { onPreToolUse: second },
        { onPreToolUse: () => ({ permissionDecision: "allow" }) },
      )!;
      expect(await composed.onPreToolUse!(preInput, inv)).toEqual({
        permissionDecision: "allow",
        modifiedArgs: { cmd: "pwd", b: 2 },
      });
      expect(second.mock.calls[0]![0]).toMatchObject({ toolName: "bash", toolArgs: { cmd: "pwd" } });
      expect(preInput.toolArgs).toEqual({ cmd: "ls" });
    });

    it("includes modified args on deny", async () => {
      const composed = composeHooks(
        { onPreToolUse: () => ({ modifiedArgs: { x: 1 } }) },
        { onPreToolUse: () => ({ permissionDecision: "deny" }) },
      )!;
      expect(await composed.onPreToolUse!(preInput, inv)).toEqual({ permissionDecision: "deny", modifiedArgs: { x: 1 } });
    });

    it("fails closed when a hook throws", async () => {
      const later = vi.fn();
      const composed = composeHooks(
        {
          onPreToolUse: async () => {
            throw new Error("audit down");
          },
        },
        { onPreToolUse: later },
      )!;
      const result = await composed.onPreToolUse!(preInput, inv);
      expect(result).toMatchObject({ permissionDecision: "deny" });
      expect((result as { permissionDecisionReason: string }).permissionDecisionReason).toContain("audit down");
      expect(later).not.toHaveBeenCalled();
    });

    it("joins additionalContext and ORs suppressOutput", async () => {
      const composed = composeHooks(
        { onPreToolUse: () => ({ additionalContext: "one", suppressOutput: false }) },
        { onPreToolUse: () => ({ additionalContext: "two", suppressOutput: true }) },
        { onPreToolUse: () => ({ additionalContext: "" }) },
      )!;
      expect(await composed.onPreToolUse!(preInput, inv)).toEqual({ additionalContext: "one\n\ntwo", suppressOutput: true });
    });

    it("passes the invocation through", async () => {
      const hook = vi.fn();
      await composeHooks({ onPreToolUse: hook })!.onPreToolUse!(preInput, inv);
      expect(hook).toHaveBeenCalledWith(preInput, inv);
    });
  });

  describe("onUserPromptSubmitted", () => {
    const input = { ...base, prompt: "hello" };

    it("first block wins and stops the chain", async () => {
      const later = vi.fn();
      const composed = composeHooks(
        { onUserPromptSubmitted: () => ({ modifiedPrompt: "HELLO" }) },
        { onUserPromptSubmitted: () => ({ decision: "block", reason: "pii" }) },
        { onUserPromptSubmitted: later },
      )!;
      expect(await composed.onUserPromptSubmitted!(input, inv)).toEqual({ decision: "block", reason: "pii" });
      expect(later).not.toHaveBeenCalled();
    });

    it("passes the modified prompt along", async () => {
      const second = vi.fn((i: { prompt: string }) => ({ modifiedPrompt: `${i.prompt}!` }));
      const composed = composeHooks(
        { onUserPromptSubmitted: () => ({ modifiedPrompt: "HELLO", additionalContext: "ctx" }) },
        { onUserPromptSubmitted: second },
      )!;
      expect(await composed.onUserPromptSubmitted!(input, inv)).toEqual({ modifiedPrompt: "HELLO!", additionalContext: "ctx" });
      expect(second.mock.calls[0]![0].prompt).toBe("HELLO");
    });

    it("blocks when a hook throws", async () => {
      const composed = composeHooks({
        onUserPromptSubmitted: () => {
          throw new Error("boom");
        },
      })!;
      expect(await composed.onUserPromptSubmitted!(input, inv)).toMatchObject({ decision: "block" });
    });
  });

  it("onUserPromptTransformed chains the transformed prompt", async () => {
    const second = vi.fn((i: { transformedPrompt: string }) => ({ modifiedTransformedPrompt: `${i.transformedPrompt}+2` }));
    const composed = composeHooks(
      { onUserPromptTransformed: () => ({ modifiedTransformedPrompt: "t1" }) },
      { onUserPromptTransformed: second },
      { onUserPromptTransformed: () => ({}) },
    )!;
    expect(await composed.onUserPromptTransformed!({ ...base, prompt: "p", transformedPrompt: "t0" }, inv)).toEqual({
      modifiedTransformedPrompt: "t1+2",
    });
  });

  it("onPostToolUse chains modified results and swallows errors", async () => {
    const toolResult = { textResultForLlm: "raw", resultType: "success" as const };
    const second = vi.fn((i: { toolResult: { textResultForLlm: string } }) => ({
      modifiedResult: { textResultForLlm: `${i.toolResult.textResultForLlm}!`, resultType: "success" as const },
    }));
    const composed = composeHooks(
      { onPostToolUse: () => ({ modifiedResult: { textResultForLlm: "redacted", resultType: "success" } }) },
      {
        onPostToolUse: () => {
          throw new Error("ignored");
        },
      },
      { onPostToolUse: second },
    )!;
    expect(await composed.onPostToolUse!({ ...preInput, toolResult }, inv)).toEqual({
      modifiedResult: { textResultForLlm: "redacted!", resultType: "success" },
    });
  });

  it("onPostToolUseFailure and onSubagentStart join additionalContext", async () => {
    const composed = composeHooks(
      { onPostToolUseFailure: () => ({ additionalContext: "a" }), onSubagentStart: () => ({ additionalContext: "x" }) },
      { onPostToolUseFailure: () => ({ additionalContext: "b" }), onSubagentStart: () => undefined },
    )!;
    expect(await composed.onPostToolUseFailure!({ ...preInput, error: "e" }, inv)).toEqual({ additionalContext: "a\n\nb" });
    expect(
      await composed.onSubagentStart!({ ...base, transcriptPath: "/t", agentName: "sub" }, inv),
    ).toEqual({ additionalContext: "x" });
  });

  it("onPreMcpToolCall chains metaToUse, including null", async () => {
    const second = vi.fn(() => ({ metaToUse: null }));
    const third = vi.fn(() => ({}));
    const composed = composeHooks(
      { onPreMcpToolCall: () => ({ metaToUse: { trace: "1" } }) },
      { onPreMcpToolCall: second },
      { onPreMcpToolCall: third },
    )!;
    const input = { ...base, serverName: "docs", toolName: "search", arguments: {} };
    expect(await composed.onPreMcpToolCall!(input, inv)).toEqual({ metaToUse: null });
    expect((second.mock.calls[0] as unknown[])[0]).toMatchObject({ _meta: { trace: "1" } });
    expect((third.mock.calls[0] as unknown[])[0]).toMatchObject({ _meta: undefined });
  });

  it("onAgentStop: first block wins", async () => {
    const later = vi.fn();
    const composed = composeHooks(
      { onAgentStop: () => ({}) },
      { onAgentStop: () => ({ decision: "block", reason: "fix tests" }) },
      { onAgentStop: later },
    )!;
    expect(await composed.onAgentStop!(base, inv)).toEqual({ decision: "block", reason: "fix tests" });
    expect(later).not.toHaveBeenCalled();
  });

  it("onSubagentStop: valid block wins, otherwise modified response chains", async () => {
    const input = { ...base, transcriptPath: "/t", agentName: "sub", agentType: "x", stopReason: "end_turn" as const, response: "r0" };
    const blocking = composeHooks(
      { onSubagentStop: () => ({ modifiedResponse: "r1" }) },
      { onSubagentStop: () => ({ decision: "block", reason: "" }) as never },
      { onSubagentStop: () => ({ decision: "block", reason: "continue" }) },
    )!;
    expect(await blocking.onSubagentStop!(input, inv)).toEqual({ decision: "block", reason: "continue" });
    const second = vi.fn((i: { response: string }) => ({ modifiedResponse: `${i.response}+` }));
    const rewriting = composeHooks(
      { onSubagentStop: () => ({ modifiedResponse: "r1" }) },
      { onSubagentStop: second },
    )!;
    expect(await rewriting.onSubagentStop!(input, inv)).toEqual({ modifiedResponse: "r1+" });
  });

  it("onSessionStart merges config and context", async () => {
    const composed = composeHooks(
      { onSessionStart: () => ({ modifiedConfig: { a: 1, b: 1 }, additionalContext: "x" }) },
      { onSessionStart: () => ({ modifiedConfig: { b: 2 } }) },
    )!;
    expect(await composed.onSessionStart!({ ...base, source: "new" }, inv)).toEqual({
      modifiedConfig: { a: 1, b: 2 },
      additionalContext: "x",
    });
  });

  it("onSessionEnd ORs suppression and keeps the last summary/actions", async () => {
    const composed = composeHooks(
      { onSessionEnd: () => ({ suppressOutput: true, sessionSummary: "s1", cleanupActions: ["a"] }) },
      { onSessionEnd: () => ({ suppressOutput: false, sessionSummary: "s2" }) },
    )!;
    expect(await composed.onSessionEnd!({ ...base, reason: "complete" }, inv)).toEqual({
      suppressOutput: true,
      sessionSummary: "s2",
      cleanupActions: ["a"],
    });
  });

  it("onErrorOccurred picks the most conservative handling", async () => {
    const composed = composeHooks(
      { onErrorOccurred: () => ({ errorHandling: "retry", retryCount: 3 }) },
      { onErrorOccurred: () => ({ errorHandling: "abort", userNotification: "sorry" }) },
      { onErrorOccurred: () => ({ errorHandling: "skip" }) },
    )!;
    expect(
      await composed.onErrorOccurred!({ ...base, error: "e", errorContext: "tool_execution", recoverable: true }, inv),
    ).toEqual({ errorHandling: "abort", retryCount: 3, userNotification: "sorry" });
  });

  it("unknown hook keys run in order with the last output winning", async () => {
    const hooks = [{ onFutureHook: () => "a" }, { onFutureHook: () => undefined }, { onFutureHook: () => "c" }];
    const composed = composeHooks(...(hooks as unknown as SessionHooks[])) as unknown as Record<
      string,
      (i: unknown, v: unknown) => Promise<unknown>
    >;
    expect(await composed.onFutureHook!({}, inv)).toBe("c");
  });
});
