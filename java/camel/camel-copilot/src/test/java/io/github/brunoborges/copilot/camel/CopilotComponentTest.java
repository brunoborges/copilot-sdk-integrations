package io.github.brunoborges.copilot.camel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.apache.camel.CamelContext;
import org.apache.camel.CamelExecutionException;
import org.apache.camel.Exchange;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.component.mock.MockEndpoint;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.support.PropertyBindingSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.github.copilot.rpc.MessageSource;
import com.github.copilot.rpc.PermissionHandler;
import com.github.copilot.rpc.PermissionInvocation;
import com.github.copilot.rpc.PermissionRequest;
import com.github.copilot.rpc.PermissionRequestResult;
import com.github.copilot.rpc.ToolDefinition;

import io.github.brunoborges.copilot.agent.ConversationId;
import io.github.brunoborges.copilot.agent.CopilotAgent;
import io.github.brunoborges.copilot.agent.CopilotLoops;
import io.github.brunoborges.copilot.agent.CopilotPermissionPolicies;
import io.github.brunoborges.copilot.agent.CopilotSessionStore;
import io.github.brunoborges.copilot.agent.UserMessage;
import io.github.brunoborges.copilot.agent.internal.CopilotAgentInvoker;
import io.github.brunoborges.copilot.camel.test.CopilotTestPrompt;
import io.github.brunoborges.copilot.camel.test.CopilotTestRuntime;

class CopilotComponentTest {

    public record OrderQuery(String orderId) {
    }

    public record Verdict(String decision, String reason) {
    }

    @CopilotAgent(name = "advisor", instructions = "You advise on Camel routes.", builtInTools = false)
    public interface Advisor {
        String ask(@ConversationId String conversationId, @UserMessage String question);
    }

    private final CopilotTestRuntime runtime = new CopilotTestRuntime();
    private CamelContext context;
    private ProducerTemplate template;

    @BeforeEach
    void setUp() {
        context = new DefaultCamelContext();
        context.getRegistry().bind("copilotClient", runtime.client());
        context.getRegistry().bind("copilotTelemetry", runtime.telemetry());
    }

    @AfterEach
    void tearDown() {
        if (context != null) {
            context.stop();
        }
    }

    private void start(RouteBuilder routes) throws Exception {
        context.addRoutes(routes);
        context.start();
        template = context.createProducerTemplate();
    }

    @Test
    void scheduledRoutesLabelPromptsAsSystemUnlessConfigured() throws Exception {
        start(new RouteBuilder() {
            @Override
            public void configure() {
                from("timer:digest?repeatCount=1").setBody(constant("Summarize")).to("copilot:agent:digest");
                from("timer:report?repeatCount=1").setBody(constant("Report"))
                        .to("copilot:agent:report?messageSource=agent-reporting");
                from("direct:ask").to("copilot:agent:support");
            }
        });
        runtime.anyAgent().replies("ok");

        template.requestBody("direct:ask", "hi");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while ((runtime.prompts("digest").isEmpty() || runtime.prompts("report").isEmpty())
                && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }

        assertThat(source("digest")).isEqualTo(MessageSource.SYSTEM);
        assertThat(source("report")).isEqualTo(MessageSource.agent("reporting"));
        assertThat(source("support")).as("request-driven routes keep the runtime default").isNull();
    }

    @Test
    void rejectsInvalidMessageSources() throws Exception {
        start(new RouteBuilder() {
            @Override
            public void configure() {
                from("direct:ask").to("copilot:agent:support?messageSource=admin");
            }
        });

        assertThatThrownBy(() -> template.requestBody("direct:ask", "hi")).hasStackTraceContaining("message source");
        assertThat(runtime.prompts()).isEmpty();
    }

    private MessageSource source(String agent) {
        return runtime.receivedPrompts().stream().filter(p -> agent.equals(p.agent())).findFirst()
                .map(CopilotTestPrompt::options).orElseThrow().getSource();
    }

