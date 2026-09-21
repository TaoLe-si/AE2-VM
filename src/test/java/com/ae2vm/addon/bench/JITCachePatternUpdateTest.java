package com.ae2vm.addon.bench;

import com.ae2vm.shim.api.crafting.IPatternDetails;
import com.ae2vm.shim.api.networking.crafting.ICraftingPlan;
import com.ae2vm.shim.api.stacks.AEItemKey;
import com.ae2vm.shim.api.stacks.AEKey;
import com.ae2vm.shim.api.stacks.GenericStack;
import com.ae2vm.addon.compiler.PatternCompiler;
import com.ae2vm.addon.vm.CraftingBytecode;
import com.ae2vm.addon.vm.CraftingVM;
import net.minecraft.world.World;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reproduces: "刚放进样板供应器的样板，在合成规划内没有被算作可合成，
 * 但是单独合成又能计算" — a newly registered pattern is NOT counted as
 * craftable in a complex chain, but CAN be calculated individually.
 *
 * Root cause: CraftingVM's JIT memoization cache (jitFailCache / bundleCache)
 * is NOT cleared when patterns are updated. If a key was tried before its
 * pattern was registered and failed (e.g. network stock was empty), it is
 * cached as "uncraftable" and subsequent pattern registration does not
 * invalidate the cache.
 *
 * Additionally tests: long complex chain with BYPRODUCT (main+secondary
 * outputs) where the byproduct output key might interfere with the main
 * chain resolution.
 */
public class JITCachePatternUpdateTest {

    // ---- keys ----
    private static final AEKey TOP     = VariantKey.of("top",     "");
    private static final AEKey INTER    = VariantKey.of("inter",   "");
    private static final AEKey INTER2   = VariantKey.of("inter2",  "");
    private static final AEKey LEAF    = VariantKey.of("leaf",    "");
    // Byproduct keys
    private static final AEKey SCRAP    = VariantKey.of("scrap",   "");

    // Patterns: TOP → INTER → INTER2 → LEAF (simple chain for JIT cache test)
    private static IPatternDetails pTop() {
        return new VPattern(TOP, 1, J8.list(new ExactInput(INTER, 1)));
    }
    private static IPatternDetails pInter() {
        return new VPattern(INTER, 1, J8.list(new ExactInput(INTER2, 1)));
    }
    private static IPatternDetails pInter2() {
        return new VPattern(INTER2, 1, J8.list(new ExactInput(LEAF, 1)));
    }

    // ---- Scenario 1: JIT fail cache not cleared on pattern update ----
    // STEP 1: Request inter when only top/inter patterns exist (inter has no pattern yet)
    //         → inter is NOT craftable, jitFailCache.add(inter) called
    // STEP 2: Register inter pattern (inter now HAS a pattern)
    // STEP 3: Request top (long chain including inter) again
    //         → VM checks jitFailCache, sees inter was cached as fail
    //         → skips inter even though it now has a pattern
    //         → inter reported as missing in plan (FALSE MISSING)
    // STEP 4: Request inter alone → works (bundleCache hit, not jitFailCache)

