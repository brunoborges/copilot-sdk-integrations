package com.microsoft.copilot.integrations.springboot.autoconfigure.internal;

import java.time.Duration;

import com.microsoft.copilot.integrations.core.internal.CopilotSessionCleaner;
import org.springframework.context.SmartLifecycle;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/** Runs {@link CopilotSessionCleaner} on a dedicated Spring task scheduler while the context is running. */
public final class SpringSessionCleanup implements SmartLifecycle {

    private final CopilotSessionCleaner cleaner;
    private final Duration interval;
    private ThreadPoolTaskScheduler scheduler;

    public SpringSessionCleanup(CopilotSessionCleaner cleaner, Duration interval) {
        this.cleaner = cleaner;
        this.interval = interval;
    }

    public CopilotSessionCleaner cleaner() {
        return cleaner;
    }

    public Duration interval() {
        return interval;
    }

    @Override
    public synchronized void start() {
        if (scheduler != null) {
            return;
        }
        var created = new ThreadPoolTaskScheduler();
        created.setPoolSize(1);
        created.setThreadNamePrefix("copilot-session-cleanup-");
        created.setDaemon(true);
        created.setWaitForTasksToCompleteOnShutdown(false);
        created.initialize();
        created.scheduleWithFixedDelay(cleaner::sweepQuietly,
                java.time.Instant.now().plus(interval), interval);
        scheduler = created;
    }

    @Override
    public synchronized void stop() {
        if (scheduler != null) {
            scheduler.shutdown();
            scheduler = null;
        }
    }

    @Override
    public synchronized boolean isRunning() {
        return scheduler != null;
    }
}
