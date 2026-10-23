package com.microsoft.copilot.integrations.springboot.example;

import java.io.IOException;

import com.github.copilot.CopilotClient;
import com.github.copilot.generated.AssistantMessageEvent;
import com.github.copilot.rpc.MessageOptions;
import com.github.copilot.rpc.PermissionHandler;
import com.github.copilot.rpc.SessionConfig;
import org.springframework.stereotype.Service;

@Service
public class ConversationService {

    private final CopilotClient client;
    private final ConversationOutput output;
    private final PermissionHandler permissions;

    public ConversationService(CopilotClient client, ConversationOutput output, PermissionHandler permissions) {
        this.client = client;
        this.output = output;
        this.permissions = permissions;
    }

    public void converse(String conversationId, String prompt) throws IOException {
        try (var session = client.createSession(new SessionConfig().setOnPermissionRequest(permissions)).join();
                var messages = session.on(AssistantMessageEvent.class,
                        event -> output.onMessage(conversationId, session.getSessionId(), event))) {
            session.sendAndWait(new MessageOptions().setPrompt(prompt)).join();
        }
    }
}
