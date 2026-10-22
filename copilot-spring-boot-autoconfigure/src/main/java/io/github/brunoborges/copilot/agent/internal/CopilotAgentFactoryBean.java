package io.github.brunoborges.copilot.agent.internal;

import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Proxy;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import io.github.brunoborges.copilot.agent.ConversationId;
import io.github.brunoborges.copilot.agent.CopilotAgent;
import io.github.brunoborges.copilot.agent.P;
import io.github.brunoborges.copilot.agent.UserMessage;
import io.github.brunoborges.copilot.agent.CopilotAgentReturnTypeAdapter;
import io.github.brunoborges.copilot.agent.CopilotAgentInstance;
import io.github.brunoborges.copilot.autoconfigure.CopilotAgentsProperties;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ResourceLoader;
import org.springframework.util.Assert;

public final class CopilotAgentFactoryBean implements FactoryBean<Object>, InitializingBean {

    private final Class<?> type;
    private final CopilotAgentInvoker invoker;
    private final BeanFactory beans;
    private final Environment environment;
    private final ResourceLoader resources;
    private final CopilotAgentsProperties properties;
    private final boolean bound;
    private Map<Method, Binding> bindings;
    private CopilotAgentDefinition definition;
    private Object proxy;

    public CopilotAgentFactoryBean(Class<?> type, CopilotAgentInvoker invoker, BeanFactory beans,
            Environment environment, ResourceLoader resources, CopilotAgentsProperties properties) {
        this.type = type;
        this.invoker = invoker;
        this.beans = beans;
        this.environment = environment;
        this.resources = resources;
        this.properties = properties;
        bound = requiresBinding(type);
    }

    @Override
    public void afterPropertiesSet() {
        Assert.isTrue(type.isInterface() && type.isAnnotationPresent(CopilotAgent.class),
                "@CopilotAgent must annotate an interface: " + type.getName());
        Map<Method, Binding> methods = new HashMap<>();
        var adapters = beans.getBeanProvider(CopilotAgentReturnTypeAdapter.class).orderedStream().toList();
        for (var method : type.getMethods()) {
            if (Modifier.isAbstract(method.getModifiers())) {
                methods.put(method, binding(method, adapters));
            }
        }
        Assert.isTrue(!methods.isEmpty(), "Copilot agent must declare an invocation method: " + type.getName());
        Assert.isTrue(methods.values().stream().allMatch(binding -> (binding.conversation() == -1) == bound),
                "Copilot agent must not mix explicit-ID and ID-less invocation methods: " + type.getName());
        bindings = Map.copyOf(methods);
        definition = CopilotAgentDefinition.create(type, beans, environment, resources, properties);
        proxy = bound ? definition : proxy(null, null);
    }

    public static boolean requiresBinding(Class<?> type) {
        return Arrays.stream(type.getMethods()).filter(method -> Modifier.isAbstract(method.getModifiers()))
                .anyMatch(method -> Arrays.stream(method.getParameters())
                        .noneMatch(parameter -> parameter.isAnnotationPresent(ConversationId.class)));
    }

    public Class<?> agentType() {
        return type;
    }

    public boolean boundDefinition() {
        return bound;
    }

    public <T> CopilotAgentInstance<T> bind(Class<T> requestedType, String conversationId, boolean resume) {
        Assert.isTrue(requestedType == type && bound, "Cannot bind this Copilot agent definition");
        var lifetime = new CopilotAgentLifetime(resume);
        return new DefaultCopilotAgentInstance<>(requestedType.cast(proxy(conversationId, lifetime)), conversationId, lifetime);
    }

