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
     * Name of an SDK {@code PermissionHandler} bean. When empty, a handler that logs and rejects every
     * permission request is used; an approving handler is never selected implicitly.
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

    /** Names of {@link CopilotAgentListener} beans attached to each invocation's session. */
    String[] listeners() default {};

    /** Names of {@link CopilotAgentCustomizer} beans applied after declarative session settings. */
    String[] customizers() default {};
}
