import { frameworkTests } from "@workshop/node-common/testing";
import { createApplication } from "../src/app.js";
import { test } from "node:test";
import assert from "node:assert/strict";
import { resolve } from "node:path";
import { createTestCopilot } from "@copilot-sdk-integrations/agent-core/testing";
import { createMuseum, exhibits } from "@workshop/node-common";

frameworkTests(createApplication);

test("Fastify plugin configuration applies environment model without weakening tool restrictions", async () => {
  const runtime = createTestCopilot({
    baseDirectory: resolve(".runs/runtime"),
    script: (rt) => rt.onPrompt("configured").reply("ok"),
  });
  const definition = createMuseum("Apollo", exhibits.apollo11.facts).definition;
  const { app, agent } = await createApplication(definition, runtime.copilot, {
    COPILOT_MODEL: "configured-model", COPILOT_RESPONSE_TIMEOUT_MS: "45000",
  });
  try {
    assert.equal(await agent.send({ prompt: "configured" }), "ok");
    assert.equal(runtime.runtime.createdSessions[0]?.model, "configured-model");
    assert.deepEqual(runtime.runtime.createdSessions[0]?.excludedTools, ["builtin:*"]);
  } finally {
    await app.close();
    await runtime.stop();
  }
});
