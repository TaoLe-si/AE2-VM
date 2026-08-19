package com.ae2vm.addon.bench;

import appeng.api.stacks.AEKey;
import appeng.api.stacks.AEKeyType;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/**
 * {@link AEKeyType} for {@link BenchAEKey}. {@link #getAmountPerByte()} inherits the
 * default of 8 (used by the VM's {@code addStackBytes} byte accounting).
 */
final class BenchKeyType extends AEKeyType {
    BenchKeyType() {
        super(
                ResourceLocation.fromNamespaceAndPath("ae2vm", "bench"),
                BenchAEKey.class,
                Component.literal("bench"));
    }

    @Override
    public AEKey readFromPacket(FriendlyByteBuf input) {
        return null;
    }

    @Override
    public AEKey loadKeyFromTag(CompoundTag tag) {
        return null;
    }
}
