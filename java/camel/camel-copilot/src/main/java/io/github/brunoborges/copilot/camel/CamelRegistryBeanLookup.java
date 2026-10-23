package io.github.brunoborges.copilot.camel;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import org.apache.camel.CamelContext;
import org.apache.camel.Ordered;

import io.github.brunoborges.copilot.agent.CopilotAgentHooks;
import io.github.brunoborges.copilot.agent.CopilotAgentHooksFor;
import io.github.brunoborges.copilot.agent.GlobalCopilotAgentHooks;
import io.github.brunoborges.copilot.agent.internal.BeanLookup;

/** Resolves {@code *Ref} attributes against the Camel {@link org.apache.camel.spi.Registry}. */
final class CamelRegistryBeanLookup implements BeanLookup {

    private final CamelContext context;

    CamelRegistryBeanLookup(CamelContext context) {
        this.context = context;
    }

    @Override
    public boolean containsBean(String name) {
        return context.getRegistry().lookupByName(name) != null;
    }

    @Override
    public boolean isTypeMatch(String name, Class<?> type) {
        return type.isInstance(context.getRegistry().lookupByName(name));
    }

    @Override
    public Class<?> getType(String name) {
        Object bean = context.getRegistry().lookupByName(name);
        return bean == null ? null : bean.getClass();
    }

    @Override
    public Object getBean(String name) {
        Object bean = context.getRegistry().lookupByName(name);
        if (bean == null) {
            throw new IllegalArgumentException("No bean named '" + name + "' in the Camel registry");
        }
        return bean;
    }

    @Override
    public <T> T getBean(String name, Class<T> type) {
        Object bean = getBean(name);
        if (!type.isInstance(bean)) {
            throw new IllegalArgumentException("Bean '" + name + "' is a " + bean.getClass().getName()
                    + ", expected " + type.getName());
        }
        return type.cast(bean);
    }

    @Override
    public <T> T getIfAvailable(Class<T> type) {
        var beans = context.getRegistry().findByTypeWithName(type);
        if (beans.size() > 1) {
            throw new IllegalStateException("Expected at most one " + type.getName() + " bean but found "
                    + beans.keySet() + "; select one per agent with the matching *Ref attribute");
        }
        return beans.isEmpty() ? null : beans.values().iterator().next();
    }

    @Override
    public <T> List<T> ordered(Class<T> type) {
        return sorted(context.getRegistry().findByTypeWithName(type), bean -> true);
    }

    @Override
    public List<CopilotAgentHooks> globalHooks() {
        return sorted(context.getRegistry().findByTypeWithName(CopilotAgentHooks.class),
                hooks -> hooks.getClass().isAnnotationPresent(GlobalCopilotAgentHooks.class));
    }

    @Override
    public List<CopilotAgentHooks> targetedHooks(Class<?> agentType) {
        return sorted(context.getRegistry().findByTypeWithName(CopilotAgentHooks.class), hooks -> {
            var targets = hooks.getClass().getAnnotation(CopilotAgentHooksFor.class);
            return targets != null && Arrays.asList(targets.value()).contains(agentType);
        });
    }

    /** Orders by {@link Ordered} when implemented, then by bean name, so registration order never matters. */
    private static <T> List<T> sorted(Map<String, T> beans, Predicate<T> filter) {
        return beans.entrySet().stream().filter(entry -> filter.test(entry.getValue()))
                .sorted(Comparator.<Map.Entry<String, T>>comparingInt(entry -> entry.getValue() instanceof Ordered ordered
                        ? ordered.getOrder() : Ordered.LOWEST).thenComparing(Map.Entry::getKey))
                .map(Map.Entry::getValue).toList();
    }
}
