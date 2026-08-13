package com.ae2vm.addon.api;

/**
 * rv4 shim for AE2 15.x's {@code GenericStack}: a key plus an amount.
 */
public final class GenericStack {

    private final AEKey what;
    private final long amount;

    public GenericStack(AEKey what, long amount) {
        this.what = what;
        this.amount = amount;
    }

    public AEKey what() {
        return what;
    }

    public long amount() {
        return amount;
    }

    public AEKey getWhat() {
        return what;
    }

    public long getAmount() {
        return amount;
    }

    @Override
    public String toString() {
        return amount + "x" + (what == null ? "null" : what.what());
    }
}
