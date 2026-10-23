# Workshop vs. integrations: developer experience and API UX

## Executive assessment

**The integrations make Copilot an application-managed dependency instead of a manually managed SDK session.** Their clearest benefits are lifecycle ownership, framework configuration, dependency injection, invocation ergonomics, and offline testing. They do not remove the need to design tools, enforce permissions, validate output, or understand the underlying Copilot runtime.

**The original is better for learning the SDK and building a small standalone script. The integrations are better for embedding agents in an existing framework application.** For a one-shot workshop CLI, adding a framework can introduce more setup than it removes.

The comparison is not perfectly like-for-like: our examples also change output validation, browser orchestration, research composition, and HTML generation. Those changes must not be credited to the integration libraries themselves.

### Scope and evidence

This report compares the workshop's finished Java, Node.js, Python, and .NET implementations against our eight framework examples. Original source was inspected at revision [`e0ab93c`](https://github.com/github/copilot-sdk-workshop/tree/e0ab93c784b9855b084e15063868c70a6dbd0059), alongside the current local `examples/` code. The Node/Python accessibility implementations are in `report.ts` and `report.py`, not just their thin or placeholder entry points. This is a source-level API/DX assessment, not a latency benchmark or a new live-model test.

## 1. What changes for the developer?

| Concern | Original workshop | Integration-based examples | DX consequence |
| --- | --- | --- | --- |
| Runtime lifecycle | Create a `CopilotClient`; start it; create sessions; close/disconnect sessions; stop/dispose the client. Python and .NET already offer context/disposal conveniences. | Framework application context, lifespan, plugin, or Generic Host owns runtime services. | Less repeated SDK lifecycle code inside business flows. Framework boot/shutdown still exists. |
| Agent definition | Construct SDK session configuration objects/dictionaries with instructions, tools, model, MCP, and permissions. | Java agent interfaces; Node agent definitions; Python agent classes; .NET named service registrations. | Agents become discoverable application components rather than local session variables. |
| Invocation | Send messages to a session, then wait or subscribe to SDK events. | Inject/resolve an agent and call `run`, `send`, `ask`/`aask`, or `IChatClient.GetResponseAsync<T>`. | A smaller application-facing surface, but different concepts in each ecosystem. |
| Tools | SDK tool factories/decorators and application-owned handlers. | Bean/delegate/function binding and integration tool helpers, still backed by application-owned handlers. | Tool registration fits the framework; tool design and validation remain work. Node's tool-definition syntax is only a modest change. |
| Configuration | Some existing environment support, including `COPILOT_MODEL`; many timeouts and MCP/browser choices are source constants/config objects. | Framework configuration plus validated environment settings and per-agent overrides. | Easier operational changes without editing agent logic. Not every example uses the framework's fullest configuration abstraction. |
| Prompts | Museum policy is already separated into reusable source modules; task data is already separate from durable instructions. | Classpath resources, packaged text files, Django templates, and .NET embedded resources. | Cleaner source and packaging. This improves an existing separation; it does not invent it. |
| Results | Primarily text/Markdown, with workshop-specific parsing and validation helpers. | Python/.NET use typed result APIs; Java/Node examples receive strings and explicitly parse/validate JSON. | Stronger boundaries in these examples, but typed result ergonomics are not uniform. |
| Permissions | Existing tool allowlists, read-only tools, target-specific browser permissions, and constrained HTML-write permissions. | Explicit application policies and allowlists remain necessary. | The framework integration is not an authorization boundary by itself. |
| Testing | Helpers and SDK interactions must be isolated or replaced by the application. | Java/Python scripted integration runtimes; Node runtime test seams; .NET replaceable `IChatClient`. | More convenient offline testing of framework wiring and application behavior. |

**Important attribution:** validation rules, escaped HTML renderers, browser restrictions, and lookup-call bookkeeping are application code we added. They are not guarantees automatically supplied by installing an integration.

## 2. API UX: the actual shapes

### Java: the largest shift toward declaration

The workshop explicitly starts the client, builds `SessionConfig`, creates a session, invokes the streamer, and closes runtime resources. Our Java applications declare the agent contract instead:

```java
@CopilotAgent(
    name = "museum",
    instructionsResource = "classpath:/workshop/prompts/museum.txt",
    toolRefs = "museumTools",
    permissionHandlerRef = "museumPermissions",
    availableTools = "custom:approved_fact_lookup")
interface MuseumAgent {
    CompletableFuture<String> run(
        @ConversationId String conversation,
        @UserMessage String task);
}
```

Spring Boot, Quarkus, and Micronaut use essentially the same agent declaration. Their differences are in native bean production, configuration, application startup, and test integration.

