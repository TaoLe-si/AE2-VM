package com.ae2vm.addon.bench;

import appeng.api.stacks.AEKey;
import appeng.api.stacks.AEKeyType;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
// (v1.10.4+ MC26.1.2) ResourceLocation was renamed to Identifier.
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueOutput;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * String-backed {@link AEKey} so the VM can run offline against the Thunderbolt
 * reference graphs (no Minecraft world / AE2 grid needed). Only the operations the
 * VM actually performs are implemented; serialization helpers throw
 * {@link UnsupportedOperationException} and are never called on the VM path.
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

    // (v1.10.4+ MC26.1.2) AE2 26.1 changed AEKey.toTag(HolderLookup.Provider) → toTag(ValueOutput).
    @Override
    public void toTag(ValueOutput output) {
        throw new UnsupportedOperationException("serialization is not supported by BenchAEKey");
    }

    @Override
    public Object getPrimaryKey() {
        return id;
    }

    @Override
    public Identifier getId() {
        return Identifier.fromNamespaceAndPath("ae2vm", id);
    }

    @Override
    public void writeToPacket(RegistryFriendlyByteBuf data) {
        throw new UnsupportedOperationException("serialization is not supported by BenchAEKey");
    }

    @Override
    protected Component computeDisplayName() {
        return Component.literal(id);
    }

    @Override
    public void addDrops(long amount, List<ItemStack> drops, Level level, BlockPos pos) {
        // no-op: never called on the VM path
    }

    @Override
    public boolean hasComponents() {
        return false;
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
