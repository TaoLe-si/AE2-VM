package com.ae2vm.addon.bench;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.stacks.AEKey;
import com.ae2vm.addon.api.AE2VMCrafting;
import com.ae2vm.addon.compiler.PatternCompiler;
import com.ae2vm.addon.vm.CraftingBytecode;
import com.ae2vm.addon.vm.CraftingVM;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * (v1.14.x SEEDED-RING / COMPLEX-CYCLE / NESTED-RING / DEEP-CHAIN benchmark)
 *
 * <p>Locks the user-reported stall: "算完的 plan，AE 网络不发货" — the plan was missing the
 * dust→ingot craft because the 2-hop ring (dust↔ingot pulverize/smelt) was pruned
 * unconditionally by {@code wouldCauseCycle}. The intermediate (ingot) then lived in
 * NEITHER patternTimes NOR usedItems, and the transfinite CPU stalled forever
 * (WAITING_FOR_INPUTS at iron_ingot/copper_ingot avail=0).</p>
 *
 * <p>Also locks the 环套环 / 非平衡环 (nested-ring / unbalanced-ring) family reported
 * in-game: multi-output patterns whose output re-flows into the ring (e.g.
 * {@code 2B -> 1D + 2A}) make {@code getCraftingFor(A)} return the ring pattern
 * itself, so a short seed must be enough to enter the ring and the plan must either
 * finish it exactly (seed covers the need) or report the real missing shortfall —
 * never silently drop the demand. Rings nested inside rings (A↔B outer, B↔C inner,
 * C↔D innermost) must each keep their craft counts exact when seeded.</p>
 *
 * <p>Scenarios (each asserts the FINAL plan quantities):</p>
 * <ol>
 *   <li>wouldCauseCycle: seeded ring allowed / dead ring pruned / self-edge always pruned;</li>
 *   <li>seeded smelt ring end-to-end: plan expands dust→ingot (patternTimes + used dust);</li>
 *   <li>dead ring end-to-end: plan REPORTS missing (never silently drops the demand);</li>
 *   <li>deep 8-level chain: every level's craft count and leaf usage are exact;</li>
 *   <li>deep 8-level chain with a seeded smelt ring embedded mid-chain: exact counts;</li>
 *   <li>complex 3-hop ring (X→Y→Z→X): seeded → every member's craft counted, dead → missing;</li>
 *   <li>UNBALANCED re-flow ring 3A→2B / 1A→1B / 2B→1D+2A with stock 2A, request 1D:
 *       exact plan (p3×1, p2×2, used A=2, missing empty);</li>
 *   <li>same ring, request 2D (seed 2A insufficient): plan must report missing A;</li>
 *   <li>NESTED ring-in-ring (A↔B outer, B↔C inner): seeded → exact counts, dead → missing;</li>
 *   <li>TRIPLE-nested ring (A↔B, B↔C, C↔D): seeded → exact counts at every ring level,
 *       dead → reported missing.</li>
 * </ol>
 */
public class ComplexCycleChainBenchmark {

    // ========================================================================
    // helpers — resolver mimics the real AE2VMCrafting.resolve cycle filter
    // ========================================================================

    private static Function<AEKey, IPatternDetails> resolverWithCycleFilter(
            Map<AEKey, List<IPatternDetails>> candidates, Map<BenchAEKey, Long> stock) {
        return key -> {
            if (!(key instanceof BenchAEKey bk)) return null;
            var subs = candidates.get(bk);
            if (subs == null || subs.isEmpty()) return null;
            var filtered = new ArrayList<IPatternDetails>();
            for (var p : subs) {
                if (!AE2VMCrafting.wouldCauseCycle(k -> candidates.get(k), p, bk,
                        k -> k instanceof BenchAEKey kb ? stock.getOrDefault(kb, 0L) : 0L)) {
                    filtered.add(p);
                }
            }
            if (filtered.isEmpty()) return AE2VMCrafting.pickBestPattern(subs, bk);
            return AE2VMCrafting.pickBestPattern(filtered, bk);
        };
    }

