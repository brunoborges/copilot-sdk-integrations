package com.microsoft.copilot.integrations.core.internal;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.github.copilot.rpc.ToolDefinition;
import com.github.copilot.tool.CopilotTool;
import com.github.copilot.tool.CopilotToolMetadataProvider;

/**
 * Binds {@code toolRefs} beans to SDK tool definitions. A bean is either an SDK {@link ToolDefinition} or declares
 * SDK {@link CopilotTool} methods, on its class or one of its interfaces, compiled with {@code CopilotToolProcessor}.
 * {@link BeanLookup#toolContracts(String, Object)} chooses the declaring types, and generated handlers are bound to the
 * injectable bean instance (a Spring AOP proxy or an ArC client proxy), so interceptors and scopes apply to tool calls.
 */
public final class ToolBindings {

    static final String METADATA_SUFFIX = "$$CopilotToolMeta";

    private ToolBindings() {
    }

    public static List<ToolDefinition> bind(String[] names, BeanLookup beans, PropertyResolver environment) {
        var tools = new ArrayList<ToolDefinition>();
        var identities = new HashSet<ToolIdentity>();
        for (String name : names) {
            String beanName = environment.resolveRequiredPlaceholders(name);
            Assert.hasText(beanName, "Copilot agent toolRefs entries must not be blank");
            Assert.isTrue(beans.containsBean(beanName), () -> "Copilot agent toolRefs references bean '" + beanName
                    + "', but no bean with that name exists; toolRefs lists bean names, use availableTools to "
                    + "filter runtime tools by tool name");
            Object bean = beans.getBean(beanName);
            var definitions = bean instanceof ToolDefinition tool ? List.of(tool) : service(beanName, bean, beans);
            Assert.notEmpty(definitions, "No SDK-annotated tools found on bean " + beanName);
            for (var tool : definitions) {
                Assert.hasText(tool.name(), "Copilot tool names must not be blank");
                Assert.isTrue(tool.namespace() == null || (tool.namespace().name() != null && !tool.namespace().name().isBlank()),
                        "Copilot tool namespace names must not be blank");
                var identity = new ToolIdentity(tool.namespace() == null ? "" : tool.namespace().name(), tool.name());
                Assert.isTrue(identities.add(identity), "Duplicate Copilot tool name in agent: " + identity);
                tools.add(tool);
            }
        }
        return List.copyOf(tools);
    }

    private static List<ToolDefinition> service(String name, Object bean, BeanLookup beans) {
        var contracts = beans.toolContracts(name, bean);
        Assert.notEmpty(contracts, "Tool bean " + name + " must be a ToolDefinition or declare SDK @CopilotTool methods");
        var definitions = new ArrayList<ToolDefinition>();
        for (var contract : contracts) {
            definitions.addAll(generated(name, contract, bean));
        }
        return definitions;
    }

    /**
     * Default tool contract resolution: the first class in the bean's hierarchy declaring {@link CopilotTool}
     * methods, otherwise every interface declaring them. Container subclasses (such as ArC client proxies and
     * intercepted subclasses) extend the bean class without redeclaring annotations, so the first declaring class
     * is the user's class.
     */
    public static List<Class<?>> declaringTypes(Object bean) {
        for (Class<?> type = bean.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            if (declaresTools(type)) {
                return List.of(type);
            }
        }
        return interfaces(bean.getClass()).stream().filter(ToolBindings::declaresTools).toList();
    }

    /** Whether the type itself declares {@link CopilotTool} methods. */
    public static boolean declaresTools(Class<?> type) {
        return Arrays.stream(type.getDeclaredMethods()).anyMatch(method -> method.isAnnotationPresent(CopilotTool.class));
    }

    private static Set<Class<?>> interfaces(Class<?> type) {
        var result = new LinkedHashSet<Class<?>>();
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            collect(current.getInterfaces(), result);
        }
        return result;
    }

    private static void collect(Class<?>[] types, Set<Class<?>> result) {
        for (var type : types) {
            if (result.add(type)) {
                collect(type.getInterfaces(), result);
            }
        }
    }

    private static List<ToolDefinition> generated(String name, Class<?> type, Object bean) {
        try {
            Class<?> metadata = Class.forName(type.getName() + METADATA_SUFFIX, true, type.getClassLoader());
            Object provider = metadata.getDeclaredConstructor().newInstance();
            Assert.isInstanceOf(CopilotToolMetadataProvider.class, provider, "Invalid SDK tool metadata for " + name);
            var mapper = new ObjectMapper().registerModule(new JavaTimeModule())
                    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                    .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)
                    .setDefaultPropertyInclusion(JsonInclude.Include.NON_NULL);
            Object result = metadata.getMethod("definitions", type, ObjectMapper.class).invoke(provider, bean, mapper);
            Assert.isInstanceOf(List.class, result, "Invalid SDK tool definitions for " + name);
            return ((List<?>) result).stream().map(ToolDefinition.class::cast).toList();
        } catch (ClassNotFoundException ex) {
            throw new IllegalArgumentException("Missing generated SDK tool metadata for " + name
                    + "; enable com.github.copilot.tool.CopilotToolProcessor when compiling " + type.getName(), ex);
        } catch (InvocationTargetException ex) {
            throw new IllegalArgumentException("SDK tool binding failed for " + name, ex.getCause());
        } catch (ReflectiveOperationException ex) {
            throw new IllegalArgumentException("Cannot bind generated SDK tools for " + name, ex);
        }
    }

    private record ToolIdentity(String namespace, String name) {
    }
}
