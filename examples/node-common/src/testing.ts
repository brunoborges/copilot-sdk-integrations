import assert from "node:assert/strict";
import { test } from "node:test";
import { resolve } from "node:path";
import { createTestCopilot, type TestCopilot } from "@copilot-sdk-integrations/agent-core/testing";
import type { AgentHandle, CopilotAgentDefinition, Copilot } from "@copilot-sdk-integrations/agent-core";
import { createMuseum, exhibits } from "./museum.js";
import { createAccessibility } from "./accessibility.js";
import { SnapshotStore } from "./snapshot.js";
import { createResearch } from "./research.js";

export const museumOutput = {
  title: "A journey to the Moon",
  narrative: `Imagine an exhibit that invites you to think about a journey shared by three people.
Apollo 11 landed humans on the Moon in July 1969. Neil Armstrong and Buzz Aldrin walked
on the lunar surface, while Michael Collins remained in lunar orbit aboard the command
module. The lunar module was named Eagle. The astronauts collected lunar samples and
returned them to Earth. These approved facts offer different ways to explore the mission:
consider the people on the surface, the person in orbit, and the samples brought home.
As you explore this story, take time to compare their experiences and reflect on what
each part of the journey means to you.`,
  questions: ["What would you ask the astronauts?", "Which part of the journey interests you most?", "How would you describe the samples?"],
  factIds: [1, 2, 3, 4, 5],
};

export const snapshotText = '- button ""\n- textbox\n- link "click here"\n';
export const accessibilityOutput = {
  findings: [
    { evidence: '- button ""', criterion: "4.1.2", issue: "Candidate unnamed button; verify its purpose in the DOM.", remediation: "Give the button a descriptive accessible name." },
    { evidence: "- textbox", criterion: "3.3.2", issue: "Candidate missing input label; verify visible and associated labels.", remediation: "Associate a descriptive label with the input." },
    { evidence: '- link "click here"', criterion: "2.4.4", issue: "Candidate ambiguous link purpose; verify surrounding context.", remediation: "Use link text describing its destination or purpose." },
  ],
  reviewLimits: "Static snapshot only. Verify each candidate in context and manually test keyboard, contrast, dynamic states and assistive technology.",
};
export const testTarget = "https://example.com/";

export function scriptMuseum(runtime: TestCopilot["runtime"]) {
  const script = runtime.onPrompt(/Draft the exhibit/);
  for (let id = 1; id <= 5; id++) script.callTool("approved_fact_lookup", { id });
  script.replyJson(museumOutput);
}

export function scriptAccessibility(runtime: TestCopilot["runtime"]) {
  runtime.onPrompt(/Review this explicitly/)
    .callTool("browser_navigate", { url: testTarget })
    .callTool("read_latest_accessibility_snapshot", {})
    .callTool("accessibility_rule_lookup", { criterion: "4.1.2" })
    .callTool("accessibility_rule_lookup", { criterion: "3.3.2" })
    .callTool("accessibility_rule_lookup", { criterion: "2.4.4" })
    .replyJson(accessibilityOutput);
}

type ApplicationFactory = (definition: CopilotAgentDefinition, copilot: Copilot) =>
  Promise<{ app: { close(): Promise<void> }; agent: AgentHandle }>;

/** Both framework suites exercise the same real integration lifecycle and governance contracts. */
export function frameworkTests(createApplication: ApplicationFactory) {
  test("framework registers optional Wikipedia MCP with an exact tool list; uncited answers fail closed", async () => {
    const runtime = createTestCopilot({
      baseDirectory: resolve(".runs/runtime"),
      script: (rt) => rt.onPrompt(/Research this topic/).replyJson({
        notes: [{ claim: "Unverified", sourceUrl: "https://en.wikipedia.org/wiki/Apollo_11" }],
      }),
    });
    const command = createResearch("Apollo 11");
    const { app, agent } = await createApplication(command.definition, runtime.copilot);
    try {
      const raw = await agent.send({ prompt: command.prompt });
      assert.throws(() => command.validate(raw), /successful Wikipedia article read/);
      assert.deepEqual(runtime.runtime.createdSessions[0]?.mcpServers?.wikipedia, {
        type: "stdio", command: "npx", args: ["-y", "wikipedia-mcp@1.0.3"], tools: ["search", "readArticle"], timeout: 30_000,
      });
    } finally {
      await app.close();
      await runtime.stop();
    }
  });

  test("framework registers museum tools and validates a complete scripted exhibit", async () => {
    const runtime = createTestCopilot({ baseDirectory: resolve(".runs/runtime"), script: scriptMuseum });
    const command = createMuseum(exhibits.apollo11.title, exhibits.apollo11.facts);
    const { app, agent } = await createApplication(command.definition, runtime.copilot);
    try {
      const result = command.validate(await agent.send({ prompt: command.prompt }));
      assert.equal(result.title, museumOutput.title);
      assert.match(result.review, /educator/);
      assert.equal(runtime.runtime.toolCalls.length, 5);
      assert(runtime.runtime.toolCalls.every((call) => call.approved && !call.error));
      assert.deepEqual(runtime.runtime.createdSessions[0]?.excludedTools, ["builtin:*"]);
    } finally {
      await app.close();
      await runtime.stop();
    }
  });

  test("framework composes navigation, fresh snapshot, and one catalog lookup per finding", async () => {
    const store = await SnapshotStore.create();
    const runtime = createTestCopilot({ baseDirectory: resolve(".runs/runtime"), script: scriptAccessibility });
    const command = createAccessibility(testTarget, store, async () => snapshotText);
    const { app, agent } = await createApplication(command.definition, runtime.copilot);
    try {
      const result = command.validate(await agent.send({ prompt: command.prompt }));
      assert.equal(result.findings.length, 3);
      assert.match(result.review, /not a full WCAG/);
      assert.equal(runtime.runtime.toolCalls.length, 5);
      assert(runtime.runtime.toolCalls.every((call) => call.approved && !call.error));
    } finally {
      await app.close();
      await runtime.stop();
      await store.dispose();
    }
  });

  test("framework rejects wrong navigation and reporting without evidence", async () => {
    const store = await SnapshotStore.create();
    let navigations = 0;
    const runtime = createTestCopilot({
      baseDirectory: resolve(".runs/runtime"),
      script: (rt) => rt.onPrompt(/Review this explicitly/)
        .callTool("browser_navigate", { url: "https://attacker.invalid/" })
        .callTool("read_latest_accessibility_snapshot", {})
        .callTool("accessibility_rule_lookup", { criterion: "4.1.2" })
        .replyJson(accessibilityOutput),
    });
    const command = createAccessibility(testTarget, store, async () => {
      navigations++;
      return snapshotText;
    });
    const { app, agent } = await createApplication(command.definition, runtime.copilot);
    try {
      const raw = await agent.send({ prompt: command.prompt });
      assert.throws(() => command.validate(raw), /current snapshot/);
      assert.equal(navigations, 0);
      assert(runtime.runtime.toolCalls.every((call) => call.error));
    } finally {
      await app.close();
      await runtime.stop();
      await store.dispose();
    }
  });
}
