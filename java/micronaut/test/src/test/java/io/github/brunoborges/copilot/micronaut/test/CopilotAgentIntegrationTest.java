package io.github.brunoborges.copilot.micronaut.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import org.junit.jupiter.api.Test;

import com.github.copilot.CopilotClient;

import io.github.brunoborges.copilot.agent.ConversationId;
import io.github.brunoborges.copilot.agent.CopilotAgent;
import io.github.brunoborges.copilot.agent.CopilotAgentHooks;
import io.github.brunoborges.copilot.agent.CopilotAgentHooksFor;
import io.github.brunoborges.copilot.agent.CopilotAgents;
import io.github.brunoborges.copilot.agent.GlobalCopilotAgentHooks;
import io.github.brunoborges.copilot.agent.UserMessage;
import io.github.brunoborges.copilot.micronaut.CopilotAgentRegistry;
import io.github.brunoborges.copilot.micronaut.config.CopilotRuntimeConfig;
import io.micronaut.context.ApplicationContext;
import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Requires;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;

@MicronautTest(startApplication = false)
@Property(name = "spec.name", value = CopilotAgentIntegrationTest.SPEC)
@Property(name = "micronaut.copilot.agents.helper.model", value = "gpt-test")
@Property(name = "micronaut.copilot.agents.helper.response-timeout", value = "PT5S")
@Property(name = "micronaut.copilot.agents.helper.available-tools", value = "custom:*,view")
@Property(name = "micronaut.copilot.mcp.servers.docs.type", value = "http")
@Property(name = "micronaut.copilot.mcp.servers.docs.url", value = "https://example.com/mcp")
@Property(name = "micronaut.copilot.mcp.servers.docs.headers.X-Api-Key", value = "secret")
class CopilotAgentIntegrationTest {

    static final String SPEC = "CopilotAgentIntegrationTest";

    @Requires(property = "spec.name", value = SPEC)
    @CopilotAgent(name = "helper", instructions = "Answer briefly.", toolRefs = "glossary")
    interface Helper {
        CompletableFuture<String> ask(@ConversationId String conversation, @UserMessage String question);
    }

    @Requires(property = "spec.name", value = SPEC)
    @CopilotAgent(name = "one-shot")
    interface OneShot {
        CompletableFuture<String> ask(@UserMessage String question);
    }

    @Singleton
    @Requires(property = "spec.name", value = SPEC)
    @GlobalCopilotAgentHooks
    static class AuditHooks implements CopilotAgentHooks {
    }

    @Singleton
    @Requires(property = "spec.name", value = SPEC)
    @CopilotAgentHooksFor(Helper.class)
    static class HelperHooks implements CopilotAgentHooks {
    }

    @Singleton
    @Requires(property = "spec.name", value = SPEC)
    @CopilotAgentHooksFor(OneShot.class)
    static class OneShotHooks implements CopilotAgentHooks {
    }

    @Inject
    ApplicationContext context;

    @Inject
    Helper helper;

    @Inject
    CopilotAgents agents;

    @Inject
    CopilotTestRuntime copilot;

    @Inject
    CopilotAgentRegistry registry;

    @Inject
    CopilotRuntimeConfig config;

    @Inject
    AuditHooks audit;

    @Inject
    HelperHooks helperHooks;

    @Test
    void injectsConversationalAgentsAndRunsTools() {
        copilot.agent("helper").invokesTool("define", Map.of("term", "DI"))
                .repliesUsing(turn -> String.valueOf(turn.toolResult("define")));

        assertEquals("DI means something", helper.ask("c-1", "What is DI?").join());
        assertEquals("Answer briefly.", copilot.sessionConfig("helper").getSystemMessage().getContent());
        assertEquals("gpt-test", copilot.sessionConfig("helper").getModel());
        assertTrue(copilot.toolCalls("helper").get(0).succeeded());
    }

    @Test
    void replacesTheClientWithTheScriptedRuntime() {
        assertSame(copilot.client(), context.getBean(CopilotClient.class));
    }

    @Test
    void createsBoundAgentsOnDemand() {
        assertFalse(context.containsBean(OneShot.class), "bound agents are not beans");
        copilot.agent("one-shot").replies("done");

        try (var instance = agents.create(OneShot.class)) {
            assertEquals("done", instance.agent().ask("go").join());
        }
    }

    @Test
    void ordersGlobalHooksBeforeTargetedHooks() {
        List<CopilotAgentHooks> hooks = registry.factory(Helper.class).definition().governance().hooks();

        assertEquals(2, hooks.size());
        assertSame(audit, hooks.get(0));
        assertSame(helperHooks, hooks.get(1));
    }

    @Test
    void appliesAgentConfiguration() {
        var definition = registry.factory(Helper.class).definition();

        assertEquals("gpt-test", definition.model());
        assertEquals(Duration.ofSeconds(5), definition.responseTimeout());
        assertEquals(List.of("define"), definition.tools().stream().map(tool -> tool.name()).toList());
        assertEquals(List.of("custom:*", "view"), config.agents().get("helper").availableTools().orElseThrow());
    }

    @Test
    void bindsNamedMaps() {
        var docs = config.mcp().servers().get("docs");

        assertEquals("https://example.com/mcp", docs.url().orElseThrow());
        assertEquals(Map.of("X-Api-Key", "secret"), docs.headers());
    }
}
