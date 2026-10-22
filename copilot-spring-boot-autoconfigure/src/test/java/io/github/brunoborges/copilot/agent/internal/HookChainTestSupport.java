package io.github.brunoborges.copilot.agent.internal;

import java.util.List;

import com.github.copilot.rpc.SessionHooks;
import io.github.brunoborges.copilot.agent.CopilotAgentContext;
import io.github.brunoborges.copilot.agent.CopilotAgentHooks;

public final class HookChainTestSupport {

    private HookChainTestSupport() {
    }

    public static SessionHooks sessionHooks(CopilotAgentContext context, CopilotAgentHooks... hooks) {
        return CopilotAgentHookChain.sessionHooks(List.of(hooks), context, CopilotAgentLifetime.Lease.NONE);
    }
}