    // Without byproduct
    @Test
    void jitCacheNotClearedOnPatternRegistration_WithoutByproduct() {
        Map<AEKey, Long> stock = new HashMap<>();
        stock.put(LEAF, 1000L);

        Map<AEKey, IPatternDetails> patterns = new HashMap<>();
        patterns.put(TOP,    pTop());
        patterns.put(INTER2, pInter2());
        // NOTE: INTER pattern NOT registered yet

        // Create VM once — simulates reusing VM across pattern updates
        CraftingVM vm = makeVM(patterns);

        // STEP 1: compileRequest for top (inter has no pattern)
        PatternCompiler.clearCache();
        PatternCompiler.clearFuzzyGroups();
        for (IPatternDetails p : patterns.values()) PatternCompiler.compileIfAbsent(p);
        CraftingBytecode req = PatternCompiler.compileRequest(patterns.get(TOP), 1);
        ICraftingPlan plan1 = vm.execute(req, new StockSimState(stock));

        // inter is genuinely missing (no pattern yet)
        assertTrue(hasMissing(plan1, INTER),
                "Step 1: INTER has no pattern yet → should be missing, missing=" + missing(plan1));

        // STEP 2: NOW register the inter pattern
        PatternCompiler.clearCache(); // clear pattern bytecode cache
        // BUT: jitFailCache is NOT cleared — this is the bug
        patterns.put(INTER, pInter());
        for (IPatternDetails p : patterns.values()) PatternCompiler.compileIfAbsent(p);

        // STEP 3: Re-request top — inter now HAS a pattern
        // BUG: jitFailCache still contains INTER from step 1
        CraftingBytecode req2 = PatternCompiler.compileRequest(patterns.get(TOP), 1);
        ICraftingPlan plan2 = vm.execute(req2, new StockSimState(stock));

        // BUG: inter is still reported missing even though P_INTER now exists
        assertEquals(0L, plan2.missingItems().size(),
                "INTER now has P_INTER, should NOT be missing. JIT cache is stale. missing="
                + missing(plan2));

        // STEP 4: Request inter alone — works (jitFailCache bypassed by direct resolution)
        CraftingBytecode req3 = PatternCompiler.compileRequest(patterns.get(INTER), 1);
        ICraftingPlan plan3 = vm.execute(req3, new StockSimState(stock));
        assertEquals(0L, plan3.missingItems().size(),
                "Requesting INTER alone should work (jitFailCache bypassed by direct resolve)");
    }

    // ---- Scenario 2: same as above but WITH byproduct in the chain ----
    // The presence of byproduct might cause the jitFailCache entry to
    // affect the main chain differently (or the byproduct output to be
    // incorrectly resolved).

    // TOP: main=TOP, byproduct=SCRAP (1:1)
    // INTER: main=INTER, byproduct=SCRAP
    // INTER2: main=INTER2, byproduct=SCRAP
    // All consume LEAF
    private static IPatternDetails pTopWithByproduct() {
        return new VPattern(TOP, 1, J8.list(new ExactInput(INTER, 1)), J8.list(new GenericStack(SCRAP, 1)));
    }
    private static IPatternDetails pInterWithByproduct() {
        return new VPattern(INTER, 1, J8.list(new ExactInput(INTER2, 1)), J8.list(new GenericStack(SCRAP, 1)));
    }
    private static IPatternDetails pInter2WithByproduct() {
        return new VPattern(INTER2, 1, J8.list(new ExactInput(LEAF, 1)), J8.list(new GenericStack(SCRAP, 1)));
    }

    @Test
    void jitCacheNotClearedOnPatternRegistration_WithByproduct() {
        Map<AEKey, Long> stock = new HashMap<>();
        stock.put(LEAF, 1000L);

        Map<AEKey, IPatternDetails> patterns = new HashMap<>();
        patterns.put(TOP,    pTopWithByproduct());
        patterns.put(INTER2, pInter2WithByproduct());
        // INTER NOT registered yet

        CraftingVM vm = makeVM(patterns);

        // STEP 1: top chain without inter pattern
        PatternCompiler.clearCache();
        PatternCompiler.clearFuzzyGroups();
        for (IPatternDetails p : patterns.values()) PatternCompiler.compileIfAbsent(p);
        CraftingBytecode req1 = PatternCompiler.compileRequest(patterns.get(TOP), 1);
        ICraftingPlan plan1 = vm.execute(req1, new StockSimState(stock));
        assertTrue(hasMissing(plan1, INTER),
                "Step 1: INTER no pattern → missing, missing=" + missing(plan1));

        // STEP 2: Register inter pattern
        PatternCompiler.clearCache();
        patterns.put(INTER, pInterWithByproduct());
        for (IPatternDetails p : patterns.values()) PatternCompiler.compileIfAbsent(p);

        // STEP 3: Re-request top — BUG: jitFailCache still has INTER
        CraftingBytecode req2 = PatternCompiler.compileRequest(patterns.get(TOP), 1);
        ICraftingPlan plan2 = vm.execute(req2, new StockSimState(stock));
        assertEquals(0L, plan2.missingItems().size(),
                "WITH byproduct: INTER now has P_INTER, should NOT be missing. missing="
                + missing(plan2));

        // STEP 4: inter alone still works
        CraftingBytecode req3 = PatternCompiler.compileRequest(patterns.get(INTER), 1);
        ICraftingPlan plan3 = vm.execute(req3, new StockSimState(stock));
        assertEquals(0L, plan3.missingItems().size(),
                "INTER alone should work despite jitFailCache");
    }

