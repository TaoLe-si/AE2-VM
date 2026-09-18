package appeng.api.stacks;

import appeng.api.storage.data.IAEItemStack;

/**
 * AE2 v8/v9 (1.16.5 / 1.17.1) compatibility shim for the v10+
 * {@code appeng.api.stacks.GenericStack} record: an immutable ({@code what}, {@code amount})
 * pair.
 * <p>
 * v10+ declares it as a {@code record}; Java 8 (required by MC 1.16.5) has no records, so
 * this is the equivalent immutable value class with the same accessor names.
 * <p>
 * v9's equivalent is a plain {@code IAEItemStack} whose stack size carries the amount;
 * use {@link #toStack()} to convert this pair back into a v9/v8 stack for AE2 API calls,
 * and {@link #wrap(IAEItemStack)} to create it from one.
 */
public final class GenericStack {

    private final AEKey what;
    private final long amount;

    public GenericStack(AEKey what, long amount) {
        this.what = what;
        this.amount = amount;
    }

    /**
     * Wraps a v8/v9 stack ({@code IAEItemStack}) into a (key, amount) pair.
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

    public AEKey what() {
        return this.what;
    }

    public long amount() {
        return this.amount;
    }

    /**
     * Converts this pair back into a v8/v9 {@code IAEItemStack} carrying the amount.
     */
    public IAEItemStack toStack() {
        return ((AEItemKey) what).toStack(amount);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof GenericStack)) {
            return false;
        }
        GenericStack other = (GenericStack) o;
        return this.amount == other.amount
                && (this.what == null ? other.what == null : this.what.equals(other.what));
    }

    @Override
    public int hashCode() {
        return 31 * (this.what == null ? 0 : this.what.hashCode()) + Long.hashCode(this.amount);
    }

    @Override
    public String toString() {
        return "GenericStack[" + this.what + " x" + this.amount + "]";
    }
}
