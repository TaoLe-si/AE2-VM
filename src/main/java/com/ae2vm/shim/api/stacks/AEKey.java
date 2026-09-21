package com.ae2vm.shim.api.stacks;

import appeng.api.storage.data.IAEItemStack;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

/**
 * AE2 v9 (1.17.1) compatibility shim for the v10+ {@code com.ae2vm.shim.api.stacks.AEKey} API.
 * <p>
 * AE2 v10 introduced the immutable {@code AEKey} stack-identity model; v9 (1.17.1) still uses
 * the mutable {@code IAEItemStack} (quantity-bearing) model. This shim lets the VM core keep
 * its {@code Map<AEKey, BigInteger>} / {@code KeyCounter} logic unchanged: it wraps the
 * v9 {@code IAEItemStack} (stack size ignored) as an immutable identity key.
 * <p>
 * Identity semantics: v9's {@code AEItemStack.equals/hashCode} are based on the item type
 * (ignoring stack size), matching the v10+ {@code AEKey} identity contract.
 * <p>
 * The abstract surface is the union of what the VM core and the offline bench harness
 * (BenchAEKey / VariantKey) need. Game-side keys extend {@link AEItemKey}; bench keys
 * implement the same contract against string ids.
 */
public abstract class AEKey {

    /**
     * Protected for subclassing (AEItemKey in this package; bench keys elsewhere).
     */
    protected AEKey() {
    }

    /**
     * Returns this key with any secondary components stripped (v10+ API).
     * The base implementation returns {@code this} (v9 has no secondary key system).
     */
    public AEKey dropSecondary() {
        return this;
    }

    /**
     * The mod id that registered this key's item (v10+ API).
     * The base implementation reads the item's registry name; bench keys override.
     */
    public String getModId() {
        return ItemIdentity.modId(getItem());
    }

    /**
     * The "type" of this key (v10+ {@code AEKeyType}). The base implementation returns
     * null (the VM core never calls it on the game path).
     */
    public AEKeyType getType() {
        return null;
    }

    /**
     * Display name for logging / tooltip purposes (v10+ API).
     */
    public net.minecraft.util.IChatComponent getDisplayName() {
        return new net.minecraft.util.ChatComponentText(String.valueOf(this));
    }

    /**
     * The primary identity object of this key (v10+ API; used by KeyCounter sub-indexing).
     * The base implementation is the item; bench keys return their string id.
     */
    public Object getPrimaryKey() {
        return getItem();
    }

    /**
     * NBT serialization (v10+ API). Not needed on the VM path.
     */
    public net.minecraft.nbt.NBTTagCompound toTag() {
        throw new UnsupportedOperationException("serialization is not supported by this AEKey shim");
    }

    /**
     * Network serialization (v10+ API). Not needed on the VM path.
     */
    public void writeToPacket(net.minecraft.network.PacketBuffer data) {
        throw new UnsupportedOperationException("serialization is not supported by this AEKey shim");
    }

    /**
     * Wraps this key into an {@link ItemStack} for display / filter purposes (v10+ API).
     */
    public ItemStack wrapForDisplayOrFilter() {
        return new ItemStack(getItem());
    }

    /**
     * Wraps this key into an {@link ItemStack} with a stack size (v10+ API).
     */
    public ItemStack wrap(int amount) {
        net.minecraft.item.ItemStack s = wrapForDisplayOrFilter();
        s.stackSize = amount;   // rv4/1.10.2 没有 setCount()，数量是 public 字段 stackSize
        return s;
    }

    /**
     * The underlying Minecraft item this key refers to.
     */
    public abstract Item getItem();

    /**
     * Converts this key into a v9 {@code IAEItemStack} with the given stack size,
     * for calling the v9 AE2 API (getCraftingFor / simulation extract / insert).
     */
    public abstract IAEItemStack toStack(long amount);

    @Override
    public abstract String toString();
}
