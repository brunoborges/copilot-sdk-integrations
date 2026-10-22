package io.github.brunoborges.copilot.agent.spring;

import io.github.brunoborges.copilot.agent.*;

import java.io.Closeable;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.github.copilot.CopilotClient;
import com.github.copilot.CopilotSession;
import com.github.copilot.generated.AssistantMessageEvent;
import com.github.copilot.rpc.MessageOptions;
import com.github.copilot.rpc.SessionConfig;
import com.github.copilot.rpc.SessionMetadata;
import io.github.brunoborges.copilot.autoconfigure.internal.CopilotAgentFactoryBean;
import io.github.brunoborges.copilot.agent.internal.CopilotAgentInvoker;
import io.github.brunoborges.copilot.autoconfigure.CopilotAgentsProperties;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.DefaultResourceLoader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class MethodContractTests {

    private final CopilotClient client = mock(CopilotClient.class);
    private final DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
    private final StandardEnvironment environment = new StandardEnvironment();
    private final CopilotAgentsProperties properties = new CopilotAgentsProperties(Map.of());

    MethodContractTests() {
        environment.getPropertySources().addFirst(new MapPropertySource("test", Map.of("prompt.tone", "friendly")));
    }

    @Test
    void inlineAndResourceTemplatesRenderNamedParameters() throws Exception {
        var session = connect("reply");
        try (var invoker = new CopilotAgentInvoker(client)) {
            var agent = agent(TemplateAgent.class, invoker, properties);
            assertThat(agent.summarize("c1", "the {{doc}} report", "executives").get(5, TimeUnit.SECONDS)).isEqualTo("reply");
            assertThat(agent.fromResource("RFC 42", "c1", 7).get(5, TimeUnit.SECONDS)).isEqualTo("reply");
            assertThat(agent.fixed("c1").get(5, TimeUnit.SECONDS)).isEqualTo("reply");
        }
        var prompts = prompts(session, 3);
        assertThat(prompts.get(0).getPrompt()).isEqualTo("Be friendly. Summarize the {{doc}} report for executives {{unknown-name}}");
        assertThat(prompts.get(0).getResponseSchema()).isNull();
        assertThat(prompts.get(1).getPrompt()).isEqualTo("Summarize RFC 42 for 7.\n");
        assertThat(prompts.get(2).getPrompt()).isEqualTo("Tell me a joke.");
    }

    @Test
    void nullTemplateArgumentsFailWithoutSdkWork() {
        try (var invoker = new CopilotAgentInvoker(client)) {
            var agent = agent(TemplateAgent.class, invoker, properties);
            assertThatThrownBy(() -> agent.summarize("c1", null, "everyone").get(5, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class).hasRootCauseInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("'doc'");
            assertThatThrownBy(() -> agent.sync("c1", null)).isInstanceOf(IllegalArgumentException.class);
        }
        verifyNoInteractions(client);
    }

    @Test
    void recordResultsUseStrictObjectSchemaAndAreDeserialized() throws Exception {
        var session = connect("{\"label\":\"POSITIVE\",\"score\":0.9,\"tags\":[\"a\",\"b\"],\"extra\":1}");
        try (var invoker = new CopilotAgentInvoker(client)) {
            var agent = agent(TypedAgent.class, invoker, properties);
            assertThat(agent.classify("c1", "great").get(5, TimeUnit.SECONDS))
                    .isEqualTo(new Verdict(Label.POSITIVE, 0.9, List.of("a", "b")));
        }
        var schema = prompts(session, 1).getFirst().getResponseSchema();
        assertThat(schema).containsEntry("type", "object").containsEntry("additionalProperties", false)
                .containsEntry("required", List.of("label", "score", "tags"));
        @SuppressWarnings("unchecked")
        var fields = (Map<String, Object>) schema.get("properties");
        assertThat(fields.get("label")).isEqualTo(Map.of("type", "string", "enum", List.of("POSITIVE", "NEGATIVE")));
        assertThat(fields.get("score")).isEqualTo(Map.of("type", "number"));
        assertThat(fields.get("tags")).isEqualTo(Map.of("type", "array", "items", Map.of("type", "string")));
    }

    @Test
    @SuppressWarnings("unchecked")
    void javaBeanResultsHonorJacksonNaming() throws Exception {
        var session = connect("{\"full_name\":\"Ada\",\"age\":36}");
        try (var invoker = new CopilotAgentInvoker(client)) {
            var person = agent(TypedAgent.class, invoker, properties).person("c1", "who").get(5, TimeUnit.SECONDS);
            assertThat(person.name).isEqualTo("Ada");
            assertThat(person.age).isEqualTo(36);
        }
        assertThat((List<Object>) prompts(session, 1).getFirst().getResponseSchema().get("required"))
                .containsExactlyInAnyOrder("full_name", "age");
    }

    @Test
    void nonObjectResultsAreWrappedAndUnwrapped() throws Exception {
        var session = connect("{\"value\":[{\"label\":\"NEGATIVE\",\"score\":0.1,\"tags\":[]}]}");
        try (var invoker = new CopilotAgentInvoker(client)) {
            var agent = agent(TypedAgent.class, invoker, properties);
            assertThat(agent.batch("c1", "meh").get(5, TimeUnit.SECONDS))
                    .containsExactly(new Verdict(Label.NEGATIVE, 0.1, List.of()));
        }
        var schema = prompts(session, 1).getFirst().getResponseSchema();
        assertThat(schema).containsEntry("required", List.of("value"));
        @SuppressWarnings("unchecked")
        var value = (Map<String, Object>) ((Map<String, Object>) schema.get("properties")).get("value");
        assertThat(value).containsEntry("type", "array");
    }

    @Test
    void synchronousMethodsReturnTextAndScalars() throws Exception {
        when(client.getSessionMetadata(anyString())).thenReturn(CompletableFuture.completedFuture(null));
        var session = session(null);
        when(session.sendAndWait(any(), anyLong())).thenAnswer(invocation -> {
            var options = invocation.<MessageOptions>getArgument(0);
            String content = options.getResponseSchema() == null ? "plain"
                    : options.getPrompt().startsWith("Is") ? "{\"value\":true}" : "{\"value\":42}";
            return CompletableFuture.completedFuture(reply(content));
        });
        stubCreate(session);
        try (var invoker = new CopilotAgentInvoker(client)) {
            var agent = agent(TypedAgent.class, invoker, properties);
            assertThat(agent.text("c1", "hi")).isEqualTo("plain");
            assertThat(agent.spam("c1", "buy now")).isTrue();
            assertThat(agent.count("c1", "x")).isEqualTo(42);
        }
    }

    @Test
    void structuredParseFailuresFailTheInvocation() {
        connect("not json");
        try (var invoker = new CopilotAgentInvoker(client)) {
            var agent = agent(TypedAgent.class, invoker, properties);
            assertThatThrownBy(() -> agent.classify("c1", "x").get(5, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class).cause().isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Verdict");
            assertThatThrownBy(() -> agent.spam("c1", "x")).isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void jsonNullIsNotAResult() {
        connect("{\"value\":null}");
        try (var invoker = new CopilotAgentInvoker(client)) {
            var agent = agent(TypedAgent.class, invoker, properties);
            assertThatThrownBy(() -> agent.spam("c1", "x")).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("null");
        }
    }

    @Test
    void synchronousCallsTimeOutAfterTheResponseTimeout() {
        when(client.getSessionMetadata(anyString())).thenReturn(CompletableFuture.completedFuture(null));
        var session = session(null);
        when(session.sendAndWait(any(), anyLong())).thenAnswer(invocation -> CompletableFuture.supplyAsync(
                () -> reply("late"), CompletableFuture.delayedExecutor(1, TimeUnit.SECONDS)));
        stubCreate(session);
        var settings = new CopilotAgentsProperties(Map.of("typedAgent",
                new CopilotAgentsProperties.Agent(null, null, Duration.ofMillis(100))));
        try (var invoker = new CopilotAgentInvoker(client)) {
            var agent = agent(TypedAgent.class, invoker, settings);
            assertThatThrownBy(() -> agent.text("c1", "hi")).isInstanceOf(CompletionException.class)
                    .hasCauseInstanceOf(TimeoutException.class);
            assertThatThrownBy(() -> agent.declared("c1", "hi")).isInstanceOf(TimeoutException.class);
        }
    }

    @Test
    void boundAgentsSupportTemplatesAndStructuredResults() throws Exception {
        var session = connect("{\"value\":\"NEGATIVE\"}");
        when(client.getSessionMetadata(anyString())).thenReturn(CompletableFuture.completedFuture(null))
                .thenReturn(CompletableFuture.completedFuture(new SessionMetadata()));
        when(client.resumeSession(anyString(), any())).thenReturn(CompletableFuture.completedFuture(session));
        try (var invoker = new CopilotAgentInvoker(client)) {
            var factory = factory(BoundTypedAgent.class, invoker, properties);
            var instance = factory.bind(BoundTypedAgent.class, "c1", false);
            assertThat(instance.agent().label("awful").get(5, TimeUnit.SECONDS)).isEqualTo(Label.NEGATIVE);
            assertThat(instance.agent().labelNow("awful")).isEqualTo(Label.NEGATIVE);
            instance.close();
        }
        assertThat(prompts(session, 2).getFirst().getPrompt()).isEqualTo("Classify: awful");
    }

    @Test
    void invalidContractsFailAtInitialization() {
        try (var invoker = new CopilotAgentInvoker(client)) {
            Map<Class<?>, String> invalid = Map.ofEntries(
                    Map.entry(UnknownPlaceholder.class, "{{missing}}"),
                    Map.entry(UnusedParameter.class, "[extra]"),
                    Map.entry(MixedPromptForms.class, "cannot be combined"),
                    Map.entry(VariableWithoutTemplate.class, "require a method-level"),
                    Map.entry(ValueAndResource.class, "exactly one of value or resource"),
                    Map.entry(BlankTemplate.class, "must not be blank"),
                    Map.entry(MissingTemplateResource.class, "Cannot read"),
                    Map.entry(DuplicateVariable.class, "Duplicate"),
                    Map.entry(ParameterTemplate.class, "only valid on methods"),
                    Map.entry(UnannotatedParameter.class, "must return"),
                    Map.entry(MapResult.class, "maps"),
                    Map.entry(OptionalResult.class, "Unsupported"),
                    Map.entry(ObjectResult.class, "Unsupported"),
                    Map.entry(RecursiveResult.class, "recursive"),
                    Map.entry(FutureResult.class, "must return"),
                    Map.entry(UnresolvedProperty.class, "missing.property"));
            invalid.forEach((type, message) -> assertThatThrownBy(() -> agent(type, invoker, properties))
                    .as(type.getSimpleName()).isInstanceOf(IllegalArgumentException.class).hasMessageContaining(message));
        }
        verifyNoInteractions(client);
    }

    private <T> T agent(Class<T> type, CopilotAgentInvoker invoker, CopilotAgentsProperties settings) {
        return type.cast(factory(type, invoker, settings).getObject());
    }

    private CopilotAgentFactoryBean factory(Class<?> type, CopilotAgentInvoker invoker, CopilotAgentsProperties settings) {
        var factory = new CopilotAgentFactoryBean(type, invoker, beans, environment, new DefaultResourceLoader(), settings);
        factory.afterPropertiesSet();
        return factory;
    }

    private CopilotSession connect(String text) {
        when(client.getSessionMetadata(anyString())).thenReturn(CompletableFuture.completedFuture(null));
        var session = session(text);
        stubCreate(session);
        return session;
    }

    private void stubCreate(CopilotSession session) {
        when(client.createSession(any())).thenAnswer(invocation -> {
            when(session.getSessionId()).thenReturn(invocation.<SessionConfig>getArgument(0).getSessionId());
            return CompletableFuture.completedFuture(session);
        });
    }

    private static List<MessageOptions> prompts(CopilotSession session, int count) {
        ArgumentCaptor<MessageOptions> options = ArgumentCaptor.captor();
        verify(session, org.mockito.Mockito.times(count)).sendAndWait(options.capture(), anyLong());
        return options.getAllValues();
    }

    private static CopilotSession session(String text) {
        var session = mock(CopilotSession.class);
        when(session.on(any())).thenReturn(mock(Closeable.class));
        if (text != null) {
            when(session.sendAndWait(any(), anyLong())).thenAnswer(invocation -> CompletableFuture.completedFuture(reply(text)));
        }
        return session;
    }

    private static AssistantMessageEvent reply(String content) {
        var event = new AssistantMessageEvent();
        var data = mock(AssistantMessageEvent.AssistantMessageEventData.class);
        when(data.content()).thenReturn(content);
        event.setData(data);
        return event;
    }

    public enum Label { POSITIVE, NEGATIVE }

    public record Verdict(Label label, double score, List<String> tags) {
    }

    public static class Person {
        @JsonProperty("full_name")
        public String name;
        public int age;
    }

    public record Node(String name, List<Node> children) {
    }

    @CopilotAgent
    public interface TemplateAgent {
        @UserMessage("Be ${prompt.tone}. Summarize {{doc}} for {{ audience }} {{unknown-name}}")
        CompletableFuture<String> summarize(@ConversationId String id, @P("doc") String document, @P String audience);

        @UserMessage(resource = "classpath:prompts/summary.txt")
        CompletableFuture<String> fromResource(@P("doc") String doc, @ConversationId String id, @P("audience") int audience);

        @UserMessage("Tell me a joke.")
        CompletableFuture<String> fixed(@ConversationId String id);

        @UserMessage("Echo {{text}}")
        String sync(@ConversationId String id, @P("text") String text);
    }

    @CopilotAgent
    public interface TypedAgent {
        CompletableFuture<Verdict> classify(@ConversationId String id, @UserMessage String text);

        CompletableFuture<List<Verdict>> batch(@ConversationId String id, @UserMessage String text);

        String text(@ConversationId String id, @UserMessage String text);

        String declared(@ConversationId String id, @UserMessage String text) throws TimeoutException;

        @UserMessage("Is this spam? {{text}}")
        boolean spam(@ConversationId String id, @P("text") String text);

        int count(@ConversationId String id, @UserMessage String text);

        CompletableFuture<Person> person(@ConversationId String id, @UserMessage String text);
    }

    @CopilotAgent
    public interface BoundTypedAgent {
        @UserMessage("Classify: {{text}}")
        CompletableFuture<Label> label(@P("text") String text);

        @UserMessage("Classify: {{text}}")
        Label labelNow(@P("text") String text);
    }

    @CopilotAgent
    public interface UnknownPlaceholder {
        @UserMessage("Hello {{missing}}")
        CompletableFuture<String> answer(@ConversationId String id);
    }

    @CopilotAgent
    public interface UnusedParameter {
        @UserMessage("Hello")
        CompletableFuture<String> answer(@ConversationId String id, @P("extra") String extra);
    }

    @CopilotAgent
    public interface MixedPromptForms {
        @UserMessage("Hello")
        CompletableFuture<String> answer(@ConversationId String id, @UserMessage String message);
    }

    @CopilotAgent
    public interface VariableWithoutTemplate {
        CompletableFuture<String> answer(@ConversationId String id, @P("x") String x);
    }

    @CopilotAgent
    public interface ValueAndResource {
        @UserMessage(value = "Hello", resource = "classpath:prompts/summary.txt")
        CompletableFuture<String> answer(@ConversationId String id);
    }

    @CopilotAgent
    public interface BlankTemplate {
        @UserMessage(value = "   ")
        CompletableFuture<String> answer(@ConversationId String id);
    }

    @CopilotAgent
    public interface MissingTemplateResource {
        @UserMessage(resource = "classpath:prompts/absent.txt")
        CompletableFuture<String> answer(@ConversationId String id);
    }

    @CopilotAgent
    public interface DuplicateVariable {
        @UserMessage("{{x}}")
        CompletableFuture<String> answer(@ConversationId String id, @P("x") String a, @P("x") String b);
    }

    @CopilotAgent
    public interface ParameterTemplate {
        CompletableFuture<String> answer(@ConversationId String id, @UserMessage("Hello") String message);
    }

    @CopilotAgent
    public interface UnannotatedParameter {
        @UserMessage("Hello")
        CompletableFuture<String> answer(@ConversationId String id, String extra);
    }

    @CopilotAgent
    public interface MapResult {
        CompletableFuture<Map<String, String>> answer(@ConversationId String id, @UserMessage String message);
    }

    @CopilotAgent
    public interface OptionalResult {
        Optional<String> answer(@ConversationId String id, @UserMessage String message);
    }

    @CopilotAgent
    public interface ObjectResult {
        CompletableFuture<Object> answer(@ConversationId String id, @UserMessage String message);
    }

    @CopilotAgent
    public interface RecursiveResult {
        CompletableFuture<Node> answer(@ConversationId String id, @UserMessage String message);
    }

    @CopilotAgent
    public interface FutureResult {
        Future<String> answer(@ConversationId String id, @UserMessage String message);
    }

    @CopilotAgent
    public interface UnresolvedProperty {
        @UserMessage("${missing.property}")
        CompletableFuture<String> answer(@ConversationId String id);
    }
}
