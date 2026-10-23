package io.github.brunoborges.copilot.camel;

import java.lang.annotation.Annotation;
import java.util.Arrays;

import com.github.copilot.SystemMessageMode;

import io.github.brunoborges.copilot.agent.CopilotAgent;

/** {@link CopilotAgent} attributes assembled from {@code copilot:agent} endpoint options. */
record CopilotAgentAttributes(String name, String instructions, String instructionsResource,
        SystemMessageMode instructionsMode, String systemMessageRef, String model, String reasoningEffort,
        String permissionHandlerRef, String[] toolRefs, String[] mcpServers, String provider, String[] listenerRefs,
        String[] customizerRefs, String[] availableTools, String[] excludedTools, String[] excludedBuiltInAgents,
        boolean builtInTools, String toolSearch, int toolSearchDeferThreshold, String workingDirectory,
        String[] additionalDirectories, String[] hookRefs, String tokenResolverRef, String messageSource)
        implements CopilotAgent {

    @Override
    public Class<? extends Annotation> annotationType() {
        return CopilotAgent.class;
    }

    @Override
    public String toString() {
        return "@CopilotAgent(name=" + name + ", toolRefs=" + Arrays.toString(toolRefs) + ")";
    }
}