**What feels better:** IDE-visible methods, injectable agents, an explicit conversation identifier, and a first-class resource reference for instructions. SDK session creation is no longer mixed into the task flow.

**What still feels awkward:** `toolRefs`, `permissionHandlerRef`, and tool allowlists contain strings. Bean names and namespaces are not entirely protected by compiler-assisted refactoring. The examples also return `CompletableFuture<String>`, not `CompletableFuture<Exhibit>`: domain typing is still an application-side JSON boundary. Annotations compress configuration, but the developer must still understand the referenced beans and session behavior.

Evidence: `examples/spring-boot/src/main/java/workshop/springboot/Agents.java`, the corresponding Quarkus/Micronaut interfaces, and `examples/java-common/src/main/java/workshop/common/Workshop.java`.

### Node.js: lifecycle integration is more significant than syntax reduction

The application-facing call is small:

```typescript
const raw = await agent.send({ prompt: command.prompt });
const result = await completeCommand(command, raw);
```

NestJS registers definitions with `CopilotModule.forRootAsync()` and `forFeature()`, then resolves `getAgentToken(name)`. Fastify registers `copilotPlugin` and resolves `app.copilot.agent(name)`.

Tool authoring remains recognizably similar to the SDK: `defineCopilotTool({ name, input, execute })` replaces the workshop's `defineTool(name, { parameters, handler })`. Both can use schemas. The main benefit is how the definition participates in the application, not a radical reduction in tool code.

**What feels better:** a reusable agent definition, application-owned shutdown, explicit concurrency/queue limits, and a consistent `AgentHandle` invocation surface.

**What still feels awkward:** named lookups, raw string replies, and explicit Zod validation. The NestJS example dynamically constructs and decorates a module instead of demonstrating a conventional injectable application service. Its configuration is a shared Zod/environment loader passed into the module, not a complete Nest `ConfigModule` example. These are choices in the sample, not necessarily limitations of the integration.

Evidence: `examples/nestjs/src/app.ts`, `examples/fastify/src/app.ts`, and `examples/node-common/src/{museum,config,cli}.ts`.

### Python: typed results are the clearest application-facing improvement

FastAPI's call site is:

```python
result = await copilot.get(agents[args.agent]).aask(
    task_prompt(args, run),
    result=Exhibit if args.agent == "museum" else AccessibilityReport,
)
```

Django's management command uses the synchronous equivalent:

```python
result = agents[args.agent]().ask(
    task_prompt(args, run),
    result=Exhibit if args.agent == "museum" else AccessibilityReport,
)
```

Compared with the original accessibility implementation's event subscription, completion event, error state, and delta handling, the intent is much easier to read: ask this agent for this model.

**What feels better:** Pydantic result validation, a framework-owned runtime, an async-friendly FastAPI API, and a synchronous Django management-command API without application-level `asyncio` orchestration. Django's `instructions_template` is especially idiomatic.

**What still feels awkward:** our agent factories use `type("MuseumAgent", ...)` and dictionaries of generated classes to capture per-run state. That hurts navigation, static discovery, and readability relative to ordinary named class definitions. The FastAPI CLI manually enters an application lifespan, and Django explicitly shuts down the runtime in `finally`; the lifecycle is not literally zero-code. These Python integrations currently expose buffered replies rather than token streaming.

Evidence: `examples/fastapi/src/workshop_fastapi/{app,__main__}.py`, `examples/django/src/workshop_django/agents.py`, and its `management/commands/workshop.py`.

### .NET: the strongest alignment with a standard AI application abstraction

The workshop uses `CopilotClient`, `SessionConfig`, and SDK event subscriptions. Our application resolves a named `Microsoft.Extensions.AI.IChatClient`:

```csharp
var chat = host.Services.GetRequiredKeyedService<IChatClient>(mode);
var response = await chat.GetResponseAsync<Exhibit>(
    "Create the exhibit from the configured fact lookup tools.");
var exhibit = response.Result.Validate();
```

Registration uses `AddCopilot()` and `WithTool()` inside the application's `AddWorkshop()` helper. Generic Host and `IConfiguration` provide the surrounding lifecycle/configuration model.

**What feels better:** standard DI, keyed agents, typed responses, ordinary `appsettings.json` overrides, and an application dependency that can be replaced with another `IChatClient` in tests. The typed-response API belongs to the `Microsoft.Extensions.AI` ecosystem exposed through this integration; it is not an invented new Copilot capability.

**What still feels awkward:** nontrivial behavior still reaches into SDK `SessionConfigBase` through `ConfigureSession()`. Permissions and MCP composition are not hidden behind a fully framework-neutral abstraction. `Program.cs` still has considerable CLI, host, research, validation, and cleanup orchestration. The integration removes manual SDK-client/session handling, not the entire program.

