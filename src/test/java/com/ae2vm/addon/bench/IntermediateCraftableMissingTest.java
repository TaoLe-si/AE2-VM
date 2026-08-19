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
 * Reproduces "在长/复杂/多合成替换链中，有概率提示已经有样板的物品缺少物品，
 * 但实际上可以合成" for the case where the intermediate product has NO replacement variants.
 *
 * This tests three scenarios that could cause a craftable intermediate to be reported missing:
 *
 * SCENARIO A — Exact-slot intermediate depleted stock chain:
 *   PTop(A=5) → PA(B=5) → PB(leaf=5), partial leaf stock → PA bundle deficit
 *   When PA is not self-sufficient (leaf stock depleted during capture), PA is
 *   reported as missing even though P_A exists and should be craftable.
 *
 * SCENARIO B — JIT memoization stale cache:
 *   First execution: partial stock → bundle captured with deficit
 *   Second execution (same VM, stock unchanged): JIT uses cached bundle → deficit still there
 *   Intermediate item incorrectly reported as missing in second execution.
 *
 * SCENARIO C — Long chain with multi-level deficits:
 *   PTop → PA → PB → PC (each level has partial stock), deficit propagates up
 *   and the top-level item is reported missing even though all patterns exist.
 */
public class IntermediateCraftableMissingTest {

    // ---- keys ----
    private static final VariantKey TOP   = VariantKey.of("top",   "");
    private static final VariantKey A     = VariantKey.of("item_a", "");
    private static final VariantKey B     = VariantKey.of("item_b", "");
    private static final VariantKey LEAF  = VariantKey.of("leaf",  "");
    // Scenario D keys: TOP2 → A2 → {B2 (has pattern), C2 (pattern added later)}
    private static final VariantKey A2    = VariantKey.of("item_a2", "");
    private static final VariantKey B2    = VariantKey.of("item_b2", "");
    private static final VariantKey C2    = VariantKey.of("item_c2", "");
    private static final VariantKey B2_LEAF = VariantKey.of("b2_leaf", "");
    private static final VariantKey C2_LEAF = VariantKey.of("c2_leaf", "");

    // PTop: top = A * 5
    private static IPatternDetails pTop() {
        return new VPattern(TOP, 1, List.of(new ExactInput(A, 5)));
    }

    // PA: A = B * 5
    private static IPatternDetails pA() {
        return new VPattern(A, 1, List.of(new ExactInput(B, 5)));
    }

    // PB: B = leaf * 5
    private static IPatternDetails pB() {
        return new VPattern(B, 1, List.of(new ExactInput(LEAF, 5)));
    }

    // ---- Scenario D patterns: TOP2 = A2*1, A2 = B2*1 + C2*1, B2 = B2_LEAF*1, C2 = C2_LEAF*1
    private static IPatternDetails pTop2() {
        return new VPattern(TOP, 1, List.of(new ExactInput(A2, 1)));
    }
    private static IPatternDetails pA2() {
        return new VPattern(A2, 1, List.of(new ExactInput(B2, 1), new ExactInput(C2, 1)));
    }
    private static IPatternDetails pB2() {
        return new VPattern(B2, 1, List.of(new ExactInput(B2_LEAF, 1)));
    }
    private static IPatternDetails pC2() {
        return new VPattern(C2, 1, List.of(new ExactInput(C2_LEAF, 1)));
    }

    // ---- Scenario E (deep chain, real bug shape): D3 = E3*1, E3 = F3*1, F3 = G3*1 + H3*1,
    //      G3 = G3_LEAF*1, H3 = H3_LEAF*1 (H3 pattern added after capture, 4 levels deep)
    private static final VariantKey D3   = VariantKey.of("item_d3", "");
    private static final VariantKey E3   = VariantKey.of("item_e3", "");
    private static final VariantKey F3   = VariantKey.of("item_f3", "");
    private static final VariantKey G3   = VariantKey.of("item_g3", "");
    private static final VariantKey H3   = VariantKey.of("item_h3", "");
    private static final VariantKey G3_LEAF = VariantKey.of("g3_leaf", "");
    private static final VariantKey H3_LEAF = VariantKey.of("h3_leaf", "");

