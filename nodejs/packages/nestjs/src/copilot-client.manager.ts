import {
  createCopilot,
  type Copilot,
  type CopilotLogger,
  type CopilotOptions,
} from "@copilot-sdk-integrations/core";
import {
  Inject,
  Injectable,
  Logger,
  type BeforeApplicationShutdown,
  type OnApplicationBootstrap,
  type OnApplicationShutdown,
} from "@nestjs/common";
import { ModuleRef } from "@nestjs/core";
import { COPILOT_CLIENT_CUSTOMIZER } from "./constants.js";
import { COPILOT_MODULE_OPTIONS } from "./copilot.module-definition.js";
import { CopilotExplorer, resolveOptional } from "./copilot.explorer.js";
import type { CopilotClientCustomizer, CopilotModuleOptions } from "./interfaces.js";
import { validateModuleOptions } from "./options.js";
import { CopilotTokenResolver } from "./token-resolver.js";

const CORE_OPTION_KEYS = [
  "cliPath",
  "cliUrl",
  "startup",
  "githubToken",
  "useLoggedInUser",
  "baseDirectory",
  "logLevel",
  "limits",
  "responseTimeout",
  "approvalWindow",
  "store",
  "hooks",
  "clientFactory",
  "logger",
] as const satisfies readonly (keyof CopilotOptions)[];

/** Adapts Nest's `Logger` to the core logger interface. */
export function nestCopilotLogger(logger = new Logger("Copilot")): CopilotLogger {
  const format = (message: string, meta?: Record<string, unknown>) =>
    meta === undefined ? message : `${message} ${JSON.stringify(meta)}`;
  return {
    debug: (m, meta) => logger.debug(format(m, meta)),
    info: (m, meta) => logger.log(format(m, meta)),
    warn: (m, meta) => logger.warn(format(m, meta)),
    error: (m, meta) => logger.error(format(m, meta)),
  };
}

/**
 * Owns the core `Copilot` (client manager) for the application: lazy or eager start, and graceful
 * shutdown (`app.enableShutdownHooks()` makes Nest run it on SIGTERM).
 */
@Injectable()
export class CopilotClientManager implements OnApplicationBootstrap, BeforeApplicationShutdown, OnApplicationShutdown {
  readonly options: CopilotModuleOptions;
  readonly copilot: Copilot;
  private readonly stopCopilot: (options?: { gracePeriodMs?: number }) => Promise<void>;
  private stopping?: Promise<void>;

  constructor(
    @Inject(COPILOT_MODULE_OPTIONS) options: CopilotModuleOptions,
    @Inject(ModuleRef) private readonly moduleRef: ModuleRef,
    @Inject(CopilotExplorer) private readonly explorer: CopilotExplorer,
  ) {
    this.options = validateModuleOptions(options);
    const coreOptions = this.coreOptions();
    if (this.options.copilotFactory) {
      const instance = this.options.copilotFactory(coreOptions);
      this.copilot = instance.copilot;
      this.stopCopilot = instance.stop ? (o) => instance.stop!(o) : (o) => instance.copilot.stop(o);
    } else {
      this.copilot = createCopilot(coreOptions);
      this.stopCopilot = (o) => this.copilot.stop(o);
    }
  }

  /** Starts the runtime (no-op when already running). */
  start(): Promise<void> {
    return this.copilot.start();
  }

  /** Eager startup: bootstrap fails if the runtime can't start. */
  async onApplicationBootstrap(): Promise<void> {
    if (this.options.startup === "eager") await this.start();
  }

  /** Stops admitting turns, drains in-flight turns up to `shutdownGracePeriod`, aborts the rest, stops the runtime. */
  beforeApplicationShutdown(): Promise<void> {
    return this.stop();
  }

  onApplicationShutdown(): Promise<void> {
    return this.stop();
  }

  /** Idempotent graceful stop. */
  stop(): Promise<void> {
    this.stopping ??= this.stopCopilot(
      this.options.shutdownGracePeriod !== undefined ? { gracePeriodMs: this.options.shutdownGracePeriod } : {},
    );
    return this.stopping;
  }

  private coreOptions(): CopilotOptions {
    const options = this.options;
    const core: CopilotOptions = {};
    for (const key of CORE_OPTION_KEYS) {
      if (options[key] !== undefined) (core as Record<string, unknown>)[key] = options[key];
    }
    core.logger ??= nestCopilotLogger();
    core.onEvent = (event, context) => {
      try {
        options.onEvent?.(event, context);
      } finally {
        this.explorer.dispatchEvent(event, context);
      }
    };
    core.tokenResolver = (context) => {
      if (options.tokenResolver) return options.tokenResolver(context);
      return resolveOptional<CopilotTokenResolver>(this.moduleRef, CopilotTokenResolver)?.resolve(context);
    };
    core.configureClient = (clientOptions) => {
      let result = options.configureClient ? options.configureClient(clientOptions) : clientOptions;
      const customizers = resolveOptional<CopilotClientCustomizer | CopilotClientCustomizer[]>(this.moduleRef, COPILOT_CLIENT_CUSTOMIZER);
      for (const customize of [customizers ?? []].flat()) result = customize(result);
      return result;
    };
    return core;
  }
}
