import "reflect-metadata";
import { Injectable, Module, Scope } from "@nestjs/common";
import { Test, type TestingModule } from "@nestjs/testing";
import { CopilotConfigurationError, CopilotConversationNotFoundError, defineAgent, getCopilotContext, permissions, type AgentHandle, type CopilotAgentEvent } from "@copilot-sdk-integrations/agent-core";
import { Observable, firstValueFrom, lastValueFrom, toArray } from "rxjs";
import { afterEach, describe, expect, it } from "vitest";
import { z } from "zod";
import {
  ConversationId,
  CopilotAgent,
  CopilotAgents,
  CopilotHook,
  CopilotModule,
  CopilotTool,
  InjectAgent,
  Message,
  OnCopilotEvent,
  P,
  Returns,
  Tools,
  UseToolInterceptors,
  UserMessage,
  implementedByCopilot,
  type CopilotToolExecutionContext,
  type CopilotToolInterceptor,
  type CopilotToolInvocation,
} from "../src/index.js";
import { CopilotTestRuntime, CopilotTestingModule } from "../src/testing/index.js";

@Injectable()
class OrdersService {
  find(id: string) {
    return { id, status: "shipped" };
  }
}

@Injectable()
class OrdersTools {
  constructor(private readonly orders: OrdersService) {}

  @CopilotTool({ description: "Look up an order", input: z.object({ id: z.string() }) })
  lookupOrder({ id }: { id: string }, invocation: CopilotToolInvocation) {
    return { ...this.orders.find(id), principal: invocation.context.principal ?? null, ambient: getCopilotContext()?.agentName };
  }
}

const Order = z.object({ id: z.string(), status: z.enum(["shipped", "pending"]) });

@CopilotAgent({ name: "support", instructions: "You are a support agent.", tools: [OrdersTools], permissions: permissions.allowCustomTools() })
abstract class SupportAgent {
  @UserMessage()
  answer(@ConversationId() conversationId: string, @Message() question: string): Promise<string> {
    return implementedByCopilot();
  }

  @UserMessage("Summarize order {{id}} for {{customer}}. Note: {{message}}")
  summarize(@P("id") id: string, @P("customer") customer: { name: string }, @Message() note: string): Promise<string> {
    return implementedByCopilot();
  }

  @UserMessage("Extract the order from: {{text}}")
  @Returns(Order)
  extract(@P("text") text: string): Promise<z.infer<typeof Order>> {
    return implementedByCopilot();
  }

  @UserMessage()
  chat(@ConversationId() conversationId: string, @Message() question: string): Observable<CopilotAgentEvent> {
    return implementedByCopilot();
  }

