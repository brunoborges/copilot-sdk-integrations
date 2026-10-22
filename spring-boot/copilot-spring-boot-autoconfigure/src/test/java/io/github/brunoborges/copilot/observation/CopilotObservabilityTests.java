package io.github.brunoborges.copilot.observation;

import java.util.UUID;
import java.util.Map;
import java.io.Closeable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeoutException;

import com.github.copilot.CopilotClient;
import com.github.copilot.CopilotSession;
import com.github.copilot.generated.AssistantMessageEvent;
import com.github.copilot.rpc.ToolDefinition;
import com.github.copilot.rpc.ToolInvocation;
import com.github.copilot.rpc.ToolNamespace;
import com.github.copilot.rpc.SessionConfig;
import com.github.copilot.generated.AssistantUsageEvent;
import io.github.brunoborges.copilot.agent.CopilotAgentTelemetry;
import io.github.brunoborges.copilot.agent.internal.MicrometerAgentTelemetry;
import io.github.brunoborges.copilot.agent.internal.CopilotAgentDefinition;
import io.github.brunoborges.copilot.agent.internal.CopilotAgentInvoker;
import io.github.brunoborges.copilot.agent.tools.ObservedToolAgent;
import io.github.brunoborges.copilot.autoconfigure.CopilotAgentExecutionProperties;
import io.github.brunoborges.copilot.autoconfigure.CopilotAgentsProperties;
import io.github.brunoborges.copilot.autoconfigure.CopilotAgentObservationAutoConfiguration;
import io.github.brunoborges.copilot.autoconfigure.CopilotAgentsAutoConfiguration;
import io.github.brunoborges.copilot.autoconfigure.CopilotAutoConfiguration;
import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.DefaultResourceLoader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;

class CopilotObservabilityTests {

