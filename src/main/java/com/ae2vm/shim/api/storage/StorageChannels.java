package com.ae2vm.shim.api.storage;

import appeng.api.storage.StorageChannel;

/**
 * v9 {@code StorageChannels} 静态入口的 1.10.2 替身。
 * <p>
 * rv4 没有 {@code IStorageChannel}，也没有 v8 的 {@code appeng.api.storage.channels.*}；
 * 通道是枚举 {@link StorageChannel}{@code .ITEMS/.FLUIDS}，每个自带 {@code createList()}，
 * 但那个方法返回 <b>raw</b> {@code IItemList}。所以本仓要列表的地方一律走
 * {@code AEApi.instance().storage().createItemList()}（带泛型、免 unchecked），
 * 这里只把"哪个通道"这一件事表达清楚。本 mod 只建模物品，故只暴露 ITEMS。
 */
public final class StorageChannels {

    private StorageChannels() {
    }

    public static StorageChannel items() {
        return StorageChannel.ITEMS;
    }
}
