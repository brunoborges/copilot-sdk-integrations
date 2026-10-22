import { describeAgent, type CopilotInfo } from "@copilot-sdk-integrations/core";
import type { FastifyInstance } from "fastify";
import type { CopilotInfoRouteOptions } from "./types.js";

const capacitySchema = {
  type: "object",
  properties: {
    maxConcurrentTurns: { type: "integer" },
    activeTurns: { type: "integer" },
    maxQueuedTurns: { type: "integer" },
    queuedTurns: { type: "integer" },
  },
  required: ["maxConcurrentTurns", "activeTurns", "maxQueuedTurns", "queuedTurns"],
} as const;

const healthSchema = {
  type: "object",
  properties: {
    state: { type: "string" },
    version: { type: "string" },
    capacity: capacitySchema,
  },
  required: ["state", "capacity"],
} as const;

const stringArray = { type: "array", items: { type: "string" } } as const;

const infoSchema = {
  type: "object",
  properties: {
    ...healthSchema.properties,
    agents: {
      type: "array",
      items: {
        type: "object",
        properties: {
          name: { type: "string" },
          description: { type: "string" },
          model: { type: "string" },
          reasoningEffort: { type: "string" },
          provider: { type: "string" },
          builtInTools: { type: "boolean" },
          tools: stringArray,
          mcpServers: stringArray,
          availableTools: stringArray,
          excludedTools: stringArray,
          instructions: {},
        },
        required: ["name", "builtInTools", "tools", "mcpServers"],
      },
    },
  },
  required: ["state", "capacity", "agents"],
} as const;

const UNHEALTHY_STATES = new Set(["failed", "stopping", "stopped"]);

/** Whether a client state should report unhealthy (503). */
export function isUnhealthyState(state: string): boolean {
  return UNHEALTHY_STATES.has(state);
}

/**
 * A health check for `@fastify/under-pressure` (`healthCheck: copilotHealthCheck(app)`): false when
 * the client is failed, stopping, or stopped. Never starts a lazy client.
 */
export function copilotHealthCheck(fastify: FastifyInstance): () => Promise<boolean> {
  return async () => !isUnhealthyState(fastify.copilot.state);
}

function joinPath(prefix: string | undefined, path: string): string {
  if (!prefix) return path;
  return `${prefix.replace(/\/+$/, "")}${path}`;
}

export function addHealthRoute(fastify: FastifyInstance, path: string, prefix: string | undefined): void {
  fastify.get(joinPath(prefix, path), { schema: { response: { 200: healthSchema, 503: healthSchema } } }, async (_request, reply) => {
    const health = await fastify.copilot.health();
    return reply.code(isUnhealthyState(health.state) ? 503 : 200).send(health);
  });
}

export function addInfoRoute(
  fastify: FastifyInstance,
  options: CopilotInfoRouteOptions & { path: string },
  prefix: string | undefined,
): void {
  if (!options.onRequest || (Array.isArray(options.onRequest) && options.onRequest.length === 0)) {
    fastify.log.warn(
      { module: "copilot" },
      `Copilot info route ${joinPath(prefix, options.path)} has no onRequest guard; it exposes agent configuration.`,
    );
  }
  fastify.get(
    joinPath(prefix, options.path),
    { schema: { response: { 200: infoSchema } }, ...(options.onRequest ? { onRequest: options.onRequest } : {}) },
    async () => {
      const health = await fastify.copilot.health();
      const info: CopilotInfo = {
        ...health,
        agents: fastify.copilot
          .agents()
          .map((def) => describeAgent(def, { includeInstructions: options.includeInstructions === true })),
      };
      return info;
    },
  );
}
