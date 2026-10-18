package com.ae2vm.addon.vm;
/** Shared by the runtime insert hook and asynchronous CPU-model tests. */
public final class FeedbackReservation {
    public static long reserve(long incoming,long expected,long pendingInput,long stored) {
        return Math.min(Math.max(0,incoming),Math.min(Math.max(0,expected),Math.max(0,pendingInput-stored)));
    }
    private FeedbackReservation() {}
}
