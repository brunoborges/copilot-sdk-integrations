package com.microsoft.copilot.integrations.springboot.agent;

import java.util.List;

import com.github.copilot.rpc.ToolDefinition;
import com.github.copilot.CopilotClient;
import com.microsoft.copilot.integrations.core.CopilotAgentTelemetry;
import com.microsoft.copilot.integrations.core.internal.CopilotAgentDefinition;
import com.microsoft.copilot.integrations.core.internal.CopilotAgentExecutionLimits;
import com.microsoft.copilot.integrations.core.internal.CopilotAgentInvoker;
import com.microsoft.copilot.integrations.core.internal.ToolBindings;
import com.microsoft.copilot.integrations.springboot.autoconfigure.CopilotAgentsProperties;
import com.microsoft.copilot.integrations.springboot.autoconfigure.internal.SpringAgentBinding;
import com.microsoft.copilot.integrations.springboot.autoconfigure.internal.SpringBeanLookup;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ResourceLoader;

/** Resolves agent definitions and tools against Spring beans the way the auto-configuration does. */
public final class SpringTestSupport {

    private SpringTestSupport() {
    }

    public static CopilotAgentDefinition definition(Class<?> type, BeanFactory beans, Environment environment,
            ResourceLoader resources, CopilotAgentsProperties properties) {
        return CopilotAgentDefinition.create(type, SpringAgentBinding.context(beans, environment, resources, properties));
    }

    public static List<ToolDefinition> tools(String[] names, BeanFactory beans, Environment environment) {
        return ToolBindings.bind(names, new SpringBeanLookup(beans), environment::resolveRequiredPlaceholders);
    }

    /** An invoker with the Micrometer context propagation the auto-configuration installs. */
    public static CopilotAgentInvoker invoker(CopilotClient client, CopilotAgentTelemetry telemetry,
            CopilotAgentExecutionLimits limits) {
        return new CopilotAgentInvoker(client, telemetry, limits,
                SpringAgentBinding.contextPropagation(SpringTestSupport.class.getClassLoader()));
    }

    public static CopilotAgentInvoker invoker(CopilotClient client) {
        return invoker(client, CopilotAgentTelemetry.NOOP, CopilotAgentExecutionLimits.DEFAULT);
    }
}
