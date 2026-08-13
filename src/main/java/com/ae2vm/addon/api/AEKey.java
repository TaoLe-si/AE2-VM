package com.ae2vm.addon.api;

import appeng.api.AEApi;
import appeng.api.storage.data.IAEFluidStack;
import appeng.api.storage.data.IAEItemStack;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidStack;

/**
 * rv4 (MC 1.10.2) shim for AE2 15.x's {@code AEKey}.
 *
 * <p>Wraps either an {@link IAEItemStack} (item key) or an {@link IAEFluidStack}
 * (fluid key). Equality follows rv4's own {@code IAEItemStack.equals()} /
 * {@code IAEFluidStack.equals()} semantics — item + damage + NBT (or fluid
 * identity), <em>ignoring stack size</em> — which is exactly the "key" semantics
 * the VM relies on for map lookups and pattern matching.
 */
public final class AEKey {

    private final IAEItemStack item;
    private final IAEFluidStack fluid;

    private AEKey(IAEItemStack item, IAEFluidStack fluid) {
        this.item = item;
        this.fluid = fluid;
    }

    public static AEKey of(IAEItemStack stack) {
        return new AEKey(stack, null);
    }

    public static AEKey of(IAEFluidStack stack) {
        return new AEKey(null, stack);
    }

    public static AEKey ofItem(Item item) {
        return new AEKey(AEApi.instance().storage().createItemStack(new ItemStack(item, 1)), null);
    }

    public static AEKey ofFluid(Fluid fluid) {
        return new AEKey(null, AEApi.instance().storage().createFluidStack(new FluidStack(fluid, 1)));
    }

    public boolean isItem() {
        return item != null;
    }

    public IAEItemStack getItemStack() {
        return item;
    }

    public IAEFluidStack getFluidStack() {
        return fluid;
    }

    public Item getItem() {
        return item != null ? item.getItem() : null;
    }

    public Fluid getFluid() {
        return fluid != null ? fluid.getFluid() : null;
    }

    /** Registry id string, e.g. {@code "minecraft:iron_ingot"} or {@code "water"}. */
    public String getId() {
        if (item != null) {
            net.minecraft.util.ResourceLocation rl = Item.REGISTRY.getNameForObject(item.getItem());
            return rl != null ? rl.toString() : String.valueOf(item.getItem().getRegistryName());
        }
        if (fluid != null) {
            return net.minecraftforge.fluids.FluidRegistry.getFluidName(fluid.getFluid());
        }
        return null;
    }

    /** Display / id string (rv4 has no separate "what()"). */
    public String what() {
        return getId();
    }

    /** Drop the NBT "secondary" — the same item with no tag (fluids are unaffected). */
    public AEKey dropSecondary() {
        if (item != null) {
            if (item.getTagCompound() == null || item.getTagCompound().hasNoTags()) {
                return this;
            }
            IAEItemStack copy = item.copy();
            copy.setTagCompound(null);
            return AEKey.of(copy);
        }
        return this;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof AEKey)) {
            return false;
        }
        AEKey k = (AEKey) o;
        if (item != null) {
            return k.item != null && item.equals(k.item);
        }
        return k.fluid != null && fluid != null && fluid.equals(k.fluid);
    }

    @Override
    public int hashCode() {
        if (item != null) {
            return item.hashCode();
        }
        return fluid != null ? fluid.hashCode() : 0;
    }

    @Override
    public String toString() {
        return what();
    }
}