    private static IPatternDetails pD3() {
        return new VPattern(D3, 1, List.of(new ExactInput(E3, 1)));
    }
    private static IPatternDetails pE3() {
        return new VPattern(E3, 1, List.of(new ExactInput(F3, 1)));
    }
    private static IPatternDetails pF3() {
        return new VPattern(F3, 1, List.of(new ExactInput(G3, 1), new ExactInput(H3, 1)));
    }
    private static IPatternDetails pG3() {
        return new VPattern(G3, 1, List.of(new ExactInput(G3_LEAF, 1)));
    }
    private static IPatternDetails pH3() {
        return new VPattern(H3, 1, List.of(new ExactInput(H3_LEAF, 1)));
    }

    // ---------- Scenario A: exact-slot intermediate with depleted stock chain ----------
    // PA's bundle is NOT self-sufficient (leaf stock is insufficient for all 5 PA crafts),
    // so PA is reported as missing even though P_A exists.
    // Expected: PA should NOT appear in missing (P_A exists, PA's deficit is a B shortfall
    // that propagates to TOP, not a "PA has no pattern" issue).
    @Test
    void exactSlotIntermediateNotSelfSufficient() {
        // leaf stock: only enough for 3 crafts (not 5)
        Map<VariantKey, Long> stock = new HashMap<>();
        stock.put(LEAF, 15L); // 15 / 5 = 3 crafts of B

        Map<VariantKey, IPatternDetails> patterns = new HashMap<>();
        patterns.put(TOP, pTop());
        patterns.put(A,   pA());
        patterns.put(B,   pB());

        ICraftingPlan plan = run(TOP, 1, stock, patterns);

        // B has only 3 crafts worth of leaf → leaf is reported as missing
        // A and B are NOT missing (they have patterns, shortfall is in leaf supply)
        assertEquals(0L, countNonLeafMissing(plan),
                "A and B should NOT be missing (they have patterns). missing="
                + missing(plan));
    }

    // ---------- Scenario B: JIT memoization with stale bundle cache ----------
    // First execution with partial stock captures PA's bundle (not self-sufficient).
    // Second execution (same VM, same stock) reuses the cached bundle via JIT.
    // The result should be the same — JIT correctly detects satisfiability.
    @Test
    void jitMemoizationWithStaleBundle() {
        Map<VariantKey, Long> stock = new HashMap<>();
        stock.put(LEAF, 15L); // only 3 crafts of B

        Map<VariantKey, IPatternDetails> patterns = new HashMap<>();
        patterns.put(TOP, pTop());
        patterns.put(A,   pA());
        patterns.put(B,   pB());

        // First execution
        ICraftingPlan plan1 = run(TOP, 1, stock, patterns);

        // Second execution with SAME VM and SAME stock
        // JIT cache: bundles[0] exists, sat1ok should be true (stock unchanged)
        ICraftingPlan plan2 = runSameVM(TOP, 1, stock, patterns);

        // Both should report the same missing
        assertEquals(missing(plan1), missing(plan2),
                "JIT memo with stale bundle should give same result on repeated exec");

        // Only leaf should be missing (insufficient stock), A and B have patterns
        assertEquals(0L, countNonLeafMissing(plan2),
                "Only leaf (insufficient stock) should be missing, not A or B. missing="
                + missing(plan2));
    }

    // ---------- Scenario C: long chain with multi-level partial stock ----------
    // PTop(A=1) → PA(B=1) → PB(leaf=1), partial leaf stock = 7
    // Expected: leaf is insufficient → leaf reported as missing
    // No intermediate with a pattern should appear in missing.
    @Test
    void longChainMultiLevelPartialStock() {
        Map<VariantKey, Long> stock = new HashMap<>();
        stock.put(LEAF, 7L);

        Map<VariantKey, IPatternDetails> patterns = new HashMap<>();
        patterns.put(TOP, pTop());
        patterns.put(A,   pA());
        patterns.put(B,   pB());

        ICraftingPlan plan = run(TOP, 1, stock, patterns);

        // Only leaf (insufficient stock) should be missing
        // A and B have patterns and should NOT appear in missing
        assertEquals(0L, countNonLeafMissing(plan),
                "Only leaf (insufficient stock) should be missing. missing="
                + missing(plan));
    }