    @Test
    void actuatorAutomaticallyEnablesInstrumentationWithoutStartingCopilot() {
        var client = mock(CopilotClient.class);
        new ApplicationContextRunner().withUserConfiguration(ObservedApplication.class)
                .withBean(CopilotClient.class, () -> client).run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(ObservationRegistry.class);
                    assertThat(context.getBean(CopilotAgentTelemetry.class)).isInstanceOf(MicrometerAgentTelemetry.class);
                    verifyNoInteractions(client);
                });
    }

    @Test
    void instrumentationIsOptionalAndCanBeDisabledOrReplaced() {
        var runner = new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(
                CopilotAutoConfiguration.class, CopilotAgentObservationAutoConfiguration.class, CopilotAgentsAutoConfiguration.class));
        runner.run(context -> assertThat(context.getBean(CopilotAgentTelemetry.class)).isSameAs(CopilotAgentTelemetry.NOOP));
        runner.withClassLoader(new FilteredClassLoader("io.micrometer")).run(context ->
                assertThat(context).hasNotFailed());
        runner.withBean(ObservationRegistry.class, ObservationRegistry::create)
                .withPropertyValues("copilot.observations.enabled=false").run(context ->
                        assertThat(context.getBean(CopilotAgentTelemetry.class)).isSameAs(CopilotAgentTelemetry.NOOP));
        var custom = mock(CopilotAgentTelemetry.class);
        runner.withBean(CopilotAgentTelemetry.class, () -> custom)
                .withBean(ObservationRegistry.class, ObservationRegistry::create).run(context ->
                        assertThat(context.getBean(CopilotAgentTelemetry.class)).isSameAs(custom));
    }

    @Test
    void observesInvocationToolsAndTimeoutsWithoutHighCardinalityContent() {
        var meters = new SimpleMeterRegistry();
        try {
            var registry = ObservationRegistry.create();
            registry.observationConfig().observationHandler(new DefaultMeterObservationHandler(meters));
            var telemetry = new MicrometerAgentTelemetry(registry, meters);
            var invocation = telemetry.start("advisor", "model");
            try (var scope = invocation.openScope()) {
                assertThat(registry.getCurrentObservation().getContextView().getName()).isEqualTo("copilot.agent.invocation");
                var tool = invocation.tool("lookup");
                try (var toolScope = tool.openScope()) {
                    assertThat(registry.getCurrentObservation().getContextView().getName()).isEqualTo("copilot.agent.tool");
                }
                tool.stop();
            }
            invocation.stop();
            invocation.stop();
            var failed = telemetry.start("advisor", "model");
            failed.error(new CompletionException(new TimeoutException("timed out")));
            failed.stop();
            telemetry.rejected("advisor");
            assertThat(meters.get("copilot.agent.invocation").tag("outcome", "success").timer().count()).isEqualTo(1);
            assertThat(meters.get("copilot.agent.invocation").tag("outcome", "timeout").timer().count()).isEqualTo(1);
            assertThat(meters.get("copilot.agent.tool").tag("tool", "lookup").timer().count()).isEqualTo(1);
            assertThat(meters.get("copilot.agent.rejections").counter().count()).isEqualTo(1);
            assertThat(registry.getCurrentObservation()).isNull();
            assertThat(meters.getMeters()).allSatisfy(meter -> assertThat(meter.getId().getTags())
                    .noneMatch(tag -> tag.getKey().matches(".*(prompt|response|conversation|session).*")));
        } finally {
            meters.close();
        }
    }

    @Test
    void countsOnlyReportedUsageAndDeduplicatesSdkEventIds() {
        var meters = new SimpleMeterRegistry();
        try {
            var telemetry = new MicrometerAgentTelemetry(ObservationRegistry.create(), meters);
            var call = telemetry.start("advisor", "");
            var event = new AssistantUsageEvent();
            event.setId(UUID.randomUUID());
            var data = mock(AssistantUsageEvent.AssistantUsageEventData.class);
            when(data.model()).thenReturn("actual-model");
            when(data.inputTokens()).thenReturn(10L);
            when(data.outputTokens()).thenReturn(2L);
            event.setData(data);
            call.usage(event);
            call.usage(event);
            call.stop();
            assertThat(meters.get("copilot.agent.tokens").tags("kind", "input", "model", "actual-model").counter().count()).isEqualTo(10);
            assertThat(meters.get("copilot.agent.tokens").tag("kind", "output").counter().count()).isEqualTo(2);
            assertThat(meters.find("copilot.agent.tokens").tag("kind", "cache-read").counter()).isNull();
        } finally {
            meters.close();
        }
    }

    @Test
    void invocationWrapsToolHandlersWithoutLosingMetadataOrTracingScope() throws Exception {
        var meters = new SimpleMeterRegistry();
        try {
            var registry = ObservationRegistry.create();
            registry.observationConfig().observationHandler(new DefaultMeterObservationHandler(meters));
            var tool = ToolDefinition.create("lookup", "Lookup", Map.of(), invocation -> {
                assertThat(registry.getCurrentObservation().getContextView().getName()).isEqualTo("copilot.agent.tool");
                return CompletableFuture.completedFuture("found");
            }).namespace(new ToolNamespace("reference")).metadata(Map.of("source", "test"));
            var beans = new DefaultListableBeanFactory();
            beans.registerSingleton("tool", tool);
            var definition = CopilotAgentDefinition.create(ObservedToolAgent.class, beans, new StandardEnvironment(),
                    new DefaultResourceLoader(), new CopilotAgentsProperties(Map.of()));
            var client = mock(CopilotClient.class);
            var session = mock(CopilotSession.class);
            var observed = new AtomicReference<ToolDefinition>();
            var reply = new AssistantMessageEvent();
            var data = mock(AssistantMessageEvent.AssistantMessageEventData.class);
            when(data.content()).thenReturn("reply");
            reply.setData(data);
            when(client.getSessionMetadata(anyString())).thenReturn(CompletableFuture.completedFuture(null));
            when(client.createSession(any())).thenAnswer(invocation -> {
                var config = invocation.<SessionConfig>getArgument(0);
                observed.set(config.getTools().getFirst());
                when(session.getSessionId()).thenReturn(config.getSessionId());
                return CompletableFuture.completedFuture(session);
            });
            when(session.on(any())).thenReturn(mock(Closeable.class));
            when(session.sendAndWait(any(), anyLong())).thenAnswer(invocation ->
                    observed.get().handler().invoke(new ToolInvocation()).thenApply(value -> reply));
            try (var invoker = new CopilotAgentInvoker(client, new MicrometerAgentTelemetry(registry, meters),
                    new CopilotAgentExecutionProperties(1, 1))) {
                assertThat(invoker.invoke(definition, "conversation", "hello").get(5, TimeUnit.SECONDS)).isEqualTo("reply");
            }
            assertThat(observed.get().namespace()).isEqualTo(tool.namespace());
            assertThat(observed.get().metadata()).isEqualTo(tool.metadata());
            assertThat(observed.get().skipPermission()).isEqualTo(tool.skipPermission());
            assertThat(meters.get("copilot.agent.tool").tag("tool", "reference.lookup").timer().count()).isEqualTo(1);
            assertThat(meters.get("copilot.agent.invocation").tag("outcome", "success").timer().count()).isEqualTo(1);
        } finally {
            meters.close();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    static class ObservedApplication {
    }
}
