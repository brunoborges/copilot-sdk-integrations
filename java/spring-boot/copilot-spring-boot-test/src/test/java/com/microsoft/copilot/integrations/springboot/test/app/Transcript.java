package com.microsoft.copilot.integrations.springboot.test.app;

import java.util.List;

import com.microsoft.copilot.generated.SessionEvent;

public record Transcript(List<SessionEvent> events, String reply) {
}