    private static ICraftingPlan run(String id, Map<AEKey, List<IPatternDetails>> candidates,
                                     Map<BenchAEKey, Long> stock, IPatternDetails root, long amount) {
        CraftingVM vm = new CraftingVM(id, resolverWithCycleFilter(candidates, stock));
        // (v1.14.x DEFINITION-GRAPH) Feed the FULL candidate graph to the VM so the cycle
        // analysis sees every ring edge (back-edges live on non-chosen candidates).
        vm.setAllPatternsResolver(candidates::get);
        return vm.execute(PatternCompiler.compileRequest(root, amount), new BenchSimulationState(stock));
    }

    private static IPatternDetails pat(BenchAEKey out, long outCount, List<BenchPatternDetails.InputSpec> ins) {
        return new BenchPatternDetails(out, outCount, ins);
    }

    private static BenchPatternDetails.InputSpec in(BenchAEKey k, long n) {
        return BenchPatternDetails.InputSpec.of(k, n);
    }

    // ========================================================================
    // 1. wouldCauseCycle semantics
    // ========================================================================

    @Test
    void wouldCauseCycle_SeededRing_Allowed() {
        BenchAEKey ingot = BenchAEKey.of("ring_ingot");
        BenchAEKey dust = BenchAEKey.of("ring_dust");
        IPatternDetails smelt = pat(ingot, 1, List.of(in(dust, 1)));
        IPatternDetails pulv = pat(dust, 1, List.of(in(ingot, 1)));
        Map<AEKey, List<IPatternDetails>> cand = new HashMap<>();
        cand.put(ingot, List.of(smelt));
        cand.put(dust, List.of(pulv));
        Map<BenchAEKey, Long> stock = new HashMap<>();
        stock.put(dust, 100L); // seed: dust present

        assertFalse(AE2VMCrafting.wouldCauseCycle(cand::get, smelt, ingot,
                k -> k instanceof BenchAEKey kb ? stock.getOrDefault(kb, 0L) : 0L),
                "seeded dust->ingot ring must be allowed (GT normal smelt/pulverize)");
    }

    @Test
    void wouldCauseCycle_DeadRing_Pruned() {
        BenchAEKey ingot = BenchAEKey.of("ring_ingot");
        BenchAEKey dust = BenchAEKey.of("ring_dust");
        IPatternDetails smelt = pat(ingot, 1, List.of(in(dust, 1)));
        IPatternDetails pulv = pat(dust, 1, List.of(in(ingot, 1)));
        Map<AEKey, List<IPatternDetails>> cand = new HashMap<>();
        cand.put(ingot, List.of(smelt));
        cand.put(dust, List.of(pulv));
        Map<BenchAEKey, Long> stock = new HashMap<>(); // no seed anywhere

        assertTrue(AE2VMCrafting.wouldCauseCycle(cand::get, smelt, ingot,
                k -> k instanceof BenchAEKey kb ? stock.getOrDefault(kb, 0L) : 0L),
                "dead ring (no stock on any member) must still be pruned");
    }

    @Test
    void wouldCauseCycle_DirectSelfEdge_AlwaysPruned() {
        BenchAEKey a = BenchAEKey.of("self_a");
        IPatternDetails selfLoop = pat(a, 2, List.of(in(a, 1)));
        Map<BenchAEKey, Long> stock = new HashMap<>();
        stock.put(a, 1000L); // even with stock, a direct self-amplifying loop is unbounded

        assertTrue(AE2VMCrafting.wouldCauseCycle(k -> List.of(selfLoop), selfLoop, a,
                k -> k instanceof BenchAEKey kb ? stock.getOrDefault(kb, 0L) : 0L),
                "direct self-edge must always be pruned");
    }

    // ========================================================================
    // 2. seeded smelt ring — end-to-end plan expansion
    // ========================================================================

