package io.github.brunoborges.copilot.micronaut.example;

import jakarta.inject.Named;
import jakarta.inject.Singleton;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.copilot.generated.AssistantMessageEvent;
import com.github.copilot.generated.SessionEvent;

import io.github.brunoborges.copilot.agent.CopilotAgentContext;
import io.github.brunoborges.copilot.agent.CopilotAgentListener;

@Singleton
@Named("conversationOutput")
public class ConversationOutput implements CopilotAgentListener {

    private static final Logger log = LoggerFactory.getLogger(ConversationOutput.class);

    @Override
    public void onEvent(CopilotAgentContext context, SessionEvent event) {
        if (event instanceof AssistantMessageEvent message) {
            log.info("[{} / {}] {}", context.conversationId(), context.sessionId(), message.getData().content());
        }
    }
}