    // ---------- Scenario D: pattern ADDED after bundle captured (stale-missing) ----------
    // The exact game bug (melodic_item_conduit → pulsating_powder):
    //   TOP2 → A2 → {B2 (has pattern), C2 (NO pattern at first)}
    // First execution: A2's bundle is captured with missing={C2} (C2 sub=null branch).
    // Player then ADDS C2's pattern — but bumpPatternVersion never fires in this repro
    // (same as the game when the PatternProviderLogicMixin is not applied / missed).
    // Second execution on the SAME VM: the stale A2 bundle (missing={C2}) would be reused
    // forever → C2 stays "missing" even though its pattern now exists.
    // staleMissingRecheck() must detect C2 now has a pattern → re-capture A2's bundle.
    @Test
    void patternAddedAfterBundleCaptured() {
        Map<VariantKey, Long> stock = new HashMap<>();
        stock.put(B2_LEAF, 100L);
        stock.put(C2_LEAF, 100L);

        Map<VariantKey, IPatternDetails> patterns = new HashMap<>();
        patterns.put(TOP, pTop2());
        patterns.put(A2,  pA2());
        patterns.put(B2,  pB2());
        // C2 pattern is intentionally ABSENT on the first run

        // First run: C2 has no pattern → A2's bundle records missing={C2}.
        // Second run: C2 pattern added to the SAME patterns map (same VM reused).
        ICraftingPlan plan2 = runAddPatternReuseVM(TOP, 1, stock, patterns, C2, pC2());

        // C2 now has a pattern → staleMissingRecheck re-captures A2's bundle →
        // C2 is crafted, NOT missing. Only C2_LEAF stock shortage could be missing;
        // with 100 stock it should be NONE.
        assertEquals(0L, countNonLeafMissingExcluding(plan2, C2_LEAF),
                "C2 has a pattern now; re-captured bundle must craft it, not report missing. missing="
                + missing(plan2));
        assertEquals(0L, countMissing(plan2),
                "With full B2_LEAF/C2_LEAF stock and all patterns present, nothing should be missing. missing="
                + missing(plan2));
    }

    // ---------- Scenario E: DEEP chain (4 levels) — the exact real bug shape ----------
    // D3 → E3 → F3 → {G3, H3}, H3 pattern ABSENT on first run.
    // First run: F3's bundle records missing={H3} (direct), E3's bundle has empty
    // missing and references F3 via itemNeeds, D3's bundle references E3.
    // On the second run the VM reuses D3/E3 bundles at the TOP of the chain; the stale
    // H3 missing lives 3 levels deep in F3's bundle. staleMissingRecheck must walk the
    // WHOLE itemNeeds subtree (not just the reused bundle's own missing) to find it —
    // this is the melodic_item_conduit → ... → crystalline_alloy_ingot → pulsating_powder
    // shape from the game logs.
    @Test
    void patternAddedDeepInChainAfterBundleCaptured() {
        Map<VariantKey, Long> stock = new HashMap<>();
        stock.put(G3_LEAF, 100L);
        stock.put(H3_LEAF, 100L);

        Map<VariantKey, IPatternDetails> patterns = new HashMap<>();
        patterns.put(D3, pD3());
        patterns.put(E3, pE3());
        patterns.put(F3, pF3());
        patterns.put(G3, pG3());
        // H3 pattern intentionally ABSENT on first run

        // First run captures D3/E3/F3 bundles with missing={H3} deep inside.
        // Second run: H3 pattern added to the SAME patterns map (same VM reused,
        // version NOT bumped — the game's bumpPatternVersion never fires).
        ICraftingPlan plan2 = runAddPatternReuseVM(D3, 1, stock, patterns, H3, pH3());

        // H3 now has a pattern → recursive staleMissingRecheck re-captures F3 (and
        // propagates up) → H3 is crafted, NOT missing.
        assertEquals(0L, countMissing(plan2),
                "H3 has a pattern now; recursive stale recheck must craft it, not report missing. missing="
                + missing(plan2));
    }