    @Test
    void seededSmeltRing_EndToEnd_PlanExpandsSmelt() {
        BenchAEKey ingot = BenchAEKey.of("smelt_ingot");
        BenchAEKey dust = BenchAEKey.of("smelt_dust");
        IPatternDetails smelt = pat(ingot, 1, List.of(in(dust, 1)));
        IPatternDetails pulv = pat(dust, 1, List.of(in(ingot, 1)));
        Map<AEKey, List<IPatternDetails>> cand = new HashMap<>();
        cand.put(ingot, List.of(smelt));
        cand.put(dust, List.of(pulv));
        Map<BenchAEKey, Long> stock = new HashMap<>();
        stock.put(dust, 100L);

        ICraftingPlan plan = run("seeded-smelt", cand, stock, smelt, 10);

        assertTrue(plan.missingItems().isEmpty(), "10 ingot from 100 dust: feasible, missing=" + plan.missingItems());
        assertEquals(10L, plan.patternTimes().getOrDefault(smelt, 0L), "smelt must fire 10x");
        assertEquals(10L, plan.usedItems().get(dust), "dust consumption must be exact (10)");
    }

    // ========================================================================
    // 3. dead ring — plan must report missing, never silently drop
    // ========================================================================

    @Test
    void deadRing_EndToEnd_ReportsMissing() {
        BenchAEKey ingot = BenchAEKey.of("dead_ingot");
        BenchAEKey dust = BenchAEKey.of("dead_dust");
        IPatternDetails smelt = pat(ingot, 1, List.of(in(dust, 1)));
        IPatternDetails pulv = pat(dust, 1, List.of(in(ingot, 1)));
        Map<AEKey, List<IPatternDetails>> cand = new HashMap<>();
        cand.put(ingot, List.of(smelt));
        cand.put(dust, List.of(pulv));
        Map<BenchAEKey, Long> stock = new HashMap<>(); // nothing anywhere

        ICraftingPlan plan = run("dead-ring", cand, stock, smelt, 10);

        assertFalse(plan.missingItems().isEmpty(), "dead ring must report missing, not silently succeed");
        assertTrue(plan.missingItems().get(ingot) > 0 || plan.missingItems().get(dust) > 0,
                "missing must name a ring member (ingot or dust)");
    }

    // ========================================================================
    // 4. deep 8-level chain — exact quantities at every level
    // ========================================================================

    @Test
    void deepChain8Levels_PlanQuantitiesCorrect() {
        int levels = 8;
        BenchAEKey[] x = new BenchAEKey[levels];
        for (int i = 0; i < levels; i++) x[i] = BenchAEKey.of("deepx" + i);
        IPatternDetails[] patterns = new IPatternDetails[levels];
        Map<AEKey, List<IPatternDetails>> cand = new HashMap<>();
        for (int i = 1; i < levels; i++) {
            patterns[i] = pat(x[i], 1, List.of(in(x[i - 1], 1)));
            cand.put(x[i], List.of(patterns[i]));
        }
        Map<BenchAEKey, Long> stock = new HashMap<>();
        stock.put(x[0], 1000L);

        ICraftingPlan plan = run("deep8", cand, stock, patterns[levels - 1], 2);

        assertTrue(plan.missingItems().isEmpty(), "deep chain from stocked leaf: missing=" + plan.missingItems());
        for (int i = 1; i < levels; i++) {
            assertEquals(2L, plan.patternTimes().getOrDefault(patterns[i], 0L),
                    "level " + i + " must fire exactly 2x (1:1 chain, request=2)");
        }
        assertEquals(2L, plan.usedItems().get(x[0]), "leaf stock usage must be 2");
    }

    // ========================================================================
    // 5. deep 8-level chain with a seeded smelt ring embedded mid-chain
    //    X2..X8 (7 levels, 1:1) + X2 needs INGOT + INGOT via smelt(DUST), DUST stocked
    // ========================================================================

