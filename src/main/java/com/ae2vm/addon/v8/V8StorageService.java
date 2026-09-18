package com.ae2vm.addon.v8;

import com.ae2vm.shim.api.networking.storage.IStorageService;
import appeng.api.storage.IStorageChannel;
import appeng.api.storage.IMEMonitor;
import appeng.api.storage.data.IAEStack;

/**
 * v8-backed {@link IStorageService}: v8's grid storage cache is
 * {@code appeng.api.networking.storage.IStorageGrid}, which extends
 * {@code IStorageMonitorable} and therefore already provides the per-channel inventory the
 * VM needs.
 */
public final class V8StorageService implements IStorageService {

    private final appeng.api.networking.storage.IStorageGrid grid;

    public V8StorageService(appeng.api.networking.storage.IStorageGrid grid) {
        this.grid = grid;
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T extends IAEStack<T>> IMEMonitor<T> getInventory(IStorageChannel<T> channel) {
        if (this.grid == null) {
            return null;
        }
        return (IMEMonitor<T>) this.grid.getInventory(channel);
    }

    @Override
    public <T extends IAEStack<T>> void postAlterationOfStoredItems(IStorageChannel<T> channel,
            Iterable<T> change, appeng.api.networking.security.IActionSource src) {
        // v8 has no grid-level equivalent; cell providers observe changes themselves.
    }

    @Override
    public void registerAdditionalCellProvider(appeng.api.storage.cells.ICellProvider provider) {
        // no-op on v8
    }

    @Override
    public void unregisterAdditionalCellProvider(appeng.api.storage.cells.ICellProvider provider) {
        // no-op on v8
    }
}
