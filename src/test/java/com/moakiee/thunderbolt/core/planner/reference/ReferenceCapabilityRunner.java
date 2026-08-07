package com.moakiee.thunderbolt.core.planner.reference;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;

/** Runs one author reference case with a hard deadline and classifies the production path. */
public final class ReferenceCapabilityRunner {
    private final Duration deadline;
    private final Duration cancellationGrace;

    public ReferenceCapabilityRunner(Duration deadline, Duration cancellationGrace) {
        if (deadline.isNegative() || deadline.isZero()
                || cancellationGrace.isNegative() || cancellationGrace.isZero()) {
            throw new IllegalArgumentException("deadline and cancellation grace must be positive");
        }
        this.deadline = deadline;
        this.cancellationGrace = cancellationGrace;
    }

    public ReferenceRunResult run(ReferencePlanner planner, ReferenceScenario scenario) {
        Objects.requireNonNull(planner, "planner");
        Objects.requireNonNull(scenario, "scenario");
        long started = System.nanoTime();

        var checked = invoke(() -> planner.check(scenario));
        if (checked.status != InvocationStatus.COMPLETED) {
            return failedInvocation(scenario, checked, started);
        }
        if (!checked.value) {
            // Diagnostic forced execution distinguishes a conservative false negative from a genuine
            // rejection. It never changes production support: check=false remains unsupported.
            var forced = invoke(() -> planner.plan(scenario));
            if (forced.status == InvocationStatus.COMPLETED && forced.value != null
                    && forced.value.supported() && scenario.validate(forced.value).valid()) {
                return result(scenario, ReferenceSupportStatus.FALSE_NEGATIVE, started,
                        scenario.validate(forced.value).missingOverhead(), forced.value, null);
            }
            return result(scenario, ReferenceSupportStatus.CHECK_REJECTED, started,
                    Double.NaN, forced.value, forced.failure);
        }

        var planned = invoke(() -> planner.plan(scenario));
        if (planned.status != InvocationStatus.COMPLETED) {
            return failedInvocation(scenario, planned, started);
        }
        if (planned.value == null) {
            return result(scenario, ReferenceSupportStatus.FALSE_POSITIVE, started,
                    Double.NaN, null, null);
        }
        if (!planned.value.supported()) {
            return result(scenario, ReferenceSupportStatus.ATTEMPT_DECLINED, started,
                    Double.NaN, planned.value, null);
        }
        var validation = scenario.validate(planned.value);
        return result(scenario,
                validation.valid() ? ReferenceSupportStatus.SUPPORTED
                        : ReferenceSupportStatus.FALSE_POSITIVE,
                started, validation.missingOverhead(), planned.value, null);
    }

    private ReferenceRunResult failedInvocation(
            ReferenceScenario scenario, Invocation<?> invocation, long started) {
        var status = switch (invocation.status) {
            case ERROR -> ReferenceSupportStatus.ENGINE_ERROR;
            case TIMEOUT -> ReferenceSupportStatus.ENGINE_TIMEOUT;
            case NON_COOPERATIVE_TIMEOUT -> ReferenceSupportStatus.NON_COOPERATIVE_TIMEOUT;
            case COMPLETED -> throw new IllegalStateException("completed invocation is not a failure");
        };
        return result(scenario, status, started, Double.NaN, null, invocation.failure);
    }

    private ReferenceRunResult result(
            ReferenceScenario scenario,
            ReferenceSupportStatus status,
            long started,
            double missingOverhead,
            com.moakiee.thunderbolt.core.planner.CraftPlan<String> plan,
            Throwable failure) {
        return new ReferenceRunResult(
                scenario, status, Math.max(0L, System.nanoTime() - started),
                missingOverhead, plan, failure);
    }

    private <T> Invocation<T> invoke(ThrowingSupplier<T> operation) {
        var workerRef = new AtomicReference<Thread>();
        var future = new CompletableFuture<T>();
        Thread worker = Thread.ofPlatform()
                .daemon(true)
                .name("thunderbolt-reference-capability")
                .unstarted(() -> {
                    workerRef.set(Thread.currentThread());
                    try {
                        future.complete(operation.get());
                    } catch (Throwable failure) {
                        future.completeExceptionally(failure);
                    }
                });
        worker.start();
        try {
            return Invocation.completed(future.get(deadline.toNanos(), java.util.concurrent.TimeUnit.NANOSECONDS));
        } catch (java.util.concurrent.TimeoutException timeout) {
            worker.interrupt();
            try {
                long graceNanos = cancellationGrace.toNanos();
                worker.join(graceNanos / 1_000_000L, (int) (graceNanos % 1_000_000L));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return Invocation.error(interrupted);
            }
            return worker.isAlive()
                    ? Invocation.nonCooperativeTimeout(timeout)
                    : Invocation.timeout(timeout);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return Invocation.error(interrupted);
        } catch (ExecutionException failed) {
            return Invocation.error(failed.getCause());
        }
    }

    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get() throws Exception;
    }

    private enum InvocationStatus {
        COMPLETED,
        ERROR,
        TIMEOUT,
        NON_COOPERATIVE_TIMEOUT
    }

    private record Invocation<T>(InvocationStatus status, T value, Throwable failure) {
        static <T> Invocation<T> completed(T value) {
            return new Invocation<>(InvocationStatus.COMPLETED, value, null);
        }

        static <T> Invocation<T> error(Throwable failure) {
            return new Invocation<>(InvocationStatus.ERROR, null, failure);
        }

        static <T> Invocation<T> timeout(Throwable failure) {
            return new Invocation<>(InvocationStatus.TIMEOUT, null, failure);
        }

        static <T> Invocation<T> nonCooperativeTimeout(Throwable failure) {
            return new Invocation<>(InvocationStatus.NON_COOPERATIVE_TIMEOUT, null, failure);
        }
    }
}
