import {
  CopilotConfigurationError,
  CopilotIntegrationError,
  createCopilot,
  defineAgent,
  toSseStream,
  type AgentHandle,
  type Copilot,
  type CopilotAgentDefinition,
  type CopilotAgentEvent,
  type CopilotOptions,
} from "@copilot-sdk-integrations/agent-core";
import type { FastifyInstance, FastifyPluginAsync, FastifyReply } from "fastify";
import fp from "fastify-plugin";
import { addContextHook, decorateRequestCopilot } from "./context.js";
import { copilotErrorHandler } from "./errors.js";
import { fastifyCopilotLogger } from "./logger.js";
import { isCopilot, resolvePluginOptions } from "./options.js";
import { addHealthRoute, addInfoRoute } from "./routes.js";
import type { CopilotEventsOptions, CopilotPluginOptions, FastifyCopilot } from "./types.js";

export const PLUGIN_NAME = "@copilot-sdk-integrations/fastify";

/** SSE response headers set by `reply.copilotEvents()`. */
export const SSE_HEADERS = {
  "content-type": "text/event-stream; charset=utf-8",
  "cache-control": "no-cache",
  "x-accel-buffering": "no",
} as const;

function createFastifyCopilot(core: Copilot, overrides: Record<string, Partial<CopilotAgentDefinition>>, isReady: () => boolean): FastifyCopilot {
  const definitions = new Map<string, CopilotAgentDefinition>();
  const facade: FastifyCopilot = {
    core,
    get state() {
      return core.state;
    },
    start: () => core.start(),
    stop: (options) => core.stop(options),
    client: () => core.client(),
    capacity: () => core.capacity(),
    health: () => core.health(),
    agent(nameOrDefinition: string | CopilotAgentDefinition): AgentHandle {
      if (typeof nameOrDefinition !== "string") return core.agent(nameOrDefinition);
      const definition = definitions.get(nameOrDefinition);
      if (!definition) {
        const known = [...definitions.keys()].map((name) => `"${name}"`).join(", ") || "(none)";
        throw new CopilotConfigurationError(`Unknown Copilot agent "${nameOrDefinition}"; registered agents: ${known}`);
      }
      return core.agent(definition);
    },
    agents: () => [...definitions.values()],
    registerAgent(definition: CopilotAgentDefinition) {
      if (isReady()) {
        throw new CopilotConfigurationError(`Cannot register Copilot agent "${definition?.name}" after the application is ready`);
      }
      const name = definition?.name;
      if (typeof name === "string" && definitions.has(name)) {
        throw new CopilotConfigurationError(`Copilot agent "${name}" is already registered`);
      }
      const override = typeof name === "string" ? overrides[name] : undefined;
      const effective = defineAgent(override ? { ...definition, ...override, name } : definition);
      definitions.set(effective.name, effective);
    },
  };
  return facade;
}

