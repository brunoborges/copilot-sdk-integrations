import Fastify from "fastify";
import { copilotPlugin } from "@copilot-sdk-integrations/fastify";
import type { Copilot, CopilotAgentDefinition, CopilotOptions } from "@copilot-sdk-integrations/agent-core";
import { loadWorkshopConfig } from "@workshop/node-common";

export async function createApplication(definition: CopilotAgentDefinition, client?: Copilot | CopilotOptions, environment = process.env) {
  const config = loadWorkshopConfig(environment);
  const configured = { ...definition, ...(config.model ? { model: config.model } : {}) };
  const app = Fastify({ logger: false });
  await app.register(copilotPlugin, {
    client: client && "agent" in client ? client : {
      responseTimeout: config.responseTimeout,
      ...client,
      limits: { maxConcurrentTurns: 1, maxQueuedTurns: 0 },
    },
    agents: [configured],
    stopOnClose: true,
  });
  await app.ready();
  return { app, agent: app.copilot.agent(definition.name) };
}
