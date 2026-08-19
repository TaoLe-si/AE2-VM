package com.ae2vm.addon.bench;

import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.IGridService;
import appeng.api.networking.events.GridEvent;
import appeng.api.networking.storage.IStorageService;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.api.storage.MEStorage;
import net.minecraft.network.chat.Component;

import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Minimal {@link IGrid} whose storage snapshot comes from a fixed
 * {@code Map<BenchAEKey, Long>}. Lets the VM's {@code realStockOf} (used by the
 * v1.8.22 stock-aware sub-craft aggregation) observe real network stock exactly
 * like in-game, so the "last craft with a fluid + partial stock" boundary can be
 * reproduced offline.
 *
 * <p>NOTE: {@code realStockOf} snapshots the inventory on first call, so unlike
 * the {@code BenchSimulationState} the stock map here is read-only from the VM's
 * perspective for the {@code used} extraction (the VM's sandbox sim tracks its own
 * consumption). To mirror the game where both read the same live inventory, tests
 * should pass the SAME map to the grid and the simulation.
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

    @Override
    public appeng.api.networking.spatial.ISpatialService getSpatialService() {
        return null;
    }

    private final class StorageServiceImpl implements IStorageService {
        @Override
        public MEStorage getInventory() {
            return new StorageImpl();
        }

        @Override
        public KeyCounter getCachedInventory() {
            var out = new KeyCounter();
            stock.forEach((k, v) -> {
                if (v > 0) out.add(k, v);
            });
            return out;
        }

        @Override
        public void addGlobalStorageProvider(appeng.api.storage.IStorageProvider cc) {
        }

        @Override
        public void removeGlobalStorageProvider(appeng.api.storage.IStorageProvider cc) {
        }

        @Override
        public void refreshNodeStorageProvider(IGridNode node) {
        }

        @Override
        public void refreshGlobalStorageProvider(appeng.api.storage.IStorageProvider provider) {
        }

        @Override
        public void invalidateCache() {
        }
    }

    private final class StorageImpl implements MEStorage {
        @Override
        public Component getDescription() {
            return Component.literal("fake-storage");
        }

        @Override
        public long extract(AEKey what, long amount, appeng.api.config.Actionable mode,
                appeng.api.networking.security.IActionSource source) {
            if (!(what instanceof BenchAEKey k)) return 0;
            long avail = stock.getOrDefault(k, 0L);
            long got = Math.min(avail, amount);
            if (mode == appeng.api.config.Actionable.MODULATE) {
                stock.put(k, avail - got);
            }
            return got;
        }

        @Override
        public void getAvailableStacks(KeyCounter out) {
            stock.forEach((k, v) -> {
                if (v > 0) out.add(k, v);
            });
        }
    }
}
