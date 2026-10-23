package io.github.brunoborges.copilot.agent;

import java.util.function.BiFunction;

/**
 * Runs a {@link CopilotLoop} against an ID-less {@link CopilotAgent} interface, binding a fresh instance (and so a
 * fresh session) for every iteration and closing it afterwards. Injectable from every Java integration.
 */
public interface CopilotLoops {

    /**
     * Runs {@code loop}; {@code call} invokes the agent with the iteration's prompt, and its result's
     * {@code toString()} becomes the iteration output.
     */
    <T> CopilotLoop.Result run(CopilotLoop loop, Class<T> agentType, BiFunction<? super T, String, ?> call);

    /** A {@link CopilotLoop.Step} that binds a fresh instance of {@code agentType} per iteration. */
    <T> CopilotLoop.Step step(Class<T> agentType, BiFunction<? super T, String, ?> call);
}
