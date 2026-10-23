package io.github.brunoborges.copilot.agent.spring;

import java.io.Closeable;
import java.time.Duration;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;

import com.github.copilot.CopilotClient;
import com.github.copilot.CopilotSession;
import com.github.copilot.generated.AssistantMessageEvent;
import com.github.copilot.rpc.MessageOptions;
import com.github.copilot.rpc.MessageSource;
import com.github.copilot.rpc.SessionConfig;
import com.github.copilot.rpc.ToolDefinition;
import io.github.brunoborges.copilot.agent.ConversationId;
import io.github.brunoborges.copilot.agent.CopilotAgent;
import io.github.brunoborges.copilot.agent.CopilotMessageSources;
import io.github.brunoborges.copilot.agent.UserMessage;
import io.github.brunoborges.copilot.agent.internal.CopilotAgentInvoker;
import io.github.brunoborges.copilot.autoconfigure.CopilotAgentsProperties;
import io.github.brunoborges.copilot.autoconfigure.internal.CopilotAgentFactoryBean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.DefaultResourceLoader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MessageSourceTests {

    private final CopilotClient client = mock(CopilotClient.class);
    private final DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
    private final StandardEnvironment environment = new StandardEnvironment();
    private final CopilotAgentsProperties properties = new CopilotAgentsProperties(Map.of());
    private final Queue<MessageOptions> sent = new ConcurrentLinkedQueue<>();

    @BeforeEach
    void runtime() {
        when(client.getSessionMetadata(anyString())).thenReturn(CompletableFuture.completedFuture(null));
        when(client.createSession(any())).thenAnswer(invocation -> {
            var config = invocation.<SessionConfig>getArgument(0);
            var session = mock(CopilotSession.class);
            when(session.getSessionId()).thenReturn(config.getSessionId());
            when(session.on(any())).thenReturn(mock(Closeable.class));
            when(session.sendAndWait(any(), anyLong())).thenAnswer(send -> {
                MessageOptions options = send.getArgument(0);
                sent.add(options);
                if (options.getPrompt().equals("delegate")) {
                    var tool = config.getTools().stream().filter(t -> t.name().equals("ask_helper")).findFirst()
                            .orElseThrow();
                    tool.handler().invoke(null).get(5, TimeUnit.SECONDS);
                }
                return CompletableFuture.completedFuture(reply("ok"));
            });
            return CompletableFuture.completedFuture(session);
        });
    }

    @Test
    void omittedByDefaultSoTheRuntimeTreatsPromptsAsUserInput() throws Exception {
        try (var invoker = new CopilotAgentInvoker(client)) {
            agent(PlainAgent.class, invoker, properties).answer("c", "hi").get(5, TimeUnit.SECONDS);
        }
        assertThat(sent).singleElement().extracting(MessageOptions::getSource).isNull();
    }

    @Test
    void annotationDeclaresTheDefaultAndPropertiesOverrideIt() throws Exception {
        assertThat(SpringTestSupport.definition(SystemAgent.class, beans, environment, new DefaultResourceLoader(),
                properties).messageSource()).isEqualTo(MessageSource.SYSTEM);
        var overridden = settings("systemAgent", "agent-reporting");
        var none = settings("systemAgent", "");
        try (var invoker = new CopilotAgentInvoker(client)) {
            agent(SystemAgent.class, invoker, properties).answer("a", "hi").get(5, TimeUnit.SECONDS);
            agent(SystemAgent.class, invoker, overridden).answer("b", "hi").get(5, TimeUnit.SECONDS);
            agent(SystemAgent.class, invoker, none).answer("c", "hi").get(5, TimeUnit.SECONDS);
        }
        assertThat(sent).extracting(MessageOptions::getSource)
                .containsExactly(MessageSource.SYSTEM, MessageSource.agent("reporting"), null);
    }

    @Test
    void callerScopeOverridesTheConfiguredDefault() throws Exception {
        try (var invoker = new CopilotAgentInvoker(client)) {
            var agent = agent(SystemAgent.class, invoker, properties);
            CopilotMessageSources.callAs(MessageSource.USER, () -> agent.answer("a", "hi")).get(5, TimeUnit.SECONDS);
            CopilotMessageSources.runAs(CopilotMessageSources.agent("incident-bot"),
                    () -> agent.answer("b", "hi").join());
            agent.answer("c", "hi").get(5, TimeUnit.SECONDS);
        }
        assertThat(sent).extracting(MessageOptions::getSource).containsExactly(MessageSource.USER,
                MessageSource.agent("incident-bot"), MessageSource.SYSTEM);
        assertThat(CopilotMessageSources.current()).isNull();
    }

    @Test
    void agentCalledFromAnotherAgentsToolIsLabeledWithTheCallingAgent() throws Exception {
        try (var invoker = new CopilotAgentInvoker(client)) {
            var helper = agent(SystemAgent.class, invoker, properties);
            beans.registerSingleton("askHelper", ToolDefinition.create("ask_helper", "Asks the helper",
                    Map.of("type", "object"), invocation -> helper.answer("nested", "help").thenApply(r -> r)));
            var delegator = agent(Delegator.class, invoker, properties);
            delegator.answer("outer", "delegate").get(5, TimeUnit.SECONDS);
            // An outer caller scope labels the outer prompt only; the delegated call keeps the calling agent.
            CopilotMessageSources.runAs(MessageSource.USER, () -> delegator.answer("outer2", "delegate").join());
        }
        assertThat(sent).extracting(MessageOptions::getPrompt, MessageOptions::getSource).containsExactly(
                tuple("delegate", null), tuple("help", MessageSource.agent("delegator")),
                tuple("delegate", MessageSource.USER), tuple("help", MessageSource.agent("delegator")));
    }

    @Test
    void invalidSourcesFailAtStartupOrScopeEntry() {
        assertThatThrownBy(() -> SpringTestSupport.definition(InvalidAgent.class, beans, environment,
                new DefaultResourceLoader(), properties)).hasMessageContaining("invalidAgent")
                .hasMessageContaining("message source");
        assertThatThrownBy(() -> settings("x", "admin")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> settings("x", "agent-")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CopilotMessageSources.runAs(MessageSource.agent("bad id\n"), () -> { }))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CopilotMessageSources.agent("../x")).isInstanceOf(IllegalArgumentException.class);
        assertThat(CopilotMessageSources.parse("agent-incident-bot")).isEqualTo(MessageSource.agent("incident-bot"));
        assertThat(CopilotMessageSources.parse("")).isNull();
    }

    private static CopilotAgentsProperties settings(String agent, String source) {
        return new CopilotAgentsProperties(Map.of(agent, new CopilotAgentsProperties.Agent(null, null,
                Duration.ofSeconds(60), null, null, null, null, null, null, null, null, null, source)));
    }

    private <T> T agent(Class<T> type, CopilotAgentInvoker invoker, CopilotAgentsProperties settings) {
        var factory = new CopilotAgentFactoryBean(type, invoker, beans, environment, new DefaultResourceLoader(), settings);
        factory.afterPropertiesSet();
        return type.cast(factory.getObject());
    }

    private static AssistantMessageEvent reply(String content) {
        var event = new AssistantMessageEvent();
        var data = mock(AssistantMessageEvent.AssistantMessageEventData.class);
        when(data.content()).thenReturn(content);
        event.setData(data);
        return event;
    }

    @CopilotAgent
    public interface PlainAgent {
        CompletableFuture<String> answer(@ConversationId String id, @UserMessage String message);
    }

    @CopilotAgent(messageSource = "system")
    public interface SystemAgent {
        CompletableFuture<String> answer(@ConversationId String id, @UserMessage String message);
    }

    @CopilotAgent(name = "delegator", toolRefs = "askHelper")
    public interface Delegator {
        CompletableFuture<String> answer(@ConversationId String id, @UserMessage String message);
    }

    @CopilotAgent(messageSource = "admin")
    public interface InvalidAgent {
        CompletableFuture<String> answer(@ConversationId String id, @UserMessage String message);
    }
}
