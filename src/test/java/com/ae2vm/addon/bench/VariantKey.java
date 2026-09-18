package com.ae2vm.addon.bench;

import com.ae2vm.shim.api.stacks.AEKey;
import com.ae2vm.shim.api.stacks.AEKeyType;
import net.minecraft.util.text.StringTextComponent;
import net.minecraft.item.Item;

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

    @Override
    public Object getPrimaryKey() {
        return base;
    }

    @Override
    public String getModId() {
        return "ae2vm";
    }

    @Override
    public net.minecraft.util.text.ITextComponent getDisplayName() {
        return new StringTextComponent(base + (variant.isEmpty() ? "" : "[" + variant + "]"));
    }

    @Override
    public Item getItem() {
        // String-keyed bench key — no Minecraft item representation.
        throw new UnsupportedOperationException("VariantKey has no item representation");
    }

    @Override
    public appeng.api.storage.data.IAEItemStack toStack(long amount) {
        // String-keyed bench key — cannot bridge into the v9 IAEItemStack world.
        throw new UnsupportedOperationException("VariantKey has no IAEItemStack representation");
    }

    @Override
    public net.minecraft.nbt.CompoundNBT toTag() {
        throw new UnsupportedOperationException("serialization is not supported by VariantKey");
    }

    @Override
    public void writeToPacket(net.minecraft.network.PacketBuffer data) {
        throw new UnsupportedOperationException("serialization is not supported by VariantKey");
    }

    @Override
    public net.minecraft.item.ItemStack wrapForDisplayOrFilter() {
        throw new UnsupportedOperationException("display/filter wrapping is not supported by VariantKey");
    }

    @Override
    public net.minecraft.item.ItemStack wrap(int amount) {
        throw new UnsupportedOperationException("wrap is not supported by VariantKey");
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
