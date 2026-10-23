package com.microsoft.copilot.integrations.micronaut.example;

import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.github.copilot.CopilotClient;

import io.micronaut.context.ApplicationContext;
import io.micronaut.management.health.indicator.HealthIndicator;
import io.micronaut.management.health.indicator.HealthResult;
import reactor.core.publisher.Flux;

class DisabledCopilotTest {

    @Test
    void startsWithoutCopilotBeansWhenDisabled() {
        try (var context = ApplicationContext.run(Map.of("micronaut.copilot.enabled", "false",
                "micronaut.copilot.test-runtime.enabled", "false", "endpoints.all.enabled", "true"))) {
            assertFalse(context.containsBean(CopilotClient.class));
            assertFalse(context.containsBean(MicronautAdvisor.class));
            assertFalse(Flux.fromIterable(context.getBeansOfType(HealthIndicator.class))
                    .flatMap(HealthIndicator::getResult).map(HealthResult::getName)
                    .any("copilot"::equals).block());
        }
    }
}
