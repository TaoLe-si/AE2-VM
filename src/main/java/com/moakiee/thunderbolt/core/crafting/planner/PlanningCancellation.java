package com.moakiee.thunderbolt.core.crafting.planner;

import java.util.concurrent.CancellationException;

import com.moakiee.thunderbolt.api.crafting.PlanningAttemptContext;
import com.moakiee.thunderbolt.api.crafting.PlanningDiagnosticSnapshot;

/** Cooperative cancellation checkpoint shared by adapter and pure planner hot loops. */
public final class PlanningCancellation {
    private static final ThreadLocal<PlanningAttemptContext> CURRENT = new ThreadLocal<>();

    private PlanningCancellation() {
    }

    public static void check() {
        var context = CURRENT.get();
        if (context != null) {
            context.checkpoint();
            return;
        }
        if (Thread.currentThread().isInterrupted()) {
            throw new CancellationException("crafting calculation interrupted");
        }
    }

    /** Runs the bound planning checkpoint and reports whether this is candidate work. */
    public static boolean checkpointIfBound() {
        var context = CURRENT.get();
        if (context == null) {
            return false;
        }
        context.checkpoint();
        return true;
    }

    public static void report(PlanningDiagnosticSnapshot snapshot) {
        var context = CURRENT.get();
        if (context != null) {
            context.report(snapshot);
        }
    }

    /** Remaining candidate time, capped for a bounded native solver call. */
    static long remainingNanos(long capNanos) {
        long boundedCap = Math.max(0L, capNanos);
        var context = CURRENT.get();
        if (context == null) {
            return boundedCap;
        }
        context.checkpoint();
        long remaining = context.deadlineNanos() - System.nanoTime();
        return Math.max(0L, Math.min(boundedCap, remaining));
    }

    public static Scope bind(PlanningAttemptContext context) {
        var previous = CURRENT.get();
        CURRENT.set(context);
        return () -> {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        };
    }

    @FunctionalInterface
    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }
}
