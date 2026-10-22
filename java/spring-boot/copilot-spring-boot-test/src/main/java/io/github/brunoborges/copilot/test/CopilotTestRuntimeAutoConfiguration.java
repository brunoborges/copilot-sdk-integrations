package io.github.brunoborges.copilot.test;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import com.github.copilot.CopilotClient;

import io.github.brunoborges.copilot.agent.CopilotAgentTelemetry;
import io.github.brunoborges.copilot.autoconfigure.CopilotAutoConfiguration;

/**
 * Auto-configuration imported by {@link AutoConfigureCopilotTestRuntime}: registers a {@link CopilotTestRuntime} and
 * exposes its client as the primary {@link CopilotClient}, which makes {@link CopilotAutoConfiguration} back off so
 * that no Copilot CLI is started.
 */
@AutoConfiguration(before = CopilotAutoConfiguration.class)
public class CopilotTestRuntimeAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    CopilotTestRuntime copilotTestRuntime() {
        return new CopilotTestRuntime();
    }

    @Bean(destroyMethod = "")
    @Primary
    CopilotClient copilotTestClient(CopilotTestRuntime runtime) {
        return runtime.client();
    }

    @Bean
    static BeanPostProcessor copilotTestRuntimeTelemetryPostProcessor(ObjectProvider<CopilotTestRuntime> runtime) {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
                if (bean instanceof CopilotAgentTelemetry telemetry) {
                    return runtime.getObject().telemetry(telemetry);
                }
                return bean;
            }
        };
    }
}
