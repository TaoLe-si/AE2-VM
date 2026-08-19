package com.ae2vm.addon.bench;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import com.ae2vm.addon.compiler.PatternCompiler;
import com.ae2vm.addon.vm.CraftingBytecode;
import com.ae2vm.addon.vm.CraftingVM;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Correct Productive Bees benchmarks, written from the real {@code ItemConversionRecipe}
 * source (JDKDigital/productive-bees, dev-1.21.0).
 *
 * <p><b>Honeycomb special handling.</b> Productive Bees' honeycomb ({@code Honeycomb}
 * item) is a SINGLE item id ({@code productivebees:honeycomb}) carrying a
 * {@code ModDataComponents.BEE_TYPE} component; different bees produce honeycombs with
 * different {@code bee_type} values that are DIFFERENT items. The conversion recipe
 * ({@code ItemConversionRecipe}) encodes its honeycomb input via a
 * {@code ComponentIngredient} (a {@code DataComponentIngredient} subclass) → the input
 * must match the bee_type EXACTLY. When a player encodes this into an AE2 processing
 * pattern, the pattern is AE2's native {@code AEProcessingPattern}, whose
 * {@code IInput.isValid} is {@code input.matches(template[0])} = exact {@code equals}
 * (verified in AE2 source: {@code CraftingCpuHelper.getValidItemTemplates} enumerates
 * {@code findFuzzyTemplates} NBT variants but filters them through {@code isValid}, so
 * only the encoded exact variant passes).
 *
 * <p>The bug this suite guards: the VM's v1.10.x "processing recipe default fuzzy"
 * behaviour treated EVERY processing input (including AE2-native exact ones) as
 * NBT-substitutable, so a recipe needing {@code honeycomb[bee_A]} consumed
 * {@code honeycomb[bee_B]} from stock as its raw material ("使用错误蜜脾当做原料").
 * The fix: AE2-native {@code AEProcessingPattern} inputs are EXACT (registered via
 * {@link PatternCompiler#registerExactProcessingInput}); this suite marks the honeycomb
 * inputs exact exactly the way AE2-native patterns are handled and asserts the VM never
 * cross-substitutes a different bee_type honeycomb.
 *
 * <p><b>Recursion seed keep.</b> Self-adjacent recipes ({@code A+B→2A} amplifier,
 * {@code A+B→A+C} essence catalyst) need a one-time seed that must stay in the network —
 * the loop's own production re-seeds it, so it is NOT a net output (and must not inflate
 * the produced quantity).
 */
public class ProductiveBeesReferenceTest {

    // ---- honeycomb variants (same item id, different bee_type component) ----
    private static final VariantKey HC_A = VariantKey.of("honeycomb", "bee_A");
    private static final VariantKey HC_B = VariantKey.of("honeycomb", "bee_B");
    private static final VariantKey EGG_A = VariantKey.of("spawn_egg", "bee_A");
    private static final VariantKey EGG_B = VariantKey.of("spawn_egg", "bee_B");

    /**
     * ItemConversionRecipe model: spawn egg = 1 × honeycomb[bee_X]. The honeycomb input is
     * EXACT (ComponentIngredient / AE2-native AEProcessingPattern semantics).
     */
    private static IPatternDetails conversion(VariantKey egg, VariantKey honeycomb) {
        return new ConversionPattern(egg, honeycomb);
    }

    /**
     * 核心 bug：请求 bee_A 蛋，网络只有 bee_B 蜜脾 —— VM 必须报缺 bee_A 蜜脾，
     * 绝不能把 bee_B 蜜脾当作 bee_A 的原料消耗。
     */
    @Test
    void honeycombExactMatchRejectsDifferentBeeType() {
        Map<VariantKey, Long> stock = new HashMap<>();
        stock.put(HC_B, 5L); // 只有 bee_B 的蜜脾

        ICraftingPlan plan = run(EGG_A, 5, stock, Map.of(EGG_A, conversion(EGG_A, HC_A)), List.of(HC_A)).plan();

        assertEquals(5L, plan.missingItems().get(HC_A),
                "bee_A egg with only bee_B honeycomb stocked must report bee_A honeycomb missing, missing=" + missing(plan));
        assertEquals(0L, plan.usedItems().get(HC_B),
                "bee_B honeycomb must NOT be consumed for a bee_A egg, used=" + used(plan));
        assertFalse(plan.missingItems().isEmpty(), "plan must be infeasible (wrong-honeycomb guard)");
    }

    /**
     * 两个转换配方各自使用自己的蜜脾：请求 bee_A 蛋只用 bee_A 蜜脾，不碰 bee_B。
     */
    @Test
    void honeycombExactMatchEachVariantUsesOwn() {
        Map<VariantKey, Long> stock = new HashMap<>();
        stock.put(HC_A, 3L);
        stock.put(HC_B, 5L);

        ICraftingPlan plan = run(EGG_A, 3, stock, Map.of(EGG_A, conversion(EGG_A, HC_A)), List.of(HC_A)).plan();
        assertTrue(plan.missingItems().isEmpty(), "3 bee_A eggs with 3 bee_A honeycombs must be feasible, missing=" + missing(plan));
        assertEquals(3L, plan.usedItems().get(HC_A), "used must name bee_A honeycomb, used=" + used(plan));
        assertEquals(0L, plan.usedItems().get(HC_B), "bee_B honeycomb must not be touched, used=" + used(plan));
    }

    /**
     * 完全没有蜜脾变体时，报缺精确的 bee_A 蜜脾。
     */
    @Test
    void honeycombExactMissingWhenNoVariantStocked() {
        ICraftingPlan plan = run(EGG_A, 5, new HashMap<>(), Map.of(EGG_A, conversion(EGG_A, HC_A)), List.of(HC_A)).plan();

        assertEquals(5L, plan.missingItems().get(HC_A),
                "no honeycomb stocked → the exact bee_A honeycomb is missing, missing=" + missing(plan));
    }

    /**
     * 多个转换配方竞争同物品的不同 bee_type 蜜脾，各自精确匹配不串料。
     */
    @Test
    void honeycombExactMatchBothConversions() {
        Map<VariantKey, Long> stock = new HashMap<>();
        stock.put(HC_A, 3L);
        stock.put(HC_B, 5L);
        Map<VariantKey, IPatternDetails> patterns = new HashMap<>();
        patterns.put(EGG_A, conversion(EGG_A, HC_A));
        patterns.put(EGG_B, conversion(EGG_B, HC_B));

        ICraftingPlan planA = run(EGG_A, 3, stock, patterns, List.of(HC_A, HC_B)).plan();
        assertTrue(planA.missingItems().isEmpty(), "3 bee_A eggs feasible, missing=" + missing(planA));
        assertEquals(3L, planA.usedItems().get(HC_A), "used bee_A honeycomb, used=" + used(planA));

        ICraftingPlan planB = run(EGG_B, 5, stock, patterns, List.of(HC_A, HC_B)).plan();
        assertTrue(planB.missingItems().isEmpty(), "5 bee_B eggs feasible, missing=" + missing(planB));
        assertEquals(5L, planB.usedItems().get(HC_B), "used bee_B honeycomb, used=" + used(planB));
    }

    // ---- recursion: seed must stay in the network ----

    /** Essence catalyst A+B→A+C: network must keep the A seed (1), not inflate to n. */
    @Test
    void recursionEssenceKeepsSeed() {
        Map<VariantKey, Long> stock = new HashMap<>();
        stock.put(A, 1L); // seed
        stock.put(B, 8L);
        Map<VariantKey, IPatternDetails> patterns = new HashMap<>();
        patterns.put(C, essencePattern());

        PlanResult result = run(C, 8, stock, patterns, List.of());
        ICraftingPlan plan = result.plan();

        assertTrue(plan.missingItems().isEmpty(), "essence with A=1 seed must be feasible, missing=" + missing(plan));
        assertEquals(8L, plan.usedItems().get(B), "B consumed 8, used=" + used(plan));
        // The catalyst A circulates — its network stock must stay at the seed (net change 0):
        // it is neither consumed from the network (would be −1) nor inflated (+n−1).
        assertEquals(0L, result.sim().netTrack().getOrDefault(A, 0L),
                "essence catalyst A must keep the seed in the network (net change 0), netTrack=" + result.sim().netTrack());
    }

    /** Amplifier A+B→2A: produce exactly the request (no over-production). */
    @Test
    void recursionAmplifierExactOutput() {
        Map<VariantKey, Long> stock = new HashMap<>();
        stock.put(A, 1L); // seed
        stock.put(B, 7L);
        Map<VariantKey, IPatternDetails> patterns = new HashMap<>();
        patterns.put(A, amplifierPattern());

        ICraftingPlan plan = run(A, 8, stock, patterns, List.of()).plan();

        assertTrue(plan.missingItems().isEmpty(), "amplifier with A=1 seed + B=7 must be feasible, missing=" + missing(plan));
        assertEquals(7L, plan.usedItems().get(B), "B consumed 7, used=" + used(plan));
        assertEquals(8L, plan.finalOutput().amount(), "final output must be exactly 8 A");
    }

    // ---- shared keys for the recursion recipes ----
    private static final VariantKey A = VariantKey.of("essence_a", "");
    private static final VariantKey B = VariantKey.of("essence_b", "");
    private static final VariantKey C = VariantKey.of("essence_c", "");

    private static IPatternDetails essencePattern() {
        // A + B -> C (byproduct A)
        return new ConversionPattern(C, 1, List.of(A, B), List.of(new OutputSpec(A, 1)));
    }

    private static IPatternDetails amplifierPattern() {
        // A + B -> 2A
        return new ConversionPattern(A, 2, List.of(A, B), List.of());
    }

    // ---- driver ----

    /**
     * Compile the given patterns (marking {@code exactInputs} as EXACT processing inputs,
     * like AE2-native AEProcessingPattern), seed the sim, run the VM, return the plan
     * plus the simulation it ran against (for post-execution network checks).
     */
    private static PlanResult run(VariantKey target, long amount,
            Map<VariantKey, Long> stock,
            Map<VariantKey, IPatternDetails> patterns,
            List<VariantKey> exactInputs) {
        PatternCompiler.clearCache();
        PatternCompiler.clearFuzzyGroups();
        for (var p : patterns.values()) {
            PatternCompiler.compileIfAbsent(p);
        }
        for (VariantKey k : exactInputs) {
            PatternCompiler.registerExactProcessingInput(k);
        }
        IPatternDetails top = patterns.get(target);
        CraftingBytecode req = PatternCompiler.compileRequest(top, amount);
        CraftingVM vm = new CraftingVM("ae2vm-bench", key -> {
            VariantKey vk = (VariantKey) key;
            return patterns.get(vk);
        });
        VariantSimState sim = new VariantSimState(stock);
        return new PlanResult(vm.execute(req, sim), sim);
    }

    private static Map<String, Long> used(ICraftingPlan p) {
        TreeMap<String, Long> out = new TreeMap<>();
        for (var e : p.usedItems()) {
            out.put(e.getKey().toString(), e.getLongValue());
        }
        return out;
    }

    private static Map<String, Long> missing(ICraftingPlan p) {
        TreeMap<String, Long> out = new TreeMap<>();
        for (var e : p.missingItems()) {
            out.put(e.getKey().toString(), e.getLongValue());
        }
        return out;
    }

    /** A plan plus the simulation it ran against (for post-execution network checks). */
    private record PlanResult(ICraftingPlan plan, VariantSimState sim) {
    }

    /** In-memory simulation state over the same VariantKey stock (fuzzy-parent aware). */
    private static final class VariantSimState extends appeng.crafting.inv.CraftingSimulationState
            implements com.ae2vm.addon.mixin.CraftingSimulationStateAccessor {
        private final Map<VariantKey, Long> stock;
        private final Map<VariantKey, Long> netTrack = new HashMap<>();

        VariantSimState(Map<VariantKey, Long> stock) {
            this.stock = stock;
        }

        /** Net MODULATE change per key (final aggregation result after capture reverts). */
        Map<VariantKey, Long> netTrack() {
            return new HashMap<>(netTrack);
        }

        @Override
        public long extract(appeng.api.stacks.AEKey what, long amount, appeng.api.config.Actionable mode) {
            long got = super.extract(what, amount, mode);
            if (mode == appeng.api.config.Actionable.MODULATE && what instanceof VariantKey k) {
                netTrack.merge(k, -got, Long::sum);
            }
            return got;
        }

        @Override
        public void insert(appeng.api.stacks.AEKey what, long amount, appeng.api.config.Actionable mode) {
            super.insert(what, amount, mode);
            if (mode == appeng.api.config.Actionable.MODULATE && what instanceof VariantKey k) {
                netTrack.merge(k, amount, Long::sum);
            }
        }

        @Override
        protected long simulateExtractParent(AEKey what, long amount) {
            long available = what instanceof VariantKey k ? stock.getOrDefault(k, 0L) : 0L;
            return Math.min(available, amount);
        }

        @Override
        protected Iterable<AEKey> findFuzzyParent(AEKey input) {
            List<AEKey> variants = new ArrayList<>();
            for (VariantKey k : stock.keySet()) {
                if (k.base().equals(input.getPrimaryKey())) {
                    variants.add(k);
                }
            }
            return variants;
        }

        @Override
        public double getBytes() {
            try {
                java.lang.reflect.Field f = appeng.crafting.inv.CraftingSimulationState.class
                        .getDeclaredField("bytes");
                f.setAccessible(true);
                return f.getDouble(this);
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException("Cannot read CraftingSimulationState.bytes", e);
            }
        }
    }

    /** A single byproduct output spec. */
    private static final class OutputSpec {
        final VariantKey key;
        final long amount;

        OutputSpec(VariantKey key, long amount) {
            this.key = key;
            this.amount = amount;
        }
    }

    /** Multi-input processing pattern with exact {@code isValid}. */
    private static final class ConversionPattern implements IPatternDetails {
        private final IInput[] inputs;
        private final GenericStack[] outputs;

        ConversionPattern(VariantKey egg, VariantKey honeycomb) {
            this(egg, 1, List.of(honeycomb), List.of());
        }

        ConversionPattern(VariantKey outputKey, long outputAmount, List<VariantKey> inputKeys,
                List<OutputSpec> byproducts) {
            this.inputs = new IInput[inputKeys.size()];
            for (int i = 0; i < inputKeys.size(); i++) {
                this.inputs[i] = new SingleInput(inputKeys.get(i));
            }
            this.outputs = new GenericStack[byproducts.size() + 1];
            this.outputs[0] = new GenericStack(outputKey, outputAmount);
            for (int i = 0; i < byproducts.size(); i++) {
                this.outputs[i + 1] = new GenericStack(byproducts.get(i).key, byproducts.get(i).amount);
            }
        }

        @Override
        public appeng.api.stacks.AEItemKey getDefinition() {
            return null;
        }

        @Override
        public IInput[] getInputs() {
            return inputs;
        }

        @Override
        public GenericStack[] getOutputs() {
            return outputs;
        }

        private static final class SingleInput implements IInput {
            private final GenericStack[] possible;

            SingleInput(VariantKey key) {
                this.possible = new GenericStack[]{new GenericStack(key, 1)};
            }

            @Override
            public GenericStack[] getPossibleInputs() {
                return possible;
            }

            @Override
            public long getMultiplier() {
                return 1;
            }

            @Override
            public boolean isValid(AEKey input, Level level) {
                return input.equals(possible[0].what());
            }

            @Override
            public AEKey getRemainingKey(AEKey template) {
                return null;
            }
        }
    }
}
