package io.github.brunoborges.copilot.agent;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Runs the same prompt repeatedly, each iteration in a fresh Copilot session (the "Ralph loop" pattern).
 *
 * <p>State lives on disk (for example an {@code IMPLEMENTATION_PLAN.md} in the agent's working directory), not in
 * model context: every iteration is an ordinary, independent agent invocation, so the agent's configured permission
 * policy, tools, hooks, and listeners apply unchanged. Nothing is approved implicitly. Builds and tests act as the
 * quality gate, either inside the prompt (the agent must make them pass) or as a {@link Check} between iterations.
 *
 * <p>The loop runs on the calling thread and stops at the first of:
 * <ul>
 * <li>{@link Builder#stopWhen(Predicate) stopWhen} matching an iteration ({@link StopReason#STOP_CONDITION});</li>
 * <li>the {@link Builder#check(Check) check} returning {@code false} ({@link StopReason#CHECK});</li>
 * <li>an iteration failing or timing out, unless {@link Builder#continueOnFailure(boolean)} ({@link StopReason#FAILED});</li>
 * <li>interruption of the calling thread or {@link Builder#cancelWhen(BooleanSupplier) cancelWhen}
 * ({@link StopReason#CANCELLED});</li>
 * <li>{@link Builder#maxIterations(int) maxIterations} iterations ({@link StopReason#MAX_ITERATIONS}).</li>
 * </ul>
 *
 * <p>Use {@link CopilotLoops} to run an {@link CopilotAgent} interface with a fresh bound instance per iteration, or
 * {@link #run(Step)} with any step. A loop is immutable and can be run any number of times.
 */
public final class CopilotLoop {

    private static final Logger log = Logger.getLogger(CopilotLoop.class.getName());

    private final Supplier<String> prompt;
    private final int maxIterations;
    private final Duration iterationTimeout;
    private final Check check;
    private final Predicate<Iteration> stopWhen;
    private final Listener listener;
    private final BooleanSupplier cancelWhen;
    private final boolean continueOnFailure;

    private CopilotLoop(Builder builder) {
        this.prompt = builder.prompt;
        this.maxIterations = builder.maxIterations;
        this.iterationTimeout = builder.iterationTimeout;
        this.check = builder.check;
        this.stopWhen = builder.stopWhen;
        this.listener = builder.listener;
        this.cancelWhen = builder.cancelWhen;
        this.continueOnFailure = builder.continueOnFailure;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Runs the loop on the calling thread; {@code step} must start a fresh session for each call. */
    public Result run(Step step) {
        Objects.requireNonNull(step, "step");
        var iterations = new ArrayList<Iteration>();
        for (int number = 1; number <= maxIterations; number++) {
            if (Thread.currentThread().isInterrupted() || cancelWhen.getAsBoolean()) {
                return new Result(StopReason.CANCELLED, iterations);
            }
            Iteration iteration;
            try {
                iteration = execute(step, number);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return new Result(StopReason.CANCELLED, iterations);
            }
            iterations.add(iteration);
            notify(iteration);
            if (!iteration.succeeded() && !continueOnFailure) {
                return new Result(StopReason.FAILED, iterations);
            }
            if (iteration.succeeded() && stopWhen.test(iteration)) {
                return new Result(StopReason.STOP_CONDITION, iterations);
            }
            if (number < maxIterations) {
                try {
                    if (!check.proceed(iteration)) {
                        return new Result(StopReason.CHECK, iterations);
                    }
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    return new Result(StopReason.CANCELLED, iterations);
                } catch (Exception ex) {
                    throw new IllegalStateException("Copilot loop check failed after iteration " + number, ex);
                }
            }
        }
        return new Result(StopReason.MAX_ITERATIONS, iterations);
    }

    private Iteration execute(Step step, int number) throws InterruptedException {
        String text = Objects.requireNonNull(prompt.get(), "Copilot loop prompt must not be null");
        long start = System.nanoTime();
        try {
            String output = iterationTimeout == null ? step.execute(number, text) : withTimeout(step, number, text);
            return new Iteration(number, text, output, null, Duration.ofNanos(System.nanoTime() - start));
        } catch (InterruptedException ex) {
            throw ex;
        } catch (Exception ex) {
            return new Iteration(number, text, null, ex, Duration.ofNanos(System.nanoTime() - start));
        }
    }

    private String withTimeout(Step step, int number, String text) throws Exception {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var future = executor.submit(() -> step.execute(number, text));
            try {
                return future.get(iterationTimeout.toNanos(), TimeUnit.NANOSECONDS);
            } catch (TimeoutException ex) {
                future.cancel(true);
                throw new TimeoutException("Copilot loop iteration " + number + " did not finish within " + iterationTimeout);
            } catch (InterruptedException ex) {
                future.cancel(true);
                throw ex;
            } catch (ExecutionException ex) {
                throw ex.getCause() instanceof Exception cause ? cause : ex;
            }
        }
    }

    private void notify(Iteration iteration) {
        try {
            listener.onIteration(iteration);
        } catch (RuntimeException ex) {
            log.log(Level.WARNING, "Copilot loop listener failed", ex);
        }
    }

    /** Runs one iteration; implementations must use a fresh session (never a shared conversation). */
    @FunctionalInterface
    public interface Step {
        /** Returns the iteration's output text (may be {@code null}). */
        String execute(int iteration, String prompt) throws Exception;
    }

    /** Between-iteration quality gate, such as running a build or test command. */
    @FunctionalInterface
    public interface Check {
        /** Returns {@code true} to run the next iteration, {@code false} to stop with {@link StopReason#CHECK}. */
        boolean proceed(Iteration previous) throws Exception;
    }

    /** Observes each completed iteration; exceptions are logged and ignored. */
    @FunctionalInterface
    public interface Listener {
        void onIteration(Iteration iteration);
    }

    /**
     * One completed iteration.
     *
     * @param number 1-based iteration number
     * @param prompt the prompt sent
     * @param output the step's output, or {@code null} when it failed
     * @param failure why the iteration failed (including {@link TimeoutException}), or {@code null}
     * @param duration the iteration's wall-clock time
     */
    public record Iteration(int number, String prompt, String output, Throwable failure, Duration duration) {
        public boolean succeeded() {
            return failure == null;
        }
    }

    /** Why a loop stopped. */
    public enum StopReason {
        /** {@code stopWhen} matched an iteration. */
        STOP_CONDITION,
        /** The between-iteration check returned {@code false}. */
        CHECK,
        /** The iteration limit was reached. */
        MAX_ITERATIONS,
        /** An iteration failed or timed out and {@code continueOnFailure} is off. */
        FAILED,
        /** The calling thread was interrupted or {@code cancelWhen} returned {@code true}. */
        CANCELLED
    }

    /** The loop outcome: why it stopped and every completed iteration, in order. */
    public record Result(StopReason stopReason, List<Iteration> iterations) {
        public Result {
            iterations = List.copyOf(iterations);
        }

        /** The last completed iteration, or {@code null} when none ran. */
        public Iteration last() {
            return iterations.isEmpty() ? null : iterations.getLast();
        }
    }

    public static final class Builder {
        private Supplier<String> prompt;
        private int maxIterations;
        private Duration iterationTimeout;
        private Check check = previous -> true;
        private Predicate<Iteration> stopWhen = iteration -> false;
        private Listener listener = iteration -> {
        };
        private BooleanSupplier cancelWhen = () -> false;
        private boolean continueOnFailure;

        private Builder() {
        }

        /** The prompt sent on every iteration. */
        public Builder prompt(String prompt) {
            Objects.requireNonNull(prompt, "prompt");
            return prompt(() -> prompt);
        }

        /** Supplies the prompt before every iteration, for example by re-reading a {@code PROMPT.md} file. */
        public Builder prompt(Supplier<String> prompt) {
            this.prompt = Objects.requireNonNull(prompt, "prompt");
            return this;
        }

        /** Upper bound on iterations; required and at least 1. */
        public Builder maxIterations(int maxIterations) {
            this.maxIterations = maxIterations;
            return this;
        }

        /** Fails an iteration that runs longer; the step then runs on a separate virtual thread and is interrupted. */
        public Builder iterationTimeout(Duration iterationTimeout) {
            this.iterationTimeout = iterationTimeout;
            return this;
        }

        public Builder check(Check check) {
            this.check = Objects.requireNonNull(check, "check");
            return this;
        }

        /** Stops after a successful iteration that matches, for example when the output reports the work is done. */
        public Builder stopWhen(Predicate<Iteration> stopWhen) {
            this.stopWhen = Objects.requireNonNull(stopWhen, "stopWhen");
            return this;
        }

        public Builder listener(Listener listener) {
            this.listener = Objects.requireNonNull(listener, "listener");
            return this;
        }

        /** Checked before every iteration, for example a scheduler's or batch job's stop flag. */
        public Builder cancelWhen(BooleanSupplier cancelWhen) {
            this.cancelWhen = Objects.requireNonNull(cancelWhen, "cancelWhen");
            return this;
        }

        /** Whether a failed or timed-out iteration is recorded and the loop continues; default {@code false}. */
        public Builder continueOnFailure(boolean continueOnFailure) {
            this.continueOnFailure = continueOnFailure;
            return this;
        }

        public CopilotLoop build() {
            if (prompt == null) {
                throw new IllegalArgumentException("Copilot loop requires a prompt");
            }
            if (maxIterations < 1) {
                throw new IllegalArgumentException("Copilot loop maxIterations must be at least 1");
            }
            if (iterationTimeout != null && (iterationTimeout.isNegative() || iterationTimeout.isZero())) {
                throw new IllegalArgumentException("Copilot loop iterationTimeout must be positive");
            }
            return new CopilotLoop(this);
        }
    }
}