Evidence: `examples/dotnet/{Program,Agents,Configuration}.cs`.

## 3. Eight-framework comparison

| Framework | Native integration surface used | Strongest DX improvement | Main friction in the current example |
| --- | --- | --- | --- |
| Spring Boot | Annotated interfaces, Spring beans, `copilot.*` properties | Agent feels like an injected service; classpath prompts and scripted Spring tests | String bean references; separate tool/configuration classes; string output parsing |
| Quarkus | Same agent API, CDI producers, MicroProfile configuration | Keeps agent contracts consistent with Spring while fitting CDI | Named producers add wiring; framework build setup remains |
| Micronaut | Same agent API, `@Factory`/`@Named` beans, native properties | Fits Micronaut's DI/configuration model | Current stack requires Java 25+; named factory wiring is explicit |
| NestJS | `CopilotModule.forRootAsync`, `forFeature`, injection tokens | Managed agent registration and application lifecycle | Dynamic module construction and token resolution; not yet a conventional service-injection tutorial |
| Fastify | Plugin registration and `app.copilot.agent(name)` | A relatively direct plugin-and-handle API | Names/raw results remain; a server framework still boots for a local CLI |
| FastAPI | Lifespan, `CopilotSettings`, `Copilot.get(...).aask(...)` | Async typed results and familiar settings/testing | Dynamic classes and explicit lifespan entry in this CLI; no token streaming |
| Django | Agent classes, settings, templates, management command | Sync invocation and native template/settings conventions | Dynamic class factory and explicit command shutdown |
| .NET | Generic Host, `AddCopilot`, keyed `IChatClient`, `IConfiguration` | Standard AI abstraction plus DI and typed results | SDK escape hatches and substantial application orchestration remain |

All eight are local, single-user applications. FastAPI's health router does not turn these into public agent APIs; the examples do not provide equivalent REST endpoints across frameworks.

## 4. Behavioral differences that affect perceived UX

These are not merely alternative API spellings.

| Area | Original workshop | Current integration examples | User/developer impact |
| --- | --- | --- | --- |
| Streaming and progress | Museum implementations stream text and tool events. Node/Python/.NET accessibility flows also print progress; the Java accessibility implementation waits for the final reply. | Current examples buffer their final responses and validate before publishing. | Easier controlled publication, but less immediate feedback. Do not describe this as an unqualified UX upgrade. |
| Museum validation | Original museum flow prints its validation result and then proceeds to the optional HTML prompt; validation is not a publication gate there. | Examples reject invalid structures or missing required lookups before producing the final artifact. | Stronger application contract, but more explicit failures and potentially more model-retry work for a caller. |
| HTML capstone | A separate model session uses constrained write tools to create the interactive page. | Trusted application code escapes validated data and renders the page. | No additional HTML-generation model turn or model write permission; more predictable output, less model-driven visual flexibility. This is a workflow redesign. |
| Wikipedia composition | Original museum flow optionally researches first, then exposes accepted cited notes to the curator. | Python/.NET preserve an integrated `--research` path. Java/Node expose research as a separate command rather than automatically passing its result into museum generation. | Java/Node are not feature-identical ports of that optional museum step. Developers must perform a separate review/composition step. |
| Research failure | Original museum flow reports the failure and continues with approved facts. | Python/.NET fail explicitly when requested research fails. | Clearer requested-operation semantics, but less forgiving than the workshop. This is an application policy choice. |
| Browser composition | Original accessibility agent uses Playwright MCP navigation plus local snapshot/catalog tools. | Python/.NET retain MCP. Java captures the snapshot before invoking the reviewer. Node exposes a custom navigation tool backed by application-controlled Playwright. | Browser API and permission behavior differ. Java/Node changes must not be presented as benefits of the integration abstraction alone. |
| Command/output consistency | Language-specific interactive workshop experiences. | Java/Node primarily print validated JSON; Python/.NET print Markdown. Command arguments and research workflows also differ. | Better automation in some paths, but no single cross-framework CLI/output contract yet. |

Neither version's structural validation proves factual grounding or accessibility conformance. Framework lifecycle ownership also does not automatically make mutable tool state safe for multiple users.

## 5. External configuration and prompt UX

The requested externalization materially improves maintainability, but its depth varies:

| Ecosystem | Operational configuration | Prompt mechanism |
| --- | --- | --- |
| Java | Framework properties and per-agent overrides | Integration-level `instructionsResource` with classpath resources |
| Node.js | Validated environment variables supplied to module/plugin options | Application helper reads ESM-relative packaged text files |
| FastAPI | Pydantic Settings, packaged defaults, optional `.env`, environment overrides | Application helper uses `importlib.resources` |
| Django | Native `COPILOT`/`WORKSHOP` settings and environment bindings | Integration-level `instructions_template` and native template loader |
| .NET | `appsettings.json`, optional local JSON, environment overrides | Application helper reads embedded assembly resources |

