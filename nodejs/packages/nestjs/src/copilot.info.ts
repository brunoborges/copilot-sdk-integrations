import { toSdkTools, type Copilot, type CopilotCapacity, type CopilotClientState } from "@copilot-sdk-integrations/core";
import {
  Controller,
  Get,
  Inject,
  Injectable,
  Module,
  Optional,
  UseGuards,
  type CanActivate,
  type DynamicModule,
  type Type,
} from "@nestjs/common";
import { COPILOT, COPILOT_INFO_OPTIONS } from "./constants.js";
import { CopilotAgents } from "./copilot-agents.service.js";
import { CopilotClientManager } from "./copilot-client.manager.js";

export interface CopilotInfoOptions {
  /** Include each agent's instructions. Default: false (they may contain sensitive prompt engineering). */
  includeInstructions?: boolean;
}

/** Read-only description of a registered agent. Never includes provider keys or MCP headers/env. */
export interface CopilotAgentInfo {
  name: string;
  description?: string;
  model?: string;
  reasoningEffort?: string;
  /** Named provider (`providers.<name>`), else the inline provider's `type` (or `"custom"`). */
  provider?: string;
  builtInTools: boolean;
  tools: string[];
  mcpServers: string[];
  availableTools?: string[];
  excludedTools?: string[];
  instructions?: unknown;
}

export interface CopilotInfo {
  state: CopilotClientState;
  /** Runtime version, when the runtime is running. */
  version?: string;
  /** Admission capacity; `activeTurns` counts in-flight turns (active sessions). */
  capacity: CopilotCapacity;
  agents: CopilotAgentInfo[];
}

/** Builds the info document (state, version, capacity, agents) without starting a lazy runtime. */
@Injectable()
export class CopilotInfoService {
  constructor(
    @Inject(COPILOT) private readonly copilot: Copilot,
    @Inject(CopilotAgents) private readonly agents: CopilotAgents,
    @Inject(CopilotClientManager) private readonly manager: CopilotClientManager,
  ) {}

  async info(options: CopilotInfoOptions = {}): Promise<CopilotInfo> {
    let health: Awaited<ReturnType<Copilot["health"]>>;
    try {
      health = await this.copilot.health();
    } catch {
      health = { state: this.copilot.state, capacity: this.copilot.capacity() };
    }
    const result: CopilotInfo = { state: health.state, capacity: health.capacity, agents: this.describeAgents(options) };
    if (health.version !== undefined) result.version = health.version;
    return result;
  }

  describeAgents(options: CopilotInfoOptions = {}): CopilotAgentInfo[] {
    const providers = Object.entries(this.manager.options.providers ?? {});
    return this.agents.names().map((name) => {
      const def = this.agents.definition(name);
      const info: CopilotAgentInfo = {
        name: def.name,
        builtInTools: def.builtInTools === true,
        tools: [...toSdkTools(def.tools ?? []).customToolNames].sort(),
        mcpServers: Object.keys(def.mcpServers ?? {}),
      };
      if (def.description !== undefined) info.description = def.description;
      if (def.model !== undefined) info.model = def.model;
      if (def.reasoningEffort !== undefined) info.reasoningEffort = def.reasoningEffort;
      if (def.provider !== undefined) {
        const named = providers.find(([, config]) => config === def.provider)?.[0];
        info.provider = named ?? (def.provider as { type?: string }).type ?? "custom";
      }
      if (def.availableTools !== undefined) info.availableTools = [...def.availableTools];
      if (def.excludedTools !== undefined) info.excludedTools = [...def.excludedTools];
      if (options.includeInstructions && def.instructions !== undefined) info.instructions = def.instructions;
      return info;
    });
  }
}

/**
 * Read-only info endpoint (`GET /copilot/info`), the counterpart of the Spring Actuator endpoint.
 * Not registered by `CopilotModule`: import `CopilotInfoModule.register({ guards })`, or add this
 * controller (or a subclass with your own `@Controller(path)` and guards) to a module's `controllers`.
 */
@Controller("copilot/info")
export class CopilotInfoController {
  constructor(
    @Inject(CopilotInfoService) private readonly service: CopilotInfoService,
    @Optional() @Inject(COPILOT_INFO_OPTIONS) private readonly options?: CopilotInfoOptions,
  ) {}

  @Get()
  info(): Promise<CopilotInfo> {
    return this.service.info(this.options ?? {});
  }
}

export interface CopilotInfoModuleOptions extends CopilotInfoOptions {
  /** Route path. Default `"copilot/info"`. */
  path?: string;
  /** Guards protecting the endpoint (strongly recommended outside development). */
  guards?: Array<Type<CanActivate> | CanActivate>;
}

/** Opt-in module exposing `CopilotInfoController`. Requires `CopilotModule.forRoot()`. */
@Module({})
export class CopilotInfoModule {
  static register(options: CopilotInfoModuleOptions = {}): DynamicModule {
    const { path = "copilot/info", guards = [], ...infoOptions } = options;
    class ConfiguredCopilotInfoController extends CopilotInfoController {}
    Controller(path)(ConfiguredCopilotInfoController);
    if (guards.length > 0) UseGuards(...guards)(ConfiguredCopilotInfoController);
    return {
      module: CopilotInfoModule,
      controllers: [ConfiguredCopilotInfoController],
      providers: [{ provide: COPILOT_INFO_OPTIONS, useValue: infoOptions }],
    };
  }
}
