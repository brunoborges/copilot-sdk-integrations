package com.microsoft.copilot.integrations.core.internal;

/**
 * Captures caller context at invocation time and restores it on the threads that run SDK work,
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
}
