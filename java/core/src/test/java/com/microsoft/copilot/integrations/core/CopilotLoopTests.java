package com.microsoft.copilot.integrations.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.microsoft.copilot.integrations.core.CopilotLoop.StopReason;
import com.microsoft.copilot.integrations.core.internal.DefaultCopilotLoops;

class CopilotLoopTests {

    @Test
    void runsUpToMaxIterations() {
        var prompts = new ArrayList<String>();
        var result = CopilotLoop.builder().prompt("build").maxIterations(3).build()
                .run((i, prompt) -> {
                    prompts.add(prompt);
                    return "done " + i;
                });
        assertThat(result.stopReason()).isEqualTo(StopReason.MAX_ITERATIONS);
        assertThat(result.iterations()).extracting(CopilotLoop.Iteration::output)
                .containsExactly("done 1", "done 2", "done 3");
        assertThat(prompts).containsExactly("build", "build", "build");
        assertThat(result.last().number()).isEqualTo(3);
    }

    @Test
    void stopsWhenConditionMatches() {
        var result = CopilotLoop.builder().prompt("p").maxIterations(10)
                .stopWhen(it -> it.output().contains("COMPLETE")).build()
                .run((i, prompt) -> i == 2 ? "COMPLETE" : "working");
        assertThat(result.stopReason()).isEqualTo(StopReason.STOP_CONDITION);
        assertThat(result.iterations()).hasSize(2);
    }

    @Test
    void checkRunsBetweenIterationsAndCanStop() {
        var checked = new ArrayList<Integer>();
        var result = CopilotLoop.builder().prompt("p").maxIterations(5)
                .check(previous -> {
                    checked.add(previous.number());
                    return previous.number() < 2;
                }).build()
                .run((i, prompt) -> "ok");
        assertThat(result.stopReason()).isEqualTo(StopReason.CHECK);
        assertThat(checked).containsExactly(1, 2);

        var notAfterLast = new ArrayList<Integer>();
        CopilotLoop.builder().prompt("p").maxIterations(2).check(previous -> notAfterLast.add(previous.number())).build()
                .run((i, prompt) -> "ok");
        assertThat(notAfterLast).containsExactly(1);
    }

    @Test
    void failureStopsUnlessContinueOnFailure() {
        CopilotLoop.Step failing = (i, prompt) -> {
            if (i == 1) {
                throw new IllegalStateException("boom");
            }
            return "ok";
        };
        var stopped = CopilotLoop.builder().prompt("p").maxIterations(3).build().run(failing);
        assertThat(stopped.stopReason()).isEqualTo(StopReason.FAILED);
        assertThat(stopped.last().failure()).hasMessage("boom");
        assertThat(stopped.last().succeeded()).isFalse();

        var continued = CopilotLoop.builder().prompt("p").maxIterations(3).continueOnFailure(true).build().run(failing);
        assertThat(continued.stopReason()).isEqualTo(StopReason.MAX_ITERATIONS);
        assertThat(continued.iterations()).extracting(CopilotLoop.Iteration::succeeded).containsExactly(false, true, true);
    }

    @Test
    void iterationTimeoutFailsAndInterruptsTheStep() {
        var interrupted = new AtomicInteger();
        var result = CopilotLoop.builder().prompt("p").maxIterations(2).iterationTimeout(Duration.ofMillis(50)).build()
                .run((i, prompt) -> {
                    try {
                        Thread.sleep(10_000);
                    } catch (InterruptedException ex) {
                        interrupted.incrementAndGet();
                        throw ex;
                    }
                    return "late";
                });
        assertThat(result.stopReason()).isEqualTo(StopReason.FAILED);
        assertThat(result.last().failure()).isInstanceOf(TimeoutException.class);
        assertThat(interrupted).hasValue(1);
    }

    @Test
    void cancelWhenAndInterruptionCancel() {
        var calls = new AtomicInteger();
        var result = CopilotLoop.builder().prompt("p").maxIterations(5).cancelWhen(() -> calls.get() >= 2).build()
                .run((i, prompt) -> "it " + calls.incrementAndGet());
        assertThat(result.stopReason()).isEqualTo(StopReason.CANCELLED);
        assertThat(result.iterations()).hasSize(2);

        var interrupted = CopilotLoop.builder().prompt("p").maxIterations(5).build().run((i, prompt) -> {
            throw new InterruptedException();
        });
        assertThat(interrupted.stopReason()).isEqualTo(StopReason.CANCELLED);
        assertThat(interrupted.iterations()).isEmpty();
        assertThat(Thread.interrupted()).isTrue();
    }

    @Test
    void promptSupplierIsReadEachIterationAndListenerSeesIterations() {
        var counter = new AtomicInteger();
        List<CopilotLoop.Iteration> seen = new CopyOnWriteArrayList<>();
        CopilotLoop.builder().prompt(() -> "prompt " + counter.incrementAndGet()).maxIterations(2)
                .listener(it -> {
                    seen.add(it);
                    throw new IllegalStateException("listener errors are ignored");
                }).build()
                .run((i, prompt) -> prompt.toUpperCase());
        assertThat(seen).extracting(CopilotLoop.Iteration::output).containsExactly("PROMPT 1", "PROMPT 2");
    }

    @Test
    void validatesOptions() {
        assertThatThrownBy(() -> CopilotLoop.builder().maxIterations(1).build()).hasMessageContaining("prompt");
        assertThatThrownBy(() -> CopilotLoop.builder().prompt("p").build()).hasMessageContaining("maxIterations");
        assertThatThrownBy(() -> CopilotLoop.builder().prompt("p").maxIterations(1).iterationTimeout(Duration.ZERO).build())
                .hasMessageContaining("iterationTimeout");
    }

    interface Builder {
        String build(String prompt);
    }

    @Test
    void copilotLoopsBindsAFreshInstancePerIteration() {
        var created = new ArrayList<String>();
        var closed = new AtomicInteger();
        CopilotAgents agents = new CopilotAgents() {
            @Override
            @SuppressWarnings("unchecked")
            public <T> CopilotAgentInstance<T> create(Class<T> agentType) {
                String id = "conversation-" + (created.size() + 1);
                created.add(id);
                Builder agent = prompt -> id + ":" + prompt;
                return new CopilotAgentInstance<>() {
                    @Override
                    public T agent() {
                        return (T) agent;
                    }

                    @Override
                    public String conversationId() {
                        return id;
                    }

                    @Override
                    public void close() {
                        closed.incrementAndGet();
                    }
                };
            }

            @Override
            public <T> CopilotAgentInstance<T> resume(Class<T> agentType, String conversationId) {
                throw new UnsupportedOperationException();
            }
        };
        var loop = CopilotLoop.builder().prompt("work").maxIterations(2).build();
        var result = new DefaultCopilotLoops(agents).run(loop, Builder.class, Builder::build);
        assertThat(result.iterations()).extracting(CopilotLoop.Iteration::output)
                .containsExactly("conversation-1:work", "conversation-2:work");
        assertThat(created).hasSize(2);
        assertThat(closed).hasValue(2);
    }
}
