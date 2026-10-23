import "reflect-metadata";
import { CopilotTestRuntime, createTestCopilot, silentLogger, type CopilotTestRuntimeOptions } from "@copilot-sdk-integrations/agent-core/testing";
import type { CopilotOptions } from "@copilot-sdk-integrations/agent-core";
import { Module, type DynamicModule, type ModuleMetadata } from "@nestjs/common";
import { CopilotModule } from "../copilot.module.js";
import type { CopilotModuleOptions } from "../interfaces.js";

export {
  CopilotTestRuntime,
  PromptScript,
  TestSession,
  createTestCopilot,
  silentLogger,
  type CopilotTestRuntimeOptions,
  type PromptMatcher,
  type TestModel,
  type TestPermissionRequest,
  type TestPrompt,
  type TestToolCall,
  type TestTurn,
  type TestUsage,
} from "@copilot-sdk-integrations/agent-core/testing";

export interface CopilotTestingModuleOptions
  extends Omit<CopilotModuleOptions, "copilotFactory" | "cliPath" | "cliUrl" | "clientFactory">,
    CopilotTestRuntimeOptions {}

export interface CopilotTestingModuleAsyncOptions extends Pick<ModuleMetadata, "imports"> {
  script?: (runtime: CopilotTestRuntime) => unknown;
  useFactory: (...args: any[]) => CopilotTestingModuleOptions | Promise<CopilotTestingModuleOptions>;
  inject?: any[];
}

@Module({})
class CopilotTestRuntimeHolder {}

const TEST_DEFAULT_SHUTDOWN_GRACE_MS = 1_000;

function runtimeModule(script: ((runtime: CopilotTestRuntime) => unknown) | undefined, runtimeOptions: CopilotTestRuntimeOptions): DynamicModule {
  return {
    module: CopilotTestRuntimeHolder,
    providers: [
      {
        provide: CopilotTestRuntime,
        useFactory: () => {
          const runtime = new CopilotTestRuntime(runtimeOptions);
          script?.(runtime);
          return runtime;
        },
      },
    ],
    exports: [CopilotTestRuntime],
  };
}

function toModuleOptions(runtime: CopilotTestRuntime, options: CopilotTestingModuleOptions = {}): CopilotModuleOptions {
  const { models, defaultModel, ...rest } = options;
  if (models !== undefined) runtime.setModels(models);
  return {
    logger: silentLogger(),
    shutdownGracePeriod: TEST_DEFAULT_SHUTDOWN_GRACE_MS,
    ...compact(rest),
    copilotFactory: (coreOptions: CopilotOptions) => {
      const test = createTestCopilot({ ...compact(coreOptions), runtime });
      return { copilot: test.copilot, stop: (o) => test.stop(o) };
    },
  };
}

function compact<T extends object>(value: T): T {
  return Object.fromEntries(Object.entries(value).filter(([, v]) => v !== undefined)) as T;
}

/**
 * Replaces the Copilot runtime with core's scripted `CopilotTestRuntime` (no process, credentials,
 * or paid requests). Registers `CopilotModule` globally; inject `CopilotTestRuntime` to script
 * replies and inspect recordings.
 *
 * ```ts
 * Test.createTestingModule({
 *   imports: [
 *     CopilotTestingModule.forRoot((rt) => rt.onPrompt(/.*\/).reply("Hello")),
 *     CopilotModule.forFeature([SupportAgent]),
 *   ],
 * })
 * ```
 */
@Module({})
export class CopilotTestingModule {
  static forRoot(script?: (runtime: CopilotTestRuntime) => unknown, options: CopilotTestingModuleOptions = {}): DynamicModule {
    const { models, defaultModel, ...rest } = options;
    const runtimeOptions: CopilotTestRuntimeOptions = compact({ models, defaultModel });
    const holder = runtimeModule(script, runtimeOptions);
    return {
      module: CopilotTestingModule,
      global: true,
      imports: [
        holder,
        CopilotModule.forRootAsync({
          imports: [holder],
          inject: [CopilotTestRuntime],
          useFactory: (runtime: CopilotTestRuntime) => toModuleOptions(runtime, rest),
        }),
      ],
      exports: [holder, CopilotModule],
    };
  }

  static forRootAsync(options: CopilotTestingModuleAsyncOptions): DynamicModule {
    const holder = runtimeModule(options.script, {});
    return {
      module: CopilotTestingModule,
      global: true,
      imports: [
        holder,
        CopilotModule.forRootAsync({
          imports: [holder, ...(options.imports ?? [])],
          inject: [CopilotTestRuntime, ...(options.inject ?? [])],
          useFactory: async (runtime: CopilotTestRuntime, ...args: unknown[]) =>
            toModuleOptions(runtime, await options.useFactory(...args)),
        }),
      ],
      exports: [holder, CopilotModule],
    };
  }
}
