package com.ae2vm.addon.bench;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.stacks.AEItemKey;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reproduces "在长/复杂/多合成替换链中，有概率提示已经有样板的物品缺少物品"：
 * an item VARIANT {@code X[B]} is reported missing even though a CRAFTABLE member
 * of the SAME fuzzy family {@code X[A]} (same base item, different NBT variant)
 * exists — the VM must craft {@code X[A]} to satisfy the {@code X[B]} slot instead
 * of treating {@code X[B]} as an un-craftable leaf.
 *
 * <p>Root cause: the VM's pattern resolution ({@code resolve()}/{@code CALL_BY_KEY})
 * only tries the exact key, {@code dropSecondary()} and the registry item — it NEVER
 * tries the fuzzy family (same base, any variant). A fuzzy/replacement chain can
 * therefore demand a variant that has no pattern of its own while a sibling variant
 * IS craftable → false "missing X[B]".
 */
public class VariantCraftableSubstituteTest {

    // ---- keys ----
    private static final AEKey X_A = VariantKey.of("x_item", "A");
    private static final AEKey X_B = VariantKey.of("x_item", "B");
    private static final AEKey TOP = VariantKey.of("top", "");
    private static final AEKey LEAF1 = VariantKey.of("leaf1", "");
    private static final AEKey LEAF2 = VariantKey.of("leaf2", "");

    /** PX: X[A] = leaf1 — the CRAFTABLE member of the fuzzy family {X[A], X[B]}. */
    private static IPatternDetails craftXA() {
        return new VPattern(X_A, 1, List.of(new ExactInput(LEAF1, 1)));
    }

    /** PTop: top = fuzzy(X[B] accepts X[A]) + leaf2 — the demanding slot. */
    private static IPatternDetails topPattern() {
        return new VPattern(TOP, 1, List.of(
                new FuzzyInput(X_B, 1, X_A),   // possible inputs [X_B, X_A]
                new ExactInput(LEAF2, 1)));
    }

    private static ICraftingPlan run(long amount, Map<AEKey, Long> stock,
                                     Map<AEKey, IPatternDetails> patterns) {
        PatternCompiler.clearCache();
        PatternCompiler.clearFuzzyGroups();
        for (IPatternDetails p : patterns.values()) {
            PatternCompiler.compileIfAbsent(p);
        }
        IPatternDetails top = patterns.get(TOP);
        CraftingBytecode req = PatternCompiler.compileRequest(top, amount);
        CraftingVM vm = new CraftingVM("variant-bench", key -> {
            AEKey vk = (AEKey) key;
            return patterns.get(vk);
        });
        return vm.execute(req, new VariantSimState(stock));
    }

    private static Map<String, Long> missing(ICraftingPlan p) {
        TreeMap<String, Long> out = new TreeMap<>();
        for (var e : BenchCompat.missing(p).entrySet()) {
            out.put(e.getKey().toString(), e.getValue());
        }
        return out;
    }

    /**
     * THE BUG: top needs X[B], but only X[A] (same base item, DIFFERENT variant) is
     * craftable. With NO X[B] stock, the VM must craft X[A] to satisfy the fuzzy slot.
     */
    @Test
    void craftableFuzzyFamilyMemberSatisfiesVariantSlot() {
        Map<AEKey, Long> stock = new HashMap<>();
        stock.put(LEAF1, 1_000_000L);
        stock.put(LEAF2, 1_000_000L);
        Map<AEKey, IPatternDetails> patterns = new HashMap<>();
        patterns.put(X_A, craftXA());
        patterns.put(TOP, topPattern());

        ICraftingPlan plan = run(5, stock, patterns);

        assertEquals(0L, plan.missingItems().size(),
                "X[B] slot must be satisfied by crafting X[A] (fuzzy family), missing=" + missing(plan));
        // the plan must craft X[A]
        boolean craftedXA = false;
        for (var e : plan.patternTimes().entrySet()) {
            for (var gs : ((BenchPatternAccess) e.getKey()).benchOutputs()) {
                if (gs != null && gs.what() != null
                        && VariantKey.variant(gs.what()).equals("A")) {
                    craftedXA = true;
                }
            }
        }
        assertTrue(craftedXA, "plan must craft X[A] to satisfy the X[B] slot, patternTimes=" + plan.patternTimes());
    }

