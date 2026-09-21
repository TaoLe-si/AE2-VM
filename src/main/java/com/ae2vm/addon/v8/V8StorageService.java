package com.ae2vm.addon.v8;

import com.ae2vm.shim.api.networking.storage.IStorageService;
import appeng.api.storage.IMEMonitor;
import appeng.api.storage.StorageChannel;
import appeng.api.storage.data.IAEItemStack;

/**
 * rv4 版 {@link IStorageService}：rv4 的网格存储缓存 {@code IStorageGrid} 直接
 * {@code extends IStorageMonitorable}，所以 {@code getItemInventory()} 就是网络物品库存；
 * v8 那条 {@code getInventory(channel)} 在 rv4 不存在。
 */
public final class V8StorageService implements IStorageService {

    private final appeng.api.networking.storage.IStorageGrid grid;

    public V8StorageService(appeng.api.networking.storage.IStorageGrid grid) {
        this.grid = grid;
    }

    @Override
    public IMEMonitor<IAEItemStack> getInventory(StorageChannel channel) {
        if (this.grid == null || channel == StorageChannel.FLUIDS) {
            return null;
        }
        return this.grid.getItemInventory();
    }
}
