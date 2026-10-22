package io.github.brunoborges.copilot.test.app;

import java.util.List;

import com.github.copilot.generated.SessionEvent;

public record Transcript(List<SessionEvent> events, String reply) {
}
