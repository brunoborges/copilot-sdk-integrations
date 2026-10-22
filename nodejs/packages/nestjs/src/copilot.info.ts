import { describeAgent, type Copilot, type CopilotAgentInfo, type CopilotInfo } from "@copilot-sdk-integrations/core";
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

export type { CopilotAgentInfo, CopilotInfo } from "@copilot-sdk-integrations/core";

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
    const providers = this.manager.options.providers ?? {};
    return this.agents
      .names()
      .map((name) => describeAgent(this.agents.definition(name), { providers, includeInstructions: options.includeInstructions === true }));
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
