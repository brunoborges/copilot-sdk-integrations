package io.github.brunoborges.copilot.agent.internal;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import io.github.brunoborges.copilot.agent.ConversationId;
import io.github.brunoborges.copilot.agent.CopilotAgent;
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

    /** Returns the resolved definition, or {@code null} before initialization. */
    public CopilotAgentDefinition definition() {
        return definition;
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
            String message = (String) args[binding.message()];
            if (lifetime != null) {
                return binding.adapter() == null ? invoker.invokeBound(definition, conversationId, message, null, lifetime)
                        : binding.adapter().adapt(events -> invoker.invokeBound(definition, conversationId, message, events, lifetime));
            }
            return binding.adapter() == null ? invoker.invoke(definition, conversationId, message)
                    : binding.adapter().adapt(events -> invoker.invoke(definition, conversationId, message, events));
        });
    }

    private static Binding binding(Method method, List<CopilotAgentReturnTypeAdapter> adapters) {
        String message = "Copilot agent method must return CompletableFuture<String> or a registered adapter type and declare "
                + "one @UserMessage String, optionally preceded or followed by one @ConversationId String: " + method.toGenericString();
        boolean text = method.getGenericReturnType() instanceof ParameterizedType result
                        && result.getRawType() == CompletableFuture.class
                        && result.getActualTypeArguments()[0] == String.class;
        var matching = adapters.stream().filter(adapter -> adapter.supports(method.getGenericReturnType())).toList();
        Assert.isTrue(matching.size() <= 1, "Multiple Copilot return-type adapters match " + method.toGenericString());
        Assert.isTrue(text || matching.size() == 1, message);
        Assert.isTrue(method.getParameterCount() == 1 || method.getParameterCount() == 2, message);
        int conversation = -1;
        int userMessage = -1;
        for (int index = 0; index < method.getParameterCount(); index++) {
            var parameter = method.getParameters()[index];
            Assert.isTrue(parameter.getType() == String.class, message);
            boolean id = parameter.isAnnotationPresent(ConversationId.class);
            boolean prompt = parameter.isAnnotationPresent(UserMessage.class);
            Assert.isTrue(id != prompt, message);
            if (id) {
                Assert.isTrue(conversation == -1, message);
                conversation = index;
            } else {
                Assert.isTrue(userMessage == -1, message);
                userMessage = index;
            }
        }
        Assert.isTrue(userMessage != -1 && (method.getParameterCount() == 1 || conversation != -1), message);
        return new Binding(conversation, userMessage, matching.isEmpty() ? null : matching.getFirst());
    }

    @Override
    public Object getObject() {
        return proxy;
    }

    @Override
    public Class<?> getObjectType() {
        return bound ? CopilotAgentDefinition.class : type;
    }

    private record Binding(int conversation, int message, CopilotAgentReturnTypeAdapter adapter) {
    }
}
