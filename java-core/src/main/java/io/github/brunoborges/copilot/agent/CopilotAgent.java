package io.github.brunoborges.copilot.agent;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.github.copilot.SystemMessageMode;

/**
 * Declares a Copilot agent interface whose abstract methods are implemented by a generated proxy.
 *
 * <p>Interfaces whose methods take a {@link ConversationId} parameter are <em>routed</em> agents and can be
 * injected directly. Interfaces without a {@link ConversationId} parameter (prompt supplied by
 * {@link UserMessage} parameters, {@link P} placeholders, or a method-level template) are ID-less
 * definitions; obtain them through {@link CopilotAgents#create(Class)} or {@link CopilotAgents#resume(Class, String)}.
 *
 * <p>String attributes fall into three kinds, distinguishable by name:
 * <ul>
 * <li><b>Bean references</b> end in {@code Ref} or {@code Refs} (for example {@link #permissionHandlerRef()}),
 * following the {@code *Ref} naming convention. Each value is a bean name (the Spring bean name, or the
 * {@code @Named} value of a CDI bean in Quarkus), and the bean must be of the documented type.</li>
 * <li><b>Named entries</b>, {@link #mcpServers()} and {@link #provider()}, name an MCP server or model provider.
 * The name is looked up in {@code copilot.mcp.servers} or {@code copilot.providers} first, then as an SDK
 * bean of the same name. Defining the same name in both places fails startup.</li>
 * <li><b>Literal values</b> are every other attribute: names, instructions, model identifiers, tool patterns,
 * and directories.</li>
 * </ul>
 *
 * <p>All string attributes support configuration property placeholders. References are resolved during application
 * startup; an unknown bean, a bean of the wrong type, or an unknown entry fails startup.
 *
 * <p>Configuration keys are shown with the Spring Boot prefix {@code copilot.}; Quarkus applications use
 * {@code copilot.} instead.
 */
@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface CopilotAgent {

    /**
     * Agent name, also used as the {@code copilot.agents.<name>} property key.
     * Defaults to the decapitalized interface simple name. Must start with a letter and contain only
     * letters, digits, dots, underscores, or hyphens.
     */
    String name() default "";

    /** Inline system-message content; mutually exclusive with {@link #instructionsResource()}. */
    String instructions() default "";

    /** Resource location of UTF-8 system-message content, such as {@code classpath:/agents/advisor.txt} or a {@code file:} path. */
    String instructionsResource() default "";

    /**
     * How instructions combine with the runtime's built-in system message. {@code REPLACE} requires content;
     * {@code CUSTOMIZE} requires a {@link #systemMessageRef()} bean.
     */
    SystemMessageMode instructionsMode() default SystemMessageMode.APPEND;

    /** Bean name of an SDK {@code SystemMessageConfig}, mutually exclusive with the instruction attributes. */
    String systemMessageRef() default "";

    /** Model identifier; empty retains the runtime default. Overridden by {@code copilot.agents.<name>.model}. */
    String model() default "";

    /** Model-specific reasoning effort; overridden by {@code copilot.agents.<name>.reasoning-effort}. */
    String reasoningEffort() default "";

    /**
     * Bean name of an SDK {@code PermissionHandler}. When empty, the {@code copilot.agents.<name>.permissions.*}
     * policy is used if configured; otherwise a handler that logs and rejects every permission request is used.
     * An approving handler is never selected implicitly. See {@link CopilotPermissionPolicies}.
     */
    String permissionHandlerRef() default "";

    /**
     * Bean names of SDK {@code ToolDefinition}s or of beans declaring SDK {@code @CopilotTool} methods.
     * These are bean names, not tool names; to filter runtime tools by name use {@link #availableTools()}.
     * Registering a tool does not grant it permission.
     */
    String[] toolRefs() default {};

    /**
     * MCP server names. Each is looked up as a {@code copilot.mcp.servers.<server>} entry, then as an SDK
     * {@code McpServerConfig} bean of that name. {@code copilot.agents.<name>.mcp-servers} replaces this selection
     * when set.
     */
    String[] mcpServers() default {};

    /**
     * BYOK (bring your own key) model provider name, used instead of GitHub Copilot; requires a {@link #model()}.
     * It is looked up as a {@code copilot.providers.<provider>} entry, then as an SDK {@code ProviderConfig} bean
     * of that name.
     * {@code copilot.agents.<name>.provider} replaces this selection when set.
     */
    String provider() default "";

    /** Bean names of {@link CopilotAgentListener}s attached to each invocation's session. */
    String[] listenerRefs() default {};

    /** Bean names of {@link CopilotAgentCustomizer}s applied after declarative session settings. */
    String[] customizerRefs() default {};

    /**
     * Runtime tool allow-list sent as the SDK {@code availableTools} session option. Entries are tool names
     * (such as {@code view}) or source-qualified patterns ({@code builtin:<name>}, {@code custom:<name>},
     * {@code mcp:<name>}, each accepting {@code *}). The runtime applies the allow-list to every tool source, so
     * {@code custom:*} is appended when the agent registers {@link #toolRefs()} and {@code mcp:*} when it selects
     * {@link #mcpServers()}, unless the list already contains a pattern for that source. Empty means no allow-list.
     * {@code copilot.agents.<name>.available-tools} replaces this selection when set.
     */
    String[] availableTools() default {};

    /**
     * Runtime tool deny-list sent as the SDK {@code excludedTools} session option, using the same patterns as
     * {@link #availableTools()}. The runtime ignores it when an allow-list is present, so combining both fails
     * startup. {@code copilot.agents.<name>.excluded-tools} replaces this selection when set.
     */
    String[] excludedTools() default {};

    /**
     * Built-in subagent names hidden from the session (SDK {@code excludedBuiltInAgents}).
     * {@code copilot.agents.<name>.excluded-built-in-agents} replaces this selection when set.
     */
    String[] excludedBuiltInAgents() default {};

    /**
     * Whether the runtime's built-in tools (shell, file, web, ...) are available. {@code false} adds
     * {@code builtin:*} to the excluded tools and cannot be combined with {@link #availableTools()}; list the
     * wanted built-ins there instead. Overridden by {@code copilot.agents.<name>.built-in-tools}.
     */
    boolean builtInTools() default true;

    /**
     * Session working directory for built-in file and shell tools; empty retains the runtime default.
     * Overridden by {@code copilot.agents.<name>.working-directory}.
     */
    String workingDirectory() default "";

    /**
     * Directories the agent may access beyond the working directory.
     * {@code copilot.agents.<name>.additional-directories} replaces this selection when set.
     */
    String[] additionalDirectories() default {};

    /**
     * Bean names of {@link CopilotAgentHooks} applied, in order, after {@link GlobalCopilotAgentHooks} beans and
     * beans targeting this agent with {@link CopilotAgentHooksFor}.
     */
    String[] hookRefs() default {};

    /**
     * Bean name of a {@link CopilotTokenResolver} that supplies the per-invocation GitHub token. When empty, a
     * single {@code CopilotTokenResolver} bean (or the {@code @Primary} one) applies; without one, sessions use
     * the client's ambient authentication.
     */
    String tokenResolverRef() default "";
}
