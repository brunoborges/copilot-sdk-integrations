package io.github.brunoborges.copilot.agent.internal;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import io.github.brunoborges.copilot.agent.ConversationId;
import io.github.brunoborges.copilot.agent.CopilotAgent;
import io.github.brunoborges.copilot.agent.UserMessage;
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
    private Object proxy;

    public CopilotAgentFactoryBean(Class<?> type, CopilotAgentInvoker invoker, BeanFactory beans,
            Environment environment, ResourceLoader resources, CopilotAgentsProperties properties) {
        this.type = type;
        this.invoker = invoker;
        this.beans = beans;
        this.environment = environment;
        this.resources = resources;
        this.properties = properties;
    }

    @Override
    public void afterPropertiesSet() {
        Assert.isTrue(type.isInterface() && type.isAnnotationPresent(CopilotAgent.class),
                "@CopilotAgent must annotate an interface: " + type.getName());
        Map<Method, Binding> bindings = new HashMap<>();
        for (var method : type.getMethods()) {
            if (Modifier.isAbstract(method.getModifiers())) {
                bindings.put(method, binding(method));
            }
        }
        Assert.isTrue(!bindings.isEmpty(), "Copilot agent must declare an invocation method: " + type.getName());
        var definition = CopilotAgentDefinition.create(type, beans, environment, resources, properties);
        proxy = Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (instance, method, args) -> {
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
            return invoker.invoke(definition, (String) args[binding.conversation()], (String) args[binding.message()]);
        });
    }

    private static Binding binding(Method method) {
        String message = "Copilot agent method must return CompletableFuture<String> and declare exactly two String "
                + "parameters, one @ConversationId and one @UserMessage: " + method.toGenericString();
        Assert.isTrue(method.getGenericReturnType() instanceof ParameterizedType result
                        && result.getRawType() == CompletableFuture.class
                        && result.getActualTypeArguments()[0] == String.class, message);
        Assert.isTrue(method.getParameterCount() == 2, message);
        int conversation = -1;
        int userMessage = -1;
        for (int index = 0; index < 2; index++) {
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
        Assert.isTrue(conversation != -1 && userMessage != -1, message);
        return new Binding(conversation, userMessage);
    }

    @Override
    public Object getObject() {
        return proxy;
    }

    @Override
    public Class<?> getObjectType() {
        return type;
    }

    private record Binding(int conversation, int message) {
    }
}
