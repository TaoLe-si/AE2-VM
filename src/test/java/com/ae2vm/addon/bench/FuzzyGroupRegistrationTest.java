package com.ae2vm.addon.bench;

import appeng.api.crafting.IPatternDetails;
import appeng.api.crafting.IPatternDetails.IInput;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import com.ae2vm.addon.compiler.PatternCompiler;
import com.ae2vm.addon.vm.CraftingBytecode;
import com.ae2vm.addon.vm.CraftingVM;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * v1.9.13: fuzzy / fluid-substitution group registration. The encoded pattern has
 * item-replacement enabled (getPossibleInputs() returns [gray_wool, white_wool]).
 * When onUpdatePatterns registers this as a fuzzy group, the VM's missing-check must
 * see white-wool stock as satisfying the gray-wool slot (no false "missing gray wool"),
 * while a pattern WITHOUT replacement (exact single input) still rejects substitutes.
 */
public class FuzzyGroupRegistrationTest {

    /** IInput whose getPossibleInputs() returns the primary + substitute variants. */
    private static final class VariantInput implements IInput {
        private final GenericStack[] possible;
        private final long multiplier;

        VariantInput(long amount, AEKey primary, AEKey... substitutes) {
            List<GenericStack> stacks = new java.util.ArrayList<>();
            stacks.add(new GenericStack(primary, amount));
            for (AEKey s : substitutes) {
                if (!s.equals(primary)) stacks.add(new GenericStack(s, amount));
            }
            this.possible = stacks.toArray(new GenericStack[0]);
            this.multiplier = 1;
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
                if (input.equals(gs.what())) return true;
            }
            return false;
        }

        @Override
        public AEKey getRemainingKey(AEKey template) {
            return null;
        }
    }

    /** Pattern details whose inputs are the given IInput array. */
    private static final class VarPattern implements IPatternDetails {
        private final IInput[] inputs;
        private final GenericStack output;

        VarPattern(AEKey out, long outAmount, IInput... inputs) {
            this.inputs = inputs;
            this.output = new GenericStack(out, outAmount);
        }

        @Override
        public AEItemKey getDefinition() {
            return null;
        }

        @Override
        public IInput[] getInputs() {
            return inputs;
        }

        @Override
        public GenericStack[] getOutputs() {
            return new GenericStack[] { output };
        }
    }

    private static ICraftingPlan run(AEKey out, Map<BenchAEKey, Long> stock, IInput... inputs) {
        PatternCompiler.clearCache();
        PatternCompiler.clearFuzzyGroups();
        VarPattern pattern = new VarPattern(out, 1, inputs);
        // compileIfAbsent → compilePattern auto-registers the fuzzy groups
        PatternCompiler.compileIfAbsent(pattern);
        CraftingBytecode req = PatternCompiler.compileRequest(pattern, 100);
        CraftingVM vm = new CraftingVM("fuzzy-bench", k -> null);
        return vm.execute(req, new BenchSimulationState(stock));
    }

    private static Map<String, Long> missing(ICraftingPlan p) {
        Map<String, Long> out = new HashMap<>();
        for (var e : p.missingItems()) out.put(((BenchAEKey) e.getKey()).itemId(), e.getLongValue());
        return out;
    }

    @Test
    void fuzzyRegisteredGrayAcceptsWhiteStock() {
        BenchAEKey product = BenchAEKey.of("product");
        BenchAEKey gray = BenchAEKey.of("gray_wool");
        BenchAEKey white = BenchAEKey.of("white_wool");
        Map<BenchAEKey, Long> stock = new HashMap<>();
        stock.put(white, 1000L);

        ICraftingPlan plan = run(product, stock,
                new VariantInput(1, gray, white)); // replacement enabled

        assertTrue(plan.missingItems().isEmpty(),
                "white wool must satisfy the gray-wool fuzzy slot, missing=" + missing(plan));
    }

    @Test
    void unregisteredExactGrayRejectsWhiteStock() {
        BenchAEKey product = BenchAEKey.of("product");
        BenchAEKey gray = BenchAEKey.of("gray_wool");
        BenchAEKey white = BenchAEKey.of("white_wool");
        Map<BenchAEKey, Long> stock = new HashMap<>();
        stock.put(white, 1000L);

        // replacement NOT enabled → single variant only (no fuzzy group)
        ICraftingPlan plan = run(product, stock, new VariantInput(1, gray));

        assertTrue(!plan.missingItems().isEmpty(),
                "without replacement, white wool must NOT satisfy gray-wool slot");
    }

    /** Pattern whose input has a sub-craft (gray is CRAFTABLE via black wool). */
    private static final class TwoLevelPattern implements IPatternDetails {
        private final IInput[] inputs;
        private final GenericStack output;

        TwoLevelPattern(AEKey out, long outAmount, IInput... inputs) {
            this.inputs = inputs;
            this.output = new GenericStack(out, outAmount);
        }

        @Override
        public AEItemKey getDefinition() {
            return null;
        }

        @Override
        public IInput[] getInputs() {
            return inputs;
        }

        @Override
        public GenericStack[] getOutputs() {
            return new GenericStack[] { output };
        }
    }

    /** VarPattern with a resolver so sub-crafts (gray <- black) are found. */
    private static ICraftingPlan run2(Map<AEKey, IPatternDetails> byOutput, AEKey out,
                                      Map<BenchAEKey, Long> stock, IInput... inputs) {
        PatternCompiler.clearCache();
        PatternCompiler.clearFuzzyGroups();
        TwoLevelPattern pattern = new TwoLevelPattern(out, 1, inputs);
        byOutput.put(out, pattern);
        for (IPatternDetails p : byOutput.values()) {
            PatternCompiler.compileIfAbsent(p); // auto-registers fuzzy groups
        }
        CraftingBytecode req = PatternCompiler.compileRequest(pattern, 100);
        CraftingVM vm = new CraftingVM("fuzzy-bench", byOutput::get);
        return vm.execute(req, new BenchSimulationState(stock));
    }

    /**
     * v1.9.12 REGRESSION (the user's "好多原来有合成样板的东西报缺失"): a gray-wool
     * slot that IS craftable (gray <- black) with PARTIAL gray stock. The compile
     * must still schedule the gray sub-craft (v1.9.11 order: CALL_BY_KEY full need
     * BEFORE EXTRACT), so partial stock + crafted deficit is feasible — NOT reported
     * missing just because the 1-craft capture saw stock cover 1 craft.
     */
    @Test
    void craftableGrayWithPartialStockStillSchedulesSubCraft() {
        BenchAEKey product = BenchAEKey.of("product");
        BenchAEKey gray = BenchAEKey.of("gray_wool");
        BenchAEKey white = BenchAEKey.of("white_wool");
        BenchAEKey black = BenchAEKey.of("black_wool");
        // gray is CRAFTABLE: gray <- black x1
        VarPattern grayPattern = new VarPattern(gray, 1, new VariantInput(1, black));
        Map<AEKey, IPatternDetails> byOutput = new HashMap<>();
        byOutput.put(gray, grayPattern);
        // product <- gray (fuzzy: gray, white), replacement enabled
        Map<BenchAEKey, Long> stock = new HashMap<>();
        stock.put(gray, 40L); // partial: 40 of 100 needed
        stock.put(black, 1000L);

        ICraftingPlan plan = run2(byOutput, product, stock, new VariantInput(1, gray, white));

        assertTrue(plan.missingItems().isEmpty(),
                "craftable gray with partial stock must schedule sub-craft (no false missing), missing=" + missing(plan));
    }
}
