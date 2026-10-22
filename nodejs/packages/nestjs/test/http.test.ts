import "reflect-metadata";
import { Controller, Get, Injectable, Module, Query, Sse, type INestApplication, type MessageEvent, type NestMiddleware } from "@nestjs/common";
import { Test } from "@nestjs/testing";
import { HealthCheck, HealthCheckService, TerminusModule } from "@nestjs/terminus";
import {
  CopilotAdmissionRejectedError,
  CopilotConfigurationError,
  CopilotConversationBusyError,
  CopilotConversationNotFoundError,
  CopilotOutputValidationError,
  CopilotRuntimeUnavailableError,
  CopilotShuttingDownError,
  CopilotTimeoutError,
  getCopilotContext,
  permissions,
  type CopilotAgentEvent,
  type CopilotContext,
} from "@copilot-sdk-integrations/core";
import { Observable } from "rxjs";
import request from "supertest";
import { afterEach, describe, expect, it } from "vitest";
import {
  ConversationId,
  CopilotAgent,
  CopilotHealthIndicator,
  CopilotModule,
  CopilotTokenResolver,
  CopilotTool,
  Message,
  UserMessage,
  implementedByCopilot,
  toSseEvents,
  type CopilotModuleOptions,
} from "../src/index.js";
import { CopilotTestRuntime, CopilotTestingModule } from "../src/testing/index.js";

@Injectable()
class ContextTools {
  @CopilotTool({ description: "Who is calling" })
  whoAmI() {
    const ctx = getCopilotContext();
    return { principal: ctx?.principal, tenant: (ctx?.attributes.headers as Record<string, string> | undefined)?.["x-tenant"] };
  }
}

@CopilotAgent({ name: "web", tools: [ContextTools], permissions: permissions.allowCustomTools() })
abstract class WebAgent {
  @UserMessage()
  ask(@Message() q: string): Promise<string> {
    return implementedByCopilot();
  }

  @UserMessage()
  chat(@ConversationId() id: string, @Message() q: string): Observable<CopilotAgentEvent> {
    return implementedByCopilot();
  }
}

const errors: Record<string, () => Error> = {
  admission: () => new CopilotAdmissionRejectedError(undefined, 7),
  busy: () => new CopilotConversationBusyError("c1"),
  missing: () => new CopilotConversationNotFoundError("c1"),
  timeout: () => new CopilotTimeoutError("slow" as never),
  unavailable: () => new CopilotRuntimeUnavailableError("down"),
  shutdown: () => new CopilotShuttingDownError(),
  output: () => new CopilotOutputValidationError("bad json"),
  config: () => new CopilotConfigurationError("secret detail"),
};

@Controller()
class WebController {
  constructor(
    private readonly agent: WebAgent,
    private readonly health: HealthCheckService,
    private readonly copilotHealth: CopilotHealthIndicator,
  ) {}

  @Get("ask")
  ask(@Query("q") q: string) {
    return this.agent.ask(q).then((answer) => ({ answer }));
  }

  @Sse("chat")
  chat(@Query("q") q: string): Observable<MessageEvent> {
    return this.agent.chat("c1", q).pipe(toSseEvents());
  }

  @Get("error")
  error(@Query("kind") kind: string) {
    throw errors[kind]();
  }

  @Get("health")
  @HealthCheck()
  check() {
    return this.health.check([() => this.copilotHealth.isHealthy("copilot")]);
  }
}

@Injectable()
class FakeAuth implements NestMiddleware {
  use(req: any, _res: unknown, next: () => void) {
    const user = req.headers["x-user"];
    if (user) req.user = { login: user, accessToken: `gho_${user}` };
    next();
  }
}

@Injectable()
class UserTokenResolver extends CopilotTokenResolver {
  resolve(context: CopilotContext) {
    return (context.attributes.user as { accessToken?: string } | undefined)?.accessToken;
  }
}

let app: INestApplication | undefined;
afterEach(async () => {
  await app?.close();
  app = undefined;
});

async function createApp(
  script: (rt: CopilotTestRuntime) => unknown,
  options: Omit<CopilotModuleOptions, "copilotFactory"> = {},
): Promise<{ app: INestApplication; runtime: CopilotTestRuntime }> {
  @Module({
    imports: [
      CopilotTestingModule.forRoot(script, { context: { headers: ["X-Tenant"] }, ...options }),
      CopilotModule.forFeature([WebAgent]),
      TerminusModule,
    ],
    controllers: [WebController],
    providers: [ContextTools, FakeAuth, { provide: CopilotTokenResolver, useClass: UserTokenResolver }],
  })
  class AppModule {
    configure(consumer: any) {
      consumer.apply(FakeAuth).forRoutes("*path");
    }
  }
  const moduleRef = await Test.createTestingModule({ imports: [AppModule] }).compile();
  app = moduleRef.createNestApplication({ logger: false });
  await app.init();
  return { app, runtime: app.get(CopilotTestRuntime) };
}

