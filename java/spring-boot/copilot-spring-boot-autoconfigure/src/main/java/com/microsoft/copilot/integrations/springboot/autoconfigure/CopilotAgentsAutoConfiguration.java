package io.github.brunoborges.copilot.autoconfigure;

import java.time.Clock;

import com.github.copilot.CopilotClient;
import io.github.brunoborges.copilot.agent.internal.CopilotAgentInvoker;
import io.github.brunoborges.copilot.agent.CopilotAgentTelemetry;
import io.github.brunoborges.copilot.agent.CopilotAgents;
import io.github.brunoborges.copilot.agent.CopilotLoops;
import io.github.brunoborges.copilot.agent.CopilotSessionStore;
import io.github.brunoborges.copilot.agent.internal.CopilotAgentDefinition;
import io.github.brunoborges.copilot.agent.internal.CopilotAgentFactory;
import io.github.brunoborges.copilot.agent.internal.CopilotSessionCleaner;
import io.github.brunoborges.copilot.agent.internal.CopilotSessionStorage;
import io.github.brunoborges.copilot.agent.internal.DefaultCopilotSessionStore;
import io.github.brunoborges.copilot.agent.internal.DefaultCopilotAgents;
import io.github.brunoborges.copilot.agent.internal.DefaultCopilotLoops;
import io.github.brunoborges.copilot.autoconfigure.internal.CopilotAgentFactoryBean;
import io.github.brunoborges.copilot.autoconfigure.internal.SpringAgentBinding;
import io.github.brunoborges.copilot.autoconfigure.internal.SpringSessionCleanup;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.core.env.Environment;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

@AutoConfiguration(after = CopilotAutoConfiguration.class)
@ConditionalOnClass(CopilotClient.class)
@ConditionalOnBean(CopilotClient.class)
@ConditionalOnProperty(prefix = "copilot", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties({CopilotAgentsProperties.class, CopilotAgentExecutionProperties.class, CopilotMcpProperties.class,
        CopilotProviderProperties.class, CopilotSessionProperties.class})
@Import(CopilotAgentsRegistrar.class)
public class CopilotAgentsAutoConfiguration {

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    CopilotAgentInvoker copilotAgentInvoker(CopilotClient client, CopilotAgentTelemetry telemetry,
            CopilotAgentExecutionProperties execution) {
        return new CopilotAgentInvoker(client, telemetry, SpringAgentBinding.limits(execution),
                SpringAgentBinding.contextPropagation(CopilotAgentInvoker.class.getClassLoader()));
    }

    @Bean
    @ConditionalOnMissingBean(CopilotAgentTelemetry.class)
    CopilotAgentTelemetry copilotAgentTelemetry() {
        return CopilotAgentTelemetry.NOOP;
    }

    @Bean
    @ConditionalOnMissingBean(CopilotAgents.class)
    CopilotAgents copilotAgents(BeanFactory beans, Environment environment) {
        return new DefaultCopilotAgents(type -> factory(type, beans, environment));
    }

    @Bean
    @ConditionalOnMissingBean(CopilotSessionStore.class)
    CopilotSessionStore copilotSessionStore(CopilotClient client, CopilotAgentInvoker invoker,
            CopilotSessionProperties sessions, BeanFactory beans, Environment environment) {
        return new DefaultCopilotSessionStore(CopilotSessionStorage.of(client), invoker, type -> {
            var factory = factory(type, beans, environment);
            return factory == null ? null : factory.definition();
        }, sessions.timeout());
    }

    @Bean
    @ConditionalOnProperty(prefix = "copilot.sessions.cleanup", name = "max-age")
    SpringSessionCleanup copilotSessionCleanup(CopilotClient client, CopilotAgentInvoker invoker,
            CopilotSessionProperties sessions) {
        var cleanup = sessions.cleanup();
        var cleaner = new CopilotSessionCleaner(CopilotSessionStorage.of(client), invoker, cleanup.maxAge(),
                sessions.timeout(), Clock.systemUTC());
        return new SpringSessionCleanup(cleaner, CopilotSessionCleaner.interval(cleanup.maxAge(), cleanup.interval()));
    }

    private static CopilotAgentFactory factory(Class<?> type, BeanFactory beans, Environment environment) {
        String name = "&" + CopilotAgentDefinition.name(type, environment::resolveRequiredPlaceholders);
        return beans.containsBean(name) ? beans.getBean(name, CopilotAgentFactoryBean.class).factory() : null;
    }

    @Bean
    @ConditionalOnMissingBean(CopilotLoops.class)
    CopilotLoops copilotLoops(CopilotAgents agents) {
        return new DefaultCopilotLoops(agents);
    }
}
