package appeng.api.stacks;

import appeng.api.storage.data.IAEItemStack;

/**
 * AE2 v9 (1.17.1) compatibility shim for the v10+ {@code appeng.api.stacks.GenericStack}
 * record: an immutable ({@code what}, {@code amount}) pair.
 * <p>
 * v9's equivalent is a plain {@code IAEItemStack} whose stack size carries the amount;
 * use {@link #toStack()} to convert this pair back into a v9 stack for AE2 v9 API calls,
 * and {@link #wrap(IAEItemStack)} to create it from a v9 stack.
 */
public record GenericStack(AEKey what, long amount) {

    /**
     * Wraps a v9 stack ({@code IAEItemStack}) into a (key, amount) pair.
     * Returns {@code null} when the input is null or not meaningful.
     */
    public static GenericStack wrap(IAEItemStack stack) {
        if (stack == null || !stack.isMeaningful()) {
            return null;
        }
        AEItemKey key = AEItemKey.wrap(stack);
        if (key == null) {
            return null;
        }
        return new GenericStack(key, stack.getStackSize());
    }

    /**
     * Converts this pair back into a v9 {@code IAEItemStack} carrying the amount.
     */
    public IAEItemStack toStack() {
        return ((AEItemKey) what).toStack(amount);
    }
}
