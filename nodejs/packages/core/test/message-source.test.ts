import { afterEach, describe, expect, it } from "vitest";
import { z } from "zod";
import { defineAgent } from "../src/define-agent.js";
import { CopilotConfigurationError } from "../src/errors.js";
import { permissions } from "../src/governance/index.js";
import {
  agentMessageSource,
  getMessageSource,
  parseMessageSource,
  withMessageSource,
} from "../src/message-source.js";
import { defineCopilotTool } from "../src/tools/index.js";
import { createTestCopilot, type TestCopilot } from "../src/testing/index.js";
import type { CopilotAgentDefinition } from "../src/types.js";

let current: TestCopilot | undefined;
afterEach(async () => {
  await current?.stop();
  current = undefined;
});

function setup(): TestCopilot {
  current = createTestCopilot();
  return current;
}

const replyToRest = (test: TestCopilot) => test.runtime.onPrompt(/.*/).replyWith((turn) => `re: ${turn.prompt}`);

const sources = (test: TestCopilot) => test.runtime.receivedPrompts.map((p) => [p.prompt, p.source]);

describe("message source", () => {
  it("parses and validates sources and agent ids", () => {
    expect(parseMessageSource("user")).toBe("user");
    expect(parseMessageSource("system")).toBe("system");
    expect(parseMessageSource("agent-incident.bot_1")).toBe("agent-incident.bot_1");
    expect(parseMessageSource(undefined)).toBeUndefined();
    expect(parseMessageSource("")).toBeUndefined();
    for (const bad of ["User", " user", "agent-", "agent-a b", "agent-a\n", "agent--x", `agent-${"a".repeat(129)}`, "admin", 42]) {
      expect(() => parseMessageSource(bad)).toThrow(CopilotConfigurationError);
    }
    expect(() => agentMessageSource("bad/id")).toThrow(CopilotConfigurationError);
    expect(() => defineAgent({ name: "a", messageSource: "root" as never })).toThrow(/messageSource/);
  });

  it("sends nothing by default, the configured source, or the scoped source", async () => {
    const test = setup();
    const plain = defineAgent({ name: "plain" });
    const nightly = defineAgent({ name: "nightly", messageSource: "system" });
    test.runtime.onPrompt("c").replyJson({ value: "ok" });
    replyToRest(test);
    await test.copilot.agent(plain).send({ prompt: "a" });
    await test.copilot.agent(nightly).send({ prompt: "b" });
    await withMessageSource("agent-reporting", async () => {
      expect(getMessageSource()).toBe("agent-reporting");
      await expect(test.copilot.agent(nightly).send({ prompt: "c", schema: z.string() })).resolves.toBe("ok");
    });
    expect(getMessageSource()).toBeUndefined();
    expect(sources(test)).toEqual([
      ["a", undefined],
      ["b", "system"],
      ["c", "agent-reporting"],
    ]);
  });

  it("labels calls from another agent's tool, and an outer scope does not leak into tools", async () => {
    const test = setup();
    const helper = defineAgent({ name: "helper", messageSource: "system" });
    const askHelper = defineCopilotTool({
      name: "askHelper",
      description: "Ask the helper",
      input: z.object({ q: z.string(), asUser: z.boolean() }),
      execute: ({ q, asUser }) =>
        asUser
          ? withMessageSource("user", () => test.copilot.agent(helper).send({ prompt: q }))
          : test.copilot.agent(helper).send({ prompt: q }),
    });
    const delegator: CopilotAgentDefinition = defineAgent({
      name: "delegator",
      tools: [askHelper],
      permissions: permissions.allowCustomTools(),
    });
    test.runtime.onPrompt(/^go/).callTool("askHelper", { q: "q1", asUser: false }).thenReply("done").once();
    test.runtime.onPrompt(/^go/).callTool("askHelper", { q: "q2", asUser: false }).thenReply("done").once();
    test.runtime.onPrompt(/^go/).callTool("askHelper", { q: "q3", asUser: true }).thenReply("done").once();
    replyToRest(test);

    await test.copilot.agent(delegator).send({ prompt: "go 1" });
    await withMessageSource("user", () => test.copilot.agent(delegator).send({ prompt: "go 2" }));
    await test.copilot.agent(delegator).send({ prompt: "go 3" });

    expect(sources(test)).toEqual([
      ["go 1", undefined],
      ["q1", "agent-delegator"],
      ["go 2", "user"],
      ["q2", "agent-delegator"],
      ["go 3", undefined],
      ["q3", "user"],
    ]);
  });
});
