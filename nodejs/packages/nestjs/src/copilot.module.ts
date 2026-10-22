import type { Copilot, CopilotAgentDefinition } from "@copilot-sdk-integrations/core";
import { Module, type DynamicModule, type Provider, type Type } from "@nestjs/common";
import { APP_FILTER, APP_INTERCEPTOR, DiscoveryModule, HttpAdapterHost } from "@nestjs/core";
import { COPILOT, COPILOT_CLIENT, getAgentToken } from "./constants.js";
import { CopilotAgents, type CopilotAgentClass } from "./copilot-agents.service.js";
import { CopilotClientManager } from "./copilot-client.manager.js";
import { CopilotContextInterceptor } from "./copilot-context.interceptor.js";
import { CopilotExceptionFilter, DisabledCopilotExceptionFilter } from "./copilot-exception.filter.js";
import { CopilotExplorer } from "./copilot.explorer.js";
import { CopilotHealthIndicator } from "./copilot.health.js";
import { CopilotInfoService } from "./copilot.info.js";
import { COPILOT_MODULE_OPTIONS, CopilotConfigurableModule } from "./copilot.module-definition.js";
import type { CopilotClientAccessor, CopilotModuleOptions, CopilotToolSource } from "./interfaces.js";

export interface CopilotFeatureOptions {
  /** Tool sources (provider classes with `@CopilotTool` methods, or core tools) added to every agent of this feature. */
  tools?: CopilotToolSource[];
}

/** Holds the agent providers of a `CopilotModule.forFeature()` call. */
@Module({})
export class CopilotFeatureModule {}

/**
 * The Copilot module. Register once with `forRoot()`/`forRootAsync()` (global by default), then
 * register agents with `forFeature([...])` in the modules that use them.
 */
@Module({
  imports: [DiscoveryModule],
  providers: [
    CopilotExplorer,
    CopilotClientManager,
    CopilotAgents,
    CopilotHealthIndicator,
    CopilotInfoService,
    CopilotContextInterceptor,
    {
      provide: COPILOT,
      useFactory: (manager: CopilotClientManager): Copilot => manager.copilot,
      inject: [CopilotClientManager],
    },
    {
      provide: COPILOT_CLIENT,
      useFactory: (manager: CopilotClientManager): CopilotClientAccessor => () => manager.copilot.client(),
      inject: [CopilotClientManager],
    },
    { provide: APP_INTERCEPTOR, useExisting: CopilotContextInterceptor },
    {
      provide: APP_FILTER,
      useFactory: (options: CopilotModuleOptions, adapterHost?: HttpAdapterHost) =>
        options.exceptionFilter === false ? new DisabledCopilotExceptionFilter() : new CopilotExceptionFilter(adapterHost),
      inject: [COPILOT_MODULE_OPTIONS, { token: HttpAdapterHost, optional: true }],
    },
  ],
  exports: [
    COPILOT,
    COPILOT_CLIENT,
    COPILOT_MODULE_OPTIONS,
    CopilotClientManager,
    CopilotAgents,
    CopilotHealthIndicator,
    CopilotInfoService,
    CopilotExplorer,
  ],
})
export class CopilotModule extends CopilotConfigurableModule {
  /**
   * Registers agents: `@CopilotAgent` classes (injectable by class) and `defineAgent()` definitions
   * (injectable with `@InjectAgent(name)`). Requires `CopilotModule.forRoot()` (global) in the application.
   */
  static forFeature(agents: Array<CopilotAgentClass | CopilotAgentDefinition>, options: CopilotFeatureOptions = {}): DynamicModule {
    const tools = options.tools ?? [];
    const providers: Provider[] = agents.map((agent) =>
      typeof agent === "function"
        ? {
            provide: agent as Type<unknown>,
            useFactory: (registry: CopilotAgents) => registry.implement(agent as CopilotAgentClass, tools),
            inject: [CopilotAgents],
          }
        : {
            provide: getAgentToken(agent.name),
            useFactory: (registry: CopilotAgents) => registry.register(agent, tools),
            inject: [CopilotAgents],
          },
    );
    return {
      module: CopilotFeatureModule,
      providers,
      exports: providers.map((p) => (p as { provide: unknown }).provide as string),
    };
  }
}
