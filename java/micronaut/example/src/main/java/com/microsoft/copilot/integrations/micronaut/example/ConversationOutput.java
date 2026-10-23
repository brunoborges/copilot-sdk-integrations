package com.microsoft.copilot.integrations.micronaut.example;

import jakarta.inject.Named;
import jakarta.inject.Singleton;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.copilot.generated.AssistantMessageEvent;
import com.github.copilot.generated.SessionEvent;

import com.microsoft.copilot.integrations.core.CopilotAgentContext;
import com.microsoft.copilot.integrations.core.CopilotAgentListener;

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
