<a id="coming-from-spring-boot"></a>

# Coming from Spring Boot

The Quarkus extension and the [Spring Boot starter](../spring-boot/) share the
same agent API in `io.github.brunoborges.copilot.agent`. `@CopilotAgent`,
`@ConversationId`, `@UserMessage`, `@P`, `CopilotAgents`, `CopilotAgentInstance`,
listeners, customizers, hooks, permission policies, and token resolvers are the
same types. An agent interface written for one framework compiles unchanged in the
other. What differs is how the framework wires them.

<a id="modules"></a>

## Modules

| Spring Boot | Quarkus |
| --- | --- |
| `copilot-spring-boot-starter` / `-autoconfigure` | `copilot-quarkus` (plus `copilot-quarkus-deployment`, resolved automatically) |
| `copilot-spring-boot-reactor` | Built in: Mutiny streaming activates when Mutiny is on the class path |
| `copilot-spring-boot-test` | `copilot-quarkus-test` |
| Actuator integration | `quarkus-smallrye-health`, `quarkus-info`, and the optional `/q/copilot` endpoint |
| Micrometer observations | `quarkus-micrometer` |

<a id="discovery-and-injection"></a>

## Discovery and injection

| Spring Boot | Quarkus |
| --- | --- |
| `@EnableCopilotAgents` or auto-configuration package scanning | Build-time Jandex scan of the whole application; no annotation needed. |
| Agent proxies are Spring beans | Routed agents (every method has `@ConversationId`) are `@Singleton` synthetic beans; inject them with `@Inject`. |
| Invalid agents fail at context refresh | `@CopilotAgent` on a class fails the **build**; missing referenced beans fail at startup. |
| `ObjectProvider<T>` for bound agents | `CopilotAgents.create(Type.class)` / `resume(Type.class, id)`; bound agents are never beans. |

<a id="bean-references"></a>

## Bean references and ordering

Attributes ending in `Ref` or `Refs` (`toolRefs`, `systemMessageRef`,
`permissionHandlerRef`, `listenerRefs`, `customizerRefs`, `hookRefs`,
`tokenResolverRef`) name beans in both frameworks. Spring derives a default name
from the class; CDI does not. Annotate every referenced bean with `@Named`:

```java
@ApplicationScoped
@Named("referenceTools")
public class ReferenceTools { ... }
```

Producer methods work too: `@Produces @Named("advisorInstructions") SystemMessageConfig ...`.

Beans collected by type, such as global hooks and telemetry decorators, are
ordered with `@jakarta.annotation.Priority`. ArC sorts **higher** priorities first,
while Spring's `@Order` sorts lower values first. If more than one return-type
adapter supports the same method, startup fails instead of choosing by priority.
With several `CopilotTokenResolver` beans there is no `@Primary`; each agent must
pick one with `tokenResolverRef`.

Tool beans must be top-level classes, because the SDK's `CopilotToolProcessor`
does not generate metadata for nested classes.

<a id="properties"></a>

## Properties

Every `copilot.*` property becomes `quarkus.copilot.*` with the same name,
default, and validation:

| Spring Boot | Quarkus |
| --- | --- |
| `copilot.cli-path` | `quarkus.copilot.cli-path` |
| `copilot.agents.<name>.model` | `quarkus.copilot.agents.<name>.model` |
| `copilot.mcp.servers.<id>.*` | `quarkus.copilot.mcp.servers.<id>.*` |
| `copilot.providers.<id>.*` | `quarkus.copilot.providers.<id>.*` |
| `copilot.reactor.buffer-capacity` | `quarkus.copilot.streaming.buffer-capacity` |
| `copilot.observations.enabled` | `quarkus.copilot.metrics.enabled` (build time) |
| `copilot.endpoint.*` | `quarkus.copilot.endpoint.*` |
| Exposing the `copilot` Actuator endpoint (`management.endpoints.web.exposure.include`) | `quarkus.copilot.endpoint.enabled=true` (build time) |

Integrations that change the bean graph are fixed at **build time**:
`quarkus.copilot.enabled`, `health.enabled`, `metrics.enabled`, `info.enabled`,
`endpoint.enabled`, `security.identity-token-resolver.enabled`, and
`test-runtime.enabled`. See the [configuration reference](configuration.html).

<a id="integrations"></a>

## Integrations

| Spring Boot | Quarkus |
| --- | --- |
| `Flux<CopilotAgentEvent>` | `Multi<CopilotAgentEvent>` ([Mutiny streaming](streaming.html)) |
| `copilot` health indicator | `copilot` SmallRye readiness check at `/q/health/ready` |
| `/actuator/copilot` | `/q/copilot` (disabled by default) |
| `/actuator/info` `copilot` section | `/q/info` `copilot` section |
| Spring Security OAuth2 client token resolver | `SecurityIdentity`-based resolver, opt-in at build time ([authentication](authentication.html)) |
| Security context, MDC, and observation propagation | CDI request context propagation |

<a id="testing"></a>

## Testing

| Spring Boot | Quarkus |
| --- | --- |
| `@AutoConfigureCopilotTestRuntime` | Automatic in every `@QuarkusTest` when `copilot-quarkus-test` is on the test class path |
| `@ServiceConnection CopilotContainer` | `@WithTestResource(CopilotContainerTestResource.class)` |

The scripting API (`agent(...)`, `replies(...)`, `invokesTool(...)`, `prompts(...)`,
`toolCalls(...)`) is unchanged. See [Testing](testing.html).

<a id="native-image"></a>

## Native image

The extension registers agent interfaces as dynamic proxies and tool metadata for
reflection, but native executables have not been tested yet.

**Previous:** [SDK client](client.html)

**Next:** [Agent interfaces](agents.html)