    @Test
    void sendsBodyAsPromptAndRepliesWithAnswer() throws Exception {
        start(new RouteBuilder() {
            @Override
            public void configure() {
                from("direct:ask").to("copilot:agent:support?instructions=Answer briefly.&model=gpt-5");
            }
        });
        runtime.agent("support").replies("Use a dead letter channel.");

        Exchange reply = template.request("direct:ask", exchange -> {
            exchange.getMessage().setBody("How do I handle poison messages?");
            exchange.getMessage().setHeader(CopilotConstants.CONVERSATION_ID, "c-1");
        });

        assertThat(reply.getException()).isNull();
        assertThat(reply.getMessage().getBody(String.class)).isEqualTo("Use a dead letter channel.");
        assertThat(reply.getMessage().getHeader(CopilotConstants.AGENT_NAME)).isEqualTo("support");
        assertThat(runtime.prompts("support")).containsExactly("How do I handle poison messages?");
        var config = runtime.sessionConfig("support");
        assertThat(config.getModel()).isEqualTo("gpt-5");
        assertThat(config.getSystemMessage().getContent()).isEqualTo("Answer briefly.");
        assertThat(config.getExcludedTools()).as("endpoint agents run without built-in tools by default").isNotEmpty();
        assertThat(config.getToolSearch()).isNull();
    }

    @Test
    void toolSearchOptionsReachTheSession() throws Exception {
        start(new RouteBuilder() {
            @Override
            public void configure() {
                from("direct:ask").to("copilot:agent:support?toolSearch=true&toolSearchDeferThreshold=20");
            }
        });
        runtime.agent("support").replies("ok");

        Exchange reply = template.request("direct:ask", exchange -> {
            exchange.getMessage().setBody("hi");
            exchange.getMessage().setHeader(CopilotConstants.CONVERSATION_ID, "c-1");
        });

        assertThat(reply.getException()).isNull();
        var search = runtime.sessionConfig("support").getToolSearch();
        assertThat(search.getEnabled()).isTrue();
        assertThat(search.getDeferThreshold()).isEqualTo(20);
    }

    @Test
    void continuesConversationFromHeader() throws Exception {
        start(new RouteBuilder() {
            @Override
            public void configure() {
                from("direct:ask").to("copilot:agent:support");
            }
        });
        runtime.agent("support").replies("first").replies("second");

        Object first = template.requestBodyAndHeader("direct:ask", "Hello", CopilotConstants.CONVERSATION_ID, "ticket-7");
        Exchange second = template.request("direct:ask", exchange -> {
            exchange.getMessage().setBody("Again");
            exchange.getMessage().setHeader(CopilotConstants.CONVERSATION_ID, "ticket-7");
        });

        assertThat(first).isEqualTo("first");
        assertThat(second.getMessage().getBody()).isEqualTo("second");
        assertThat(second.getMessage().getHeader(CopilotConstants.CONVERSATION_ID)).isEqualTo("ticket-7");
        assertThat(runtime.sessions("support")).hasSize(1);
        assertThat(runtime.sessions("support").get(0).prompts()).containsExactly("Hello", "Again");
    }

    @Test
    void conversationIdAndPromptCanBeExpressions() throws Exception {
        start(new RouteBuilder() {
            @Override
            public void configure() {
                from("direct:ask").to("copilot:agent:support?conversationId=${header.customer}"
                        + "&prompt=Summarize: ${body}");
            }
        });
        runtime.agent("support").replies("ok");

        template.requestBodyAndHeader("direct:ask", "order 42 is late", "customer", "acme");

        assertThat(runtime.prompts("support")).containsExactly("Summarize: order 42 is late");
        assertThat(runtime.sessions("support")).hasSize(1);
    }

    @Test
    void oneShotConversationsDeleteTheirSession() throws Exception {
        start(new RouteBuilder() {
            @Override
            public void configure() {
                from("direct:ask").to("copilot:agent:support");
            }
        });
        runtime.agent("support").replies("one").replies("two");

        Exchange first = template.request("direct:ask", exchange -> exchange.getMessage().setBody("a"));
        Object second = template.requestBody("direct:ask", "b");

        assertThat(first.getMessage().getBody()).isEqualTo("one");
        assertThat(second).isEqualTo("two");
        assertThat(first.getMessage().getHeader(CopilotConstants.CONVERSATION_ID)).isNull();
        assertThat(runtime.sessions("support")).isEmpty();
    }

