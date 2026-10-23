package io.github.brunoborges.copilot.agent;

import com.github.copilot.rpc.MessageSource;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CopilotMessageSourcesTests {

    @Test
    void parsesSupportedSources() {
        assertThat(CopilotMessageSources.parse("user")).isEqualTo(MessageSource.USER);
        assertThat(CopilotMessageSources.parse("system")).isEqualTo(MessageSource.SYSTEM);
        assertThat(CopilotMessageSources.parse("agent-incident.bot_1")).isEqualTo(MessageSource.agent("incident.bot_1"));
        assertThat(CopilotMessageSources.parse(null)).isNull();
        assertThat(CopilotMessageSources.parse("")).isNull();
    }

    @Test
    void rejectsUnsupportedSourcesAndUnsafeAgentIds() {
        for (var value : new String[] { "admin", "USER", " system", "agent-", "agent-bad id", "agent--x", "command-x",
                "agent-" + "a".repeat(129) }) {
            assertThatThrownBy(() -> CopilotMessageSources.parse(value)).as(value)
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> CopilotMessageSources.agent("../etc")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CopilotMessageSources.agent(null)).isInstanceOf(IllegalArgumentException.class);
        assertThat(CopilotMessageSources.agent("a".repeat(128)).getValue()).isEqualTo("agent-" + "a".repeat(128));
    }

    @Test
    void scopesNestAndRestore() {
        assertThat(CopilotMessageSources.current()).isNull();
        CopilotMessageSources.runAs(MessageSource.SYSTEM, () -> {
            assertThat(CopilotMessageSources.current()).isEqualTo(MessageSource.SYSTEM);
            var inner = CopilotMessageSources.callAs(CopilotMessageSources.agent("bot"), CopilotMessageSources::current);
            assertThat(inner).isEqualTo(MessageSource.agent("bot"));
            assertThat(CopilotMessageSources.current()).isEqualTo(MessageSource.SYSTEM);
        });
        assertThat(CopilotMessageSources.current()).isNull();
    }

    @Test
    void scopesRestoreAfterFailuresAndRevalidateSdkValues() {
        assertThatThrownBy(() -> CopilotMessageSources.runAs(MessageSource.SYSTEM, () -> {
            throw new IllegalStateException("boom");
        })).hasMessage("boom");
        assertThat(CopilotMessageSources.current()).isNull();
        assertThatThrownBy(() -> CopilotMessageSources.runAs(MessageSource.agent("bad id"), () -> { }))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CopilotMessageSources.runAs(null, () -> { }))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
