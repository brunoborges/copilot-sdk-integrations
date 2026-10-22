package io.github.brunoborges.copilot.autoconfigure;

import com.github.copilot.CopilotClient;
import io.github.brunoborges.copilot.agent.CopilotAgentTelemetry;
import io.github.brunoborges.copilot.agent.internal.MicrometerAgentTelemetry;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

@AutoConfiguration(before = CopilotAgentsAutoConfiguration.class, afterName = {
        "org.springframework.boot.micrometer.observation.autoconfigure.ObservationAutoConfiguration",
        "org.springframework.boot.actuate.autoconfigure.observation.ObservationAutoConfiguration"})
@ConditionalOnClass({CopilotClient.class, ObservationRegistry.class, MeterRegistry.class})
@ConditionalOnBean(ObservationRegistry.class)
@ConditionalOnProperty(prefix = "copilot", name = {"enabled", "observations.enabled"}, havingValue = "true", matchIfMissing = true)
public class CopilotAgentObservationAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(CopilotAgentTelemetry.class)
    CopilotAgentTelemetry micrometerCopilotAgentTelemetry(ObservationRegistry observations, ObjectProvider<MeterRegistry> meters) {
        return new MicrometerAgentTelemetry(observations, meters.getIfAvailable());
    }
}
