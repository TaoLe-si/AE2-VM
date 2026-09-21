package com.moakiee.thunderbolt.core.planner;

import java.util.concurrent.CancellationException;

/** Cooperative cancellation checkpoint shared by adapter and pure planner hot loops. */
public final class PlanningCancellation {
    private PlanningCancellation() {
    }

    public static void check() {
        if (Thread.currentThread().isInterrupted()) {
            throw new CancellationException("crafting calculation interrupted");
        }
    }
}