function createPlugin(applyPrefix: boolean): FastifyPluginAsync<CopilotPluginOptions> {
  return async function copilotPlugin(fastify: FastifyInstance, options: CopilotPluginOptions) {
    const opts = resolvePluginOptions(options);
    const owned = !isCopilot(opts.client);
    const core = owned
      ? createCopilot({ logger: fastifyCopilotLogger(fastify.log.child({ module: "copilot" })), ...(opts.client as CopilotOptions) })
      : (opts.client as Copilot);
    const stopOnClose = opts.stopOnClose ?? owned;
    // Routes of an fastify-plugin-wrapped plugin ignore the register prefix; apply it ourselves.
    const prefix = applyPrefix ? opts.prefix : undefined;

    let ready = false;
    const copilot = createFastifyCopilot(core, opts.agentOverrides, () => ready);
    for (const definition of opts.agents) copilot.registerAgent(definition);

    fastify.decorate("copilot", copilot);
    decorateRequestCopilot(fastify);
    fastify.decorateReply("copilotEvents", sendCopilotEvents);

    if (opts.context !== false) addContextHook(fastify, opts.context);
    if (opts.errorHandler) fastify.setErrorHandler(copilotErrorHandler);
    if (opts.health) addHealthRoute(fastify, opts.health.path, prefix);
    if (opts.info) addInfoRoute(fastify, opts.info, prefix);

    fastify.addHook("onReady", async () => {
      ready = true;
      const names = new Set(copilot.agents().map((definition) => definition.name));
      const unknown = Object.keys(opts.agentOverrides).filter((name) => !names.has(name));
      if (unknown.length > 0) {
        fastify.log.warn({ module: "copilot" }, `agentOverrides for unknown Copilot agents: ${unknown.join(", ")}`);
      }
      if (opts.startup === "eager") await core.start();
    });

    if (stopOnClose) {
      let stopping: Promise<void> | undefined;
      // Stopping closes admission at once and drains in-flight turns for the grace period;
      // onClose waits for it after the server stopped accepting requests.
      const stop = () => (stopping ??= core.stop({ gracePeriodMs: opts.shutdownGracePeriod }));
      fastify.addHook("preClose", (done) => {
        stop().catch((error: unknown) => fastify.log.error({ err: error }, "Stopping Copilot failed"));
        done();
      });
      fastify.addHook("onClose", async () => {
        await stop();
      });
    }
  };
}

/**
 * Waits for the first event so errors before the stream starts (admission, start failure, unknown
 * conversation) go to the error handler with a real status; later errors become `event: error`.
 */
async function sendCopilotEvents(
  this: FastifyReply,
  events: AsyncIterable<CopilotAgentEvent>,
  eventsOptions: CopilotEventsOptions = {},
): Promise<FastifyReply> {
  const iterator = events[Symbol.asyncIterator]();
  let first: IteratorResult<CopilotAgentEvent>;
  try {
    first = await iterator.next();
  } catch (error) {
    return this.send(error);
  }
  if (!first.done && first.value.type === "error") {
    void Promise.resolve(iterator.return?.()).catch(() => undefined);
    return this.send(first.value.error);
  }
  let head: IteratorResult<CopilotAgentEvent> | undefined = first;
  const rest: AsyncIterable<CopilotAgentEvent> = {
    [Symbol.asyncIterator]: () => ({
      next: () => {
        if (head) {
          const result = head;
          head = undefined;
          return Promise.resolve(result);
        }
        return iterator.next();
      },
      return: (value?: unknown) =>
        iterator.return ? iterator.return(value) : Promise.resolve({ done: true as const, value: undefined }),
    }),
  };
  const log = this.log;
  const stream = toSseStream(rest, {
    ...eventsOptions,
    onError: (error) => {
      if (error instanceof CopilotIntegrationError) log.debug({ err: error }, "Copilot event stream ended with an error");
      else log.error({ err: error }, "Copilot event stream failed");
      if (eventsOptions.onError) return eventsOptions.onError(error);
      return error instanceof CopilotIntegrationError ? error.message : "An error occurred.";
    },
  });
  return this.headers(SSE_HEADERS).send(stream);
}

type CopilotPlugin = FastifyPluginAsync<CopilotPluginOptions> & {
  /** The unwrapped plugin, for an encapsulated child scope with its own client, decorators, and error handler. */
  scoped: FastifyPluginAsync<CopilotPluginOptions>;
};

/**
 * Registers Copilot on a Fastify instance: `fastify.copilot`, `request.copilot`,
 * `reply.copilotEvents()`, the request context hook, the Copilot error handler, and optional health
 * and info routes. Wrapped with `fastify-plugin`, so the decorators are visible app-wide.
 */
export const copilotPlugin: CopilotPlugin = Object.assign(
  fp(createPlugin(true), { name: PLUGIN_NAME, fastify: "5.x" }),
  { scoped: createPlugin(false) },
);
