package com.microsoft.copilot.integrations.springboot.autoconfigure.internal;

import com.microsoft.copilot.integrations.core.internal.ContextPropagation;
import io.micrometer.context.ContextRegistry;
import io.micrometer.context.ContextSnapshot;
import io.micrometer.context.ContextSnapshotFactory;
import io.micrometer.context.integration.Slf4jThreadLocalAccessor;
import org.springframework.util.ClassUtils;

public final class MicrometerContextPropagation implements ContextPropagation {

    private final ContextRegistry registry;
    private final ContextSnapshotFactory global;
    private final ContextSnapshotFactory mdc;

    public MicrometerContextPropagation(ClassLoader classLoader) {
        this(ContextRegistry.getInstance(), ClassUtils.isPresent("org.slf4j.MDC", classLoader));
    }

    MicrometerContextPropagation(ContextRegistry registry, boolean slf4j) {
        this.registry = registry;
        global = ContextSnapshotFactory.builder().contextRegistry(registry).clearMissing(true).build();
        mdc = slf4j ? ContextSnapshotFactory.builder()
                .contextRegistry(new ContextRegistry().registerThreadLocalAccessor(new Slf4jThreadLocalAccessor()))
                .clearMissing(true).build() : null;
    }

    @Override
    public Snapshot capture() {
        // Empty caller context is still restored so stale values on SDK threads are cleared.
        ContextSnapshot captured = global.captureAll();
        ContextSnapshot logging = mdc == null || mdcRegistered() ? null : mdc.captureAll();
        return () -> {
            var restored = captured.setThreadLocals();
            if (logging == null) {
                return restored::close;
            }
            ContextSnapshot.Scope restoredMdc;
            try {
                restoredMdc = logging.setThreadLocals();
            } catch (RuntimeException | Error ex) {
                restored.close();
                throw ex;
            }
            return () -> {
                try {
                    restoredMdc.close();
                } finally {
                    restored.close();
                }
            };
        };
    }

    private boolean mdcRegistered() {
        return registry.getThreadLocalAccessors().stream().anyMatch(accessor -> Slf4jThreadLocalAccessor.KEY.equals(accessor.key()));
    }
}
