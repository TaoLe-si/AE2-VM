package com.ae2vm.addon.bench;

import appeng.api.stacks.AEKey;
import appeng.api.stacks.AEKeyType;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.world.item.Item;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * String-backed {@link AEKey} so the VM can run offline against the Thunderbolt
 * reference graphs (no Minecraft world / AE2 grid needed). Only the operations the
 * VM actually performs are implemented; serialization helpers throw
 * {@link UnsupportedOperationException} and are never called on the VM path.
 * <p>
 * (v9 shim) The AE2 v10 {@code AEKey} abstract surface this class extended in the
 * 1.18.1 fork is re-created by the {@code appeng.api.stacks} shim; the v9-only
 * abstract members ({@link #getItem()} / {@link #toStack(long)}) have no string-key
 * representation and throw.
 */
public final class BenchAEKey extends AEKey {
    private static final Map<String, BenchAEKey> CACHE = new ConcurrentHashMap<>();

    private final String id;
    private final AEKeyType type;

    private BenchAEKey(String id) {
        this.id = id;
        this.type = new BenchKeyType();
    }

    public static BenchAEKey of(String id) {
        return CACHE.computeIfAbsent(id, BenchAEKey::new);
    }

    public String itemId() {
        return id;
    }

    @Override
    public AEKeyType getType() {
        return type;
    }

    @Override
    public AEKey dropSecondary() {
        return this;
    }

    @Override
    public Object getPrimaryKey() {
        return id;
    }

    @Override
    public String getModId() {
        return "ae2vm";
    }

    @Override
    public net.minecraft.network.chat.Component getDisplayName() {
        return new TextComponent(id);
    }

    @Override
    public Item getItem() {
        // String-keyed bench key — no Minecraft item representation.
        throw new UnsupportedOperationException("BenchAEKey has no item representation");
    }

    @Override
    public appeng.api.storage.data.IAEItemStack toStack(long amount) {
        // String-keyed bench key — cannot bridge into the v9 IAEItemStack world.
        throw new UnsupportedOperationException("BenchAEKey has no IAEItemStack representation");
    }

    @Override
    public net.minecraft.nbt.CompoundTag toTag() {
        throw new UnsupportedOperationException("serialization is not supported by BenchAEKey");
    }

    @Override
    public void writeToPacket(net.minecraft.network.FriendlyByteBuf data) {
        throw new UnsupportedOperationException("serialization is not supported by BenchAEKey");
    }

    @Override
    public net.minecraft.world.item.ItemStack wrapForDisplayOrFilter() {
        throw new UnsupportedOperationException("display/filter wrapping is not supported by BenchAEKey");
    }

    @Override
    public net.minecraft.world.item.ItemStack wrap(int amount) {
        throw new UnsupportedOperationException("wrap is not supported by BenchAEKey");
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof BenchAEKey k && k.id.equals(id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return id;
    }
}
