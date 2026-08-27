package com.moakiee.thunderbolt.core.crafting.planner;

/** Pure accounting helpers for normal-network reusable-seed fallbacks. */
public final class ReusableStockFallback {
    /**
     * Returns the seed stock hidden from the ordinary planning snapshot that still needs publishing.
     * A dependency key is already visible as normal stock and must not be counted a second time.
     */
    public static long supplementalSelfSeedStock(
            long required, long seedSnapshotAmount, long ordinaryVisibleAmount) {
        long positiveRequired = Math.max(0L, required);
        long available = Math.min(positiveRequired, Math.max(0L, seedSnapshotAmount));
        long ordinaryVisible = Math.min(
                positiveRequired, Math.max(0L, ordinaryVisibleAmount));
        return Math.max(0L, available - ordinaryVisible);
    }

    private ReusableStockFallback() {
    }
}
