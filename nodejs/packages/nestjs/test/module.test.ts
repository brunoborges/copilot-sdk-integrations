import "reflect-metadata";
import { Inject, Injectable, Module } from "@nestjs/common";
import { Test, type TestingModule } from "@nestjs/testing";
import { CopilotConfigurationError, CopilotShuttingDownError, type Copilot, type CopilotOptions } from "@copilot-sdk-integrations/agent-core";
import { createTestCopilot } from "@copilot-sdk-integrations/agent-core/testing";
import { afterEach, describe, expect, it } from "vitest";
import {
  COPILOT_CLIENT_CUSTOMIZER,
  COPILOT_SESSION_CUSTOMIZER,
  CopilotAgent,
  CopilotModule,
  InjectCopilot,
  InjectCopilotClient,
  Message,
  UserMessage,
  implementedByCopilot,
  type CopilotClientAccessor,
  type CopilotModuleOptions,
  type CopilotSessionCustomizer,
} from "../src/index.js";
import { CopilotTestRuntime, CopilotTestingModule } from "../src/testing/index.js";

@CopilotAgent({ name: "echo", instructions: "Echo." })
abstract class EchoAgent {
  @UserMessage()
  say(@Message() text: string): Promise<string> {
    return implementedByCopilot();
  }
}

@Injectable()
class Consumer {
  constructor(
    @InjectCopilot() readonly copilot: Copilot,
    @InjectCopilotClient() readonly client: CopilotClientAccessor,
  ) {}
}

/** A `copilotFactory` backed by a scripted runtime, to exercise `CopilotModule.forRoot` directly. */
function scripted(runtime: CopilotTestRuntime, seen?: CopilotOptions[]) {
  return (options: CopilotOptions) => {
    seen?.push(options);
    const test = createTestCopilot({ ...options, runtime });
    return { copilot: test.copilot, stop: (o?: { gracePeriodMs?: number }) => test.stop(o) };
  };
}

let moduleRef: TestingModule | undefined;
afterEach(async () => {
  await moduleRef?.close();
  moduleRef = undefined;
});

async function compile(imports: any[], providers: any[] = []): Promise<TestingModule> {
  moduleRef = await Test.createTestingModule({ imports, providers }).compile();
  await moduleRef.init();
  return moduleRef;
}

describe("module registration", () => {
  it("forRoot registers a global module with the Copilot, client accessor, and agents", async () => {
    const runtime = new CopilotTestRuntime();
    runtime.onPrompt(/.*/).reply("pong");
    const seen: CopilotOptions[] = [];

    @Module({ imports: [CopilotModule.forFeature([EchoAgent])], providers: [Consumer], exports: [Consumer] })
    class FeatureModule {}

    const ref = await compile([
      CopilotModule.forRoot({ copilotFactory: scripted(runtime, seen), responseTimeout: 5_000, shutdownGracePeriod: 100 }),
      FeatureModule,
    ]);
    await expect(ref.get(EchoAgent).say("ping")).resolves.toBe("pong");
    const consumer = ref.get(Consumer);
    expect(consumer.copilot.state).toBe("running");
    await expect(consumer.client()).resolves.toBeDefined();
    expect(seen[0].responseTimeout).toBe(5_000);
    expect(seen[0]).not.toHaveProperty("shutdownGracePeriod");
    expect(seen[0]).not.toHaveProperty("copilotFactory");
  });

  it("forRootAsync supports useFactory with inject (e.g. ConfigService)", async () => {
    const runtime = new CopilotTestRuntime();
    runtime.onPrompt(/.*/).reply("configured");

    @Injectable()
    class Config {
      get(key: string) {
        return ({ model: "gpt-config", timeout: 4_000 } as Record<string, unknown>)[key];
      }
    }
    @Module({ providers: [Config], exports: [Config] })
    class ConfigModule {}

    const ref = await compile([
      CopilotModule.forRootAsync({
        imports: [ConfigModule],
        inject: [Config],
        useFactory: (config: Config): CopilotModuleOptions => ({
          copilotFactory: scripted(runtime),
          responseTimeout: config.get("timeout") as number,
          agents: { echo: { model: config.get("model") as string } },
        }),
      }),
      CopilotModule.forFeature([EchoAgent]),
    ]);
    await expect(ref.get(EchoAgent).say("hi")).resolves.toBe("configured");
    expect(runtime.createdSessions[0].model).toBe("gpt-config");
  });

  it("CopilotTestingModule.forRootAsync resolves options from DI", async () => {
    const ref = await compile([
      CopilotTestingModule.forRootAsync({
        script: (rt) => rt.onPrompt(/.*/).reply("async"),
        useFactory: () => ({ agents: { echo: { model: "m-async" } } }),
      }),
      CopilotModule.forFeature([EchoAgent]),
    ]);
    await expect(ref.get(EchoAgent).say("hi")).resolves.toBe("async");
    expect(ref.get(CopilotTestRuntime).createdSessions[0].model).toBe("m-async");
  });

  it("rejects invalid options at bootstrap", async () => {
    await expect(compile([CopilotModule.forRoot({ cliPath: "/bin/copilot", cliUrl: "localhost:1234" })])).rejects.toThrow(
      CopilotConfigurationError,
    );
    await expect(compile([CopilotModule.forRoot({ startup: "sometimes" } as never)])).rejects.toThrow(/Invalid CopilotModule options/);
  });

  it("applies client and session customizer providers", async () => {
    const customize: CopilotSessionCustomizer = (config, _ctx, agent) => ({ ...config, model: `custom-${agent.name}` });
    const ref = await compile(
      [CopilotTestingModule.forRoot((rt) => rt.onPrompt(/.*/).reply("ok")), CopilotModule.forFeature([EchoAgent])],
      [
        { provide: COPILOT_SESSION_CUSTOMIZER, useValue: customize },
        { provide: COPILOT_CLIENT_CUSTOMIZER, useValue: [(o: Record<string, unknown>) => ({ ...o, logLevel: "debug" })] },
      ],
    );
    await ref.get(EchoAgent).say("hi");
    const runtime = ref.get(CopilotTestRuntime);
    expect(runtime.createdSessions[0].model).toBe("custom-echo");
    expect(runtime.clientOptions[0].logLevel).toBe("debug");
  });
});

