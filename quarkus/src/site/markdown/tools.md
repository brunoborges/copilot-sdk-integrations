---
title: CDI tool beans
---

<a id="cdi-tool-beans"></a>

# CDI tool beans

**In this chapter**

- [Annotation processing](#annotation-processing)
- [Proxies and permissions](#proxies-and-permissions)

Select tools explicitly through `@CopilotAgent(toolRefs = {"referenceTools"})`.
Unreferenced bean methods are not exposed just because they are CDI beans.
Each `toolRefs` entry is the `@Named` name of a CDI bean; without `@Named` the
bean cannot be referenced and startup fails with a hint that `toolRefs` lists
bean names, not tool names.

| Referenced bean | Binding |
| --- | --- |
| SDK `ToolDefinition` (for example from a `@Produces @Named` method) | Registered directly; no annotation processor needed for that definition. |
| Bean with SDK `@CopilotTool` methods | Uses SDK-generated schemas, argument conversion, and handlers. |

Example bean, as in the example application:

```java
import java.util.Locale;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Named;

import com.github.copilot.tool.CopilotTool;
import com.github.copilot.tool.CopilotToolParam;

@ApplicationScoped
@Named("referenceTools")
public class ReferenceTools {
    @CopilotTool(value = "Describe a local Quarkus concept", name = "describe_term")
    public String describeTerm(@CopilotToolParam("Concept to describe") String term) {
        return switch (term.toLowerCase(Locale.ROOT)) {
            case "dependency injection" -> "Dependencies are supplied to an object.";
            default -> throw new IllegalArgumentException("Unknown reference term");
        };
    }
}
```

`@Named` beans are never removed as unused by ArC, so the bean survives the
build even though nothing injects it.

<a id="annotation-processing"></a>

## Annotation processing

Enable the SDK processor in the module compiling the annotated tool methods,
and put the SDK on the processor path:

```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-compiler-plugin</artifactId>
    <configuration>
        <annotationProcessorPaths>
            <path>
                <groupId>com.github</groupId>
                <artifactId>copilot-sdk-java</artifactId>
                <version>1.0.24</version>
            </path>
        </annotationProcessorPaths>
        <annotationProcessors>
            <annotationProcessor>com.github.copilot.tool.CopilotToolProcessor</annotationProcessor>
        </annotationProcessors>
    </configuration>
</plugin>
```

Keep any other processors your application needs; listing
`annotationProcessors` explicitly disables discovery of processors not named
there. Generated `$$CopilotToolMeta` companions are required. Missing metadata
fails startup with a compiler-configuration hint; the extension does not
substitute another schema engine. The build registers every generated metadata
class for reflection.

Declare tool beans as top-level classes. The SDK processor does not support
`@CopilotTool` methods on nested classes.

Annotation-based tooling is experimental in SDK 1.0.24. If also using the SDK's
experimental-API checker, follow its opt-in requirements. The complete build
configuration is demonstrated in
[the example POM](https://github.com/brunoborges/copilot-sdk-integrations/blob/main/quarkus/example/pom.xml).

<a id="proxies-and-permissions"></a>

## Proxies and permissions

Tool beans are resolved once, when the agent is initialized at startup, and the
generated handlers are bound to the injectable bean instance, not an unwrapped
target:

| Bean form | Behavior |
| --- | --- |
| Normal-scoped bean (`@ApplicationScoped`, `@RequestScoped`, ...) | Handlers call the ArC client proxy, which delegates to the current contextual instance on every call. A `@RequestScoped` tool bean needs an active request context, which the extension propagates from the caller (see [Conversations and lifecycle](conversations.html#threading-errors-and-cancellation)). |
| `@Singleton` or `@Dependent` bean with interceptors | Handlers call the intercepted subclass, so interceptor bindings apply. A `@Dependent` bean is a single instance held for the application's lifetime. |
| Bean whose `@CopilotTool` methods are declared on an interface it implements | Metadata is taken from that interface, which must be compiled with the SDK processor. |

The first class in the bean's hierarchy that declares `@CopilotTool` methods
supplies the metadata, so ArC's generated subclasses are transparent.
Interceptors such as `@Transactional`, `@RolesAllowed`, or `@Timed` therefore
still run on tool calls. Security interceptors see the caller's identity only
when the caller's CDI request context is still active; see
[Per-user authentication](authentication.html).

Names and namespace names must be non-blank. Duplicate tool names within a
namespace fail startup; different namespaces remain independent. Metadata and
explicit SDK flags, including `skipPermission`, are preserved. Registering a
tool does not itself approve its execution or disable built-in tools; use
`quarkus.copilot.agents.<name>.permissions.allow-agent-tools`,
`CopilotPermissionPolicies`, and `builtInTools`/`availableTools` for that (see
[Tool governance, permissions, and hooks](governance.html)). An empty tool list
means no custom tools supplied by this definition, not a text-only runtime
sandbox.

---

**Previous:** [System instructions](instructions.html)

**Next:** [MCP servers](mcp.html)