  @UserMessage({ stream: "async-iterable" })
  iterate(@Message() question: string): AsyncIterable<CopilotAgentEvent> {
    return implementedByCopilot();
  }
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

describe("declarative agents", () => {
  it("implements methods: free-form message with conversation id (DESIGN §15 example)", async () => {
    const ref = await compile(
      [
        CopilotTestingModule.forRoot((runtime) =>
          runtime
            .onPrompt(/order 42/)
            .callTool("lookupOrder", { id: "42" })
            .thenReply("Order 42 shipped.")
            .onPrompt(/.*/)
            .reply("Hello"),
        ),
        CopilotModule.forFeature([SupportAgent]),
      ],
      [OrdersService, OrdersTools],
    );
    const support = ref.get(SupportAgent);
    expect(support).toBeInstanceOf(SupportAgent);
    await expect(support.answer("c1", "Where is order 42?")).resolves.toBe("Order 42 shipped.");
    const runtime = ref.get(CopilotTestRuntime);
    expect(runtime.prompts).toEqual(["Where is order 42?"]);
    expect(runtime.toolCalls).toHaveLength(1);
    expect(runtime.toolCalls[0]).toMatchObject({ name: "lookupOrder", approved: true });
    expect(JSON.parse(runtime.toolCalls[0].result as string)).toMatchObject({ id: "42", status: "shipped", ambient: "support" });
    expect(runtime.createdSessions[0].systemMessage).toBeDefined();
  });

  it("renders prompt templates with @P and @Message (non-strings as JSON)", async () => {
    const ref = await compile(
      [CopilotTestingModule.forRoot((rt) => rt.onPrompt(/.*/).reply("ok")), CopilotModule.forFeature([SupportAgent])],
      [OrdersService, OrdersTools],
    );
    await expect(ref.get(SupportAgent).summarize("42", { name: "Ada" }, "be brief")).resolves.toBe("ok");
    expect(ref.get(CopilotTestRuntime).prompts).toEqual(['Summarize order 42 for {"name":"Ada"}. Note: be brief']);
  });

  it("returns structured results with @Returns(zod)", async () => {
    const ref = await compile(
      [
        CopilotTestingModule.forRoot((rt) => rt.onPrompt(/Extract/).replyJson({ id: "7", status: "pending" })),
        CopilotModule.forFeature([SupportAgent]),
      ],
      [OrdersService, OrdersTools],
    );
    await expect(ref.get(SupportAgent).extract("order 7 is pending")).resolves.toEqual({ id: "7", status: "pending" });
  });

  it("streams events as a cold Observable and as an AsyncIterable", async () => {
    const ref = await compile(
      [CopilotTestingModule.forRoot((rt) => rt.onPrompt(/.*/).streamReply(["Hel", "lo"])), CopilotModule.forFeature([SupportAgent])],
      [OrdersService, OrdersTools],
    );
    const support = ref.get(SupportAgent);
    const stream = support.chat("c1", "hi");
    expect(ref.get(CopilotTestRuntime).prompts).toEqual([]); // cold until subscribed
    const events = await lastValueFrom(stream.pipe(toArray()));
    const types = events.map((e) => e.type);
    expect(types.filter((t) => t === "text-delta")).toHaveLength(2);
    expect(events.at(-1)).toMatchObject({ type: "done", text: "Hello" });

    const iterated: CopilotAgentEvent[] = [];
    for await (const event of support.iterate("again")) iterated.push(event);
    expect(iterated.at(-1)).toMatchObject({ type: "done", text: "Hello" });
  });

  it("aborts the turn when an Observable subscriber unsubscribes", async () => {
    const ref = await compile(
      [CopilotTestingModule.forRoot((rt) => rt.onPrompt(/.*/).delay(5_000).reply("late")), CopilotModule.forFeature([SupportAgent])],
      [OrdersService, OrdersTools],
    );
    const first = await firstValueFrom(ref.get(SupportAgent).chat("c1", "hi"));
    expect(first.type).toBe("turn-start");
    await new Promise((r) => setTimeout(r, 50));
    expect(ref.get(CopilotTestRuntime).abortCount).toBe(1);
  });

  it("binds instances to a conversation (resume-only by default)", async () => {
    const ref = await compile(
      [CopilotTestingModule.forRoot((rt) => rt.onPrompt(/.*/).reply("bound")), CopilotModule.forFeature([SupportAgent])],
      [OrdersService, OrdersTools],
    );
    const agents = ref.get(CopilotAgents);
    await expect(agents.bind(SupportAgent, "missing").answer("hi")).rejects.toBeInstanceOf(CopilotConversationNotFoundError);
    await expect(agents.create(SupportAgent, "c9").answer("hi")).resolves.toBe("bound");
    await expect(agents.bind(SupportAgent, "c9").answer("again")).resolves.toBe("bound");
    expect(ref.get(CopilotTestRuntime).createdSessions).toHaveLength(1);
    expect(agents.names()).toEqual(["support"]);
    expect(agents.definition(SupportAgent).tools?.length).toBe(1);
  });

  it("applies per-agent configuration overrides", async () => {
    const ref = await compile(
      [
        CopilotTestingModule.forRoot((rt) => rt.onPrompt(/.*/).reply("ok"), { agents: { support: { model: "gpt-test" } } }),
        CopilotModule.forFeature([SupportAgent]),
      ],
      [OrdersService, OrdersTools],
    );
    await ref.get(SupportAgent).answer("c1", "hi");
    expect(ref.get(CopilotTestRuntime).createdSessions[0].model).toBe("gpt-test");
  });

  it("labels prompts with the configured message source", async () => {
    const ref = await compile(
      [
        CopilotTestingModule.forRoot((rt) => rt.onPrompt(/.*/).reply("ok"), { agents: { support: { messageSource: "system" } } }),
        CopilotModule.forFeature([SupportAgent]),
      ],
      [OrdersService, OrdersTools],
    );
    await ref.get(SupportAgent).answer("c1", "hi");
    expect(ref.get(CopilotTestRuntime).receivedPrompts.map((p) => p.source)).toEqual(["system"]);
    expect(ref.get(CopilotAgents).definition(SupportAgent).messageSource).toBe("system");
  });
});

describe("toolSearch", () => {
  @CopilotAgent({ name: "searcher", toolSearch: { deferThreshold: 10 } })
  abstract class SearcherAgent {
    @UserMessage()
    ask(@Message() q: string): Promise<string> {
      return implementedByCopilot();
    }
  }

  it("applies the decorator value and lets configuration overrides win", async () => {
    const plain = await compile([CopilotTestingModule.forRoot((rt) => rt.onPrompt(/.*/).reply("ok")), CopilotModule.forFeature([SearcherAgent])]);
    await plain.get(SearcherAgent).ask("hi");
    expect(plain.get(CopilotTestRuntime).createdSessions[0].toolSearch).toEqual({ deferThreshold: 10 });

    const overridden = await compile([
      CopilotTestingModule.forRoot((rt) => rt.onPrompt(/.*/).reply("ok"), { agents: { searcher: { toolSearch: { enabled: false } } } }),
      CopilotModule.forFeature([SearcherAgent]),
    ]);
    await overridden.get(SearcherAgent).ask("hi");
    expect(overridden.get(CopilotTestRuntime).createdSessions[0].toolSearch).toEqual({ enabled: false });
  });

  it("rejects invalid toolSearch overrides at bootstrap", async () => {
    for (const toolSearch of [{ deferThreshold: 0 }, { enabled: false, deferThreshold: 5 }, { extra: true }]) {
      await expect(
        compile([CopilotModule.forRoot({ agents: { searcher: { toolSearch } } } as never), CopilotModule.forFeature([SearcherAgent])]),
      ).rejects.toThrow(/agents\.searcher\.toolSearch/);
    }
  });
});

describe("tools and permissions", () => {
  @CopilotAgent({ name: "locked", tools: [OrdersTools] })
  abstract class LockedAgent {
    @UserMessage()
    ask(@Message() q: string): Promise<string> {
      return implementedByCopilot();
    }
  }

  it("denies custom tools by default (core denyAll)", async () => {
    const ref = await compile(
      [
        CopilotTestingModule.forRoot((rt) => rt.onPrompt(/.*/).callTool("lookupOrder", { id: "1" }).thenReply("done")),
        CopilotModule.forFeature([LockedAgent]),
      ],
      [OrdersService, OrdersTools],
    );
    await ref.get(LockedAgent).ask("go");
    expect(ref.get(CopilotTestRuntime).toolCalls[0]).toMatchObject({ name: "lookupOrder", approved: false });
  });

  it("uses an injectable permission policy class and tool interceptors", async () => {
    const seen: string[] = [];

    @Injectable()
    class Policy {
      evaluate(request: { kind: string }) {
        seen.push(request.kind);
        return "approve" as const;
      }
    }

    @Injectable()
    class Audit implements CopilotToolInterceptor {
      async intercept(ctx: CopilotToolExecutionContext, next: () => Promise<unknown>) {
        seen.push(`before:${ctx.toolName}`);
        const result = await next();
        seen.push(`after:${ctx.toolName}`);
        return result;
      }
    }

    @Injectable()
    @UseToolInterceptors(Audit)
    class ClockTools {
      @CopilotTool({ name: "now", description: "Current time" })
      now() {
        return "12:00";
      }
    }

    @CopilotAgent({ name: "clock", permissions: Policy })
    @Tools(ClockTools)
    abstract class ClockAgent {
      @UserMessage()
      ask(@Message() q: string): Promise<string> {
        return implementedByCopilot();
      }
    }

    const ref = await compile(
      [
        CopilotTestingModule.forRoot((rt) => rt.onPrompt(/.*/).callTool("now").thenReply("noon")),
        CopilotModule.forFeature([ClockAgent]),
      ],
      [Policy, Audit, ClockTools],
    );
    await expect(ref.get(ClockAgent).ask("time?")).resolves.toBe("noon");
    expect(ref.get(CopilotTestRuntime).toolCalls[0]).toMatchObject({ name: "now", approved: true, result: "12:00" });
    expect(seen).toEqual(["custom-tool", "before:now", "after:now"]);
  });

  it("resolves request-scoped tool providers per turn", async () => {
    let instances = 0;

    @Injectable({ scope: Scope.REQUEST })
    class ScopedTools {
      readonly id = ++instances;
      @CopilotTool({ description: "Who", skipPermission: true })
      who() {
        return this.id;
      }
    }

    @CopilotAgent({ name: "scoped", tools: [ScopedTools] })
    abstract class ScopedAgent {
      @UserMessage()
      ask(@Message() q: string): Promise<string> {
        return implementedByCopilot();
      }
    }

    const ref = await compile(
      [
        CopilotTestingModule.forRoot((rt) => rt.onPrompt(/.*/).callTool("who").callTool("who").thenReply("ok")),
        CopilotModule.forFeature([ScopedAgent]),
      ],
      [ScopedTools],
    );
    await ref.get(ScopedAgent).ask("1");
    await ref.get(ScopedAgent).ask("2");
    const results = ref.get(CopilotTestRuntime).toolCalls.map((c) => c.result);
    expect(results).toEqual(["1", "1", "2", "2"]);
  });

  it("fails at bootstrap when a tool provider is not registered", async () => {
    await expect(
      compile([CopilotTestingModule.forRoot(), CopilotModule.forFeature([SupportAgent])], [OrdersService]),
    ).rejects.toThrow(/OrdersTools is not registered/);
  });

  it("supports forFeature tools and functional agents via @InjectAgent", async () => {
    const helper = defineAgent({ name: "helper", instructions: "Help." });

    @Injectable()
    class UsesHelper {
      constructor(@InjectAgent("helper") readonly agent: AgentHandle) {}
    }

    @Module({ imports: [CopilotModule.forFeature([helper], { tools: [OrdersTools] })], providers: [UsesHelper] })
    class HelperModule {}

    const ref = await compile(
      [CopilotTestingModule.forRoot((rt) => rt.onPrompt(/.*/).callTool("lookupOrder", { id: "3" }).thenReply("ok")), HelperModule],
      [OrdersService, OrdersTools],
    );
    const handle = ref.get(UsesHelper).agent;
    await expect(handle.send({ prompt: "x" })).resolves.toBe("ok");
    expect(handle.definition.tools?.length).toBe(1);
    expect(ref.get(CopilotTestRuntime).toolCalls[0]).toMatchObject({ approved: false });
  });
});

describe("hooks and events", () => {
  it("composes @CopilotHook methods and delivers @OnCopilotEvent", async () => {
    const calls: string[] = [];

    @Injectable()
    class Governance {
      @CopilotHook("preToolUse")
      deny(input: { toolName: string }) {
        calls.push(`pre:${input.toolName}`);
        return { permissionDecision: "deny", permissionDecisionReason: "nope" };
      }

      @CopilotHook("userPromptSubmitted", { agents: ["other"] })
      notMine() {
        calls.push("wrong-agent");
      }

      @OnCopilotEvent("done")
      done(event: { text: string }) {
        calls.push(`done:${event.text}`);
      }

      @OnCopilotEvent("*")
      broken() {
        throw new Error("listener failure");
      }
    }

    const ref = await compile(
      [
        CopilotTestingModule.forRoot((rt) => rt.onPrompt(/.*/).callTool("lookupOrder", { id: "1" }).thenReply("fin")),
        CopilotModule.forFeature([SupportAgent]),
      ],
      [OrdersService, OrdersTools, Governance],
    );
    await expect(ref.get(SupportAgent).answer("c1", "go")).resolves.toBe("fin");
    expect(calls).toEqual(["pre:lookupOrder", "done:fin"]);
    expect(ref.get(CopilotTestRuntime).toolCalls[0]).toMatchObject({ approved: false });
  });
});

describe("agent validation", () => {
  it.each([
    [
      "unknown placeholder",
      () => {
        @CopilotAgent()
        abstract class A {
          @UserMessage("Hi {{who}}")
          m(): Promise<string> {
            return implementedByCopilot();
          }
        }
        return A;
      },
      /unknown placeholder \{\{who\}\}/,
    ],
    [
      "missing message",
      () => {
        @CopilotAgent()
        abstract class B {
          @UserMessage()
          m(@P("x") x: string): Promise<string> {
            return implementedByCopilot();
          }
        }
        return B;
      },
      /needs a @UserMessage\(template\) or a @Message\(\) parameter/,
    ],
    [
      "streaming with @Returns",
      () => {
        @CopilotAgent()
        abstract class C {
          @UserMessage({ stream: "observable" })
          @Returns(Order)
          m(@Message() q: string): Observable<CopilotAgentEvent> {
            return implementedByCopilot();
          }
        }
        return C;
      },
      /cannot be combined with a streaming result/,
    ],
  ])("rejects %s", async (_label, make, message) => {
    const error = await compile([CopilotTestingModule.forRoot(), CopilotModule.forFeature([make()])]).catch((e) => e);
    expect(error).toBeInstanceOf(CopilotConfigurationError);
    expect(error.message).toMatch(message);
  });

  it("rejects undecorated classes and unknown providers", async () => {
    class Plain {}
    await expect(compile([CopilotTestingModule.forRoot(), CopilotModule.forFeature([Plain])])).rejects.toThrow(/not decorated with @CopilotAgent/);

    @CopilotAgent({ provider: "missing" })
    abstract class Byok {
      @UserMessage()
      m(@Message() q: string): Promise<string> {
        return implementedByCopilot();
      }
    }
    await expect(compile([CopilotTestingModule.forRoot(), CopilotModule.forFeature([Byok])])).rejects.toThrow(/unknown provider "missing"/);
  });

  it("throws when an un-implemented stub is called directly", () => {
    expect(() => (SupportAgent.prototype as SupportAgent).summarize("1", { name: "x" }, "y")).toThrow(CopilotConfigurationError);
  });
});
