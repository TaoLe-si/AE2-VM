package com.ae2vm.addon.bench;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Offline stand-in for UselessMod's {@code ScaledProcessingPattern} (the smart-doubling
 * wrapper). It mirrors the in-game class exactly:
 * <ul>
 *   <li>wraps an {@link IPatternDetails} {@code original};</li>
 *   <li>{@code getInputs()} returns {@link ScaledInput}s whose {@code getMultiplier()}
 *       is {@code original.getMultiplier() × operationsPerPush} (possible inputs are the
 *       ORIGINAL variants, unscaled);</li>
 *   <li>{@code getOutputs()} returns every output scaled by {@code operationsPerPush};</li>
 *   <li>{@code getPrimaryOutput()} falls back to the interface default (outputs[0], the
 *       SCALED amount) — exactly like the in-game class, which does NOT override it;</li>
 *   <li>{@code getOriginal()} exposes the wrapped pattern for recursive unwrapping;</li>
 *   <li>{@code equals}/{@code hashCode} are based on (original, operationsPerPush).</li>
 * </ul>
 * Used by {@link ScaledPatternReproTest} to reproduce the "翻倍样板没被识别/编译成字节码"
 * bug class: the VM must unwrap the virtual scaled wrapper and compile the ORIGINAL
 * pattern (so {@code patternTimes} keys are real AE2 patterns and UselessMod's
 * submit-time doubling still applies), instead of compiling the scaled wrapper as if it
 * were a plain pattern.
 */
public final class ScaledBenchPatternDetails implements IPatternDetails {

    private final IPatternDetails original;
    private final long operationsPerPush;
    private final IInput[] inputs;
    private final GenericStack[] outputs;

    public ScaledBenchPatternDetails(IPatternDetails original, long operationsPerPush) {
        if (operationsPerPush <= 0) {
            throw new IllegalArgumentException("operationsPerPush must be positive");
        }
        this.original = Objects.requireNonNull(original, "original");
        this.operationsPerPush = operationsPerPush;
        IInput[] originalInputs = original.getInputs();
        this.inputs = new IInput[originalInputs.length];
        for (int i = 0; i < originalInputs.length; i++) {
            this.inputs[i] = new ScaledInput(originalInputs[i], operationsPerPush);
        }
        GenericStack[] origOutputs = original.getOutputs();
        List<GenericStack> scaledOutputs = new ArrayList<>(origOutputs.length);
        for (GenericStack output : origOutputs) {
            if (output != null) {
                scaledOutputs.add(new GenericStack(
                        output.what(), Math.multiplyExact(output.amount(), operationsPerPush)));
            }
        }
        this.outputs = scaledOutputs.toArray(new GenericStack[0]);
    }

    /** The wrapped (unscaled) pattern. */
    public IPatternDetails getOriginal() {
        return original;
    }

    /** How many original operations one scaled push represents. */
    public long getOperationsPerPush() {
        return operationsPerPush;
    }

    @Override
    public AEItemKey getDefinition() {
        return null; // never called on the VM path
    }

    @Override
    public IInput[] getInputs() {
        return inputs.clone();
    }

    @Override
    public GenericStack[] getOutputs() {
        return outputs;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ScaledBenchPatternDetails s)) {
            return false;
        }
        return operationsPerPush == s.operationsPerPush && original.equals(s.original);
    }

    @Override
    public int hashCode() {
        return 31 * original.hashCode() + Long.hashCode(operationsPerPush);
    }

    @Override
    public String toString() {
        return "ScaledBenchPatternDetails[operationsPerPush=" + operationsPerPush
                + ", original=" + original + ']';
    }

    /** Input view with the multiplier scaled by {@code operationsPerPush}. */
    private record ScaledInput(IInput original, long operationsPerPush) implements IInput {
        @Override
        public GenericStack[] getPossibleInputs() {
            return original.getPossibleInputs();
        }

        @Override
        public long getMultiplier() {
            return Math.multiplyExact(original.getMultiplier(), operationsPerPush);
        }

        @Override
        public boolean isValid(AEKey input, Level level) {
            return original.isValid(input, level);
        }

        @Override
        public AEKey getRemainingKey(AEKey template) {
            return original.getRemainingKey(template);
        }
    }
}
