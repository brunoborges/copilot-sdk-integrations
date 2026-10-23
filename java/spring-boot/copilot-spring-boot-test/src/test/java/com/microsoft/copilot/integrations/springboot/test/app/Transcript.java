package com.microsoft.copilot.integrations.springboot.test.app;

import java.util.List;

import com.github.copilot.generated.SessionEvent;

public record Transcript(List<SessionEvent> events, String reply) {
}
