package io.github.brunoborges.copilot.agent.internal;

import java.beans.Introspector;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Logger;

import com.github.copilot.SystemMessageMode;
import com.github.copilot.rpc.PermissionHandler;
import com.github.copilot.rpc.PermissionRequestResult;
import com.github.copilot.rpc.SystemMessageConfig;
import com.github.copilot.rpc.ToolDefinition;
import io.github.brunoborges.copilot.agent.CopilotAgent;
import io.github.brunoborges.copilot.agent.CopilotAgentCustomizer;
import io.github.brunoborges.copilot.agent.CopilotAgentListener;
import io.github.brunoborges.copilot.autoconfigure.CopilotAgentsProperties;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ResourceLoader;
import org.springframework.util.Assert;

public record CopilotAgentDefinition(
        String name, String typeName, String model, String reasoningEffort, Duration responseTimeout,
        SystemMessageConfig systemMessage, PermissionHandler permissions, List<ToolDefinition> tools,
        List<CopilotAgentListener> listeners, List<CopilotAgentCustomizer> customizers) {

    private static final Logger log = Logger.getLogger(CopilotAgentDefinition.class.getName());
    private static final PermissionHandler DENY = (request, invocation) -> {
        log.warning("Copilot agent denied permission request of kind '" + request.getKind() + "'.");
        return java.util.concurrent.CompletableFuture.completedFuture(
                PermissionRequestResult.reject("No permission handler was configured for this agent."));
    };

    public static String name(Class<?> type, Environment environment) {
        var annotation = type.getAnnotation(CopilotAgent.class);
        String name = environment.resolveRequiredPlaceholders(annotation.name());
        if (name.isEmpty()) {
            name = Introspector.decapitalize(type.getSimpleName());
        }
        Assert.isTrue(name.matches("[A-Za-z][A-Za-z0-9._-]*"), "Invalid Copilot agent name on " + type.getName());
        return name;
    }

    public static CopilotAgentDefinition create(Class<?> type, BeanFactory beans, Environment environment,
            ResourceLoader resources, CopilotAgentsProperties properties) {
        var annotation = type.getAnnotation(CopilotAgent.class);
        String name = name(type, environment);
        String instructions = resolve(annotation.instructions(), environment);
        String resource = resolve(annotation.instructionsResource(), environment);
        String systemBean = resolve(annotation.systemMessage(), environment);
        Assert.isTrue(instructions.isEmpty() || resource.isEmpty(),
                "Copilot agent " + name + ": instructions and instructionsResource are mutually exclusive");
        Assert.isTrue(systemBean.isEmpty() || (instructions.isEmpty() && resource.isEmpty()
                        && annotation.instructionsMode() == SystemMessageMode.APPEND),
                "Copilot agent " + name + ": systemMessage bean cannot be combined with inline/resource instructions or instructionsMode");

        SystemMessageConfig systemMessage;
        if (!systemBean.isEmpty()) {
            systemMessage = beans.getBean(systemBean, SystemMessageConfig.class);
            Assert.notNull(systemMessage.getMode(), "Copilot agent " + name + ": systemMessage bean must declare its mode");
            Assert.isTrue(systemMessage.getMode() != SystemMessageMode.REPLACE
                            || (systemMessage.getContent() != null && !systemMessage.getContent().isBlank()),
                    "Copilot agent " + name + ": REPLACE requires instructions");
        } else {
            Assert.isTrue(annotation.instructionsMode() != SystemMessageMode.CUSTOMIZE,
                    "Copilot agent " + name + ": CUSTOMIZE requires a systemMessage bean with section configuration");
            if (!resource.isEmpty()) {
                try (var input = resources.getResource(resource).getInputStream()) {
                    instructions = new String(input.readAllBytes(), StandardCharsets.UTF_8);
                } catch (IOException ex) {
                    throw new IllegalArgumentException("Cannot read instructionsResource for Copilot agent " + name, ex);
                }
                Assert.hasText(instructions, "Copilot agent " + name + ": instructionsResource must not be empty");
            }
            Assert.isTrue(annotation.instructionsMode() != SystemMessageMode.REPLACE || !instructions.isEmpty(),
                    "Copilot agent " + name + ": REPLACE requires instructions");
            systemMessage = instructions.isEmpty() ? null : new SystemMessageConfig()
                    .setMode(annotation.instructionsMode()).setContent(instructions);
        }
        var settings = properties.agents().get(name);
        String model = settings != null && settings.model() != null
                ? settings.model() : resolve(annotation.model(), environment);
        String reasoning = settings != null && settings.reasoningEffort() != null
                ? settings.reasoningEffort() : resolve(annotation.reasoningEffort(), environment);
        String permissionBean = resolve(annotation.permissionHandler(), environment);
        return new CopilotAgentDefinition(name, type.getName(), model, reasoning,
                settings == null ? Duration.ofSeconds(60) : settings.responseTimeout(), systemMessage,
                permissionBean.isEmpty() ? DENY : beans.getBean(permissionBean, PermissionHandler.class),
                SpringToolBindings.bind(annotation.tools(), beans, environment),
                beans(annotation.listeners(), CopilotAgentListener.class, beans, environment),
                beans(annotation.customizers(), CopilotAgentCustomizer.class, beans, environment));
    }

    private static String resolve(String value, Environment environment) {
        String resolved = environment.resolveRequiredPlaceholders(value);
        Assert.isTrue(resolved.isEmpty() || !resolved.isBlank(), "Copilot agent annotation values must not be blank");
        return resolved;
    }

    private static <T> List<T> beans(String[] names, Class<T> type, BeanFactory beans, Environment environment) {
        return Arrays.stream(names).map(name -> {
            String resolved = resolve(name, environment);
            Assert.hasText(resolved, "Copilot agent bean references must not be empty");
            return beans.getBean(resolved, type);
        }).toList();
    }
}