    // ---- Scenario 3: bundleCache contamination across pattern updates ----
    // If a key's bundle was captured with a different (older) set of patterns,
    // reusing it without re-capture gives wrong results.

    @Test
    void bundleCacheContaminatedAcrossPatternUpdates() {
        Map<AEKey, Long> stock = new HashMap<>();
        stock.put(LEAF, 1000L);

        // Initially: inter2→leaf chain
        Map<AEKey, IPatternDetails> patterns = new HashMap<>();
        patterns.put(TOP,    pTop());  // top→inter (no inter pattern yet)
        patterns.put(INTER2, pInter2()); // inter2→leaf

        CraftingVM vm = makeVM(patterns);

        PatternCompiler.clearCache();
        PatternCompiler.clearFuzzyGroups();
        for (IPatternDetails p : patterns.values()) PatternCompiler.compileIfAbsent(p);
        CraftingBytecode req1 = PatternCompiler.compileRequest(patterns.get(TOP), 1);
        ICraftingPlan plan1 = vm.execute(req1, new StockSimState(stock));
        assertTrue(hasMissing(plan1, INTER),
                "INTER no pattern → missing, missing=" + missing(plan1));

        // Now add inter pattern
        PatternCompiler.clearCache();
        patterns.put(INTER, pInter());
        for (IPatternDetails p : patterns.values()) PatternCompiler.compileIfAbsent(p);

        // Re-request top with inter now available
        // The bundleCache still has inter as "uncached" (from step 1)
        // But jitFailCache has inter as "failed"
        // Both should be re-evaluated with the new pattern
        CraftingBytecode req2 = PatternCompiler.compileRequest(patterns.get(TOP), 1);
        ICraftingPlan plan2 = vm.execute(req2, new StockSimState(stock));
        assertEquals(0L, plan2.missingItems().size(),
                "After inter pattern added, chain should be fully craftable. missing="
                + missing(plan2));
    }

    // ---------- helpers ----------

    private static CraftingVM makeVM(Map<AEKey, IPatternDetails> patterns) {
        return new CraftingVM("jit-cache-test", key -> {
            AEKey vk = key;
            return patterns.get(vk);
        });
    }

    private static Map<String, Long> missing(ICraftingPlan p) {
        TreeMap<String, Long> out = new TreeMap<>();
        for (java.util.Map.Entry<String, Long> e : BenchCompat.missing(p).entrySet()) {
            out.put(e.getKey().toString(), e.getValue());
        }
        return out;
    }

    private static boolean hasMissing(ICraftingPlan p, AEKey key) {
        for (java.util.Map.Entry<String, Long> e : BenchCompat.missing(p).entrySet()) {
            if (BenchCompat.stringOf(key).equals(e.getKey())) return true;
        }
        return false;
    }

    // ---- minimal pattern/sim helpers ----

    private static final class VPattern implements IPatternDetails, BenchPatternAccess {
        private final IPatternDetails.IInput[] inputs;
        private final GenericStack[] outputs;
        VPattern(AEKey out, long amount, List<IPatternDetails.IInput> inputList) {
            this.inputs = inputList.toArray(new IPatternDetails.IInput[0]);
            this.outputs = new GenericStack[]{new GenericStack(out, amount)};
        }
        VPattern(AEKey out, long amount, List<IPatternDetails.IInput> inputList,
                 List<GenericStack> byproducts) {
            this.inputs = inputList.toArray(new IPatternDetails.IInput[0]);
            List<GenericStack> all = new ArrayList<>();
            all.add(new GenericStack(out, amount));
            all.addAll(byproducts);
            this.outputs = all.toArray(new GenericStack[0]);
        }
        @Override public GenericStack[] benchOutputs() { return outputs; }
        @Override public IPatternDetails.IInput[] getInputs() { return inputs; }
        public AEItemKey getDefinition() { return null; }
    }

