package io.github.brunoborges.copilot.quarkus.runtime;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.spi.Bean;

import io.github.brunoborges.copilot.agent.CopilotAgentHooks;
import io.github.brunoborges.copilot.agent.internal.BeanLookup;
import io.quarkus.arc.Arc;
import io.quarkus.arc.ArcContainer;
import io.quarkus.arc.InjectableBean;
import io.quarkus.arc.InstanceHandle;

/** Resolves {@code @Named} beans and ordered bean lists from the ArC container. */
final class ArcBeanLookup implements BeanLookup {

    private final ArcContainer container;

    ArcBeanLookup(ArcContainer container) {
        this.container = container;
    }

    static ArcBeanLookup current() {
        return new ArcBeanLookup(Arc.container());
    }

    @Override
    public boolean containsBean(String name) {
        return !beans(name).isEmpty();
    }

    @Override
    public boolean isTypeMatch(String name, Class<?> type) {
        var beans = beans(name);
        return beans.size() == 1 && beans.iterator().next().getTypes().stream().anyMatch(candidate -> raw(candidate) != null
                && type.isAssignableFrom(raw(candidate)));
    }

    @Override
    public Class<?> getType(String name) {
        var beans = beans(name);
        if (beans.size() != 1) {
            return null;
        }
        var bean = beans.iterator().next();
        return bean instanceof InjectableBean<?> injectable && injectable.getImplementationClass() != null
                ? injectable.getImplementationClass() : bean.getBeanClass();
    }

    @Override
    public Object getBean(String name) {
        var beans = beans(name);
        if (beans.size() > 1) {
            throw new IllegalArgumentException("Several beans are named '" + name + "'");
        }
        InstanceHandle<Object> handle = container.instance(name);
        if (!handle.isAvailable()) {
            throw new IllegalArgumentException("No bean named '" + name + "'");
        }
        return handle.get();
    }

    @Override
    public <T> T getBean(String name, Class<T> type) {
        return type.cast(getBean(name));
    }

    @Override
    public <T> T getIfAvailable(Class<T> type) {
        List<InstanceHandle<T>> handles = container.listAll(type, Any.Literal.INSTANCE);
        if (handles.size() > 1) {
            throw new IllegalStateException("Expected at most one " + type.getName() + " bean but found "
                    + handles.size() + "; select one per agent with the matching *Ref attribute");
        }
        return handles.isEmpty() ? null : handles.get(0).get();
    }

    @Override
    public <T> List<T> ordered(Class<T> type) {
        return container.listAll(type, Any.Literal.INSTANCE).stream().map(InstanceHandle::get).toList();
    }

    @Override
    public List<CopilotAgentHooks> globalHooks() {
        return hooks(registration -> registration.isGlobal());
    }

    @Override
    public List<CopilotAgentHooks> targetedHooks(Class<?> agentType) {
        return hooks(registration -> registration.getTargets().contains(agentType.getName()));
    }

    private List<CopilotAgentHooks> hooks(java.util.function.Predicate<HookRegistration> filter) {
        var ids = CopilotAgentCatalog.hooks().stream().filter(filter).map(HookRegistration::getBeanId)
                .collect(java.util.stream.Collectors.toSet());
        var result = new ArrayList<CopilotAgentHooks>();
        for (InstanceHandle<CopilotAgentHooks> handle : container.listAll(CopilotAgentHooks.class, Any.Literal.INSTANCE)) {
            if (ids.contains(handle.getBean().getIdentifier())) {
                result.add(handle.get());
            }
        }
        return result;
    }

    private Set<Bean<?>> beans(String name) {
        return container.beanManager().getBeans(name);
    }

    private static Class<?> raw(Type type) {
        if (type instanceof Class<?> raw) {
            return raw;
        }
        return type instanceof ParameterizedType parameterized && parameterized.getRawType() instanceof Class<?> raw
                ? raw : null;
    }
}