    @Test
    void deepChain8Levels_WithSeededSmeltRing_PlanCorrect() {
        BenchAEKey[] x = new BenchAEKey[9]; // x[1..8]
        for (int i = 1; i <= 8; i++) x[i] = BenchAEKey.of("cx" + i);
        BenchAEKey ingot = BenchAEKey.of("cx_ingot");
        BenchAEKey dust = BenchAEKey.of("cx_dust");

        IPatternDetails[] patterns = new IPatternDetails[9];
        Map<AEKey, List<IPatternDetails>> cand = new HashMap<>();
        for (int i = 3; i <= 8; i++) {
            patterns[i] = pat(x[i], 1, List.of(in(x[i - 1], 1)));
            cand.put(x[i], List.of(patterns[i]));
        }
        patterns[2] = pat(x[2], 1, List.of(in(ingot, 1)));
        cand.put(x[2], List.of(patterns[2]));
        IPatternDetails smelt = pat(ingot, 1, List.of(in(dust, 1)));
        IPatternDetails pulv = pat(dust, 1, List.of(in(ingot, 1)));
        cand.put(ingot, List.of(smelt));
        cand.put(dust, List.of(pulv));

        Map<BenchAEKey, Long> stock = new HashMap<>();
        stock.put(dust, 100L); // seed on the ring leaf

        ICraftingPlan plan = run("deep8-ring", cand, stock, patterns[8], 2);

        assertTrue(plan.missingItems().isEmpty(), "deep chain + seeded ring: missing=" + plan.missingItems());
        for (int i = 2; i <= 8; i++) {
            assertEquals(2L, plan.patternTimes().getOrDefault(patterns[i], 0L),
                    "chain level " + i + " must fire exactly 2x");
        }
        assertEquals(2L, plan.patternTimes().getOrDefault(smelt, 0L), "embedded smelt must fire 2x");
        assertEquals(2L, plan.usedItems().get(dust), "ring seed dust usage must be 2");
    }

    // ========================================================================
    // 6. complex 3-hop ring X→Y→Z→X — seeded feasible / dead reported
    // ========================================================================

    @Test
    void threeHopRing_Seeded_PlanCorrect() {
        BenchAEKey x = BenchAEKey.of("hx"), y = BenchAEKey.of("hy"), z = BenchAEKey.of("hz");
        IPatternDetails px = pat(x, 1, List.of(in(z, 1)));
        IPatternDetails py = pat(y, 1, List.of(in(x, 1)));
        IPatternDetails pz = pat(z, 1, List.of(in(y, 1)));
        Map<AEKey, List<IPatternDetails>> cand = new HashMap<>();
        cand.put(x, List.of(px));
        cand.put(y, List.of(py));
        cand.put(z, List.of(pz));
        Map<BenchAEKey, Long> stock = new HashMap<>();
        stock.put(x, 5L); // seed on the ring

        ICraftingPlan plan = run("3hop-seeded", cand, stock, px, 3);

        assertTrue(plan.missingItems().isEmpty(), "3-hop ring with seed 5 >= need 3: missing=" + plan.missingItems());
        assertEquals(3L, plan.patternTimes().getOrDefault(px, 0L), "X craft must fire 3x");
        assertEquals(3L, plan.patternTimes().getOrDefault(py, 0L), "Y craft must fire 3x (must NOT be dropped)");
        assertEquals(3L, plan.patternTimes().getOrDefault(pz, 0L), "Z craft must fire 3x");
        assertEquals(3L, plan.usedItems().get(x), "seed X consumption must be 3");
    }

    @Test
    void threeHopRing_Dead_ReportsMissing() {
        BenchAEKey x = BenchAEKey.of("hx"), y = BenchAEKey.of("hy"), z = BenchAEKey.of("hz");
        IPatternDetails px = pat(x, 1, List.of(in(z, 1)));
        IPatternDetails py = pat(y, 1, List.of(in(x, 1)));
        IPatternDetails pz = pat(z, 1, List.of(in(y, 1)));
        Map<AEKey, List<IPatternDetails>> cand = new HashMap<>();
        cand.put(x, List.of(px));
        cand.put(y, List.of(py));
        cand.put(z, List.of(pz));
        Map<BenchAEKey, Long> stock = new HashMap<>(); // dead ring

        ICraftingPlan plan = run("3hop-dead", cand, stock, px, 3);

        assertFalse(plan.missingItems().isEmpty(), "dead 3-hop ring must report missing");
        assertTrue(plan.missingItems().get(x) > 0 || plan.missingItems().get(y) > 0
                        || plan.missingItems().get(z) > 0,
                "missing must name a ring member");
    }

    // ========================================================================
    // 7. UNBALANCED re-flow ring: 3A→2B / 1A→1B / 2B→1D+2A, stock 2A, request 1D
    // ========================================================================