    // ---------- Scenario F: DYNAMIC PATTERN ENCODING at runtime (game-realistic) ----------
    // Simulates the exact game flow for the melodic_item_conduit → pulsating_powder bug:
    //   1) Player requests TOP (D3 chain) while H3 has NO pattern → H3 reported missing.
    //   2) Player writes H3's pattern into a pattern provider at runtime → the pattern is
    //      DYNAMICALLY ENCODED via PatternCompiler.compileIfAbsent (like AE2's
    //      updatePatterns → PatternCompiler.compileIfAbsent in the mixin).
    //   3) Player requests TOP again on the SAME VM.
    // The bug is that the stale H3 missing lives deep in a reused bundle. Two recovery
    // paths must BOTH converge on "H3 is now crafted, not missing":
    //   - bumpVersion=true  → mixin fired → execute() drops bundleCache (version check).
    //   - bumpVersion=false → mixin missed → staleMissingRecheck() re-captures.
    // This test proves the dynamic-encode step is recognized on BOTH paths.
    @Test
    void dynamicEncodePatternBumpVersion() {
        Map<VariantKey, Long> stock = new HashMap<>();
        stock.put(G3_LEAF, 100L);
        stock.put(H3_LEAF, 100L);
        Map<VariantKey, IPatternDetails> patterns = new HashMap<>();
        patterns.put(D3, pD3());
        patterns.put(E3, pE3());
        patterns.put(F3, pF3());
        patterns.put(G3, pG3());
        // H3 pattern absent on first run
        List<ICraftingPlan> plan1Out = new ArrayList<>();

        // Mixin fires → bumpPatternVersion → execute() clears bundleCache → re-capture.
        ICraftingPlan plan = runDynamicEncodeReuseVM(D3, 1, stock, patterns, H3, pH3(), true, plan1Out);

        // Sanity: the FIRST run (before dynamic encoding) really did report H3 missing —
        // proving this test genuinely exercises the "pattern absent → dynamically encoded"
        // transition, not a trivially-feasible graph.
        assertTrue(countMissing(plan1Out.get(0)) > 0,
                "precondition: first run (H3 unencoded) should report H3 missing. missing="
                + missing(plan1Out.get(0)));

        // After dynamic encoding + version bump: H3 must be crafted, not missing.
        assertEquals(0L, countMissing(plan),
                "bumpVersion path: dynamic-encoded H3 must be crafted, not missing. missing="
                + missing(plan));
    }

    @Test
    void dynamicEncodePatternNoBumpVersion() {
        Map<VariantKey, Long> stock = new HashMap<>();
        stock.put(G3_LEAF, 100L);
        stock.put(H3_LEAF, 100L);
        Map<VariantKey, IPatternDetails> patterns = new HashMap<>();
        patterns.put(D3, pD3());
        patterns.put(E3, pE3());
        patterns.put(F3, pF3());
        patterns.put(G3, pG3());
        // H3 pattern absent on first run
        List<ICraftingPlan> plan1Out = new ArrayList<>();

        // Mixin missed → no bump → staleMissingRecheck() must re-capture deep bundle.
        ICraftingPlan plan = runDynamicEncodeReuseVM(D3, 1, stock, patterns, H3, pH3(), false, plan1Out);

        // Sanity: the FIRST run (before dynamic encoding) really did report H3 missing.
        assertTrue(countMissing(plan1Out.get(0)) > 0,
                "precondition: first run (H3 unencoded) should report H3 missing. missing="
                + missing(plan1Out.get(0)));

        // After dynamic encoding (no version bump): staleMissingRecheck must craft H3.
        assertEquals(0L, countMissing(plan),
                "no-bump path: recursive stale recheck must craft dynamic-encoded H3, not missing. missing="
                + missing(plan));
    }

