package io.github.brunoborges.copilot.quarkus.example;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Named;

import org.jboss.logging.Logger;

import com.github.copilot.generated.AssistantMessageEvent;
import com.github.copilot.generated.SessionEvent;

import io.github.brunoborges.copilot.agent.CopilotAgentContext;
import io.github.brunoborges.copilot.agent.CopilotAgentListener;

@ApplicationScoped
@Named("conversationOutput")
public class ConversationOutput implements CopilotAgentListener {

    private static final Logger log = Logger.getLogger(ConversationOutput.class);

    @Override
    public void onEvent(CopilotAgentContext context, SessionEvent event) {
        if (event instanceof AssistantMessageEvent message) {
            log.infof("[%s / %s] %s", context.conversationId(), context.sessionId(), message.getData().content());
        }
    }
}
