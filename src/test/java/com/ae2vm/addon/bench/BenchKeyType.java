package com.ae2vm.addon.bench;

import appeng.api.stacks.AEKeyType;
import net.minecraft.util.ResourceLocation;

/**
 * {@link AEKeyType} for {@link BenchAEKey}. {@code getAmountPerByte()} inherits the
 * shim default of 8 (matching the byte accounting the VM performs via
 * {@code addStackBytes}).
 * <p>
 * (v9 shim) The v10+ abstract members {@code readFromPacket} / {@code loadKeyFromTag}
 * do not exist on the shim surface — the bench never serializes.
 */
final class BenchKeyType extends AEKeyType {
    BenchKeyType() {
        super(
                new ResourceLocation("ae2vm", "bench"),
                BenchAEKey.class,
                new net.minecraft.util.text.StringTextComponent("bench"));
    }
}
