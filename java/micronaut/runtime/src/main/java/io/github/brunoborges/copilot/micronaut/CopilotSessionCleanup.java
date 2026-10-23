package io.github.brunoborges.copilot.micronaut;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.ScheduledFuture;

import jakarta.annotation.PreDestroy;
import jakarta.inject.Named;
import jakarta.inject.Singleton;

import com.github.copilot.CopilotClient;

import io.github.brunoborges.copilot.agent.internal.CopilotAgentInvoker;
import io.github.brunoborges.copilot.agent.internal.CopilotSessionCleaner;
import io.github.brunoborges.copilot.agent.internal.CopilotSessionStorage;
import io.github.brunoborges.copilot.micronaut.config.CopilotConfigs;
import io.github.brunoborges.copilot.micronaut.config.CopilotRuntimeConfig;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.event.ApplicationEventListener;
import io.micronaut.context.event.StartupEvent;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.TaskScheduler;

/**
 * Deletes agent sessions older than {@code micronaut.copilot.sessions.cleanup.max-age} on the scheduled task
 * executor while the application runs. Only registered when the max age is set.
 */
@Singleton
@Requires(property = CopilotRuntimeConfig.PREFIX + ".enabled", notEquals = "false")
@Requires(property = CopilotRuntimeConfig.PREFIX + ".sessions.cleanup.max-age")
public class CopilotSessionCleanup implements ApplicationEventListener<StartupEvent> {

    private final CopilotSessionCleaner cleaner;
    private final Duration interval;
    private final TaskScheduler scheduler;
    private ScheduledFuture<?> task;

    CopilotSessionCleanup(CopilotClient client, CopilotAgentInvoker invoker, CopilotRuntimeConfig config,
            @Named(TaskExecutors.SCHEDULED) TaskScheduler scheduler) {
        var sessions = config.sessions();
        var maxAge = sessions.cleanup().maxAge().orElseThrow();
        CopilotConfigs.requirePositive(maxAge, CopilotRuntimeConfig.PREFIX + ".sessions.cleanup.max-age");
        CopilotConfigs.requirePositive(sessions.timeout(), CopilotRuntimeConfig.PREFIX + ".sessions.timeout");
        sessions.cleanup().interval().ifPresent(value ->
                CopilotConfigs.requirePositive(value, CopilotRuntimeConfig.PREFIX + ".sessions.cleanup.interval"));
        this.cleaner = new CopilotSessionCleaner(CopilotSessionStorage.of(client), invoker, maxAge, sessions.timeout(),
                Clock.systemUTC());
        this.interval = CopilotSessionCleaner.interval(maxAge, sessions.cleanup().interval().orElse(null));
        this.scheduler = scheduler;
    }

    @Override
    public synchronized void onApplicationEvent(StartupEvent event) {
        if (task == null) {
            task = scheduler.scheduleWithFixedDelay(interval, interval, cleaner::sweepQuietly);
        }
    }

    public CopilotSessionCleaner cleaner() {
        return cleaner;
    }

    public Duration interval() {
        return interval;
    }

    /** Whether sweeps are scheduled. */
    public synchronized boolean isRunning() {
        return task != null;
    }

    @PreDestroy
    synchronized void stop() {
        if (task != null) {
            task.cancel(false);
            task = null;
        }
    }
}
