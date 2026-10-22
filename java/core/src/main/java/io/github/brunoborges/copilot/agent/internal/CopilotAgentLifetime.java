package io.github.brunoborges.copilot.agent.internal;

import java.util.concurrent.atomic.AtomicBoolean;

/** Tracks actual operations, independently of user-visible future completion or cancellation. */
public final class CopilotAgentLifetime implements AutoCloseable {

    private final AtomicBoolean existingHistory;
    private final ThreadLocal<Integer> callbacks = new ThreadLocal<>();
    private boolean closed;
    private int active;

    public CopilotAgentLifetime(boolean requireExistingHistory) {
        existingHistory = new AtomicBoolean(requireExistingHistory);
    }

    public synchronized Lease acquire() {
        assertOpen();
        active++;
        return new Lease(this);
    }

    public synchronized void assertOpen() {
        if (closed) {
            throw new IllegalStateException("Copilot agent instance is closed");
        }
    }

    private Scope enter() {
        Integer previous = callbacks.get();
        callbacks.set(previous == null ? 1 : previous + 1);
        return () -> {
            int remaining = callbacks.get() - 1;
            if (remaining == 0) {
                callbacks.remove();
            } else {
                callbacks.set(remaining);
            }
        };
    }

    private synchronized void release() {
        active--;
        if (active == 0) {
            notifyAll();
        }
    }

    @Override
    public void close() {
        boolean interrupted = false;
        synchronized (this) {
            if (closed && active == 0) {
                return;
            }
            Integer callbackDepth = callbacks.get();
            if (callbackDepth == null) {
                callbacks.remove();
            } else {
                throw new IllegalStateException("Cannot close a Copilot agent instance from its own invocation callback");
            }
            closed = true;
            while (active != 0) {
                try {
                    wait();
                } catch (InterruptedException ex) {
                    interrupted = true;
                }
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    @FunctionalInterface
    public interface Scope extends AutoCloseable {
        Scope NONE = () -> { };
        @Override
        void close();
    }

    public static final class Lease implements AutoCloseable {
        public static final Lease NONE = new Lease(null);
        private final CopilotAgentLifetime lifetime;
        private final AtomicBoolean released = new AtomicBoolean();

        private Lease(CopilotAgentLifetime lifetime) {
            this.lifetime = lifetime;
        }

        public boolean requiresHistory() {
            return lifetime != null && lifetime.existingHistory.get();
        }

        public void sessionOpened() {
            if (lifetime != null) {
                lifetime.existingHistory.set(true);
            }
        }

        public Scope enter() {
            return lifetime == null ? Scope.NONE : lifetime.enter();
        }

        public boolean bound() {
            return lifetime != null;
        }

        @Override
        public void close() {
            if (lifetime != null && released.compareAndSet(false, true)) {
                lifetime.release();
            }
        }
    }
}
