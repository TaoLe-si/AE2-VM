package com.ae2vm.addon.api;

import appeng.api.networking.crafting.ICraftingPatternDetails;
import appeng.api.storage.data.IAEItemStack;

/**
 * Adapts AE2 rv4's {@link ICraftingPatternDetails} to {@link IPatternDetails}.
 *
 * <p>Wrappers are cached by the resolver so a single {@code ICraftingPatternDetails}
 * instance maps to a single {@code Rv4PatternDetails} instance (identity-stable for
 * use as a map key in {@code patternTimes} and the compile cache).
 */
public final class Rv4PatternDetails implements IPatternDetails {

    private final ICraftingPatternDetails delegate;

    public Rv4PatternDetails(ICraftingPatternDetails delegate) {
        this.delegate = delegate;
    }

    public ICraftingPatternDetails delegate() {
        return delegate;
    }

    @Override
    public GenericStack getPrimaryOutput() {
        // rv4 has no getPrimaryOutput(): the primary output is the first condensed output.
        IAEItemStack[] outs = delegate.getCondensedOutputs();
        IAEItemStack out = (outs == null || outs.length == 0) ? null : outs[0];
        return out == null ? null : new GenericStack(AEKey.of(out), out.getStackSize());
    }

    @Override
    public IInput[] getInputs() {
        // rv4 has no {@code ICraftingPatternDetails.IInput}: {@code getInputs()} returns one
        // {@code IAEItemStack} per encoded slot, each with a single possible input and no
        // multiplier. Each slot is adapted to an {@link IInput} with multiplier 1 and
        // {@code null} remaining key (no catalyst/container concept in rv4).
        IAEItemStack[] ins = delegate.getInputs();
        if (ins == null) {
            return new IInput[0];
        }
        IInput[] out = new IInput[ins.length];
        for (int i = 0; i < ins.length; i++) {
            out[i] = new Rv4Input(ins[i]);
        }
        return out;
    }

    @Override
    public GenericStack[] getOutputs() {
        IAEItemStack[] outs = delegate.getOutputs();
        if (outs == null) {
            return new GenericStack[0];
        }
        GenericStack[] out = new GenericStack[outs.length];
        for (int i = 0; i < outs.length; i++) {
            out[i] = new GenericStack(AEKey.of(outs[i]), outs[i].getStackSize());
        }
        return out;
    }

    private static final class Rv4Input implements IInput {
        private final IAEItemStack delegate;

        Rv4Input(IAEItemStack delegate) {
            this.delegate = delegate;
        }

        @Override
        public GenericStack[] getPossibleInputs() {
            if (delegate == null) {
                return new GenericStack[0];
            }
            return new GenericStack[]{new GenericStack(AEKey.of(delegate), delegate.getStackSize())};
        }

        @Override
        public long getMultiplier() {
            return 1; // rv4 has no multiplier concept; per-craft consumption = stack size
        }

        @Override
        public GenericStack getRemainingKey(AEKey key) {
            return null; // rv4 has no catalyst/container detection
        }
    }

    @Override
    public boolean equals(Object o) {
        return this == o || (o instanceof Rv4PatternDetails
                && ((Rv4PatternDetails) o).delegate == this.delegate);
    }

    @Override
    public int hashCode() {
        return System.identityHashCode(delegate);
    }

    @Override
    public String toString() {
        return String.valueOf(delegate);
    }
}