    @Test
    void convertsStructuredReplies() throws Exception {
        start(new RouteBuilder() {
            @Override
            public void configure() {
                from("direct:triage").to("copilot:agent:triage?outputClass=" + Verdict.class.getName());
            }
        });
        runtime.agent("triage").replyJson(new Verdict("refund", "damaged"));

        Object verdict = template.requestBody("direct:triage", "The parcel arrived broken");

        assertThat(verdict).isEqualTo(new Verdict("refund", "damaged"));
    }

    @Test
    void toolRoutesAnswerToolCalls() throws Exception {
        start(new RouteBuilder() {
            @Override
            public void configure() {
                from("copilot:tool:lookupOrder?description=Looks up an order&parametersClass="
                        + OrderQuery.class.getName())
                        .setBody(simple("Order ${body.orderId} shipped via ${header.CopilotToolName}"));
                from("direct:ask").to("copilot:agent:support?toolRefs=lookupOrder&allowAgentTools=true");
            }
        });
        runtime.agent("support").invokesTool("lookupOrder", Map.of("orderId", "42")).replies("It shipped.");

        Object reply = template.requestBodyAndHeader("direct:ask", "Where is order 42?",
                CopilotConstants.CONVERSATION_ID, "c-1");

        assertThat(reply).isEqualTo("It shipped.");
        var call = runtime.toolCalls("support").get(0);
        assertThat(call.succeeded()).isTrue();
        assertThat(call.result()).isEqualTo("Order 42 shipped via lookupOrder");
        assertThat(context.getRegistry().lookupByName("lookupOrder")).isInstanceOf(ToolDefinition.class);
        var policy = runtime.sessionConfig("support").getOnPermissionRequest();
        var request = new PermissionRequest();
        request.setKind(CopilotPermissionPolicies.CUSTOM_TOOL);
        request.setExtensionData(Map.of("toolName", "lookupOrder"));
        assertThat(CopilotPermissionPolicies.isApproval(policy.handle(request, new PermissionInvocation()).join()))
                .isTrue();
    }

    @Test
    void failingToolRoutesFailTheToolCall() throws Exception {
        start(new RouteBuilder() {
            @Override
            public void configure() {
                from("copilot:tool:lookupOrder?description=Looks up an order")
                        .throwException(new IllegalStateException("database down"));
                from("direct:ask").to("copilot:agent:support?toolRefs=lookupOrder");
            }
        });
        runtime.agent("support").invokesTool("lookupOrder", Map.of("orderId", "42")).replies("Sorry.");

        template.requestBody("direct:ask", "Where is order 42?");

        assertThat(runtime.toolCalls("support").get(0).succeeded()).isFalse();
    }

    @Test
    void endpointAgentsDenyPermissionsByDefault() throws Exception {
        start(new RouteBuilder() {
            @Override
            public void configure() {
                from("direct:ask").to("copilot:agent:support");
            }
        });
        runtime.agent("support").replies("ok");
        template.requestBodyAndHeader("direct:ask", "hi", CopilotConstants.CONVERSATION_ID, "c-1");

        var request = new PermissionRequest();
        request.setKind(CopilotPermissionPolicies.SHELL);
        var result = runtime.sessionConfig("support").getOnPermissionRequest()
                .handle(request, new PermissionInvocation()).join();

        assertThat(CopilotPermissionPolicies.isApproval(result)).isFalse();
    }

