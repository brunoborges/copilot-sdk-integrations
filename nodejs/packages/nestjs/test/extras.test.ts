import "reflect-metadata";
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { pathToFileURL } from "node:url";
import {
  Controller,
  Get,
  Injectable,
  Module,
  type CanActivate,
  type ExecutionContext,
  type INestApplication,
} from "@nestjs/common";
import { EventEmitterModule, OnEvent } from "@nestjs/event-emitter";
import { Test, type TestingModule } from "@nestjs/testing";
import { CopilotConfigurationError, getCopilotContext, permissions, runWithCopilotContext, type CopilotAgentEvent, type CopilotContext } from "@copilot-sdk-integrations/agent-core";
import { ClsModule, ClsService } from "nestjs-cls";
import { Observable, lastValueFrom, tap, toArray } from "rxjs";
import request from "supertest";
import { afterAll, afterEach, describe, expect, it } from "vitest";
import {
  ConversationId,
  CopilotAgent,
  CopilotAgents,
  CopilotExplorer,
  CopilotInfoModule,
  CopilotInfoService,
  CopilotModule,
  CopilotTool,
  Message,
  P,
  UserMessage,
  UserMessageFrom,
  implementedByCopilot,
  resolveTemplatePath,
} from "../src/index.js";
import { CLS_STORE_ATTRIBUTE, CopilotClsModule } from "../src/cls/index.js";
import { CopilotTestRuntime, CopilotTestingModule } from "../src/testing/index.js";

let moduleRef: TestingModule | undefined;
let app: INestApplication | undefined;
afterEach(async () => {
  await app?.close();
  await moduleRef?.close();
  app = undefined;
  moduleRef = undefined;
});

async function compile(imports: any[], providers: any[] = []): Promise<TestingModule> {
  moduleRef = await Test.createTestingModule({ imports, providers }).compile();
  await moduleRef.init();
  return moduleRef;
}

@Injectable()
class WhoTools {
  constructor(private readonly cls: ClsService) {}

  @CopilotTool({ description: "Who is calling" })
  whoAmI() {
    const ctx = getCopilotContext();
    return { principal: ctx?.principal ?? null, tenant: this.cls.isActive() ? (this.cls.get("tenant") ?? null) : "no-cls" };
  }
}

@CopilotAgent({ name: "who", tools: [WhoTools], permissions: permissions.allowCustomTools(), model: "gpt-x", instructions: "secret prompt" })
abstract class WhoAgent {
  @UserMessage()
  ask(@Message() q: string): Promise<string> {
    return implementedByCopilot();
  }

  @UserMessage()
  talk(@ConversationId() id: string, @Message() q: string): Promise<string> {
    return implementedByCopilot();
  }

  @UserMessage({ stream: "async-iterable" })
  iterate(@Message() q: string): AsyncIterable<CopilotAgentEvent> {
    return implementedByCopilot();
  }
}

const callWho = (rt: CopilotTestRuntime) => rt.onPrompt(/.*/).callTool("whoAmI").thenReply("ok");

