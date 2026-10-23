package com.microsoft.copilot.integrations.quarkus.test;

import io.quarkus.arc.Arc;
import io.quarkus.arc.ArcContainer;
import io.quarkus.test.junit.callback.QuarkusTestAfterEachCallback;
import io.quarkus.test.junit.callback.QuarkusTestMethodContext;

/** Resets the {@link CopilotTestRuntime} after each {@code @QuarkusTest} method. */
public class CopilotTestRuntimeResetCallback implements QuarkusTestAfterEachCallback {

    @Override
    public void afterEach(QuarkusTestMethodContext context) {
        ArcContainer container = Arc.container();
        if (container != null) {
            var runtime = container.instance(CopilotTestRuntime.class);
            if (runtime.isAvailable()) {
                runtime.get().reset();
            }
        }
    }
}