    private static final class ReflowFixture {
        final BenchAEKey a = BenchAEKey.of("rf_a");
        final BenchAEKey b = BenchAEKey.of("rf_b");
        final BenchAEKey d = BenchAEKey.of("rf_d");
        final IPatternDetails p1; // 3A -> 2B
        final IPatternDetails p2; // 1A -> 1B
        final IPatternDetails p3; // 2B -> 1D + 2A (re-flow)
        final Map<AEKey, List<IPatternDetails>> cand = new HashMap<>();

        ReflowFixture() {
            p1 = pat(b, 2, List.of(in(a, 3)));
            p2 = pat(b, 1, List.of(in(a, 1)));
            p3 = pat(d, 1, List.of(in(b, 2)));
            cand.put(b, List.of(p1, p2));
            cand.put(d, List.of(p3));
            // A has NO dedicated pattern — but p3 outputs 2A, so getCraftingFor(A)
            // would surface p3 in a real CraftingService; mirror that here:
            cand.put(a, List.of(p3));
        }
    }

    @Test
    void reflowRing_Stock2A_Request1D_ExactPlan() {
        ReflowFixture f = new ReflowFixture();
        Map<BenchAEKey, Long> stock = new HashMap<>();
        stock.put(f.a, 2L);

        ICraftingPlan plan = run("reflow-1d", f.cand, stock, f.p3, 1);

        assertTrue(plan.missingItems().isEmpty(), "2A seed is exactly enough for 1D: missing=" + plan.missingItems());
        assertEquals(1L, plan.patternTimes().getOrDefault(f.p3, 0L), "p3 (2B→1D+2A) must fire 1x");
        assertEquals(2L, plan.patternTimes().getOrDefault(f.p2, 0L), "p2 (1A→1B) must fire 2x (2B total)");
        assertEquals(0L, plan.patternTimes().getOrDefault(f.p1, 0L), "p1 (3A→2B) must NOT fire (p2 is cheaper)");
        assertEquals(2L, plan.usedItems().get(f.a), "seed A consumption must be exactly 2");
    }

    @Test
    void reflowRing_Stock2A_Request2D_ReportsMissingShortfall() {
        ReflowFixture f = new ReflowFixture();
        Map<BenchAEKey, Long> stock = new HashMap<>();
        stock.put(f.a, 2L);

        ICraftingPlan plan = run("reflow-2d", f.cand, stock, f.p3, 2);

        // 2D needs 4B = 4A total; seed 2A covers only half -> real shortfall 2A.
        assertFalse(plan.missingItems().isEmpty(), "seed 2A cannot cover 2D: must report missing");
        assertTrue(plan.missingItems().get(f.a) > 0 || plan.missingItems().get(f.b) > 0,
                "missing must name a ring member (A or B)");
    }

    // ========================================================================
    // 8. NESTED ring-in-ring: A↔B outer ring, B↔C inner ring (B is shared)
    // ========================================================================

    private static final class NestedRingFixture {
        final BenchAEKey a = BenchAEKey.of("nr_a");
        final BenchAEKey b = BenchAEKey.of("nr_b");
        final BenchAEKey c = BenchAEKey.of("nr_c");
        final IPatternDetails ab; // A -> B (outer forward)
        final IPatternDetails ba; // 2B -> A (outer back)
        final IPatternDetails bc; // B -> C (inner forward)
        final IPatternDetails cb; // 2C -> B (inner back)
        final Map<AEKey, List<IPatternDetails>> cand = new HashMap<>();

        NestedRingFixture() {
            ab = pat(b, 1, List.of(in(a, 1)));
            ba = pat(a, 1, List.of(in(b, 2)));
            bc = pat(c, 1, List.of(in(b, 1)));
            cb = pat(b, 1, List.of(in(c, 2)));
            cand.put(b, List.of(ab, cb));
            cand.put(a, List.of(ba));
            cand.put(c, List.of(bc));
        }
    }

