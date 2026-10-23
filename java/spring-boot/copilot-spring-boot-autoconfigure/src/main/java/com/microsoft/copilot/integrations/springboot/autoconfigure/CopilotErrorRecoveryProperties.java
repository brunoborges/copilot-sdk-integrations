package com.microsoft.copilot.integrations.springboot.autoconfigure;

import java.util.List;

import com.microsoft.copilot.integrations.core.CopilotErrorRecoveryHooks;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.Assert;

/**
 * @param enabled whether to register {@link CopilotErrorRecoveryHooks} as a global hook bean for every agent
 * @param permissionPhrases case-insensitive phrases that mark a permission denial in tool output; replaces the
 *        defaults when set
 * @param shellErrorPhrases case-insensitive phrases that mark a shell or file error in tool output; replaces the
 *        defaults when set
 * @param permissionNote note added to the model's context after a permission denial
 * @param errorNote note added to the model's context after a shell error or tool failure
 * @param maxConsecutiveFailures consecutive failures of one tool in a session that still get a note; 0 for no limit
 */
@ConfigurationProperties("copilot.error-recovery")
public record CopilotErrorRecoveryProperties(
        @DefaultValue("false") boolean enabled,
        List<String> permissionPhrases,
        List<String> shellErrorPhrases,
        String permissionNote,
        String errorNote,
        @DefaultValue("3") int maxConsecutiveFailures) {

    public CopilotErrorRecoveryProperties {
        Assert.isTrue(maxConsecutiveFailures >= 0, "copilot.error-recovery.max-consecutive-failures must not be negative");
    }

    CopilotErrorRecoveryHooks toHooks() {
        var builder = CopilotErrorRecoveryHooks.builder().maxConsecutiveFailures(maxConsecutiveFailures);
        if (permissionPhrases != null) {
            builder.permissionPhrases(permissionPhrases);
        }
        if (shellErrorPhrases != null) {
            builder.shellErrorPhrases(shellErrorPhrases);
        }
        if (permissionNote != null && !permissionNote.isBlank()) {
            builder.permissionNote(permissionNote);
        }
        if (errorNote != null && !errorNote.isBlank()) {
            builder.errorNote(errorNote);
        }
        return builder.build();
    }
}
