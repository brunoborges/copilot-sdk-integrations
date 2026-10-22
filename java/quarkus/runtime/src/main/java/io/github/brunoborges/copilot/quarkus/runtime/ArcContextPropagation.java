package io.github.brunoborges.copilot.quarkus.runtime;

import io.github.brunoborges.copilot.agent.internal.ContextPropagation;
import io.quarkus.arc.Arc;
import io.quarkus.arc.InjectableContext;
import io.quarkus.arc.ManagedContext;

/**
 * Propagates the caller's CDI request context to SDK threads, so request-scoped beans (for example the
 * {@code SecurityIdentity}) are usable from tools, hooks, and listeners while the caller's request is active.
 */
final class ArcContextPropagation implements ContextPropagation {

    @Override
    public Snapshot capture() {
        var container = Arc.container();
        if (container == null) {
            return Snapshot.NONE;
        }
        ManagedContext context = container.requestContext();
        if (!context.isActive()) {
            return Snapshot.NONE;
        }
        InjectableContext.ContextState state = context.getState();
        return () -> {
            if (context.isActive() || !state.isValid()) {
                return () -> { };
            }
            try {
                context.activate(state);
            } catch (RuntimeException ex) {
                return () -> { };
            }
            return context::deactivate;
        };
    }
}
