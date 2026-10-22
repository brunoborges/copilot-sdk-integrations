package io.github.brunoborges.copilot.quarkus.deployment;

import org.jboss.jandex.DotName;

import io.quarkus.builder.item.MultiBuildItem;

/** A {@code @CopilotAgent} interface found in the application index. */
public final class CopilotAgentBuildItem extends MultiBuildItem {

    private final DotName type;
    private final boolean bound;

    public CopilotAgentBuildItem(DotName type, boolean bound) {
        this.type = type;
        this.bound = bound;
    }

    public DotName type() {
        return type;
    }

    /** Whether no method takes a {@code @ConversationId}, so instances must come from {@code CopilotAgents}. */
    public boolean bound() {
        return bound;
    }
}