    @Test
    void permissionRoutesDecideRequests() throws Exception {
        start(new RouteBuilder() {
            @Override
            public void configure() {
                from("copilot:permission:reviewer")
                        .setBody(simple("${header.CopilotPermissionKind} == 'read'", Boolean.class));
                from("direct:ask").to("copilot:agent:support?permissionHandlerRef=reviewer");
            }
        });
        runtime.agent("support").replies("ok");
        template.requestBodyAndHeader("direct:ask", "hi", CopilotConstants.CONVERSATION_ID, "c-1");
        var handler = runtime.sessionConfig("support").getOnPermissionRequest();

        assertThat(handler).isSameAs(context.getRegistry().lookupByNameAndType("reviewer", PermissionHandler.class));
        assertThat(decide(handler, "read").getKind()).startsWith("approve");
        assertThat(decide(handler, "shell").getKind()).isEqualTo(PermissionRequestResult.reject("x").getKind());
    }

    @Test
    void failingPermissionRoutesReject() throws Exception {
        start(new RouteBuilder() {
            @Override
            public void configure() {
                from("copilot:permission:reviewer").throwException(new IllegalStateException("boom"));
            }
        });
        var handler = context.getRegistry().lookupByNameAndType("reviewer", PermissionHandler.class);

        assertThat(CopilotPermissionPolicies.isApproval(decide(handler, "read"))).isFalse();
    }

    private static PermissionRequestResult decide(PermissionHandler handler, String kind) {
        var request = new PermissionRequest();
        request.setKind(kind);
        return handler.handle(request, new PermissionInvocation().setSessionId("s-1")).join();
    }

    @Test
    void eventRoutesReceiveFilteredSessionEvents() throws Exception {
        start(new RouteBuilder() {
            @Override
            public void configure() {
                from("copilot:events:audit?types=assistant.message").to("mock:events");
                from("direct:ask").to("copilot:agent:support?listenerRefs=audit");
            }
        });
        MockEndpoint events = context.getEndpoint("mock:events", MockEndpoint.class);
        events.expectedMinimumMessageCount(1);
        runtime.agent("support").replies("hello");

        template.requestBodyAndHeader("direct:ask", "hi", CopilotConstants.CONVERSATION_ID, "c-1");

        events.assertIsSatisfied(TimeUnit.SECONDS.toMillis(5));
        var received = events.getReceivedExchanges().get(0).getMessage();
        assertThat(received.getHeader(CopilotConstants.EVENT_TYPE)).isEqualTo("assistant.message");
        assertThat(received.getHeader(CopilotConstants.AGENT_NAME)).isEqualTo("support");
        assertThat(received.getHeader(CopilotConstants.CONVERSATION_ID)).isEqualTo("c-1");
        assertThat(events.getReceivedExchanges()).allSatisfy(exchange -> assertThat(
                exchange.getMessage().getHeader(CopilotConstants.EVENT_TYPE)).isEqualTo("assistant.message"));
    }

    @Test
    void declarativeAgentsAreRoutableAndBoundInTheRegistry() throws Exception {
        var component = new CopilotComponent();
        component.setAgentTypes(Advisor.class.getName());
        context.addComponent("copilot", component);
        start(new RouteBuilder() {
            @Override
            public void configure() {
                from("direct:ask").to("copilot:agent:advisor");
            }
        });
        runtime.agent("advisor").replies("Use split/aggregate.").replies("Use the bean.");

        Object routed = template.requestBodyAndHeader("direct:ask", "How do I batch?",
                CopilotConstants.CONVERSATION_ID, "c-9");
        var advisor = context.getRegistry().lookupByNameAndType("advisor", Advisor.class);

        assertThat(routed).isEqualTo("Use split/aggregate.");
        assertThat(advisor.ask("c-9", "And from Java?")).isEqualTo("Use the bean.");
        assertThat(runtime.sessions("advisor")).hasSize(1);
        assertThat(runtime.sessionConfig("advisor").getSystemMessage().getContent())
                .isEqualTo("You advise on Camel routes.");
        assertThat(context.getRegistry().lookupByNameAndType("copilotLoops", CopilotLoops.class)).isNotNull();
    }

