package com.ae2vm.addon.bench;

import appeng.api.networking.IGrid;
import appeng.api.networking.IGridCache;
import appeng.api.networking.IGridHost;
import appeng.api.networking.IGridNode;
import appeng.api.networking.IMachineSet;
import appeng.api.networking.events.MENetworkEvent;
import appeng.api.networking.storage.IStorageService;
import appeng.api.storage.IStorageChannel;
import appeng.api.storage.IMEMonitor;
import appeng.api.storage.data.IAEStack;
import appeng.api.util.IReadOnlyCollection;

import java.util.Collections;
import java.util.Map;

/**
 * Minimal {@link IGrid} whose storage snapshot comes from a fixed
 * {@code Map<BenchAEKey, Long>}. Lets the VM's {@code realStockOf} (used by the
 * v1.8.22 stock-aware sub-craft aggregation) observe real network stock exactly
 * like in-game, so the "last craft with a fluid + partial stock" boundary can be
 * reproduced offline.
 *
 * <p><b>(v8, 1.16.5)</b> AE2 v8 还没有 v9 的 {@code IGridService} / {@code GridEvent}
 * 抽象：网格能力是 {@code IGridCache}（{@code IGrid#getCache}），事件是
 * {@code MENetworkEvent}。因此这里按 v8 的 {@code IGrid} 签名实现同样的"形状兼容"
 * 桩 —— 存储监视器仍然返回空（字符串 bench key 进不了 {@code IAEItemStack} 通道），
 * 库存仍然通过 API 层的 stock-reader lambda 可达。
 */
public final class FakeBenchGrid extends BenchV8Grid {

    private final Map<BenchAEKey, Long> stock;

    public FakeBenchGrid(Map<BenchAEKey, Long> stock) {
        this.stock = stock;
    }

    @SuppressWarnings("unchecked")
    @Override
    public <C extends IGridCache> C getCache(Class<? extends IGridCache> iface) {
        // 形状兼容：v8 的 IStorageService 是本项目为 VM 内核提供的 v9 面 shim，
        // 不是 AE2 v8 自带的 IStorageGrid，所以这里直接按接口类型返回桩实现。
        if (iface.getName().equals(IStorageService.class.getName())) {
            return (C) (Object) new StorageServiceImpl();
        }
        return null;
    }

    private final class StorageServiceImpl implements IStorageService {
        @Override
        public <T extends IAEStack<T>> IMEMonitor<T> getInventory(IStorageChannel<T> channel) {
            // v8/v9 通用：字符串 bench key 无法进入 IAEStack 通道世界 —— 返回空监视器。
            return null;
        }

        @Override
        public <T extends IAEStack<T>> void postAlterationOfStoredItems(
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
