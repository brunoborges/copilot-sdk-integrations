package io.github.brunoborges.copilot.micronaut;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.function.Predicate;

import io.github.brunoborges.copilot.agent.CopilotAgentHooks;
import io.github.brunoborges.copilot.agent.CopilotAgentHooksFor;
import io.github.brunoborges.copilot.agent.GlobalCopilotAgentHooks;
import io.github.brunoborges.copilot.agent.internal.BeanLookup;
import io.micronaut.context.BeanContext;
import io.micronaut.context.BeanRegistration;
import io.micronaut.context.exceptions.NonUniqueBeanException;
import io.micronaut.core.order.OrderUtil;
import io.micronaut.inject.BeanDefinition;
import io.micronaut.inject.qualifiers.Qualifiers;

/** Resolves {@code @Named} beans, ordered bean lists, and governance hooks from the Micronaut bean context. */
final class MicronautBeanLookup implements BeanLookup {

    private final BeanContext context;

    MicronautBeanLookup(BeanContext context) {
        this.context = context;
    }

    @Override
    public boolean containsBean(String name) {
        return !definitions(name).isEmpty();
    }

    @Override
    public boolean isTypeMatch(String name, Class<?> type) {
        var definitions = definitions(name);
        return definitions.size() == 1 && type.isAssignableFrom(definitions.iterator().next().getBeanType());
    }

    @Override
    public Class<?> getType(String name) {
        var definitions = definitions(name);
        return definitions.size() == 1 ? definitions.iterator().next().getBeanType() : null;
    }

    @Override
    public Object getBean(String name) {
        var definitions = definitions(name);
        if (definitions.size() > 1) {
            throw new IllegalArgumentException("Several beans are named '" + name + "'");
        }
        if (definitions.isEmpty()) {
            throw new IllegalArgumentException("No bean named '" + name + "'");
        }
        return context.getBean(definitions.iterator().next());
    }

    @Override
    public <T> T getBean(String name, Class<T> type) {
        return type.cast(getBean(name));
    }

    @Override
    public <T> T getIfAvailable(Class<T> type) {
        try {
            return context.findBean(type).orElse(null);
        } catch (NonUniqueBeanException ex) {
            throw new IllegalStateException("Expected at most one " + type.getName() + " bean but found several; "
                    + "select one per agent with the matching *Ref attribute", ex);
        }
    }

    @Override
    public <T> List<T> ordered(Class<T> type) {
        return registrations(type).stream().map(BeanRegistration::getBean).toList();
    }

    @Override
    public List<CopilotAgentHooks> globalHooks() {
        return hooks(definition -> definition.hasStereotype(GlobalCopilotAgentHooks.class));
    }

    @Override
    public List<CopilotAgentHooks> targetedHooks(Class<?> agentType) {
        return hooks(definition -> Arrays.stream(definition.classValues(CopilotAgentHooksFor.class))
                .anyMatch(target -> target.getName().equals(agentType.getName())));
    }

    private List<CopilotAgentHooks> hooks(Predicate<BeanDefinition<?>> filter) {
        var result = new ArrayList<CopilotAgentHooks>();
        for (var registration : registrations(CopilotAgentHooks.class)) {
            if (filter.test(registration.getBeanDefinition())) {
                result.add(registration.getBean());
            }
        }
        return result;
    }

    private <T> List<BeanRegistration<T>> registrations(Class<T> type) {
        return OrderUtil.sortOrderedCollection(context.getBeanRegistrations(type));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Collection<BeanDefinition<Object>> definitions(String name) {
        return context.getBeanDefinitions(Object.class, (io.micronaut.context.Qualifier) Qualifiers.byName(name));
    }
}