describe("nestjs-cls integration", () => {
  async function clsApp(clsOptions?: Parameters<typeof CopilotClsModule.forRoot>[0]) {
    const ref = await compile(
      [
        ClsModule.forRoot({ global: true }),
        CopilotTestingModule.forRoot(callWho),
        CopilotClsModule.forRoot(clsOptions),
        CopilotModule.forFeature([WhoAgent]),
      ],
      [WhoTools],
    );
    return { ref, cls: ref.get(ClsService), runtime: ref.get(CopilotTestRuntime), agent: ref.get(WhoAgent) };
  }

  it("reads the principal from CLS in non-HTTP entry points (default mapping)", async () => {
    const { cls, runtime, agent, ref } = await clsApp();
    await cls.run(async () => {
      cls.set("user", { login: "alice" });
      cls.set("tenant", "acme");
      await agent.talk("c1", "hi");
    });
    expect(JSON.parse(runtime.toolCalls[0]!.result as string)).toEqual({ principal: "alice", tenant: "acme" });
    // The principal feeds the session id: another user can't resume alice's conversation.
    await expect(
      cls.run(() => {
        cls.set("principal", "bob");
        return ref.get(CopilotAgents).bind(WhoAgent, "c1").talk("again");
      }),
    ).rejects.toThrow(/does not exist/);
    // Outside a CLS context nothing is contributed.
    await agent.ask("anon");
    expect(JSON.parse(runtime.toolCalls[1]!.result as string)).toEqual({ principal: null, tenant: "no-cls" });
  });

  it("uses a custom mapping, and captures CLS when a lazy stream is created", async () => {
    const { cls, runtime, agent } = await clsApp({
      context: (c) => ({ principal: c.get("userId"), attributes: { tenant: c.get("tenant") } }),
    });
    const iterable = cls.run(() => {
      cls.set("userId", "u-7");
      cls.set("tenant", "globex");
      return agent.iterate("stream it");
    });
    // Iterated outside the CLS scope: the store captured at call time is restored around the tool.
    const events: CopilotAgentEvent[] = [];
    for await (const event of iterable) events.push(event);
    expect(events.at(-1)).toMatchObject({ type: "done", text: "ok" });
    expect(JSON.parse(runtime.toolCalls[0]!.result as string)).toEqual({ principal: "u-7", tenant: "globex" });
  });

  it("restores the captured CLS store around tools invoked outside the CLS context", async () => {
    const { cls, ref } = await clsApp();
    const store = { tenant: "initech" };
    const [tool] = ref.get(CopilotExplorer).toolsOf(WhoTools);
    const context = { principal: "p", attributes: { [CLS_STORE_ATTRIBUTE]: store } } as unknown as CopilotContext;
    expect(cls.isActive()).toBe(false);
    const result = await tool!.execute({}, { toolCallId: "t", toolName: "whoAmI", sessionId: "s", context });
    expect(result).toMatchObject({ tenant: "initech" });
  });

  it("reads CLS populated by HTTP middleware (no request.user needed)", async () => {
    @Controller()
    class AskController {
      constructor(private readonly agent: WhoAgent) {}
      @Get("ask")
      ask() {
        return this.agent.ask("hi").then((answer) => ({ answer }));
      }
    }
    @Module({
      imports: [
        ClsModule.forRoot({
          global: true,
          middleware: {
            mount: true,
            setup: (cls, req: any) => {
              cls.set("principal", req.headers["x-user"]);
              cls.set("tenant", req.headers["x-tenant"]);
            },
          },
        }),
        CopilotTestingModule.forRoot(callWho),
        CopilotClsModule.forRoot(),
        CopilotModule.forFeature([WhoAgent]),
      ],
      controllers: [AskController],
      providers: [WhoTools],
    })
    class AppModule {}
    const ref = await Test.createTestingModule({ imports: [AppModule] }).compile();
    app = ref.createNestApplication({ logger: false });
    await app.init();
    await request(app.getHttpServer()).get("/ask").set("x-user", "carol").set("x-tenant", "umbrella").expect(200, { answer: "ok" });
    expect(JSON.parse(app.get(CopilotTestRuntime).toolCalls[0]!.result as string)).toEqual({ principal: "carol", tenant: "umbrella" });
  });
});

