package com.microsoft.copilot.integrations.quarkus.runtime;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import jakarta.annotation.PreDestroy;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import com.github.copilot.CopilotClient;

import com.microsoft.copilot.integrations.core.internal.CopilotAgentInvoker;
import com.microsoft.copilot.integrations.core.internal.CopilotSessionCleaner;
import com.microsoft.copilot.integrations.core.internal.CopilotSessionStorage;
import com.microsoft.copilot.integrations.quarkus.runtime.config.CopilotConfigs;
import com.microsoft.copilot.integrations.quarkus.runtime.config.CopilotRuntimeConfig;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;

/**
 * Deletes agent sessions older than {@code quarkus.copilot.sessions.cleanup.max-age} on a dedicated daemon thread
 * while the application runs. Does nothing unless the max age is set.
 */
@Singleton
public class CopilotSessionCleanup {

    private final CopilotSessionCleaner cleaner;
    private final Duration interval;
    private ScheduledExecutorService scheduler;

    @Inject
    public CopilotSessionCleanup(CopilotClient client, CopilotAgentInvoker invoker, CopilotRuntimeConfig config) {
        var sessions = config.sessions();
        var maxAge = sessions.cleanup().maxAge().orElse(null);
        if (maxAge == null) {
            cleaner = null;
            interval = null;
            return;
        }
        CopilotConfigs.requirePositive(maxAge, "quarkus.copilot.sessions.cleanup.max-age");
        CopilotConfigs.requirePositive(sessions.timeout(), "quarkus.copilot.sessions.timeout");
        sessions.cleanup().interval()
                .ifPresent(value -> CopilotConfigs.requirePositive(value, "quarkus.copilot.sessions.cleanup.interval"));
        cleaner = new CopilotSessionCleaner(CopilotSessionStorage.of(client), invoker, maxAge, sessions.timeout(),
                Clock.systemUTC());
        interval = CopilotSessionCleaner.interval(maxAge, sessions.cleanup().interval().orElse(null));
    }

    void onStart(@Observes StartupEvent event) {
        start();
    }

    void onStop(@Observes ShutdownEvent event) {
        stop();
    }

    /** Whether cleanup is configured. */
    public boolean enabled() {
        return cleaner != null;
    }

    /** The cleaner, or {@code null} when cleanup is disabled. */
    public CopilotSessionCleaner cleaner() {
        return cleaner;
    }

    /** Time between sweeps, or {@code null} when cleanup is disabled. */
    public Duration interval() {
        return interval;
    }

    /** Whether sweeps are scheduled. */
    public synchronized boolean isRunning() {
        return scheduler != null;
    }

    synchronized void start() {
        if (cleaner == null || scheduler != null) {
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(task -> {
            var thread = new Thread(task, "copilot-session-cleanup");
            thread.setDaemon(true);
            return thread;
        });
        long delay = interval.toMillis();
        scheduler.scheduleWithFixedDelay(cleaner::sweepQuietly, delay, delay, TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    synchronized void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
    }
}