    private Object proxy(String boundId, CopilotAgentLifetime lifetime) {
        return Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (instance, method, args) -> {
            if (method.getDeclaringClass() == Object.class) {
                return switch (method.getName()) {
                    case "toString" -> "CopilotAgent[" + definition.name() + "]";
                    case "hashCode" -> System.identityHashCode(instance);
                    case "equals" -> instance == args[0];
                    default -> throw new IllegalStateException("Unexpected Object method");
                };
            }
            if (method.isDefault()) {
                return InvocationHandler.invokeDefault(instance, method, args);
            }
            var binding = bindings.get(method);
            String conversationId = boundId == null ? (String) args[binding.conversation()] : boundId;
            String message;
            try {
                message = binding.prompt().render(args);
            } catch (IllegalArgumentException ex) {
                if (binding.sync()) {
                    throw ex;
                }
                return binding.adapter() == null ? CompletableFuture.failedFuture(ex)
                        : binding.adapter().adapt(events -> CompletableFuture.failedFuture(ex));
            }
            if (binding.adapter() != null) {
                return lifetime != null
                        ? binding.adapter().adapt(events -> invoker.invokeBound(definition, conversationId, message, events, lifetime))
                        : binding.adapter().adapt(events -> invoker.invoke(definition, conversationId, message, events));
            }
            CompletableFuture<?> result;
            if (binding.structured() != null) {
                result = binding.structured().convert(invoker.invokeStructured(definition, conversationId, message,
                        binding.structured().schema(), lifetime));
            } else {
                result = lifetime != null ? invoker.invokeBound(definition, conversationId, message, null, lifetime)
                        : invoker.invoke(definition, conversationId, message);
            }
            return binding.sync() ? await(result, definition.responseTimeout(), method) : result;
        });
    }

    /**
     * Blocks for a synchronous agent method. Unchecked failures propagate as-is; checked failures (including the
     * {@link TimeoutException} raised once the response timeout elapses) are thrown when the method declares them
     * and otherwise wrapped in a {@link CompletionException}.
     */
    private static Object await(CompletableFuture<?> result, Duration timeout, Method method) throws Throwable {
        Throwable failure;
        try {
            return result.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (ExecutionException ex) {
            failure = ex.getCause();
        } catch (TimeoutException ex) {
            result.cancel(true);
            failure = new TimeoutException("Copilot agent did not respond within " + timeout);
        } catch (InterruptedException ex) {
            result.cancel(true);
            Thread.currentThread().interrupt();
            failure = ex;
        }
        if (failure instanceof RuntimeException || failure instanceof Error) {
            throw failure;
        }
        for (var declared : method.getExceptionTypes()) {
            if (declared.isInstance(failure)) {
                throw failure;
            }
        }
        throw new CompletionException(failure);
    }

    private Binding binding(Method method, List<CopilotAgentReturnTypeAdapter> adapters) {
        String signature = method.toGenericString();
        String message = "Copilot agent method must return CompletableFuture<T>, T, or a registered adapter type, and declare "
                + "either one @UserMessage String parameter or a method-level @UserMessage template with @P parameters, "
                + "optionally with one @ConversationId String: " + signature;
        Type returned = method.getGenericReturnType();
        var matching = adapters.stream().filter(adapter -> adapter.supports(returned)).toList();
        Assert.isTrue(matching.size() <= 1, "Multiple Copilot return-type adapters match " + signature);
        var adapter = matching.isEmpty() ? null : matching.getFirst();
        StructuredResponse structured = null;
        boolean sync = false;
        if (adapter == null) {
            Type value;
            if (returned instanceof ParameterizedType future && future.getRawType() == CompletableFuture.class) {
                value = future.getActualTypeArguments()[0];
            } else {
                Class<?> raw = method.getReturnType();
                Assert.isTrue(raw != void.class && raw != Void.class && !Future.class.isAssignableFrom(raw)
                        && !CompletionStage.class.isAssignableFrom(raw), message);
                value = returned;
                sync = true;
            }
            if (value != String.class) {
                try {
                    structured = StructuredResponse.of(value);
                } catch (IllegalArgumentException ex) {
                    throw new IllegalArgumentException(ex.getMessage() + ": " + signature, ex);
                }
            }
        }
        int conversation = -1;
        int verbatim = -1;
        var named = new LinkedHashMap<String, Integer>();
        for (int index = 0; index < method.getParameterCount(); index++) {
            var parameter = method.getParameters()[index];
            boolean id = parameter.isAnnotationPresent(ConversationId.class);
            var prompt = parameter.getAnnotation(UserMessage.class);
            var variable = parameter.getAnnotation(P.class);
            Assert.isTrue((id ? 1 : 0) + (prompt != null ? 1 : 0) + (variable != null ? 1 : 0) == 1, message);
            if (id) {
                Assert.isTrue(parameter.getType() == String.class && conversation == -1, message);
                conversation = index;
            } else if (prompt != null) {
                Assert.isTrue(parameter.getType() == String.class && verbatim == -1, message);
                Assert.isTrue(prompt.value().isEmpty() && prompt.resource().isEmpty(),
                        "@UserMessage template attributes are only valid on methods: " + signature);
                verbatim = index;
            } else {
                String name = variable.value().isEmpty() && parameter.isNamePresent() ? parameter.getName() : variable.value();
                Assert.hasText(name, "@P requires a name unless compiled with -parameters: " + signature);
                Assert.isTrue(named.put(name, index) == null, "Duplicate @P(\"" + name + "\") parameters: " + signature);
            }
        }
        var template = method.getAnnotation(UserMessage.class);
        PromptTemplate prompt;
        if (template == null) {
            Assert.isTrue(named.isEmpty(), "@P parameters require a method-level @UserMessage template: " + signature);
            Assert.isTrue(verbatim != -1, message);
            prompt = PromptTemplate.verbatim(verbatim);
        } else {
            Assert.isTrue(verbatim == -1,
                    "A method-level @UserMessage template cannot be combined with a @UserMessage parameter: " + signature);
            prompt = PromptTemplate.compile(template(template, signature), named, signature);
        }
        return new Binding(conversation, prompt, adapter, structured, sync);
    }

    private String template(UserMessage annotation, String signature) {
        String inline = environment.resolveRequiredPlaceholders(annotation.value());
        String location = environment.resolveRequiredPlaceholders(annotation.resource());
        Assert.isTrue(inline.isEmpty() != location.isEmpty(),
                "Method-level @UserMessage requires exactly one of value or resource: " + signature);
        if (inline.isEmpty()) {
            try (var input = resources.getResource(location).getInputStream()) {
                inline = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException ex) {
                throw new IllegalArgumentException("Cannot read @UserMessage resource " + location + ": " + signature, ex);
            }
        }
        Assert.hasText(inline, "@UserMessage template must not be blank: " + signature);
        return inline;
    }

    @Override
    public Object getObject() {
        return proxy;
    }

    @Override
    public Class<?> getObjectType() {
        return bound ? CopilotAgentDefinition.class : type;
    }

    private record Binding(int conversation, PromptTemplate prompt, CopilotAgentReturnTypeAdapter adapter,
            StructuredResponse structured, boolean sync) {
    }
}
