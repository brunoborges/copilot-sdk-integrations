package io.github.brunoborges.copilot.agent.internal;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.github.copilot.rpc.ToolDefinition;
import com.github.copilot.tool.CopilotTool;
import com.github.copilot.tool.CopilotToolMetadataProvider;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.core.env.Environment;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;

public final class SpringToolBindings {

    private SpringToolBindings() {
    }

    public static List<ToolDefinition> bind(String[] names, BeanFactory beans, Environment environment) {
        var tools = new ArrayList<ToolDefinition>();
        var identities = new HashSet<ToolIdentity>();
        for (String name : names) {
            String beanName = environment.resolveRequiredPlaceholders(name);
            Assert.hasText(beanName, "Copilot tool bean references must not be blank");
            Object bean = beans.getBean(beanName);
            var definitions = bean instanceof ToolDefinition tool ? List.of(tool) : service(beanName, bean);
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

    private static List<ToolDefinition> service(String name, Object bean) {
        Class<?> type = AopUtils.getTargetClass(bean);
        boolean declaredTools = Arrays.stream(type.getDeclaredMethods())
                .anyMatch(method -> method.isAnnotationPresent(CopilotTool.class));
        if (AopUtils.isJdkDynamicProxy(bean) || !declaredTools) {
            var definitions = new ArrayList<ToolDefinition>();
            for (var contract : ClassUtils.getAllInterfacesForClassAsSet(bean.getClass())) {
                if (Arrays.stream(contract.getDeclaredMethods()).anyMatch(method -> method.isAnnotationPresent(CopilotTool.class))) {
                    if (AopUtils.isCglibProxy(bean)) {
                        for (var method : contract.getDeclaredMethods()) {
                            if (method.isAnnotationPresent(CopilotTool.class) && !Modifier.isStatic(method.getModifiers())) {
                                var implementation = ClassUtils.getMethodIfAvailable(type, method.getName(), method.getParameterTypes());
                                Assert.isTrue(implementation != null && !Modifier.isFinal(implementation.getModifiers()),
                                        "Class-proxied Copilot tool methods must not be final: " + name);
                            }
                        }
                    }
                    definitions.addAll(generated(name, contract, bean));
                }
            }
            Assert.notEmpty(definitions, AopUtils.isJdkDynamicProxy(bean) ? "JDK-proxied Copilot tool bean " + name
                    + " must expose @CopilotTool methods on an interface compiled with CopilotToolProcessor"
                    : "Tool bean " + name + " must be a ToolDefinition or declare SDK @CopilotTool methods");
            return definitions;
        }
        if (AopUtils.isCglibProxy(bean)) {
            Assert.isTrue(Arrays.stream(type.getMethods()).filter(method -> method.isAnnotationPresent(CopilotTool.class))
                            .noneMatch(method -> Modifier.isFinal(method.getModifiers())),
                    "Class-proxied Copilot tool methods must not be final: " + name);
        }
        return generated(name, type, bean);
    }

    private static List<ToolDefinition> generated(String name, Class<?> type, Object bean) {
        try {
            Class<?> metadata = Class.forName(type.getName() + "$$CopilotToolMeta", true, type.getClassLoader());
            Object provider = metadata.getDeclaredConstructor().newInstance();
            Assert.isInstanceOf(CopilotToolMetadataProvider.class, provider, "Invalid SDK tool metadata for " + name);
            var mapper = new ObjectMapper().registerModule(new JavaTimeModule())
                    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                    .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)
                    .setDefaultPropertyInclusion(JsonInclude.Include.NON_NULL);
            // Bind the generated SDK handler to the Spring proxy, never its unwrapped target.
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
