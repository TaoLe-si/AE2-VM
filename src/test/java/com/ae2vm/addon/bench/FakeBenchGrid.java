package com.ae2vm.addon.bench;

import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.IGridService;
import appeng.api.networking.events.GridEvent;
import appeng.api.networking.storage.IStorageService;
import appeng.api.storage.IStorageChannel;
import appeng.api.storage.IMEMonitor;
import appeng.api.storage.data.IAEStack;

import java.util.Map;
import java.util.Set;

/**
 * Minimal {@link IGrid} whose storage snapshot comes from a fixed
 * {@code Map<BenchAEKey, Long>}. Lets the VM's {@code realStockOf} (used by the
 * v1.8.22 stock-aware sub-craft aggregation) observe real network stock exactly
 * like in-game, so the "last craft with a fluid + partial stock" boundary can be
 * reproduced offline.
 *
 * <p>(v9, 1.17.1) The storage bridge is channel-based
 * ({@code IStorageService.getInventory(channel)}); string bench keys cannot
 * produce the {@code IAEItemStack}s that interface requires, so the item-channel
 * monitor returns an EMPTY list — the string-key stock map remains reachable
 * through the API layer's stock-reader lambdas. The grid is retained for
 * {@code getService}/{@code getCraftingService} shape compatibility
 * (compile-only bench, §5).
 */
public final class FakeBenchGrid implements IGrid {

    private final Map<BenchAEKey, Long> stock;

    public FakeBenchGrid(Map<BenchAEKey, Long> stock) {
        this.stock = stock;
    }

    @Override
    public <C extends IGridService> C getService(Class<C> iface) {
        if (iface == IStorageService.class) {
            return iface.cast(new StorageServiceImpl());
        }
        return null;
    }

    @Override
    public <T extends GridEvent> T postEvent(T ev) {
        return ev;
    }

    @Override
    public Iterable<Class<?>> getMachineClasses() {
        return Set.of();
    }

    @Override
    public Iterable<IGridNode> getMachineNodes(Class<?> machineClass) {
        return Set.of();
    }

    @Override
    public <T> Set<T> getMachines(Class<T> machineClass) {
        return Set.of();
    }

    @Override
    public <T> Set<T> getActiveMachines(Class<T> machineClass) {
        return Set.of();
    }

    @Override
    public Iterable<IGridNode> getNodes() {
        return Set.of();
    }

    @Override
    public boolean isEmpty() {
        return true;
    }

    @Override
    public IGridNode getPivot() {
        return null;
    }

    @Override
    public int size() {
        return 0;
    }

    @Override
    public appeng.api.networking.ticking.ITickManager getTickManager() {
        return null;
    }

    @Override
    public appeng.api.networking.energy.IEnergyService getEnergyService() {
        return null;
    }

    @Override
    public appeng.api.networking.crafting.ICraftingService getCraftingService() {
        return null;
    }

    @Override
    public appeng.api.networking.pathing.IPathingService getPathingService() {
        return null;
    }

    private final class StorageServiceImpl implements IStorageService {
        @Override
        public <T extends IAEStack> IMEMonitor<T> getInventory(IStorageChannel<T> channel) {
            // v9: string bench keys cannot enter the IAEStack channel world — empty monitor.
            return null;
        }

        @Override
        public <T extends IAEStack> void postAlterationOfStoredItems(
                IStorageChannel<T> channel, Iterable<T> change,
                appeng.api.networking.security.IActionSource src) {
        }

        @Override
        public void registerAdditionalCellProvider(appeng.api.storage.cells.ICellProvider provider) {
        }

        @Override
        public void unregisterAdditionalCellProvider(appeng.api.storage.cells.ICellProvider provider) {
        }
    }
}
