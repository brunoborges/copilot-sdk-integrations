package com.microsoft.copilot.integrations.core;

import java.time.OffsetDateTime;

/**
 * A user or assistant message read from a persisted session's history.
 *
 * @param role who sent the message
 * @param content the message text
 * @param messageId the runtime's message ID, or {@code null}
 * @param timestamp when the message was recorded, or {@code null}
 */
public record CopilotSessionMessage(Role role, String content, String messageId, OffsetDateTime timestamp) {

    public enum Role {
        USER, ASSISTANT
    }
}