    private static final class ExactInput implements IPatternDetails.IInput, BenchInputAccess {
        private final GenericStack[] possible;
        ExactInput(AEKey key, long amount) {
            this.possible = new GenericStack[]{new GenericStack(key, amount)};
        }
        @Override public GenericStack[] benchPossibleInputs() { return possible; }
        @Override public long getMultiplier() { return 1; }
        public boolean isValid(AEKey input, net.minecraft.world.World level) {
            return input.equals(possible[0].what());
        }
        @Override public AEKey benchContainerItem(AEKey template) { return null; }
    }

    private static final class StockSimState extends com.ae2vm.shim.crafting.inv.CraftingSimulationState
            implements com.ae2vm.shim.crafting.inv.CraftingSimulationStateAccessor {
        private final Map<AEKey, Long> stock;
        StockSimState(Map<AEKey, Long> stock) { this.stock = stock; }
@Override
        protected appeng.api.storage.data.IAEStack simulateExtractParent(
                appeng.api.storage.data.IAEStack input) {
            return simulateExtractParent(input, appeng.api.config.Actionable.SIMULATE);
        }

        /**
         * 与 AE2 v15 的 CraftingSimulationState.extract 同语义：沙箱是一份会被抽干的库存，
         * MODULATE 必须扣减（注入入账 + 抽取扣减同时成立，否则同一份库存会被再借一次）。
         */
        @Override
        protected appeng.api.storage.data.IAEStack simulateExtractParent(
                appeng.api.storage.data.IAEStack input, appeng.api.config.Actionable mode) {
            com.ae2vm.shim.api.stacks.AEKey k = asBenchKey(input);
            Long have = k == null ? null : stock.get(k);
            if (have == null || have.longValue() <= 0L) {
                return null;
            }
            long take = Math.min(input.getStackSize(), have.longValue());
            if (take <= 0L) {
                return null;
            }
            if (mode == appeng.api.config.Actionable.MODULATE) {
                stock.put(k, Long.valueOf(have.longValue() - take));
            }
            appeng.api.storage.data.IAEStack got = input.copy();
            got.setStackSize(take);
            return got;
        }

        @Override
        protected java.util.Collection<appeng.api.storage.data.IAEStack> findFuzzyParent(appeng.api.storage.data.IAEStack input) {
            com.ae2vm.shim.api.stacks.AEKey k = asBenchKey(input);
            java.util.List<appeng.api.storage.data.IAEStack> out =
                    new java.util.ArrayList<appeng.api.storage.data.IAEStack>();
            if (k == null) {
                return out;
            }
            for (java.util.Map.Entry<AEKey, Long> e : stock.entrySet()) {
                if (e.getValue() == null || e.getValue().longValue() <= 0L) {
                    continue;
                }
                if (e.getKey() != null && e.getKey().getItem() == k.getItem()) {
                    out.add(e.getKey().toStack(e.getValue().longValue()));
                }
            }
            return out;
        }

        /** IAEStack -> 可作为 stock 键的 AEItemKey（identity = 物品+damage，不含数量）。 */
        private static AEKey asBenchKey(appeng.api.storage.data.IAEStack stack) {
            if (!(stack instanceof appeng.api.storage.data.IAEItemStack)) {
                return null;
            }
            return com.ae2vm.shim.api.stacks.AEItemKey.wrap((appeng.api.storage.data.IAEItemStack) stack);
        }

        @Override
        public double getBytes() {
            try {
                java.lang.reflect.Field f = com.ae2vm.shim.crafting.inv.CraftingSimulationState.class.getDeclaredField("bytes");
                f.setAccessible(true);
                return f.getDouble(this);
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException(e);
            }
        }
    }
}