describe("CopilotInfoModule", () => {
  @Injectable()
  class TokenGuard implements CanActivate {
    canActivate(ctx: ExecutionContext) {
      return ctx.switchToHttp().getRequest().headers["x-admin"] === "yes";
    }
  }

  async function infoApp(info: Parameters<typeof CopilotInfoModule.register>[0], extra: any[] = []) {
    @Module({
      imports: [
        CopilotTestingModule.forRoot(callWho, {
          providers: { foundry: { type: "azure", baseUrl: "https://x", apiKey: "sk-secret" } },
          mcpServers: { docs: { type: "http", url: "http://docs", headers: { Authorization: "Bearer secret" } } },
          agents: { who: { provider: "foundry", mcpServers: ["docs"] } },
        }),
        ClsModule.forRoot({ global: true }),
        CopilotModule.forFeature([WhoAgent]),
        CopilotInfoModule.register(info),
        ...extra,
      ],
      providers: [WhoTools, TokenGuard],
    })
    class AppModule {}
    const ref = await Test.createTestingModule({ imports: [AppModule] }).compile();
    app = ref.createNestApplication({ logger: false });
    await app.init();
    return app;
  }

  it("lists agents, capacity, and state without starting the runtime or leaking secrets", async () => {
    const app = await infoApp({ guards: [TokenGuard] });
    await request(app.getHttpServer()).get("/copilot/info").expect(403);
    const res = await request(app.getHttpServer()).get("/copilot/info").set("x-admin", "yes").expect(200);
    expect(res.body).toEqual({
      state: "not-started",
      capacity: expect.objectContaining({ activeTurns: 0, maxConcurrentTurns: expect.any(Number) }),
      agents: [
        {
          name: "who",
          model: "gpt-x",
          provider: "foundry",
          builtInTools: false,
          tools: ["whoAmI"],
          mcpServers: ["docs"],
        },
      ],
    });
    expect(JSON.stringify(res.body)).not.toMatch(/secret/);
    expect(app.get(CopilotTestRuntime).started).toBe(false);
  });

  it("supports a custom path and opt-in instructions; is not registered by default", async () => {
    const app = await infoApp({ path: "admin/copilot", includeInstructions: true });
    const res = await request(app.getHttpServer()).get("/admin/copilot").expect(200);
    expect(res.body.agents[0].instructions).toBe("secret prompt");
    await request(app.getHttpServer()).get("/copilot/info").expect(404);
    expect((await app.get(CopilotInfoService).info()).agents[0]).not.toHaveProperty("instructions");
  });
});

describe("@nestjs/event-emitter re-emission", () => {
  const received: Array<{ name: string; type: string; agent?: string }> = [];

  @Injectable()
  class Listeners {
    @OnEvent("copilot.done")
    onDone(event: CopilotAgentEvent, context: CopilotContext) {
      received.push({ name: "done", type: event.type, agent: context.agentName });
    }

    @OnEvent("copilot.tool-end")
    onToolEnd(event: CopilotAgentEvent) {
      received.push({ name: "tool-end", type: event.type });
      throw new Error("listener failure must not break the turn");
    }

    @OnEvent("ai.turn-start")
    onPrefixed(event: CopilotAgentEvent) {
      received.push({ name: "prefixed", type: event.type });
    }
  }

  afterEach(() => {
    received.length = 0;
  });

  it("re-emits events as copilot.<type> with (event, context); failures are swallowed", async () => {
    const ref = await compile(
      [
        EventEmitterModule.forRoot(),
        ClsModule.forRoot({ global: true }),
        CopilotTestingModule.forRoot(callWho, { eventEmitter: true }),
        CopilotModule.forFeature([WhoAgent]),
      ],
      [WhoTools, Listeners],
    );
    await expect(ref.get(WhoAgent).ask("hi")).resolves.toBe("ok");
    expect(received).toEqual([
      { name: "tool-end", type: "tool-end" },
      { name: "done", type: "done", agent: "who" },
    ]);
  });

  it("supports a custom prefix and is off by default", async () => {
    let ref = await compile(
      [
        EventEmitterModule.forRoot(),
        ClsModule.forRoot({ global: true }),
        CopilotTestingModule.forRoot(callWho, { eventEmitter: { prefix: "ai" } }),
        CopilotModule.forFeature([WhoAgent]),
      ],
      [WhoTools, Listeners],
    );
    await ref.get(WhoAgent).ask("hi");
    expect(received).toEqual([{ name: "prefixed", type: "turn-start" }]);
    await ref.close();
    received.length = 0;

    ref = await compile(
      [EventEmitterModule.forRoot(), ClsModule.forRoot({ global: true }), CopilotTestingModule.forRoot(callWho), CopilotModule.forFeature([WhoAgent])],
      [WhoTools, Listeners],
    );
    await ref.get(WhoAgent).ask("hi");
    expect(received).toEqual([]);
  });

  it("fails at bootstrap when enabled without EventEmitterModule", async () => {
    await expect(
      compile([ClsModule.forRoot({ global: true }), CopilotTestingModule.forRoot(callWho, { eventEmitter: true }), CopilotModule.forFeature([WhoAgent])], [WhoTools]),
    ).rejects.toThrow(/EventEmitterModule\.forRoot\(\)/);
  });
});

