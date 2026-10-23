package com.microsoft.copilot.integrations.quarkus.runtime;

import java.util.List;
import java.util.function.Supplier;

import io.quarkus.arc.Arc;
import io.quarkus.runtime.annotations.Recorder;

@Recorder
public class CopilotRecorder {

    public void catalog(List<String> agents, List<HookRegistration> hooks) {
        CopilotAgentCatalog.set(agents, hooks);
    }

    public Supplier<Object> agent(String type) {
        return () -> Arc.container().instance(CopilotAgentRegistry.class).get().proxy(type);
    }
}
