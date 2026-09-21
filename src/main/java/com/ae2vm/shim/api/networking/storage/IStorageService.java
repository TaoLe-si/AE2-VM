package com.ae2vm.shim.api.networking.storage;

import appeng.api.storage.IMEMonitor;
import appeng.api.storage.StorageChannel;
import appeng.api.storage.data.IAEItemStack;

/**
 * v9 {@code IStorageService} 的 1.10.2 替身。
 * <p>
 * rv4 的通道枚举不带泛型参数（{@code StorageChannel.ITEMS/.FLUIDS}），而本 mod 只建模物品，
 * 所以丢掉 v9 的 {@code <T>} 形参、直接返回物品监视器 —— 四个调用方（{@code CraftingVM} 1 处、
 * {@code AE2VMCrafting} 2 处、{@code RealtimeNetworkCraftingSimulationState} 1 处）
 * 本来就全部按 {@code IMEMonitor<IAEItemStack>} 接。
 * <p>
 * 原先为对齐 v9 面而写的 {@code postAlterationOfStoredItems} 与
 * register/unregisterAdditionalCellProvider 三个方法在本仓<b>零调用方</b>，已删
 * （rv4 的 {@code IStorageGrid} 上确有同名方法，但没有任何代码走它）。
 */
public interface IStorageService {

    /** 该通道的网络库存；{@code null} 表示没有网格或该通道不建模。 */
    IMEMonitor<IAEItemStack> getInventory(StorageChannel channel);
}
