package com.ae2vm.addon.bench;

import com.ae2vm.shim.api.stacks.AEKey;

import java.util.Map;

/**
 * Minimal {@link appeng.api.networking.IGrid} whose storage snapshot comes from a fixed
 * {@code Map<AEKey, Long>}. Lets the VM's {@code realStockOf} (used by the
 * v1.8.22 stock-aware sub-craft aggregation) observe real network stock exactly
 * like in-game, so the "last craft with a fluid + partial stock" boundary can be
 * reproduced offline.
 *
 * <p>NOTE: {@code realStockOf} snapshots the inventory once per {@code execute()}, so
 * the stock map is read-only from the VM's {@code used} accounting (the sandbox sim
 * tracks its own consumption). To mirror the game — where both read the same live
 * inventory — tests pass the SAME map to the grid and the simulation.
 *
 * <p><b>(v8, 1.16.1)</b> AE2 v8 没有 v9 的 {@code IGridService}：网格能力是
 * {@code IGridCache}。库存快照的接线见 {@link BenchV8Grid#getCache}。
 */
public final class FakeBenchGrid extends BenchV8Grid {

    private final Map<AEKey, Long> stock;

    public FakeBenchGrid(Map<AEKey, Long> stock) {
        this.stock = stock;
    }

    @Override
    protected Map<AEKey, Long> benchStock() {
        return stock;
    }
}
