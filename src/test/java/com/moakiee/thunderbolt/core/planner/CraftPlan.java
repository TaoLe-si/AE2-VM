package com.moakiee.thunderbolt.core.planner;

import java.util.Map;
import java.util.Objects;

/**
 * Result of the reference planner.
 *
 * @param <K> item key type
 */
public final class CraftPlan<K> {

    private final boolean supported;
    private final boolean feasible;
    private final Map<CraftPattern<K>, Long> firings;
    private final Map<K, Long> usedStock;
    private final Map<ReusableStockUsageKey<K>, Long> usedReusableStock;
    private final Map<K, Long> missing;
    private final Map<K, Long> grossDemand;
    private final int itemsProcessed;
    private final boolean budgetExhausted;

    public CraftPlan(boolean supported,
                     boolean feasible,
                     Map<CraftPattern<K>, Long> firings,
                     Map<K, Long> usedStock,
                     Map<ReusableStockUsageKey<K>, Long> usedReusableStock,
                     Map<K, Long> missing,
                     Map<K, Long> grossDemand,
                     int itemsProcessed,
                     boolean budgetExhausted) {
        this.supported = supported;
        this.feasible = feasible;
        this.firings = firings;
        this.usedStock = usedStock;
        this.usedReusableStock = usedReusableStock;
        this.missing = missing;
        this.grossDemand = grossDemand;
        this.itemsProcessed = itemsProcessed;
        this.budgetExhausted = budgetExhausted;
    }

    public boolean supported() {
        return this.supported;
    }

    public boolean feasible() {
        return this.feasible;
    }

    public Map<CraftPattern<K>, Long> firings() {
        return this.firings;
    }

    public Map<K, Long> usedStock() {
        return this.usedStock;
    }

    public Map<ReusableStockUsageKey<K>, Long> usedReusableStock() {
        return this.usedReusableStock;
    }

    public Map<K, Long> missing() {
        return this.missing;
    }

    public Map<K, Long> grossDemand() {
        return this.grossDemand;
    }

    public int itemsProcessed() {
        return this.itemsProcessed;
    }

    public boolean budgetExhausted() {
        return this.budgetExhausted;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof CraftPlan)) {
            return false;
        }
        CraftPlan<?> other = (CraftPlan<?>) o;
        return this.supported == other.supported
                && this.feasible == other.feasible
                && this.itemsProcessed == other.itemsProcessed
                && this.budgetExhausted == other.budgetExhausted
                && Objects.equals(this.firings, other.firings)
                && Objects.equals(this.usedStock, other.usedStock)
                && Objects.equals(this.usedReusableStock, other.usedReusableStock)
                && Objects.equals(this.missing, other.missing)
                && Objects.equals(this.grossDemand, other.grossDemand);
    }

    @Override
    public int hashCode() {
        return Objects.hash(this.supported, this.feasible, this.firings, this.usedStock,
                this.usedReusableStock, this.missing, this.grossDemand, this.itemsProcessed,
                this.budgetExhausted);
    }

    @Override
    public String toString() {
        return "CraftPlan[supported=" + this.supported
                + ", feasible=" + this.feasible
                + ", firings=" + this.firings
                + ", usedStock=" + this.usedStock
                + ", usedReusableStock=" + this.usedReusableStock
                + ", missing=" + this.missing
                + ", grossDemand=" + this.grossDemand
                + ", itemsProcessed=" + this.itemsProcessed
                + ", budgetExhausted=" + this.budgetExhausted + "]";
    }
}