describe("lifecycle", () => {
  it("starts eagerly on bootstrap when startup is eager, lazily otherwise", async () => {
    const lazy = await compile([CopilotTestingModule.forRoot()]);
    expect(lazy.get(CopilotTestRuntime).started).toBe(false);
    await lazy.close();

    const eager = await compile([CopilotTestingModule.forRoot(undefined, { startup: "eager" })]);
    expect(eager.get(CopilotTestRuntime).started).toBe(true);
  });

  it("drains in-flight turns on shutdown, then stops the runtime and rejects new turns", async () => {
    const ref = await compile([
      CopilotTestingModule.forRoot((rt) => rt.onPrompt(/.*/).delay(100).reply("drained"), { shutdownGracePeriod: 2_000 }),
      CopilotModule.forFeature([EchoAgent]),
    ]);
    const echo = ref.get(EchoAgent);
    const runtime = ref.get(CopilotTestRuntime);
    const inFlight = echo.say("hi");
    while (runtime.prompts.length === 0) await new Promise((r) => setTimeout(r, 5));
    await ref.close();
    moduleRef = undefined;
    await expect(inFlight).resolves.toBe("drained");
    expect(runtime.stopped).toBe(true);
    await expect(echo.say("again")).rejects.toBeInstanceOf(CopilotShuttingDownError);
  });

  it("aborts turns that exceed the shutdown grace period", async () => {
    const ref = await compile([
      CopilotTestingModule.forRoot((rt) => rt.onPrompt(/.*/).delay(10_000).reply("never"), { shutdownGracePeriod: 50 }),
      CopilotModule.forFeature([EchoAgent]),
    ]);
    const runtime = ref.get(CopilotTestRuntime);
    const inFlight = ref.get(EchoAgent).say("hi");
    inFlight.catch(() => undefined);
    while (runtime.prompts.length === 0) await new Promise((r) => setTimeout(r, 5));
    await ref.close();
    moduleRef = undefined;
    await expect(inFlight).rejects.toBeInstanceOf(CopilotShuttingDownError);
    expect(runtime.stopped).toBe(true);
  });
});

describe("injection helpers", () => {
  it("exposes the core Copilot via @InjectCopilot without starting it", async () => {
    @Injectable()
    class Holder {
      constructor(@Inject(Consumer) readonly consumer: Consumer) {}
    }
    const ref = await compile([CopilotTestingModule.forRoot()], [Consumer, Holder]);
    expect(ref.get(Holder).consumer.copilot.state).toBe("not-started");
  });
});
