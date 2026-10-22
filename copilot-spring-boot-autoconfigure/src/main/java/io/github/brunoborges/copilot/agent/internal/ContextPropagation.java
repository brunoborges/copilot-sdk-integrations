package io.github.brunoborges.copilot.agent.internal;

import org.springframework.util.ClassUtils;

/**
 * Captures caller thread-local context at invocation time and restores it on the threads that run SDK work,
 * tool callbacks, permission handlers, and listeners.
 */
public interface ContextPropagation {

    ContextPropagation NONE = () -> Snapshot.NONE;

    Snapshot capture();

    /** Captured caller context. */
    @FunctionalInterface
    interface Snapshot {

        Snapshot NONE = () -> () -> { };

        Scope restore();
    }

    @FunctionalInterface
    interface Scope extends AutoCloseable {
        @Override
        void close();
    }

    /**
     * Uses Micrometer Context Propagation when it is on the classpath, which restores every registered
     * {@code ThreadLocalAccessor} (such as Spring Security's {@code SecurityContextHolder} and Micrometer
     * observations) plus the SLF4J MDC; otherwise propagation is a no-op.
     */
    static ContextPropagation detect(ClassLoader classLoader) {
        if (ClassUtils.isPresent("io.micrometer.context.ContextSnapshotFactory", classLoader)) {
            return new MicrometerContextPropagation(classLoader);
        }
        return NONE;
    }
}
