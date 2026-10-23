package io.github.brunoborges.copilot.agent;

import java.util.List;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.github.copilot.rpc.PostToolUseFailureHookInput;
import com.github.copilot.rpc.PostToolUseHookInput;
import com.github.copilot.rpc.SessionEndHookInput;
import io.github.brunoborges.copilot.agent.CopilotErrorRecoveryHooks.Category;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CopilotErrorRecoveryHooksTests {

    private static final CopilotAgentContext CONTEXT = new CopilotAgentContext("agent", "c1", "s1");

    private final CopilotErrorRecoveryHooks hooks = new CopilotErrorRecoveryHooks();

    @Test
    void classifiesByPhraseIgnoringCase() {
        assertThat(hooks.classify("open: Permission Denied")).isEqualTo(Category.PERMISSION_DENIED);
        assertThat(hooks.classify("HTTP 403 Forbidden")).isEqualTo(Category.PERMISSION_DENIED);
        assertThat(hooks.classify("bash: rg: command not found")).isEqualTo(Category.SHELL_ERROR);
        assertThat(hooks.classify("Traceback (most recent call last):")).isEqualTo(Category.SHELL_ERROR);
        assertThat(hooks.classify("all good\n<exited with exit code 0>")).isEqualTo(Category.NORMAL);
        assertThat(hooks.classify(null)).isEqualTo(Category.NORMAL);
    }

    @Test
    void addsANoteForErrorsInToolResultsWithoutChangingTheResult() {
        var output = hooks.onPostToolUse(CONTEXT, result("bash", "cat: x: No such file or directory"));
        assertThat(output.additionalContext()).isEqualTo(CopilotErrorRecoveryHooks.DEFAULT_ERROR_NOTE);
        assertThat(output.modifiedResult()).isNull();

        output = hooks.onPostToolUse(CONTEXT, result("view", "EACCES: permission denied"));
        assertThat(output.additionalContext()).isEqualTo(CopilotErrorRecoveryHooks.DEFAULT_PERMISSION_NOTE);

        assertThat(hooks.onPostToolUse(CONTEXT, result("bash", "hello"))).isNull();
    }

    @Test
    void readsTheLlmTextOfStructuredResults() {
        var structured = JsonNodeFactory.instance.objectNode().put("textResultForLlm", "access denied")
                .put("resultType", "success");
        var input = new PostToolUseHookInput().setSessionId("s1").setToolName("fetch").setToolResult(structured);
        assertThat(hooks.onPostToolUse(CONTEXT, input).additionalContext())
                .isEqualTo(CopilotErrorRecoveryHooks.DEFAULT_PERMISSION_NOTE);
    }

    @Test
    void notesEveryToolFailureEvenWithoutAMatchingPhrase() {
        var output = hooks.onPostToolUseFailure(CONTEXT, failure("lookup", "Order not found"));
        assertThat(output.getAdditionalContext()).isEqualTo(CopilotErrorRecoveryHooks.DEFAULT_ERROR_NOTE);
        output = hooks.onPostToolUseFailure(CONTEXT, failure("lookup", "operation not permitted"));
        assertThat(output.getAdditionalContext()).isEqualTo(CopilotErrorRecoveryHooks.DEFAULT_PERMISSION_NOTE);
    }

    @Test
    void stopsNudgingAfterTheCapUntilTheToolSucceeds() {
        var capped = CopilotErrorRecoveryHooks.builder().maxConsecutiveFailures(2).build();
        assertThat(capped.onPostToolUseFailure(CONTEXT, failure("bash", "boom"))).isNotNull();
        assertThat(capped.onPostToolUseFailure(CONTEXT, failure("bash", "boom"))).isNotNull();
        assertThat(capped.onPostToolUseFailure(CONTEXT, failure("bash", "boom"))).isNull();
        assertThat(capped.onPostToolUse(CONTEXT, result("bash", "command not found"))).isNull();

        // Other tools and other sessions have their own counts.
        assertThat(capped.onPostToolUseFailure(CONTEXT, failure("view", "boom"))).isNotNull();
        assertThat(capped.onPostToolUseFailure(CONTEXT, failure("bash", "boom").setSessionId("s2"))).isNotNull();

        assertThat(capped.onPostToolUse(CONTEXT, result("bash", "ok"))).isNull();
        assertThat(capped.onPostToolUseFailure(CONTEXT, failure("bash", "boom"))).isNotNull();
    }

    @Test
    void sessionEndForgetsTheSessionCounts() {
        var capped = CopilotErrorRecoveryHooks.builder().maxConsecutiveFailures(1).build();
        assertThat(capped.onPostToolUseFailure(CONTEXT, failure("bash", "boom"))).isNotNull();
        assertThat(capped.onPostToolUseFailure(CONTEXT, failure("bash", "boom"))).isNull();
        capped.onSessionEnd(CONTEXT, new SessionEndHookInput("s1", 0L, null, "complete", null, null));
        assertThat(capped.onPostToolUseFailure(CONTEXT, failure("bash", "boom"))).isNotNull();
    }

    @Test
    void zeroMeansNoCap() {
        var unlimited = CopilotErrorRecoveryHooks.builder().maxConsecutiveFailures(0).build();
        for (int i = 0; i < 10; i++) {
            assertThat(unlimited.onPostToolUseFailure(CONTEXT, failure("bash", "boom"))).isNotNull();
        }
    }

    @Test
    void phraseListsAndNotesAreConfigurable() {
        var custom = CopilotErrorRecoveryHooks.builder().permissionPhrases(List.of("Not Authorized"))
                .shellErrorPhrases(List.of("fatal:")).permissionNote("ask for access").errorNote("keep going")
                .build();
        assertThat(custom.classify("permission denied")).isEqualTo(Category.NORMAL);
        assertThat(custom.onPostToolUse(CONTEXT, result("git", "fatal: not a git repository")).additionalContext())
                .isEqualTo("keep going");
        assertThat(custom.onPostToolUse(CONTEXT, result("api", "401 not authorized")).additionalContext())
                .isEqualTo("ask for access");
        assertThatThrownBy(() -> CopilotErrorRecoveryHooks.builder().maxConsecutiveFailures(-1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CopilotErrorRecoveryHooks.builder().errorNote(" "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static PostToolUseHookInput result(String tool, String text) {
        return new PostToolUseHookInput().setSessionId("s1").setToolName(tool)
                .setToolResult(JsonNodeFactory.instance.textNode(text));
    }

    private static PostToolUseFailureHookInput failure(String tool, String error) {
        return new PostToolUseFailureHookInput().setSessionId("s1").setToolName(tool).setError(error);
    }
}
