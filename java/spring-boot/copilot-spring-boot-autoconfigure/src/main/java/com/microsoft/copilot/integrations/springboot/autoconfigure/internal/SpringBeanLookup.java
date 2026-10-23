package com.microsoft.copilot.integrations.springboot.autoconfigure.internal;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.function.Predicate;

import com.github.copilot.tool.CopilotTool;
import com.microsoft.copilot.integrations.core.CopilotAgentHooks;
import com.microsoft.copilot.integrations.core.CopilotAgentHooksFor;
import com.microsoft.copilot.integrations.core.GlobalCopilotAgentHooks;
import com.microsoft.copilot.integrations.core.internal.BeanLookup;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;

/** Adapts a Spring {@link BeanFactory} to the shared agent engine, including Spring AOP proxy handling for tools. */
public record SpringBeanLookup(BeanFactory beans) implements BeanLookup {

    @Override
    public boolean containsBean(String name) {
        return beans.containsBean(name);
    }

    @Override
    public boolean isTypeMatch(String name, Class<?> type) {
        return beans.isTypeMatch(name, type);
    }

    @Override
    public Class<?> getType(String name) {
        return beans.getType(name);
    }

    @Override
    public Object getBean(String name) {
        return beans.getBean(name);
    }

    @Override
    public <T> T getBean(String name, Class<T> type) {
        return beans.getBean(name, type);
    }

    @Override
    public <T> T getIfAvailable(Class<T> type) {
        return beans.getBeanProvider(type).getIfAvailable();
    }

    @Override
    public <T> List<T> ordered(Class<T> type) {
        return beans.getBeanProvider(type).orderedStream().toList();
    }

    @Override
    public List<CopilotAgentHooks> globalHooks() {
        return hooks((listable, name) -> listable.findAnnotationOnBean(name, GlobalCopilotAgentHooks.class) != null);
    }

    @Override
    public List<CopilotAgentHooks> targetedHooks(Class<?> agentType) {
        return hooks((listable, name) -> {
            var target = listable.findAnnotationOnBean(name, CopilotAgentHooksFor.class);
            return target != null && Arrays.asList(target.value()).contains(agentType);
        });
    }

    private List<CopilotAgentHooks> hooks(HookFilter filter) {
        if (!(beans instanceof ListableBeanFactory listable)) {
            return List.of();
        }
        var selected = new IdentityHashMap<CopilotAgentHooks, Boolean>();
        for (String name : listable.getBeanNamesForType(CopilotAgentHooks.class)) {
            if (filter.matches(listable, name)) {
                selected.put(listable.getBean(name, CopilotAgentHooks.class), true);
            }
        }
        // orderedStream honours @Order on @Bean methods in an application context; fall back to the
        // annotation-aware comparator for factories without a dependency comparator.
        var sorted = new ArrayList<>(listable.getBeanProvider(CopilotAgentHooks.class).orderedStream()
                .filter(selected::containsKey).toList());
        if (!(listable instanceof DefaultListableBeanFactory factory) || factory.getDependencyComparator() == null) {
            AnnotationAwareOrderComparator.sort(sorted);
        }
        return sorted;
    }

    /**
     * Binds generated tool metadata to the Spring proxy, never its unwrapped target. JDK proxies, and targets that do
     * not declare tools themselves, bind through their {@code @CopilotTool} interfaces; class-based (CGLIB) proxies
     * must not have final tool methods, which would bypass advice.
     */
    @Override
    public List<Class<?>> toolContracts(String name, Object bean) {
        Class<?> type = AopUtils.getTargetClass(bean);
        Predicate<Class<?>> declaresTools = candidate -> Arrays.stream(candidate.getDeclaredMethods())
                .anyMatch(method -> method.isAnnotationPresent(CopilotTool.class));
        if (AopUtils.isJdkDynamicProxy(bean) || !declaresTools.test(type)) {
            var contracts = new ArrayList<Class<?>>();
            for (var contract : ClassUtils.getAllInterfacesForClassAsSet(bean.getClass())) {
                if (declaresTools.test(contract)) {
                    if (AopUtils.isCglibProxy(bean)) {
                        for (var method : contract.getDeclaredMethods()) {
                            if (method.isAnnotationPresent(CopilotTool.class) && !Modifier.isStatic(method.getModifiers())) {
                                var implementation = ClassUtils.getMethodIfAvailable(type, method.getName(), method.getParameterTypes());
                                Assert.isTrue(implementation != null && !Modifier.isFinal(implementation.getModifiers()),
                                        "Class-proxied Copilot tool methods must not be final: " + name);
                            }
                        }
                    }
                    contracts.add(contract);
                }
            }
            Assert.isTrue(!contracts.isEmpty() || !AopUtils.isJdkDynamicProxy(bean), "JDK-proxied Copilot tool bean "
                    + name + " must expose @CopilotTool methods on an interface compiled with CopilotToolProcessor");
            return contracts;
        }
        if (AopUtils.isCglibProxy(bean)) {
            Assert.isTrue(Arrays.stream(type.getMethods()).filter(method -> method.isAnnotationPresent(CopilotTool.class))
                            .noneMatch(method -> Modifier.isFinal(method.getModifiers())),
                    "Class-proxied Copilot tool methods must not be final: " + name);
        }
        return List.of(type);
    }

    @FunctionalInterface
    private interface HookFilter {
        boolean matches(ListableBeanFactory beans, String name);
    }
}