    // ---------- Scenario G: PATTERN ADD/REMOVE/RE-ADD cycle (game-realistic) ----------
    // Mirrors the exact player flow in latest (3).log:
    //   1) H3 pattern PRESENT → chain request → SUCCESS (01:31:35, sub=true).
    //   2) Player REMOVES H3 pattern → chain request → missing (CORRECT — 01:34:15,
    //      sub=null; pattern genuinely gone).
    //   3) Player RE-ADDS H3 pattern (writes it back into the provider, dynamically
    //      encoded) → chain request on the SAME VM → must RECOVER and craft H3.
    // Step 3 is the crux: after the pattern was removed (bundle captured with H3 missing)
    // and re-added, staleMissingRecheck must detect H3 now has a pattern again and
    // re-capture — otherwise the intermediate stays "missing" forever (the "游戏内写
    // 中间产物样板识别不了" report). No version bump (mixin missed) to prove the
    // self-healing path on a reused VM.
    @Test
    void patternRemoveThenReaddRecovers() {
        Map<VariantKey, Long> stock = new HashMap<>();
        stock.put(G3_LEAF, 100L);
        stock.put(H3_LEAF, 100L);
        Map<VariantKey, IPatternDetails> patterns = new HashMap<>();
        patterns.put(D3, pD3());
        patterns.put(E3, pE3());
        patterns.put(F3, pF3());
        patterns.put(G3, pG3());
        patterns.put(H3, pH3()); // H3 present initially

        // One shared VM reused across all three steps — its bundleCache survives.
        CraftingVM vm = new CraftingVM("interm-test", key -> {
            VariantKey vk = (VariantKey) key;
            return patterns.get(vk);
        });

        // Step 1: H3 present → chain feasible (no missing).
        ICraftingPlan plan1 = runPrepared(vm, D3, 1, stock, patterns);
        assertEquals(0L, countMissing(plan1),
                "step1: H3 present → chain must be feasible. missing=" + missing(plan1));

        // Step 2: player REMOVES H3 pattern → chain must report H3 missing (correct).
        patterns.remove(H3);
        ICraftingPlan plan2 = runPrepared(vm, D3, 1, stock, patterns);
        assertTrue(countMissing(plan2) > 0,
                "step2: H3 removed → chain must report missing. missing=" + missing(plan2));

        // Step 3: player RE-ADDS H3 pattern (dynamic encode, NO version bump) → request
        // the chain on the SAME VM. The bundleCache still holds the step2-captured bundle
        // with H3 missing. staleMissingRecheck must re-capture the deep bundle and craft
        // H3 — otherwise H3 stays "missing" (the reported bug).
        patterns.put(H3, pH3());
        PatternCompiler.compileIfAbsent(pH3());
        ICraftingPlan plan3 = runPrepared(vm, D3, 1, stock, patterns);
        assertEquals(0L, countMissing(plan3),
                "step3: H3 re-added (no bump, same VM) → stale recheck must recover, not missing. missing="
                + missing(plan3));
    }

    // ---------- helpers ----------

    /** Runs a request on the GIVEN (possibly reused) VM with the current pattern set. */
    private static ICraftingPlan runPrepared(CraftingVM vm, VariantKey output, long amount,
                                             Map<VariantKey, Long> stock,
                                             Map<VariantKey, IPatternDetails> patterns) {
        PatternCompiler.clearCache();
        PatternCompiler.clearFuzzyGroups();
        for (IPatternDetails p : patterns.values()) {
            PatternCompiler.compileIfAbsent(p);
        }
        CraftingBytecode req = PatternCompiler.compileRequest(patterns.get(output), amount);
        return vm.execute(req, new StockSimState(stock));
    }

    /** Runs on a FRESH VM each call (captures all patterns, no version bump). */
    private static ICraftingPlan runOnVM(VariantKey output, long amount,
                                         Map<VariantKey, Long> stock,
                                         Map<VariantKey, IPatternDetails> patterns) {
        PatternCompiler.clearCache();
        PatternCompiler.clearFuzzyGroups();
        for (IPatternDetails p : patterns.values()) {
            PatternCompiler.compileIfAbsent(p);
        }
        IPatternDetails top = patterns.get(output);
        CraftingBytecode req = PatternCompiler.compileRequest(top, amount);
        CraftingVM vm = new CraftingVM("interm-test", key -> {
            VariantKey vk = (VariantKey) key;
            return patterns.get(vk);
        });
        return vm.execute(req, new StockSimState(stock));
    }

