package io.github.brunoborges.copilot.quarkus.runtime;

import java.util.List;

/** Agent interfaces and governance hooks discovered at build time. */
public final class CopilotAgentCatalog {

    private static volatile List<String> agents = List.of();
    private static volatile List<HookRegistration> hooks = List.of();

    private CopilotAgentCatalog() {
    }

    static void set(List<String> agentTypes, List<HookRegistration> hookRegistrations) {
        agents = List.copyOf(agentTypes);
        hooks = List.copyOf(hookRegistrations);
    }

    public static List<String> agents() {
        return agents;
    }

    public static List<HookRegistration> hooks() {
        return hooks;
    }
}
