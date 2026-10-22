---
title: Spring tool beans
---

<a id="spring-tool-beans"></a>

# Spring tool beans

**In this chapter**

- [Annotation processing](#annotation-processing)
- [Proxies and permissions](#proxies-and-permissions)

Select tools explicitly through `@CopilotAgent(tools = {"referenceTools"})`.
Unreferenced service methods are not exposed just because they are Spring beans.

| Referenced bean | Binding |
| --- | --- |
| SDK `ToolDefinition` | Registered directly; no annotation processor needed for that definition. |
| Service with SDK `@CopilotTool` methods | Uses SDK-generated schemas, argument conversion, and handlers. |

Example service:

```java
import com.github.copilot.tool.CopilotTool;
import com.github.copilot.tool.CopilotToolParam;
import org.springframework.stereotype.Service;

@Service
public class ReferenceTools {
    @CopilotTool(value = "Describe a local Spring concept", name = "describe_term")
    public String describeTerm(@CopilotToolParam("Concept to describe") String term) {
        return switch (term) {
            case "dependency injection" -> "Dependencies are supplied to an object.";
            default -> throw new IllegalArgumentException("Unknown reference term");
        };
    }
}
```

<a id="annotation-processing"></a>

## Annotation processing

Enable the SDK processor in the module compiling the annotated tool methods:

```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-compiler-plugin</artifactId>
    <configuration>
        <annotationProcessors>
            <annotationProcessor>com.github.copilot.tool.CopilotToolProcessor</annotationProcessor>
        </annotationProcessors>
    </configuration>
</plugin>
```

Keep any other processors your application needs, and put the SDK on the
processor classpath/path. Generated `$$CopilotToolMeta` companions are required.
Missing metadata fails initialization with a compiler-configuration hint; the
integration does not substitute another schema engine.

Annotation-based tooling is experimental in SDK 1.0.24. If also using the SDK's
experimental-API checker, follow its opt-in requirements. The complete build
configuration is demonstrated in
[the example POM](https://github.com/brunoborges/copilot-spring-boot/blob/main/copilot-spring-boot-example/pom.xml).

<a id="proxies-and-permissions"></a>

## Proxies and permissions

Bindings invoke the Spring bean/proxy, not an unwrapped target:

| Bean form | Requirements |
| --- | --- |
| Plain service | SDK-generated metadata for its declared tool methods or annotated interface. |
| Class-based Spring proxy | Class/interface tool metadata; tool methods must be overridable, not final. |
| JDK Spring proxy | Annotated tool methods must be exposed on an interface compiled with the SDK processor. |

Transactions/security advice therefore still run. This does not automatically
propagate a caller's Spring Security context or request scope into SDK callback
threads.

Names and namespace names must be non-blank. Duplicate tool names within a
namespace fail initialization; different namespaces remain independent.
Metadata and explicit SDK flags, including `skipPermission`, are preserved.
Registering a tool does not itself approve its execution or disable built-in
tools; use `permissions.allow-agent-tools`, `CopilotPermissionPolicies`, and
`builtInTools`/`availableTools` for that (see
[Tool governance, permissions, and hooks](governance.html)). An empty tool list means no custom tools supplied by this definition,
not a text-only runtime sandbox.

---

**Previous:** [System instructions](instructions.html)

**Next:** [MCP servers](mcp.html)
