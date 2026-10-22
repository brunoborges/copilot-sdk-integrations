package io.github.brunoborges.copilot.test;

import org.springframework.core.Ordered;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.TestExecutionListener;

/**
 * Resets the {@link CopilotTestRuntime} of the test application context after each test method.
 */
public class CopilotTestRuntimeTestExecutionListener implements TestExecutionListener, Ordered {

    @Override
    public void afterTestMethod(TestContext testContext) {
        if (testContext.hasApplicationContext()) {
            testContext.getApplicationContext().getBeanProvider(CopilotTestRuntime.class)
                    .ifAvailable(CopilotTestRuntime::reset);
        }
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE - 100;
    }
}
