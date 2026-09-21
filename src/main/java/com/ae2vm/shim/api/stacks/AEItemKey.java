package com.ae2vm.shim.api.stacks;

import appeng.api.storage.data.IAEItemStack;
import appeng.util.item.AEItemStack;
import java.util.Objects;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

/**
 * AE2 v9 (1.17.1) compatibility shim for the v10+ {@code com.ae2vm.shim.api.stacks.AEItemKey} API.
 * <p>
 * Wraps a v9 {@code IAEItemStack} (stack size ignored) as an immutable identity key.
 * {@code AEItemStack.equals/hashCode} are type-based (item + damage + NBT, stack size
 * ignored), which exactly matches the v10+ {@code AEItemKey} identity contract, so this
 * class is safe to use as a HashMap key.
 */
public final class AEItemKey extends AEKey {

    /**
     * The wrapped identity template. Stack size is meaningless here (equals/hashCode
     * ignore it); it is only a carrier of the item type definition.
     */
    private final IAEItemStack template;

    AEItemKey(IAEItemStack template) {
        this.template = Objects.requireNonNull(template, "template");
    }

    /**
     * Wraps a v9 item stack (any size) into a key. The stack is NOT modified.
     */
    public static AEItemKey of(ItemStack stack) {
        // rv4/1.10.2 的 ItemStack 没有 isEmpty()（1.12 才加）：空栈就是 null，数量看 stackSize 字段。
        if (stack == null || stack.stackSize <= 0) {
            return null;
        }
        // rv4 那边的工厂方法叫 create()。
        return new AEItemKey(AEItemStack.create(stack));
    }

    /**
     * Wraps a bare item (no NBT) into a key.
     */
    public static AEItemKey of(Item item) {
        if (item == null) {
            return null;
        }
        return of(new ItemStack(item));
    }

    /**
     * Wraps a v9 {@code IAEItemStack} (any size) into a key.
     */
    public static AEItemKey wrap(IAEItemStack stack) {
        if (stack == null || !stack.isMeaningful()) {
            return null;
        }
        return new AEItemKey(stack);
    }

    @Override
    public Item getItem() {
        return template.getItem();
    }

    /**
     * The wrapped identity template (do not mutate).
     */
    public IAEItemStack getTemplate() {
        return template;
    }

    @Override
    public IAEItemStack toStack(long amount) {
        appeng.api.storage.data.IAEItemStack copy = template.copy();
        copy.setStackSize(amount);
        return copy;
    }

    /**
     * Identity is the wrapped stack's type (item + damage + NBT; size ignored).
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        AEItemKey other = (AEItemKey) o;
        return template.equals(other.template);
    }

    @Override
    public int hashCode() {
        return template.hashCode();
    }

    @Override
    public String toString() {
        return ItemIdentity.name(template.getItem());
    }
}
