package com.microsoft.copilot.integrations.springboot.example;

import java.io.IOException;

import com.microsoft.copilot.CopilotClient;
import com.microsoft.copilot.generated.AssistantMessageEvent;
import com.microsoft.copilot.rpc.MessageOptions;
import com.microsoft.copilot.rpc.PermissionHandler;
import com.microsoft.copilot.rpc.CreateSessionOptions;
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
        var options = new CreateSessionOptions();
        options.getPermissions().setOnRequest(permissions);
        try (var session = client.createSession(options).join();
                var messages = session.on(AssistantMessageEvent.class,
                        event -> output.onMessage(conversationId, session.getSessionId(), event))) {
            session.sendAndWait(new MessageOptions().setPrompt(prompt)).join();
        }
    }
}
