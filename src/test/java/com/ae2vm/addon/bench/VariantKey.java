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

/**
 * An {@link AEKey} that models an item with NBT variants: several {@link VariantKey}s
 * sharing the same {@code base} (the primary key — e.g. the item) but differing by a
 * {@code variant} discriminator (e.g. NBT/damage). Two variants are fuzzy-related
 * ({@code findFuzzy(base, IGNORE_ALL)} returns both), which is exactly the AE2
 * {@code AEItemKey.getPrimaryKey() == stack.getItem()} semantic used to test the
 * v1.10.x processing-recipe default-fuzzy fix (GTL greenhouse block / MA essence).
 *
 * <p>Only the operations the VM / simulation actually perform are implemented;
 * serialization helpers throw {@link UnsupportedOperationException}.
 */
public final class VariantKey extends AEKey {
    private final String base;
    private final String variant;
    private final AEKeyType type;

    private VariantKey(String base, String variant) {
        this.base = base.intern(); // reference identity used by KeyCounter's primary-key map
        this.variant = variant;
        this.type = new BenchKeyType();
    }

    public static VariantKey of(String base, String variant) {
        return new VariantKey(base, variant);
    }

    public String base() {
        return base;
    }

    public String variant() {
        return variant;
    }

    @Override
    public AEKeyType getType() {
        return type;
    }

    @Override
    public AEKey dropSecondary() {
        return new VariantKey(base, "");
    }

    // (v1.10.4+ MC26.1.2) AE2 26.1 changed AEKey.toTag(HolderLookup.Provider) → toTag(ValueOutput).
    @Override
    public void toTag(ValueOutput output) {
        throw new UnsupportedOperationException("serialization is not supported by VariantKey");
    }

    @Override
    public Object getPrimaryKey() {
        return base;
    }

    @Override
    public Identifier getId() {
        return Identifier.fromNamespaceAndPath("ae2vm", base);
    }

    @Override
    public void writeToPacket(RegistryFriendlyByteBuf data) {
        throw new UnsupportedOperationException("serialization is not supported by VariantKey");
    }

    @Override
    protected Component computeDisplayName() {
        return Component.literal(base + (variant.isEmpty() ? "" : "[" + variant + "]"));
    }

    @Override
    public void addDrops(long amount, List<ItemStack> drops, Level level, BlockPos pos) {
        // no-op: never called on the VM path
    }

    @Override
    public boolean hasComponents() {
        return !variant.isEmpty();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof VariantKey k && k.base.equals(base) && k.variant.equals(variant);
    }

    @Override
    public int hashCode() {
        return base.hashCode() * 31 + variant.hashCode();
    }

    @Override
    public String toString() {
        return base + (variant.isEmpty() ? "" : "[" + variant + "]");
    }
}