    private static ICraftingPlan run(VariantKey output, long amount,
                                      Map<VariantKey, Long> stock,
                                      Map<VariantKey, IPatternDetails> patterns) {
        PatternCompiler.clearCache();
        PatternCompiler.clearFuzzyGroups();
        for (IPatternDetails p : patterns.values()) {
            PatternCompiler.compileIfAbsent(p);
        }
        IPatternDetails top = patterns.get(output);
        CraftingBytecode req = PatternCompiler.compileRequest(top, amount);
        CraftingVM vm = new CraftingVM("interm-test", key -> {
            VariantKey vk = (VariantKey) key;
            return patterns.get(vk);
        });
        return vm.execute(req, new StockSimState(stock));
    }

    private static ICraftingPlan runSameVM(VariantKey output, long amount,
                                           Map<VariantKey, Long> stock,
                                           Map<VariantKey, IPatternDetails> patterns) {
        // Same VM instance — bundleCache and jitFailCache persist
        PatternCompiler.clearCache();
        PatternCompiler.clearFuzzyGroups();
        for (IPatternDetails p : patterns.values()) {
            PatternCompiler.compileIfAbsent(p);
        }
        IPatternDetails top = patterns.get(output);
        CraftingBytecode req = PatternCompiler.compileRequest(top, amount);
        // Re-use the SAME CraftingVM to test JIT memoization persistence
        CraftingVM vm = new CraftingVM("interm-test", key -> {
            VariantKey vk = (VariantKey) key;
            return patterns.get(vk);
        });
        return vm.execute(req, new StockSimState(stock));
    }

    /**
     * (v1.11.x STALE-MISSING) Runs TWICE on the SAME CraftingVM instance:
     *   run #1 with {@code patterns} as-is (the {@code newKey} pattern ABSENT),
     *   then DYNAMICALLY ENCODES {@code newPattern} for {@code newKey} (simulating the
     *   player writing a new pattern into a pattern provider at runtime →
     *   {@code PatternCompiler.compileIfAbsent}) and runs #2.
     * The pattern version is bumped iff {@code bumpVersion} is true:
     *   - bumpVersion=true  → simulates the PatternProviderLogicMixin.onUpdatePatterns
     *     firing (bumpPatternVersion) → the version check in execute() drops the whole
     *     bundleCache → the new pattern is picked up by re-capture.
     *   - bumpVersion=false → simulates the mixin NOT firing (the observed game bug)
     *     → the VM's bundleCache survives; only staleMissingRecheck() can detect that
     *     the newly-encoded pattern turns a stale missing leaf into a craftable
     *     intermediate.
     * Both paths must converge on the SAME correct result: the new intermediate is
     * crafted, not reported missing.
     */
    private static ICraftingPlan runDynamicEncodeReuseVM(VariantKey output, long amount,
                                                         Map<VariantKey, Long> stock,
                                                         Map<VariantKey, IPatternDetails> patterns,
                                                         VariantKey newKey,
                                                         IPatternDetails newPattern,
                                                         boolean bumpVersion) {
        return runDynamicEncodeReuseVM(output, amount, stock, patterns, newKey, newPattern,
                bumpVersion, null);
    }

    private static ICraftingPlan runDynamicEncodeReuseVM(VariantKey output, long amount,
                                                         Map<VariantKey, Long> stock,
                                                         Map<VariantKey, IPatternDetails> patterns,
                                                         VariantKey newKey,
                                                         IPatternDetails newPattern,
                                                         boolean bumpVersion,
                                                         List<ICraftingPlan> plan1Out) {
        PatternCompiler.clearCache();
        PatternCompiler.clearFuzzyGroups();
        for (IPatternDetails p : patterns.values()) {
            PatternCompiler.compileIfAbsent(p);
        }
        IPatternDetails top = patterns.get(output);
        CraftingBytecode req1 = PatternCompiler.compileRequest(top, amount);
        CraftingVM vm = new CraftingVM("interm-test", key -> {
            VariantKey vk = (VariantKey) key;
            return patterns.get(vk);
        });
        ICraftingPlan plan1 = vm.execute(req1, new StockSimState(stock));
        if (plan1Out != null) plan1Out.add(plan1);
        // Player DYNAMICALLY ENCODES the new pattern at runtime (writes it into a
        // pattern provider). If the mixin fires, the pattern version is bumped.
        patterns.put(newKey, newPattern);
        PatternCompiler.compileIfAbsent(newPattern);
        if (bumpVersion) {
            PatternCompiler.bumpPatternVersion();
        }
        CraftingBytecode req2 = PatternCompiler.compileRequest(top, amount);
        return vm.execute(req2, new StockSimState(stock));
    }

