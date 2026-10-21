package io.github.brunoborges.copilot.example;

import com.github.copilot.generated.AssistantMessageEvent;
import com.github.copilot.generated.SessionEvent;
import io.github.brunoborges.copilot.agent.CopilotAgentContext;
import io.github.brunoborges.copilot.agent.CopilotAgentListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class ConversationOutput implements CopilotAgentListener {

    private static final Logger log = LoggerFactory.getLogger(ConversationOutput.class);

    public void onMessage(String conversationId, String sessionId, AssistantMessageEvent event) {
        log.info("[{} / {}] {}", conversationId, sessionId, event.getData().content());
    }

    @Override
    public void onEvent(CopilotAgentContext context, SessionEvent event) {
        if (event instanceof AssistantMessageEvent message) {
            onMessage(context.conversationId(), context.sessionId(), message);
        }
    }
}