Java and Django provide particularly discoverable prompt references at the agent declaration. Node, FastAPI, and .NET still need small application-owned resource loaders.

**Resource-based does not mean hot-reloadable external configuration.** Embedded .NET prompts and packaged Java/Python/Node resources generally travel with the application build/package. Also, configuration names differ: for example, FastAPI's `COPILOT_DEFAULTS__MODEL`, Django's `COPILOT_MODEL`, and .NET's `Copilot__Model` are not a uniform cross-language API.

## 6. Recommended improvements

1. **Close or explicitly label feature-parity gaps.** In particular, connect Java/Node research to museum generation if matching the finished workshop is the goal. Keep the deterministic HTML adaptation clearly documented.
2. **Add an optional progress/event channel without publishing unvalidated final artifacts.** The original workshop demonstrates streaming well; the current examples sacrifice that feedback. Python needs an integration capability change, not just a CLI print statement.
3. **Make the examples more idiomatic where registration is currently dynamic.** Prefer named Python classes with an explicit run-context/tool-factory pattern and a conventional injectable Nest service when demonstrating normal application development.
4. **Make typed-result ergonomics consistent.** Java/Node examples currently cross a string/JSON boundary manually, while Python/.NET present typed calls. Use or expose equivalent typed patterns where supported, without claiming that schema validation proves semantic correctness.
5. **Normalize developer-facing conventions.** A shared conceptual guide for agent names, conversation identity, settings precedence, result schemas, and actionable startup/configuration errors would make moving between examples easier.
6. **Demonstrate application state scoping before presenting these as service patterns.** Current single-user mutable tool/run state must not be assumed to become request-safe merely because it is registered in DI.

## Bottom line

The most defensible value proposition is **less Copilot infrastructure in application code, better alignment with existing framework conventions, and easier testing**. It is not automatically fewer total lines, better streaming UX, stronger security, faster startup, or perfect feature parity.

For SDK education or a disposable script, the original workshop remains the clearer starting point. For an agent that belongs inside an existing Spring, CDI, Micronaut, Nest, Fastify, FastAPI, Django, or .NET application, the integrations provide the more maintainable boundary—provided the application still owns policy, validation, state scoping, and user feedback.

## Primary original-source references

- [Java museum orchestration](https://github.com/github/copilot-sdk-workshop/blob/e0ab93c784b9855b084e15063868c70a6dbd0059/finished/java/museum-exhibit-studio/src/main/java/workshop/MuseumExhibitStudio.java) and [accessibility implementation](https://github.com/github/copilot-sdk-workshop/blob/e0ab93c784b9855b084e15063868c70a6dbd0059/finished/java/accessibility-report/src/main/java/workshop/AccessibilityReport.java).
- [Node museum orchestration](https://github.com/github/copilot-sdk-workshop/blob/e0ab93c784b9855b084e15063868c70a6dbd0059/finished/nodejs/museum-exhibit-studio/src/index.ts), [museum helpers](https://github.com/github/copilot-sdk-workshop/blob/e0ab93c784b9855b084e15063868c70a6dbd0059/finished/nodejs/museum-exhibit-studio/src/curator.ts), and [accessibility orchestration](https://github.com/github/copilot-sdk-workshop/blob/e0ab93c784b9855b084e15063868c70a6dbd0059/finished/nodejs/accessibility-report/src/report.ts).
- [Python museum orchestration](https://github.com/github/copilot-sdk-workshop/blob/e0ab93c784b9855b084e15063868c70a6dbd0059/finished/python/museum-exhibit-studio/main.py) and [accessibility orchestration](https://github.com/github/copilot-sdk-workshop/blob/e0ab93c784b9855b084e15063868c70a6dbd0059/finished/python/accessibility-report/report.py).
- [.NET museum orchestration](https://github.com/github/copilot-sdk-workshop/blob/e0ab93c784b9855b084e15063868c70a6dbd0059/finished/dotnet/museum-exhibit-studio/Program.cs), [streaming helper](https://github.com/github/copilot-sdk-workshop/blob/e0ab93c784b9855b084e15063868c70a6dbd0059/finished/dotnet/museum-exhibit-studio/Helpers/CuratorStreamer.cs), and [accessibility orchestration](https://github.com/github/copilot-sdk-workshop/blob/e0ab93c784b9855b084e15063868c70a6dbd0059/finished/dotnet/accessibility-report/Program.cs).

Local integration-example evidence is identified by repository-relative source paths throughout the report. No repository code was changed for this comparison.
