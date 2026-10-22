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
 * injected directly. Interfaces whose methods take only a {@link UserMessage} parameter are ID-less
 * definitions; obtain them through {@link CopilotAgents#create(Class)} or {@link CopilotAgents#resume(Class, String)}.
 *
 * <p>All string attributes support Spring property placeholders. Bean references are resolved by name
 * during application context initialization; invalid contracts fail startup.
 */
@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface CopilotAgent {

    /**
     * Agent name, also used as the Spring bean name and the {@code copilot.agents.<name>} property key.
     * Defaults to the decapitalized interface simple name. Must start with a letter and contain only
     * letters, digits, dots, underscores, or hyphens.
     */
    String name() default "";

    /** Inline system-message content; mutually exclusive with {@link #instructionsResource()}. */
    String instructions() default "";

    /** Spring resource location of UTF-8 system-message content, such as {@code classpath:/agents/advisor.txt}. */
    String instructionsResource() default "";

    /**
     * How instructions combine with the runtime's built-in system message. {@code REPLACE} requires content;
     * {@code CUSTOMIZE} requires a {@link #systemMessage()} bean.
     */
    SystemMessageMode instructionsMode() default SystemMessageMode.APPEND;

    /** Name of an SDK {@code SystemMessageConfig} bean, mutually exclusive with the instruction attributes. */
    String systemMessage() default "";

    /** Model identifier; empty retains the runtime default. Overridden by {@code copilot.agents.<name>.model}. */
    String model() default "";

    /** Model-specific reasoning effort; overridden by {@code copilot.agents.<name>.reasoning-effort}. */
    String reasoningEffort() default "";

    /**
     * Name of an SDK {@code PermissionHandler} bean. When empty, the {@code copilot.agents.<name>.permissions.*}
     * policy is used if configured; otherwise a handler that logs and rejects every permission request is used.
     * An approving handler is never selected implicitly. See {@link CopilotPermissionPolicies}.
     */
    String permissionHandler() default "";

    /**
     * Names of SDK {@code ToolDefinition} beans or Spring beans declaring SDK {@code @CopilotTool} methods.
     * Registering a tool does not grant it permission.
     */
    String[] tools() default {};

    /**
     * Names of {@code copilot.mcp.servers} entries or SDK {@code McpServerConfig} beans.
     * {@code copilot.agents.<name>.mcp-servers} replaces this selection when set.
     */
    String[] mcpServers() default {};

    /**
     * Name of a {@code copilot.providers} entry or SDK {@code ProviderConfig} bean used as a BYOK
     * (bring your own key) model provider instead of GitHub Copilot; requires a {@link #model()}.
     * {@code copilot.agents.<name>.provider} replaces this selection when set.
     */
    String provider() default "";

    /** Names of {@link CopilotAgentListener} beans attached to each invocation's session. */
    String[] listeners() default {};

    /** Names of {@link CopilotAgentCustomizer} beans applied after declarative session settings. */
    String[] customizers() default {};

    /**
     * Runtime tool allow-list sent as the SDK {@code availableTools} session option. Entries are tool names
     * (such as {@code view}) or source-qualified patterns ({@code builtin:<name>}, {@code custom:<name>},
     * {@code mcp:<name>}, each accepting {@code *}). The runtime applies the allow-list to every tool source, so
     * {@code custom:*} is appended when the agent registers {@link #tools()} and {@code mcp:*} when it selects
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
     * Names of {@link CopilotAgentHooks} beans applied, in order, after every {@link GlobalCopilotAgentHooks}
     * bean. {@code copilot.agents.<name>.hooks} replaces this selection when set.
     */
    String[] hooks() default {};

    /**
     * Name of a {@link CopilotTokenResolver} bean that supplies the per-invocation GitHub token. When empty, a
     * single {@code CopilotTokenResolver} bean (or the {@code @Primary} one) applies; without one, sessions use
     * the client's ambient authentication.
     */
    String tokenResolver() default "";
}