    /** Backward-compat wrapper: no version bump (the observed bug path). */
    private static ICraftingPlan runAddPatternReuseVM(VariantKey output, long amount,
                                                      Map<VariantKey, Long> stock,
                                                      Map<VariantKey, IPatternDetails> patterns,
                                                      VariantKey newKey,
                                                      IPatternDetails newPattern) {
        return runDynamicEncodeReuseVM(output, amount, stock, patterns, newKey, newPattern, false);
    }

    private static Map<String, Long> missing(ICraftingPlan p) {
        TreeMap<String, Long> out = new TreeMap<>();
        for (var e : p.missingItems()) {
            out.put(e.getKey().toString(), e.getLongValue());
        }
        return out;
    }

    private static long countNonLeafMissing(ICraftingPlan p) {
        long count = 0;
        for (var e : p.missingItems()) {
            if (!e.getKey().equals(LEAF)) count++;
        }
        return count;
    }

    /** Count missing items EXCLUDING the given key. */
    private static long countNonLeafMissingExcluding(ICraftingPlan p, VariantKey excluded) {
        long count = 0;
        for (var e : p.missingItems()) {
            if (!e.getKey().equals(excluded)) count++;
        }
        return count;
    }

    /** Total missing count. */
    private static long countMissing(ICraftingPlan p) {
        long count = 0;
        for (var e : p.missingItems()) count += e.getLongValue();
        return count;
    }

    // ---- minimal pattern/sim helpers (VariantKey-aware) ----

    private static final class VPattern implements IPatternDetails {
        private final IPatternDetails.IInput[] inputs;
        private final GenericStack[] outputs;
        VPattern(VariantKey out, long amount, List<IPatternDetails.IInput> inputList) {
            this.inputs = inputList.toArray(new IPatternDetails.IInput[0]);
            this.outputs = new GenericStack[]{new GenericStack(out, amount)};
        }
        @Override public GenericStack[] getOutputs() { return outputs; }
        @Override public IPatternDetails.IInput[] getInputs() { return inputs; }
        @Override public AEItemKey getDefinition() { return null; }
    }

    private static final class ExactInput implements IPatternDetails.IInput {
        private final GenericStack[] possible;
        ExactInput(AEKey key, long amount) {
            this.possible = new GenericStack[]{new GenericStack(key, amount)};
        }
        @Override public GenericStack[] getPossibleInputs() { return possible; }
        @Override public long getMultiplier() { return 1; }
        @Override public boolean isValid(AEKey input, Level level) {
            return input.equals(possible[0].what());
        }
        @Override public AEKey getRemainingKey(AEKey template) { return null; }
    }

    private static final class StockSimState extends appeng.crafting.inv.CraftingSimulationState
            implements com.ae2vm.addon.mixin.CraftingSimulationStateAccessor {
        private final Map<VariantKey, Long> stock;
        StockSimState(Map<VariantKey, Long> stock) { this.stock = stock; }
        @Override
        protected long simulateExtractParent(AEKey what, long amount) {
            long available = what instanceof VariantKey k ? stock.getOrDefault(k, 0L) : 0L;
            return Math.min(available, amount);
        }
        @Override
        protected Iterable<AEKey> findFuzzyParent(AEKey input) {
            List<AEKey> variants = new ArrayList<>();
            for (VariantKey k : stock.keySet()) {
                if (k.base().equals(input.getPrimaryKey())) variants.add(k);
            }
            return variants;
        }
        @Override
        public double getBytes() {
            try {
                var f = appeng.crafting.inv.CraftingSimulationState.class.getDeclaredField("bytes");
                f.setAccessible(true);
                return f.getDouble(this);
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException(e);
            }
        }
    }
}
