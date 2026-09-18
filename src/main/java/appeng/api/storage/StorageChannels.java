package appeng.api.storage;

import appeng.api.storage.channels.IItemStorageChannel;
import appeng.core.Api;

/**
 * AE2 v8 (1.16.5) shim of the v9 {@code appeng.api.storage.StorageChannels}.
 * <p>
 * v9 exposes the channels as static accessors; v8 (and older) reach them through
 * {@code Api.instance().storage().getStorageChannel(Class)}. The VM is item-only, so only
 * the item channel is surfaced.
 */
public final class StorageChannels {

    private StorageChannels() {
    }

    public static IItemStorageChannel items() {
        return Api.instance().storage().getStorageChannel(IItemStorageChannel.class);
    }
}
