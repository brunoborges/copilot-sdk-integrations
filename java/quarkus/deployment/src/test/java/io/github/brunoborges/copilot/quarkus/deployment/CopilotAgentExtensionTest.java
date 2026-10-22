package io.github.brunoborges.copilot.quarkus.deployment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.github.brunoborges.copilot.agent.ConversationId;
import io.github.brunoborges.copilot.agent.CopilotAgent;
import io.github.brunoborges.copilot.agent.CopilotAgentHooks;
import io.github.brunoborges.copilot.agent.CopilotAgentHooksFor;
import io.github.brunoborges.copilot.agent.CopilotAgents;
import io.github.brunoborges.copilot.agent.GlobalCopilotAgentHooks;
import io.github.brunoborges.copilot.agent.UserMessage;
import io.github.brunoborges.copilot.quarkus.runtime.CopilotAgentRegistry;
import io.github.brunoborges.copilot.quarkus.test.CopilotTestRuntime;
import io.quarkus.arc.Arc;
import io.quarkus.test.QuarkusUnitTest;

class CopilotAgentExtensionTest {

    @RegisterExtension
    static final QuarkusUnitTest TEST = new QuarkusUnitTest()
            .withApplicationRoot(jar -> jar.addClasses(Helper.class, OneShot.class, Glossary.class,
                    AuditHooks.class, HelperHooks.class, OneShotHooks.class)
                    .addClass(Glossary.class.getName() + "$$CopilotToolMeta"))
            .overrideConfigKey("quarkus.copilot.agents.helper.model", "gpt-test")
            .overrideConfigKey("quarkus.copilot.agents.helper.response-timeout", "PT5S");

    @CopilotAgent(name = "helper", instructions = "Answer briefly.", toolRefs = "glossary")
    interface Helper {
        CompletableFuture<String> ask(@ConversationId String conversation, @UserMessage String question);
    }

    @CopilotAgent(name = "one-shot")
    interface OneShot {
        CompletableFuture<String> ask(@UserMessage String question);
    }

    @Singleton
    @GlobalCopilotAgentHooks
    static class AuditHooks implements CopilotAgentHooks {
    }

    @Singleton
    @CopilotAgentHooksFor(Helper.class)
    static class HelperHooks implements CopilotAgentHooks {
    }

    @Singleton
    @CopilotAgentHooksFor(OneShot.class)
    static class OneShotHooks implements CopilotAgentHooks {
    }

    @Inject
    Helper helper;

    @Inject
    CopilotAgents agents;

    @Inject
    CopilotTestRuntime copilot;

    @Inject
    CopilotAgentRegistry registry;

    @Inject
    AuditHooks audit;

    @Inject
    HelperHooks helperHooks;

    @AfterEach
    void reset() {
        copilot.reset();
    }

    @Test
    void injectsConversationalAgentsAndRunsTools() {
        copilot.agent("helper").invokesTool("define", Map.of("term", "CDI"))
                .repliesUsing(turn -> String.valueOf(turn.toolResult("define")));

        assertEquals("CDI means something", helper.ask("c-1", "What is CDI?").join());
        assertEquals("Answer briefly.", copilot.sessionConfig("helper").getSystemMessage().getContent());
        assertEquals("gpt-test", copilot.sessionConfig("helper").getModel());
        assertTrue(copilot.toolCalls("helper").get(0).succeeded());
    }

    @Test
    void createsBoundAgentsOnDemand() {
        assertFalse(Arc.container().instance(OneShot.class).isAvailable(), "bound agents are not beans");
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
        assertEquals(java.time.Duration.ofSeconds(5), definition.responseTimeout());
        assertEquals(List.of("define"), definition.tools().stream().map(tool -> tool.name()).toList());
    }
}