describe("HTTP integration", () => {
  it("propagates the request principal and headers into tools, and resolves per-user tokens", async () => {
    const { app, runtime } = await createApp((rt) => rt.onPrompt(/.*/).callTool("whoAmI").thenReply("checked"));
    const res = await request(app.getHttpServer()).get("/ask?q=who").set("x-user", "octocat").set("x-tenant", "acme").expect(200);
    expect(res.body).toEqual({ answer: "checked" });
    expect(JSON.parse(runtime.toolCalls[0].result as string)).toEqual({ principal: "octocat", tenant: "acme" });
    expect(runtime.createdSessions[0].gitHubToken).toBe("gho_octocat");

    await request(app.getHttpServer()).get("/ask?q=anon").expect(200);
    expect(JSON.parse(runtime.toolCalls[1].result as string)).toEqual({});
    expect(runtime.createdSessions[1].gitHubToken).toBeUndefined();
  });

  it("streams agent events over SSE", async () => {
    const { app } = await createApp((rt) => rt.onPrompt(/.*/).streamReply(["Hi ", "there"]));
    const res = await request(app.getHttpServer()).get("/chat?q=hello").set("x-user", "octocat").expect(200);
    expect(res.headers["content-type"]).toMatch(/text\/event-stream/);
    const events = res.text
      .split("\n\n")
      .filter((block) => block.trim() !== "")
      .map((block) => {
        const type = /^event: (.*)$/m.exec(block)?.[1];
        const data = JSON.parse(/^data: (.*)$/m.exec(block)?.[1] ?? "null");
        return { type, data };
      });
    expect(events.filter((e) => e.type === "text-delta").map((e) => e.data.delta)).toEqual(["Hi ", "there"]);
    expect(events.at(-1)).toMatchObject({ type: "done", data: { type: "done", text: "Hi there" } });
  });

  it.each([
    ["admission", 429],
    ["busy", 409],
    ["missing", 404],
    ["timeout", 504],
    ["unavailable", 503],
    ["shutdown", 503],
    ["output", 502],
    ["config", 500],
  ])("maps %s errors to HTTP %i", async (kind, status) => {
    const { app } = await createApp(() => undefined);
    const res = await request(app.getHttpServer()).get(`/error?kind=${kind}`).expect(status);
    expect(res.body.statusCode).toBe(status);
    if (kind === "admission") expect(res.headers["retry-after"]).toBe("7");
    if (kind === "config") expect(res.body.message).toBe("Internal server error");
    else expect(res.body.error).toMatch(/^Copilot/);
  });

  it("returns 429 with Retry-After when admission is saturated", async () => {
    const { app, runtime } = await createApp((rt) => rt.onPrompt(/slow/).delay(300).reply("done").onPrompt(/.*/).reply("fast"), {
      limits: { maxConcurrentTurns: 1, maxQueuedTurns: 0 },
    });
    const server = app.getHttpServer();
    const slow = request(server).get("/ask?q=slow").then((r) => r);
    while (runtime.prompts.length === 0) await new Promise((r) => setTimeout(r, 5));
    const rejected = await request(server).get("/ask?q=other").expect(429);
    expect(rejected.headers["retry-after"]).toBeDefined();
    expect((await slow).body).toEqual({ answer: "done" });
  });

  it("aborts the turn when the client disconnects", async () => {
    const { app, runtime } = await createApp((rt) => rt.onPrompt(/.*/).delay(5_000).reply("late"));
    await expect(request(app.getHttpServer()).get("/ask?q=wait").timeout(150)).rejects.toThrow(/Timeout/);
    for (let i = 0; i < 100 && runtime.abortCount === 0; i++) await new Promise((r) => setTimeout(r, 10));
    expect(runtime.abortCount).toBe(1);
  });

  it("reports health via Terminus without starting a lazy runtime", async () => {
    const { app, runtime } = await createApp((rt) => rt.onPrompt(/.*/).reply("ok"));
    const before = await request(app.getHttpServer()).get("/health").expect(200);
    expect(before.body.details.copilot).toMatchObject({ status: "up", state: "not-started" });
    expect(runtime.started).toBe(false);

    await request(app.getHttpServer()).get("/ask?q=hi").expect(200);
    const after = await request(app.getHttpServer()).get("/health").expect(200);
    expect(after.body.details.copilot).toMatchObject({
      status: "up",
      state: "running",
      version: "test",
      capacity: { activeTurns: 0, queuedTurns: 0 },
    });

    await app.get(CopilotHealthIndicator)["copilot"].stop();
    const down = await request(app.getHttpServer()).get("/health").expect(503);
    expect(down.body.error.copilot).toMatchObject({ status: "down", state: "stopped" });
  });

  it("answers 503 from /health after the runtime connection is lost, and 200 once it restarts", async () => {
    const { app, runtime } = await createApp((rt) => rt.onPrompt(/.*/).reply("ok"), {
      restart: { backoffMs: 10, checkIntervalMs: 60_000 },
    });
    await request(app.getHttpServer()).get("/ask?q=hi").expect(200);
    await request(app.getHttpServer()).get("/health").expect(200);

    runtime.crash();
    const down = await request(app.getHttpServer()).get("/health").expect(503);
    expect(down.body.status).toBe("error");
    expect(down.body.error.copilot).toMatchObject({ status: "down", state: "failed" });
    expect(runtime.started).toBe(false);

    await request(app.getHttpServer()).get("/ask?q=again").expect(200);
    expect(runtime.started).toBe(true);
    const up = await request(app.getHttpServer()).get("/health").expect(200);
    expect(up.body.details.copilot).toMatchObject({ status: "up", state: "running" });
  });

  it("can disable the exception filter and the context interceptor", async () => {
    const { app, runtime } = await createApp((rt) => rt.onPrompt(/.*/).callTool("whoAmI").thenReply("ok"), {
      exceptionFilter: false,
      context: false,
    });
    await request(app.getHttpServer()).get("/error?kind=missing").expect(500);
    await request(app.getHttpServer()).get("/ask?q=x").set("x-user", "octocat").expect(200);
    expect(JSON.parse(runtime.toolCalls[0].result as string)).toEqual({});
  });
});