    @Test
    void nestedRing_Seeded_PlanCorrect() {
        NestedRingFixture f = new NestedRingFixture();
        Map<BenchAEKey, Long> stock = new HashMap<>();
        stock.put(f.a, 100L);

        ICraftingPlan plan = run("nested-seeded", f.cand, stock, f.bc, 3);

        assertTrue(plan.missingItems().isEmpty(), "seeded nested ring must be feasible: missing=" + plan.missingItems());
        assertEquals(3L, plan.patternTimes().getOrDefault(f.bc, 0L), "B→C (inner) must fire 3x");
        assertEquals(3L, plan.patternTimes().getOrDefault(f.ab, 0L), "A→B (outer) must fire 3x (seeds inner)");
        assertEquals(3L, plan.usedItems().get(f.a), "seed A consumption must be 3");
    }

    @Test
    void nestedRing_Dead_ReportsMissing() {
        NestedRingFixture f = new NestedRingFixture();
        Map<BenchAEKey, Long> stock = new HashMap<>();

        ICraftingPlan plan = run("nested-dead", f.cand, stock, f.bc, 3);

        assertFalse(plan.missingItems().isEmpty(), "dead nested ring must report missing");
        assertTrue(plan.missingItems().get(f.a) > 0 || plan.missingItems().get(f.b) > 0
                        || plan.missingItems().get(f.c) > 0,
                "missing must name a ring member");
    }

    // ========================================================================
    // 9. TRIPLE-nested ring: A↔B, B↔C, C↔D (three rings sharing the chain)
    // ========================================================================

    private static final class TripleNestedRingFixture {
        final BenchAEKey a = BenchAEKey.of("tn_a");
        final BenchAEKey b = BenchAEKey.of("tn_b");
        final BenchAEKey c = BenchAEKey.of("tn_c");
        final BenchAEKey d = BenchAEKey.of("tn_d");
        final IPatternDetails ab; // A -> B
        final IPatternDetails ba; // 2B -> A
        final IPatternDetails bc; // B -> C
        final IPatternDetails cb; // 2C -> B
        final IPatternDetails cd; // C -> D
        final IPatternDetails dc; // 2D -> C
        final Map<AEKey, List<IPatternDetails>> cand = new HashMap<>();

        TripleNestedRingFixture() {
            ab = pat(b, 1, List.of(in(a, 1)));
            ba = pat(a, 1, List.of(in(b, 2)));
            bc = pat(c, 1, List.of(in(b, 1)));
            cb = pat(b, 1, List.of(in(c, 2)));
            cd = pat(d, 1, List.of(in(c, 1)));
            dc = pat(c, 1, List.of(in(d, 2)));
            cand.put(b, List.of(ab, cb));
            cand.put(a, List.of(ba));
            cand.put(c, List.of(bc, dc));
            cand.put(d, List.of(cd));
        }
    }

    @Test
    void tripleNestedRing_Seeded_PlanCorrect() {
        TripleNestedRingFixture f = new TripleNestedRingFixture();
        Map<BenchAEKey, Long> stock = new HashMap<>();
        stock.put(f.a, 100L);

        ICraftingPlan plan = run("triple-seeded", f.cand, stock, f.cd, 2);

        assertTrue(plan.missingItems().isEmpty(), "seeded triple-nested ring feasible: missing=" + plan.missingItems());
        assertEquals(2L, plan.patternTimes().getOrDefault(f.cd, 0L), "C→D (innermost) must fire 2x");
        assertEquals(2L, plan.patternTimes().getOrDefault(f.bc, 0L), "B→C must fire 2x");
        assertEquals(2L, plan.patternTimes().getOrDefault(f.ab, 0L), "A→B (outermost) must fire 2x");
        assertEquals(2L, plan.usedItems().get(f.a), "seed A consumption must be 2");
    }

    @Test
    void tripleNestedRing_Dead_ReportsMissing() {
        TripleNestedRingFixture f = new TripleNestedRingFixture();
        Map<BenchAEKey, Long> stock = new HashMap<>();

        ICraftingPlan plan = run("triple-dead", f.cand, stock, f.cd, 2);

        assertFalse(plan.missingItems().isEmpty(), "dead triple-nested ring must report missing");
        assertTrue(plan.missingItems().get(f.a) > 0 || plan.missingItems().get(f.b) > 0
                        || plan.missingItems().get(f.c) > 0 || plan.missingItems().get(f.d) > 0,
                "missing must name a ring member");
    }
}