describe("MCP servers with request-context headers", () => {
  it("resolves header functions per session from the caller's context and token", async () => {
    @CopilotAgent({ name: "mcp", mcpServers: ["github"] })
    abstract class McpAgent {
      @UserMessage()
      ask(@Message() q: string): Promise<string> {
        return implementedByCopilot();
      }
    }
    const ref = await compile([
      CopilotTestingModule.forRoot((rt) => rt.onPrompt(/.*/).reply("ok"), {
        tokenResolver: (ctx) => (ctx.principal ? `gho_${ctx.principal}` : undefined),
        mcpServers: {
          github: {
            type: "http",
            url: "https://api.githubcopilot.com/mcp/",
            headers: { Authorization: (ctx) => (ctx.githubToken ? `Bearer ${ctx.githubToken}` : undefined) },
          },
        },
      }),
      CopilotModule.forFeature([McpAgent]),
    ]);
    const agents = ref.get(CopilotAgents);
    await agents.handle(McpAgent).send({ prompt: "x", context: { principal: "alice" } });
    await ref.get(McpAgent).ask("anonymous");
    const [first, second] = ref.get(CopilotTestRuntime).createdSessions;
    expect(first!.mcpServers!.github).toEqual({
      type: "http",
      url: "https://api.githubcopilot.com/mcp/",
      headers: { Authorization: "Bearer gho_alice" },
    });
    expect((second!.mcpServers!.github as { headers?: unknown }).headers).toEqual({});
  });
});

describe("CopilotAgents.respondToPermission", () => {
  @Injectable()
  class RefundTools {
    @CopilotTool({ description: "Refund an order" })
    refund() {
      return "refunded";
    }
  }

  @CopilotAgent({ name: "refunds", tools: [RefundTools], permissions: permissions.askUser() })
  abstract class RefundAgent {
    @UserMessage()
    chat(@ConversationId() id: string, @Message() q: string): Observable<CopilotAgentEvent> {
      return implementedByCopilot();
    }

    @UserMessage()
    ask(@Message() q: string): Promise<string> {
      return implementedByCopilot();
    }
  }

  async function refundApp() {
    const ref = await compile(
      [CopilotTestingModule.forRoot((rt) => rt.onPrompt(/.*/).callTool("refund").thenReply("handled")), CopilotModule.forFeature([RefundAgent])],
      [RefundTools],
    );
    return { agents: ref.get(CopilotAgents), agent: ref.get(RefundAgent), runtime: ref.get(CopilotTestRuntime) };
  }

  it("resolves a pending ask-user permission emitted on a stream", async () => {
    const { agents, agent, runtime } = await refundApp();
    const results: boolean[] = [];
    const events = await lastValueFrom(
      agent.chat("c1", "refund order 7").pipe(
        tap((event) => {
          if (event.type !== "permission-request") return;
          results.push(agents.respondToPermission("other-conversation", event.requestId, true));
          results.push(agents.respondToPermission("c1", "unknown-id", true));
          results.push(agents.respondToPermission("c1", event.requestId, true, { principal: "mallory" }));
          results.push(agents.respondToPermission("c1", event.requestId, true));
          results.push(agents.respondToPermission("c1", event.requestId, false));
        }),
        toArray(),
      ),
    );
    expect(results).toEqual([false, false, false, true, false]);
    expect(events.map((e) => e.type)).toEqual(expect.arrayContaining(["permission-request", "permission-resolved", "tool-end", "done"]));
    expect(events.find((e) => e.type === "permission-resolved")).toMatchObject({ approved: true });
    expect(runtime.toolCalls[0]).toMatchObject({ name: "refund", approved: true, result: "refunded" });
  });

  it("only lets the principal that started the turn answer", async () => {
    const { agents, agent, runtime } = await refundApp();
    const results: boolean[] = [];
    await lastValueFrom(
      runWithCopilotContext({ principal: "alice" }, () => agent.chat("c3", "refund order 9")).pipe(
        tap((event) => {
          if (event.type !== "permission-request") return;
          results.push(agents.respondToPermission("c3", event.requestId, true, { principal: "bob" }));
          results.push(runWithCopilotContext({ principal: "bob" }, () => agents.respondToPermission("c3", event.requestId, true)));
          results.push(agents.respondToPermission("c3", event.requestId, true, { principal: undefined }));
          results.push(runWithCopilotContext({ principal: "alice" }, () => agents.respondToPermission("c3", event.requestId, true)));
        }),
        toArray(),
      ),
    );
    expect(results).toEqual([false, false, false, true]);
    expect(runtime.toolCalls[0]).toMatchObject({ name: "refund", approved: true });
  });

  it("denies with feedback, and forgets requests once the stream ends", async () => {
    const { agents, agent, runtime } = await refundApp();
    let requestId = "";
    await lastValueFrom(
      agent.chat("c2", "refund order 8").pipe(
        tap((event) => {
          if (event.type === "permission-request") {
            requestId = event.requestId;
            agents.respondToPermission("c2", event.requestId, { approved: false, feedback: "not allowed" });
          }
        }),
        toArray(),
      ),
    );
    expect(runtime.toolCalls[0]).toMatchObject({ name: "refund", approved: false });
    expect(agents.respondToPermission("c2", requestId, true)).toBe(false);
  });

  it("treats ask-user as denial on non-streaming calls", async () => {
    const { agent, runtime } = await refundApp();
    await expect(agent.ask("refund")).resolves.toBe("handled");
    expect(runtime.toolCalls[0]).toMatchObject({ approved: false });
  });
});

