package io.github.brunoborges.copilot.agent.internal;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

import com.github.copilot.SystemMessageMode;
import com.github.copilot.rpc.McpServerConfig;
import com.github.copilot.rpc.PermissionHandler;
import com.github.copilot.rpc.PermissionRequestResult;
import com.github.copilot.rpc.ProviderConfig;
import com.github.copilot.rpc.SystemMessageConfig;
import com.github.copilot.rpc.ToolDefinition;
import io.github.brunoborges.copilot.agent.CopilotAgent;
import io.github.brunoborges.copilot.agent.CopilotAgentCustomizer;
import io.github.brunoborges.copilot.agent.CopilotAgentListener;
import io.github.brunoborges.copilot.agent.CopilotTokenResolver;

public record CopilotAgentDefinition(
        String name, String typeName, String model, String reasoningEffort, Duration responseTimeout,
        SystemMessageConfig systemMessage, PermissionHandler permissions, List<ToolDefinition> tools,
        Map<String, McpServerConfig> mcpServers, ProviderConfig provider,
        List<CopilotAgentListener> listeners, List<CopilotAgentCustomizer> customizers,
        CopilotAgentGovernance governance, CopilotTokenResolver tokenResolver) {

    private static final Logger log = Logger.getLogger(CopilotAgentDefinition.class.getName());
    private static final PermissionHandler DENY = (request, invocation) -> {
        log.warning("Copilot agent denied permission request of kind '" + request.getKind() + "'.");
        return CompletableFuture.completedFuture(
                PermissionRequestResult.reject("No permission handler was configured for this agent."));
    };

    public static String name(Class<?> type, PropertyResolver environment) {
        var annotation = type.getAnnotation(CopilotAgent.class);
        String name = environment.resolveRequiredPlaceholders(annotation.name());
        if (name.isEmpty()) {
            name = uncapitalizeAsProperty(type.getSimpleName());
        }
        Assert.isTrue(name.matches("[A-Za-z][A-Za-z0-9._-]*"), "Invalid Copilot agent name on " + type.getName());
        return name;
    }

    public static CopilotAgentDefinition create(Class<?> type, BindingContext binding) {
        BeanLookup beans = binding.beans();
        PropertyResolver environment = binding.environment();
        CopilotAgentSettings properties = binding.agents();
        var annotation = type.getAnnotation(CopilotAgent.class);
        String name = name(type, environment);
        String instructions = resolve(annotation.instructions(), environment);
        String resource = resolve(annotation.instructionsResource(), environment);
        String systemBean = resolve(annotation.systemMessageRef(), environment);
        Assert.isTrue(instructions.isEmpty() || resource.isEmpty(),
                "Copilot agent " + name + ": instructions and instructionsResource are mutually exclusive");
        Assert.isTrue(systemBean.isEmpty() || (instructions.isEmpty() && resource.isEmpty()
                        && annotation.instructionsMode() == SystemMessageMode.APPEND),
                "Copilot agent " + name + ": systemMessageRef cannot be combined with inline/resource instructions or instructionsMode");

        SystemMessageConfig systemMessage;
        if (!systemBean.isEmpty()) {
            systemMessage = BeanRefs.resolve(name, "systemMessageRef", systemBean, SystemMessageConfig.class, beans);
            Assert.notNull(systemMessage.getMode(), "Copilot agent " + name + ": systemMessageRef bean must declare its mode");
            Assert.isTrue(systemMessage.getMode() != SystemMessageMode.REPLACE
                            || (systemMessage.getContent() != null && !systemMessage.getContent().isBlank()),
                    "Copilot agent " + name + ": REPLACE requires instructions");
        } else {
            Assert.isTrue(annotation.instructionsMode() != SystemMessageMode.CUSTOMIZE,
                    "Copilot agent " + name + ": CUSTOMIZE requires a systemMessageRef bean with section configuration");
            if (!resource.isEmpty()) {
                try (var input = binding.resources().open(resource)) {
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
        String permissionBean = resolve(annotation.permissionHandlerRef(), environment);
        String providerName = settings != null && settings.provider() != null
                ? settings.provider() : resolve(annotation.provider(), environment);
        Assert.isTrue(providerName.isEmpty() || !model.isEmpty(),
                "Copilot agent " + name + ": a BYOK provider requires a model");
        var tools = ToolBindings.bind(annotation.toolRefs(), beans, environment);
        var mcpServers = McpServerBindings.bind(settings != null && settings.mcpServers() != null
                ? settings.mcpServers() : Arrays.asList(annotation.mcpServers()), binding);
        var policy = CopilotAgentGovernance.permissions(name, settings, !permissionBean.isEmpty(), tools);
        return new CopilotAgentDefinition(name, type.getName(), model, reasoning,
                settings == null ? CopilotAgentSettings.DEFAULT_RESPONSE_TIMEOUT : settings.responseTimeout(), systemMessage,
                permissionBean.isEmpty() ? (policy == null ? DENY : policy) : BeanRefs.resolve(name, "permissionHandlerRef", permissionBean, PermissionHandler.class, beans),
                tools, mcpServers,
                ProviderBindings.bind(providerName, binding),
                beans(name, "listenerRefs", annotation.listenerRefs(), CopilotAgentListener.class, beans, environment),
                beans(name, "customizerRefs", annotation.customizerRefs(), CopilotAgentCustomizer.class, beans, environment),
                CopilotAgentGovernance.create(name, type, annotation, settings, tools, mcpServers, beans, environment),
                tokenResolver(name, annotation, beans, environment));
    }

    private static CopilotTokenResolver tokenResolver(String agent, CopilotAgent annotation, BeanLookup beans,
            PropertyResolver environment) {
        String selected = resolve(annotation.tokenResolverRef(), environment);
        // An ambiguous global selection fails startup instead of silently falling back to ambient credentials.
        return selected.isEmpty() ? beans.getIfAvailable(CopilotTokenResolver.class)
                : BeanRefs.resolve(agent, "tokenResolverRef", selected, CopilotTokenResolver.class, beans);
    }

    private static String resolve(String value, PropertyResolver environment) {
        String resolved = environment.resolveRequiredPlaceholders(value);
        Assert.isTrue(resolved.isEmpty() || !resolved.isBlank(), "Copilot agent annotation values must not be blank");
        return resolved;
    }

    private static <T> List<T> beans(String agent, String attribute, String[] names, Class<T> type, BeanLookup beans,
            PropertyResolver environment) {
        return Arrays.stream(names)
                .map(name -> BeanRefs.resolve(agent, attribute, resolve(name, environment), type, beans))
                .toList();
    }

    /** Lower-cases the first character unless the first two are upper case, like {@code java.beans.Introspector}. */
    static String uncapitalizeAsProperty(String name) {
        if (name.isEmpty() || (name.length() > 1 && Character.isUpperCase(name.charAt(0))
                && Character.isUpperCase(name.charAt(1)))) {
            return name;
        }
        return Character.toLowerCase(name.charAt(0)) + name.substring(1);
    }
}
