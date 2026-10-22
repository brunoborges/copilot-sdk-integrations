package io.github.brunoborges.copilot.agent.spring;

import io.github.brunoborges.copilot.agent.*;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.copilot.rpc.ToolDefinition;
import com.github.copilot.rpc.ToolInvocation;
import com.github.copilot.rpc.ToolNamespace;
import io.github.brunoborges.copilot.agent.tools.EchoOperations;
import io.github.brunoborges.copilot.agent.tools.EchoTools;
import org.aopalliance.intercept.MethodInterceptor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.core.env.StandardEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SpringToolBindingsTests {

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void generatedHandlersInvokeTheSpringProxyRatherThanBypassingAdvice(boolean classProxy) {
        var advised = new AtomicInteger();
        var factory = new ProxyFactory(new EchoTools());
        factory.setInterfaces(EchoOperations.class);
        factory.setProxyTargetClass(classProxy);
        factory.addAdvice((MethodInterceptor) invocation -> {
            advised.incrementAndGet();
            return invocation.proceed();
        });
        var beans = new DefaultListableBeanFactory();
        beans.registerSingleton("echoTools", factory.getProxy());
        var tools = SpringTestSupport.tools(new String[]{"echoTools"}, beans, new StandardEnvironment());
        assertThat(tools).hasSize(1);
        var tool = tools.getFirst();
        assertThat(tool.name()).isEqualTo("echo");
        assertThat(tool.parameters().toString()).contains("value", "required");
        var invocation = new ToolInvocation().setArguments(new ObjectMapper().valueToTree(Map.of("value", "hello")));
        assertThat(tool.handler().invoke(invocation).join()).isEqualTo("echo:hello");
        assertThat(advised.get()).isEqualTo(1);
        assertThat(tool.skipPermission()).isNotEqualTo(Boolean.TRUE);
    }

    @Test
    void supportsIndividualDefinitionsAndRejectsDuplicateNamesWithinTheirNamespace() {
        var beans = new DefaultListableBeanFactory();
        var tool = ToolDefinition.create("echo", "Echo", Map.of(), invocation ->
                java.util.concurrent.CompletableFuture.completedFuture("hello"));
        beans.registerSingleton("first", tool);
        beans.registerSingleton("second", tool);
        assertThatThrownBy(() -> SpringTestSupport.tools(new String[]{"first", "second"}, beans, new StandardEnvironment()))
                .hasMessageContaining("Duplicate Copilot tool");
        beans.registerSingleton("namespaced", tool.namespace(new ToolNamespace("separate")));
        assertThat(SpringTestSupport.tools(new String[]{"first", "namespaced"}, beans, new StandardEnvironment())).hasSize(2);
    }

    @Test
    void missingToolRefPointsToAvailableTools() {
        assertThatThrownBy(() -> SpringTestSupport.tools(new String[]{"view"}, new DefaultListableBeanFactory(),
                new StandardEnvironment()))
                .hasMessageContaining("toolRefs references bean 'view'")
                .hasMessageContaining("use availableTools");
    }

    @Test
    void rejectsBeansWithoutSdkTools() {
        var beans = new DefaultListableBeanFactory();
        beans.registerSingleton("notTools", "not a tool service");
        assertThatThrownBy(() -> SpringTestSupport.tools(new String[]{"notTools"}, beans, new StandardEnvironment()))
                .hasMessageContaining("must be a ToolDefinition or declare SDK @CopilotTool");
    }

    @Test
    void plainServiceUsesSdkGeneratedSchemasAndArgumentConversion() {
        var beans = new DefaultListableBeanFactory();
        beans.registerSingleton("echo", new EchoTools());
        var tools = SpringTestSupport.tools(new String[]{"echo"}, beans, new StandardEnvironment());
        var invocation = new ToolInvocation().setArguments(new ObjectMapper().valueToTree(Map.of("value", "plain")));
        assertThat(tools.getFirst().handler().invoke(invocation).join()).isEqualTo("echo:plain");
    }

    @Test
    void classProxyCanBindAnnotationsDeclaredOnlyOnItsInterface() {
        var advised = new AtomicInteger();
        var factory = new ProxyFactory(new InterfaceOnlyTools());
        factory.setProxyTargetClass(true);
        factory.addAdvice((MethodInterceptor) invocation -> {
            advised.incrementAndGet();
            return invocation.proceed();
        });
        var beans = new DefaultListableBeanFactory();
        beans.registerSingleton("tools", factory.getProxy());
        var tool = SpringTestSupport.tools(new String[]{"tools"}, beans, new StandardEnvironment()).getFirst();
        assertThat(tool.handler().invoke(new ToolInvocation().setArguments(
                new ObjectMapper().valueToTree(Map.of("value", "contract")))).join()).isEqualTo("contract");
        assertThat(advised.get()).isEqualTo(1);
    }

    public static class InterfaceOnlyTools implements EchoOperations {
        @Override
        public String echo(String value) {
            return value;
        }
    }
}