    /** No variant stocked and the fuzzy family member is NOT craftable → X[B] genuinely missing. */
    @Test
    void variantMissingWhenNoFamilyMemberCraftable() {
        Map<AEKey, Long> stock = new HashMap<>();
        stock.put(LEAF2, 1_000_000L);
        Map<AEKey, IPatternDetails> patterns = new HashMap<>();
        patterns.put(TOP, topPattern()); // X[A] NOT craftable

        ICraftingPlan plan = run(5, stock, patterns);

        assertTrue(BenchCompat.missingOf(plan, X_B) > 0,
                "no craftable family member and no stock → X[B] genuinely missing, missing=" + missing(plan));
    }

    // ------------------------------------------------------------------
    // minimal pattern/sim helpers (AEKey-aware)
    // ------------------------------------------------------------------

    /** Single-output pattern with the given IInputs. */
    private static final class VPattern implements IPatternDetails, BenchPatternAccess {
        private final IPatternDetails.IInput[] inputs;
        private final GenericStack[] outputs;

        VPattern(AEKey out, long amount, List<IPatternDetails.IInput> inputList) {
            this.inputs = inputList.toArray(new IPatternDetails.IInput[0]);
            this.outputs = new GenericStack[]{new GenericStack(out, amount)};
        }

        @Override
        public IPatternDetails.IInput[] getInputs() {
            return inputs;
        }

        @Override
        public GenericStack[] benchOutputs() {
            return outputs;
        }
    }

    /** Exact single-variant input. */
    private static final class ExactInput implements IPatternDetails.IInput, BenchInputAccess {
        private final GenericStack[] possible;

        ExactInput(AEKey key, long amount) {
            this.possible = new GenericStack[]{new GenericStack(key, amount)};
        }

        @Override
        public GenericStack[] benchPossibleInputs() {
            return possible;
        }

        @Override
        public long getMultiplier() {
            return 1;
        }

        @Override
        public AEKey benchContainerItem(AEKey template) {
            return null;
        }
    }

    /** Fuzzy input: primary + substitute variants (registers a fuzzy group). */
    private static final class FuzzyInput implements IPatternDetails.IInput, BenchInputAccess {
        private final GenericStack[] possible;

        FuzzyInput(AEKey primary, long amount, AEKey... variants) {
            List<GenericStack> stacks = new ArrayList<>();
            stacks.add(new GenericStack(primary, amount));
            for (AEKey v : variants) {
                if (!v.equals(primary)) {
                    stacks.add(new GenericStack(v, amount));
                }
            }
            this.possible = stacks.toArray(new GenericStack[0]);
        }

        @Override
        public GenericStack[] benchPossibleInputs() {
            return possible;
        }

        @Override
        public long getMultiplier() {
            return 1;
        }

        @Override
        public AEKey benchContainerItem(AEKey template) {
            return null;
        }
    }

    /** Fuzzy-parent aware simulation over the same AEKey stock map. */
    private static final class VariantSimState extends appeng.crafting.inv.CraftingSimulationState
            implements com.ae2vm.addon.mixin.CraftingSimulationStateAccessor {
        private final Map<AEKey, Long> stock;

        VariantSimState(Map<AEKey, Long> stock) {
            this.stock = stock;
        }

        @Override
        protected appeng.api.storage.data.IAEStack simulateExtractParent(appeng.api.storage.data.IAEStack input) {
            appeng.api.stacks.AEKey k = BenchSimulationState.asKey(input);
            Long have = k == null ? null : stock.get(k);
            if (have == null || have.longValue() <= 0L) {
                return null;
            }
            long take = Math.min(input.getStackSize(), have.longValue());
            return take <= 0L ? null : appeng.api.storage.data.IAEStack.copy(input, take);
        }

        @Override
        protected java.util.Collection<appeng.api.storage.data.IAEStack> findFuzzyParent(appeng.api.storage.data.IAEStack input) {
            appeng.api.stacks.AEKey k = BenchSimulationState.asKey(input);
            java.util.List<appeng.api.storage.data.IAEStack> out = new java.util.ArrayList<>();
            if (k == null) {
                return out;
            }
            for (java.util.Map.Entry<appeng.api.stacks.AEKey, Long> e : stock.entrySet()) {
                Long amount = e.getValue();
                if (amount == null || amount.longValue() <= 0L) {
                    continue;
                }
                if (e.getKey() != null && e.getKey().getItem() == k.getItem()) {
                    out.add(e.getKey().toStack(amount.longValue()));
                }
            }
            return out;
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
}