describe("@UserMessageFrom template resolution", () => {
  const dir = mkdtempSync(join(tmpdir(), "copilot-templates-test-"));
  mkdirSync(join(dir, "prompts"));
  writeFileSync(join(dir, "prompts", "greet.md"), "Greet {{name}} warmly.");
  afterAll(() => rmSync(dir, { recursive: true, force: true }));

  it("resolves relative paths against templatesDir, and absolute paths and file URLs as is", async () => {
    const absolute = join(dir, "prompts", "greet.md");
    const url = pathToFileURL(absolute);

    @CopilotAgent({ name: "greeter" })
    abstract class Greeter {
      @UserMessageFrom("prompts/greet.md")
      relative(@P("name") name: string): Promise<string> {
        return implementedByCopilot();
      }

      @UserMessageFrom(absolute)
      absolute(@P("name") name: string): Promise<string> {
        return implementedByCopilot();
      }

      @UserMessageFrom(url)
      url(@P("name") name: string): Promise<string> {
        return implementedByCopilot();
      }
    }
    const ref = await compile([
      CopilotTestingModule.forRoot((rt) => rt.onPrompt(/.*/).reply("hi"), { templatesDir: dir }),
      CopilotModule.forFeature([Greeter]),
    ]);
    const greeter = ref.get(Greeter);
    await greeter.relative("Ada");
    await greeter.absolute("Bob");
    await greeter.url("Cy");
    expect(ref.get(CopilotTestRuntime).prompts).toEqual(["Greet Ada warmly.", "Greet Bob warmly.", "Greet Cy warmly."]);
  });

  it("defaults to the working directory and names the resolved file on errors", async () => {
    expect(resolveTemplatePath("a/b.md")).toBe(join(process.cwd(), "a/b.md"));
    expect(resolveTemplatePath("a/b.md", "/base")).toBe("/base/a/b.md");
    expect(resolveTemplatePath("file:///x/y.md", "/base")).toBe("/x/y.md");

    @CopilotAgent({ name: "missing" })
    abstract class Missing {
      @UserMessageFrom("prompts/nope.md")
      go(): Promise<string> {
        return implementedByCopilot();
      }
    }
    const error = await compile([
      CopilotTestingModule.forRoot(undefined, { templatesDir: dir }),
      CopilotModule.forFeature([Missing]),
    ]).catch((e: unknown) => e);
    expect(error).toBeInstanceOf(CopilotConfigurationError);
    expect((error as Error).message).toContain(join(dir, "prompts", "nope.md"));
  });
});
