package com.ae2vm.addon.bench;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import com.moakiee.thunderbolt.core.planner.CraftPattern;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * {@link IPatternDetails} backed by a plain recipe line, so the VM's
 * {@code PatternCompiler} can compile reference graphs without AE2 encoded
 * patterns or Minecraft items.
 *
 * <p>The first output is the primary output (what the pattern is used to craft);
 * the rest are byproducts. The VM inserts every output into the simulation, which
 * mirrors AE2's handling of byproducts. {@link #sourcePattern} keeps a link back
 * to the Thunderbolt {@link CraftPattern} so plan {@code firings} can be mapped
 * back to the reference graph.
 */
public final class BenchPatternDetails implements IPatternDetails {

    private final IInput[] inputs;
    private final List<GenericStack> outputs;
    private final CraftPattern<String> sourcePattern;

    public BenchPatternDetails(BenchAEKey output, long outputAmount, List<InputSpec> inputSpecs) {
        this(output, outputAmount, inputSpecs, List.of(), null);
    }

    public BenchPatternDetails(
            BenchAEKey output,
            long outputAmount,
            List<InputSpec> inputSpecs,
            List<OutputSpec> byproducts,
            CraftPattern<String> sourcePattern) {
        this.outputs = new ArrayList<>(byproducts.size() + 1);
        this.outputs.add(new GenericStack(output, outputAmount));
        for (var b : byproducts) {
            this.outputs.add(new GenericStack(b.key, b.amount));
        }
        this.inputs = new IInput[inputSpecs.size()];
        for (int i = 0; i < inputSpecs.size(); i++) {
            var spec = inputSpecs.get(i);
            this.inputs[i] = new Input(spec.key, spec.amount, spec.multiplier, spec.variants);
        }
        this.sourcePattern = sourcePattern;
    }

    /** The Thunderbolt graph pattern this details object was translated from (may be null). */
    public CraftPattern<String> sourcePattern() {
        return sourcePattern;
    }

    @Override
    public AEItemKey getDefinition() {
        return null; // never called on the VM path
    }

    @Override
    public IInput[] getInputs() {
        return inputs;
    }

    @Override
    public List<GenericStack> getOutputs() {
        return outputs;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof BenchPatternDetails d)) {
            return false;
        }
        return Objects.equals(outputs, d.outputs) && java.util.Arrays.equals(inputs, d.inputs);
    }

    @Override
    public int hashCode() {
        return 31 * Objects.hash(outputs) + java.util.Arrays.hashCode(inputs);
    }

    /** Plain recipe input: key + per-craft amount + optional multiplier. */
    public static final class InputSpec {
        final BenchAEKey key;
        final long amount;
        final long multiplier;
        /** Extra possible-input variants for fuzzy/fluid substitution (exact when empty). */
        final List<BenchAEKey> variants;

        public InputSpec(BenchAEKey key, long amount, long multiplier) {
            this(key, amount, multiplier, List.of());
        }

        public InputSpec(BenchAEKey key, long amount, long multiplier, List<BenchAEKey> variants) {
            this.key = key;
            this.amount = amount;
            this.multiplier = multiplier;
            this.variants = List.copyOf(variants);
        }

        public static InputSpec of(BenchAEKey key, long amount) {
            return new InputSpec(key, amount, 1);
        }

        /** Fuzzy/fluid-substituted input: {@code key} is the primary (encoded) variant. */
        public static InputSpec fuzzy(BenchAEKey key, long amount, BenchAEKey... variants) {
            return new InputSpec(key, amount, 1, List.of(variants));
        }
    }

    /** Plain byproduct output: key + per-craft amount. */
    public static final class OutputSpec {
        final BenchAEKey key;
        final long amount;

        public OutputSpec(BenchAEKey key, long amount) {
            this.key = key;
            this.amount = amount;
        }

        public static OutputSpec of(BenchAEKey key, long amount) {
            return new OutputSpec(key, amount);
        }
    }

    private static final class Input implements IInput {
        private final GenericStack[] possible;
        private final long multiplier;

        Input(BenchAEKey key, long amount, long multiplier) {
            this(key, amount, multiplier, List.of());
        }

        Input(BenchAEKey key, long amount, long multiplier, List<BenchAEKey> variants) {
            List<GenericStack> stacks = new ArrayList<>(variants.size() + 1);
            stacks.add(new GenericStack(key, amount));
            for (BenchAEKey v : variants) {
                if (!v.equals(key)) {
                    stacks.add(new GenericStack(v, amount));
                }
            }
            this.possible = stacks.toArray(new GenericStack[0]);
            this.multiplier = multiplier;
        }

        @Override
        public GenericStack[] getPossibleInputs() {
            return possible;
        }

        @Override
        public long getMultiplier() {
            return multiplier;
        }

        @Override
        public boolean isValid(AEKey input, Level level) {
            for (GenericStack gs : possible) {
                if (input.equals(gs.what())) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public AEKey getRemainingKey(AEKey template) {
            return null;
        }
    }
}

