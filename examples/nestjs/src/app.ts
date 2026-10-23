import "reflect-metadata";
import { Module } from "@nestjs/common";
import { NestFactory } from "@nestjs/core";
import { CopilotModule, getAgentToken, type CopilotModuleOptions } from "@copilot-sdk-integrations/nestjs";
import type { AgentHandle, CopilotAgentDefinition } from "@copilot-sdk-integrations/agent-core";
import { loadWorkshopConfig } from "@workshop/node-common";

export async function createApplication(definition: CopilotAgentDefinition, options: CopilotModuleOptions = {}, environment = process.env) {
  const config = loadWorkshopConfig(environment);
  const configured = { ...definition, ...(config.model ? { model: config.model } : {}) };
  class WorkshopModule {}
  Module({
    imports: [
      CopilotModule.forRootAsync({
        useFactory: () => ({ responseTimeout: config.responseTimeout, ...options, limits: { maxConcurrentTurns: 1, maxQueuedTurns: 0 } }),
      }),
      CopilotModule.forFeature([configured]),
    ],
  })(WorkshopModule);
  const app = await NestFactory.createApplicationContext(WorkshopModule, { logger: false, abortOnError: false });
  return { app, agent: app.get<AgentHandle>(getAgentToken(definition.name)) };
}
