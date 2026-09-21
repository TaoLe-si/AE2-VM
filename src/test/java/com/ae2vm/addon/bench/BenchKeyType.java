package com.ae2vm.addon.bench;

import appeng.api.stacks.AEKey;
import appeng.api.stacks.AEKeyType;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.resources.ResourceLocation;

/**
 * {@link AEKeyType} for {@link BenchAEKey}. {@link #getAmountPerByte()} inherits the
 * default of 8 (used by the VM's {@code addStackBytes} byte accounting).
 */
final class BenchKeyType extends AEKeyType {
    // AE2 10.0.1 的 AEKeyType.<init> 末尾会调 setRegistryName -> ForgeRegistryEntry.checkRegistryName
    // -> GameData/ResourceKey 静态初始化（11.7.4 起该调用被移除，所以只有这一版需要）。
    // 纯 JUnit JVM 里没有 FML 做这件事，必须先引导 Minecraft，否则整个 bench 死于 ExceptionInInitializerError。
    static {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    BenchKeyType() {
        super(
                new ResourceLocation("ae2vm", "bench"),
                BenchAEKey.class,
                new TextComponent("bench"));
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
