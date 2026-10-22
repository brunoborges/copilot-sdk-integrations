package io.github.brunoborges.copilot.agent.internal;

import java.util.List;

/** Bean container operations used to resolve {@code *Ref} attributes and optional collaborators. */
public interface BeanLookup {

    boolean containsBean(String name);

    boolean isTypeMatch(String name, Class<?> type);

    /** The bean class of the named bean, or {@code null} when unknown. */
    Class<?> getType(String name);

    Object getBean(String name);

    <T> T getBean(String name, Class<T> type);

    /** The single bean of the type, {@code null} when none exists; fails when several exist. */
    <T> T getIfAvailable(Class<T> type);

    /** Every bean of the type, in the container's order (Spring {@code @Order}, or CDI {@code @Priority}). */
    <T> List<T> ordered(Class<T> type);

    /** Hook beans annotated with {@code @GlobalCopilotAgentHooks}, ordered. */
    List<io.github.brunoborges.copilot.agent.CopilotAgentHooks> globalHooks();

    /** Hook beans whose {@code @CopilotAgentHooksFor} lists the agent type, ordered. */
    List<io.github.brunoborges.copilot.agent.CopilotAgentHooks> targetedHooks(Class<?> agentType);

    /**
     * The types whose generated {@code @CopilotTool} metadata binds the named bean's tools. Containers that proxy
     * beans override this to account for their proxy kinds; an empty list fails binding.
     */
    default List<Class<?>> toolContracts(String name, Object bean) {
        return ToolBindings.declaringTypes(bean);
    }
}
