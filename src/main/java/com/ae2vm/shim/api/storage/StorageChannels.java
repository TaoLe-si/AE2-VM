package com.ae2vm.shim.api.storage;

import appeng.api.storage.channels.IItemStorageChannel;
import appeng.api.AEApi;

/**
 * AE2 v8 (1.16.5) shim of the v9 {@code com.ae2vm.shim.api.storage.StorageChannels}.
 * <p>
 * v9 exposes the channels as static accessors; uel(1.12.2) 走 {@code AEApi.instance().storage()
 * .getStorageChannel(Class)}（注意 {@code appeng.core.Api} 那个老入口只有 {@code Api.INSTANCE}
 * 字段、没有 {@code instance()}；{@code AEApi.instance()} 才是 uel 提供的现代入口）。item-only：只
 * the item channel is surfaced.
 */
public final class StorageChannels {

    private StorageChannels() {
    }

    public static IItemStorageChannel items() {
        return AEApi.instance().storage().getStorageChannel(IItemStorageChannel.class);
    }
}
