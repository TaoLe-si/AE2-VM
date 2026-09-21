package com.ae2vm.shim.api.networking.storage;

import appeng.api.storage.IStorageChannel;
import appeng.api.storage.IMEMonitor;
import appeng.api.storage.data.IAEStack;

/**
 * AE2 v8 (1.16.5) shim of the v9 {@code com.ae2vm.shim.api.networking.storage.IStorageService}.
 * <p>
 * v8 splits this role between {@code IStorageGrid} (grid cache) and
 * {@code IStorageMonitorable} (channel inventory lookup). The VM only needs the
 * per-channel inventory, so this shim exposes exactly that; see
 * {@code com.ae2vm.addon.v8.V8StorageService} for the v8-backed implementation.
 */
public interface IStorageService {

    <T extends IAEStack<T>> IMEMonitor<T> getInventory(IStorageChannel<T> channel);

    /**
     * v9 surface: announce that {@code change} was extracted/injected outside of the
     * monitor. v8 has no equivalent on the grid cache (it lives on
     * {@code IStorageMonitorable}), so implementations may no-op.
     */
    <T extends IAEStack<T>> void postAlterationOfStoredItems(IStorageChannel<T> channel,
            Iterable<T> change, appeng.api.networking.security.IActionSource src);

    /** v9 surface: register an extra cell provider (no-op on v8). */
    void registerAdditionalCellProvider(appeng.api.storage.ICellProvider provider);

    /** v9 surface: unregister an extra cell provider (no-op on v8). */
    void unregisterAdditionalCellProvider(appeng.api.storage.ICellProvider provider);
}