    @Test
    void sessionStoreListsMapsAndDeletesAgentSessions() throws Exception {
        var component = new CopilotComponent();
        component.setAgentTypes(Advisor.class.getName());
        context.addComponent("copilot", component);
        start(new RouteBuilder() {
            @Override
            public void configure() {
                from("direct:ask").to("copilot:agent:advisor");
            }
        });
        runtime.agent("advisor").replies("Use split/aggregate.");
        template.requestBodyAndHeader("direct:ask", "How do I batch?", CopilotConstants.CONVERSATION_ID, "c-9");

        CopilotSessionStore store = component.sessionStore();
        String sessionId = store.sessionId(Advisor.class, "c-9");

        assertThat(context.getRegistry().lookupByNameAndType("copilotSessionStore", CopilotSessionStore.class))
                .isSameAs(store);
        assertThat(sessionId).isEqualTo(CopilotAgentInvoker.sessionId(component.factory(Advisor.class).definition(), "c-9"))
                .startsWith(CopilotAgentInvoker.MANAGED_SESSION_PREFIX);
        assertThat(store.list()).extracting(session -> session.getSessionId()).containsExactly(sessionId);
        assertThat(store.delete(sessionId)).isTrue();
        assertThat(store.exists(sessionId)).isFalse();
        assertThatThrownBy(() -> store.sessionId(String.class, "c-9")).isInstanceOf(IllegalArgumentException.class);
        assertThat(component.activeCleanupInterval()).as("cleanup is disabled by default").isNull();
    }

    @Test
    void sessionCleanupRunsWhenAMaxAgeIsSet() throws Exception {
        var component = new CopilotComponent();
        PropertyBindingSupport.build().bind(context, component, new java.util.HashMap<>(Map.of("sessionMaxAge", "1h")));
        context.addComponent("copilot", component);
        start(new RouteBuilder() {
            @Override
            public void configure() {
                from("direct:ask").to("copilot:agent:support");
            }
        });

        assertThat(component.getSessionMaxAge()).isEqualTo(Duration.ofHours(1));
        assertThat(component.activeCleanupInterval()).isEqualTo(Duration.ofMinutes(30));

        context.stop();
        assertThat(component.activeCleanupInterval()).isNull();
        assertThatThrownBy(component::sessionStore).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void sessionCleanupUsesTheConfiguredInterval() throws Exception {
        var component = new CopilotComponent();
        component.setSessionMaxAge(Duration.ofDays(7));
        component.setSessionCleanupInterval(Duration.ofMinutes(5));
        context.addComponent("copilot", component);
        start(new RouteBuilder() {
            @Override
            public void configure() {
                from("direct:ask").to("copilot:agent:support");
            }
        });

        assertThat(component.activeCleanupInterval()).isEqualTo(Duration.ofMinutes(5));
        assertThatThrownBy(() -> component.setSessionMaxAge(Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void declarativeAgentsRejectEndpointConfiguration() throws Exception {
        var component = new CopilotComponent();
        component.setAgentTypes(Advisor.class.getName());
        context.addComponent("copilot", component);
        start(new RouteBuilder() {
            @Override
            public void configure() {
                from("direct:ask").to("copilot:agent:advisor?model=gpt-5");
            }
        });

        assertThatThrownBy(() -> template.requestBody("direct:ask", "hi"))
                .isInstanceOf(CamelExecutionException.class)
                .hasRootCauseMessage("copilot:agent:advisor refers to @CopilotAgent " + Advisor.class.getName()
                        + "; configure it on the interface, not on the endpoint");
    }

    @Test
    void toolRoutesRequireADescription() {
        assertThatThrownBy(() -> start(new RouteBuilder() {
            @Override
            public void configure() {
                from("copilot:tool:lookupOrder").log("unused");
            }
        })).hasStackTraceContaining("requires a description");
    }

    @Test
    void agentFailuresFailTheExchange() throws Exception {
        start(new RouteBuilder() {
            @Override
            public void configure() {
                from("direct:ask").to("copilot:agent:support");
            }
        });
        runtime.agent("support").fails(new IllegalStateException("model unavailable"));

        Exchange reply = template.request("direct:ask", exchange -> exchange.getMessage().setBody("hi"));

        assertThat(reply.getException()).hasStackTraceContaining("model unavailable");
        assertThat(List.of(runtime.prompts("support"))).hasSize(1);
    }
}
