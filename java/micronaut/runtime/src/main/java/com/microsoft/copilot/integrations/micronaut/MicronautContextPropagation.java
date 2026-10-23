package com.microsoft.copilot.integrations.micronaut;

import com.microsoft.copilot.integrations.core.internal.ContextPropagation;
import io.micronaut.core.propagation.PropagatedContext;

/**
 * Propagates the caller's Micronaut {@link PropagatedContext} (for example the current HTTP request and its
 * authentication) to SDK threads, so tools, hooks, and listeners observe the invoking request.
 */
final class MicronautContextPropagation implements ContextPropagation {

    @Override
    public Snapshot capture() {
        var context = PropagatedContext.getOrEmpty();
        if (context.isEmpty()) {
            return Snapshot.NONE;
        }
        return () -> {
            var scope = context.propagate();
            return scope::close;
        };
    }
}
