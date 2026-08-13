package com.ae2vm.addon.api;

/**
 * rv4 shim for AE2 15.x's {@code IPatternDetails}.
 *
 * <p>AE2 rv4's {@code appeng.api.networking.crafting.ICraftingPatternDetails} is the
 * source of truth; {@link Rv4PatternDetails} adapts it to this interface so the VM and
 * compiler (ported from the 1.20.1 code) keep their modern shape unchanged.
 */
public interface IPatternDetails {

    GenericStack getPrimaryOutput();

    IInput[] getInputs();

    GenericStack[] getOutputs();

    /** Shim for {@code IPatternDetails.IInput}. */
    interface IInput {

        GenericStack[] getPossibleInputs();

        long getMultiplier();

        /**
         * rv4 has no "remaining key" (catalyst/container) concept — always {@code null}.
         * The catalyst/durability opcodes therefore never fire on rv4; such inputs are
         * treated as normal consumed inputs.
         */
        GenericStack getRemainingKey(AEKey key);
    }
}
