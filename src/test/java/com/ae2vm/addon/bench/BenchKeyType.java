package com.ae2vm.addon.bench;

import appeng.api.stacks.AEKey;
import appeng.api.stacks.AEKeyType;
import com.mojang.serialization.MapCodec;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
// (v1.10.4+ MC26.1.2) ResourceLocation was renamed to Identifier.
import net.minecraft.resources.Identifier;

/**
 * {@link AEKeyType} for {@link BenchAEKey}. {@link #getAmountPerByte()} inherits the
 * default of 8 (used by the VM's {@code addStackBytes} byte accounting).
 */
final class BenchKeyType extends AEKeyType {
    BenchKeyType() {
        super(
                Identifier.fromNamespaceAndPath("ae2vm", "bench"),
                BenchAEKey.class,
                Component.literal("bench"));
    }

    @Override
    public MapCodec<? extends AEKey> codec() {
        return MapCodec.unit(() -> null);
    }

    @Override
    public AEKey readFromPacket(RegistryFriendlyByteBuf input) {
        return null;
    }
}
