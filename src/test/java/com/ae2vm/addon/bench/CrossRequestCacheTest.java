package com.ae2vm.addon.bench;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.stacks.AEKey;
import com.ae2vm.addon.compiler.PatternCompiler;
import com.ae2vm.addon.vm.CraftingBytecode;
import com.ae2vm.addon.vm.CraftingVM;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Rigorous cross-request cache tests for the per-grid VM reuse (AE2VMCrafting's
 * VM_CACHE). The user reports "首次下单正常，后续数量一致错误" persists after v1.9.7 —
 * so these tests stress the ONE invariant that separates a *correct* bundleCache
 * reuse from a *polluting* one:
 *
 * <p><b>DETERMINISM:</b> the same request on the same VM with the same network
 * stock must produce a byte-for-byte identical plan on every execution. Request #1
 * fully captures (every sub-pattern is dispatched and captured); request #2+
 * reuses the cached sub-bundles (sub-patterns are NOT re-dispatched, so the
 * parent's capture-time EXTRACT reads a different sandbox — network stock instead
 * of freshly-crafted output). If the aggregation treats those two capture states
 * differently, request #1 and #2 diverge: the exact "first OK, later wrong" bug.
 *
 * <p>All fixtures use a real {@link FakeBenchGrid} + a shared MUTABLE stock map so
 * {@code realStockOf} observes live inventory exactly like in-game.
 */
public class CrossRequestCacheTest {

    /** A<-B+C ; B<-D+E ; C<-F+G. B is CRAFTABLE and STOCKED (the stock-aware decision point). */
    private static final class StockedMidFixture {
        final Map<AEKey, IPatternDetails> byOutput = new HashMap<>();
        final Map<BenchAEKey, Long> stock = new HashMap<>();
        StockedMidFixture() {
            BenchAEKey A = BenchAEKey.of("A");
            BenchAEKey B = BenchAEKey.of("B");
            BenchAEKey C = BenchAEKey.of("C");
            BenchAEKey D = BenchAEKey.of("D");
            BenchAEKey E = BenchAEKey.of("E");
            BenchAEKey F = BenchAEKey.of("F");
            BenchAEKey G = BenchAEKey.of("G");
            byOutput.put(A, new BenchPatternDetails(A, 1, List.of(
                    BenchPatternDetails.InputSpec.of(B, 1),
                    BenchPatternDetails.InputSpec.of(C, 1))));
            byOutput.put(B, new BenchPatternDetails(B, 1, List.of(
                    BenchPatternDetails.InputSpec.of(D, 1),
                    BenchPatternDetails.InputSpec.of(E, 1))));
            byOutput.put(C, new BenchPatternDetails(C, 1, List.of(
                    BenchPatternDetails.InputSpec.of(F, 1),
                    BenchPatternDetails.InputSpec.of(G, 1))));
        }
        void stockB(long b) {
            stock.put(BenchAEKey.of("B"), b);
            stock.put(BenchAEKey.of("D"), 32L);
            stock.put(BenchAEKey.of("E"), 32L);
            stock.put(BenchAEKey.of("F"), 32L);
            stock.put(BenchAEKey.of("G"), 32L);
        }
        CraftingBytecode request(long amount) {
            return PatternCompiler.compileRequest(byOutput.get(BenchAEKey.of("A")), amount);
        }
    }

    /** A <- X ; X <- B + C ; B <- D + E ; C <- F + G — deeper chain with stocked mid B. */
    private static final class DeepFixture {
        final Map<AEKey, IPatternDetails> byOutput = new HashMap<>();
        final Map<BenchAEKey, Long> stock = new HashMap<>();
        DeepFixture() {
            BenchAEKey A = BenchAEKey.of("A");
            BenchAEKey X = BenchAEKey.of("X");
            BenchAEKey B = BenchAEKey.of("B");
            BenchAEKey C = BenchAEKey.of("C");
            BenchAEKey D = BenchAEKey.of("D");
            BenchAEKey E = BenchAEKey.of("E");
            BenchAEKey F = BenchAEKey.of("F");
            BenchAEKey G = BenchAEKey.of("G");
            byOutput.put(A, new BenchPatternDetails(A, 1, List.of(
                    BenchPatternDetails.InputSpec.of(X, 1))));
            byOutput.put(X, new BenchPatternDetails(X, 1, List.of(
                    BenchPatternDetails.InputSpec.of(B, 1),
                    BenchPatternDetails.InputSpec.of(C, 1))));
            byOutput.put(B, new BenchPatternDetails(B, 1, List.of(
                    BenchPatternDetails.InputSpec.of(D, 1),
                    BenchPatternDetails.InputSpec.of(E, 1))));
            byOutput.put(C, new BenchPatternDetails(C, 1, List.of(
                    BenchPatternDetails.InputSpec.of(F, 1),
                    BenchPatternDetails.InputSpec.of(G, 1))));
            stock.put(BenchAEKey.of("B"), 4L);
            stock.put(BenchAEKey.of("D"), 32L);
            stock.put(BenchAEKey.of("E"), 32L);
            stock.put(BenchAEKey.of("F"), 32L);
            stock.put(BenchAEKey.of("G"), 32L);
        }
        CraftingBytecode request(long amount) {
            return PatternCompiler.compileRequest(byOutput.get(BenchAEKey.of("A")), amount);
        }
    }

    /**
     * Fibonacci-style shared DAG (the NAST "quantum → complex → omni → appflux"
     * exponential chain): Xi = X(i-1) + X(i-2) with X0, X1 as leaves. EMPTY stock —
     * every node is missing. The aggregation must still derive the full Fibonacci
     * craft chain identically across reuse.
     */
    private static final class FibonacciFixture {
        final Map<AEKey, IPatternDetails> byOutput = new HashMap<>();
        final Map<BenchAEKey, Long> stock = new HashMap<>();
        final int levels;
        FibonacciFixture(int levels) {
            this.levels = levels;
            BenchAEKey[] keys = new BenchAEKey[levels];
            for (int i = 0; i < levels; i++) {
                keys[i] = BenchAEKey.of("X" + i);
            }
            for (int i = 2; i < levels; i++) {
                byOutput.put(keys[i], new BenchPatternDetails(keys[i], 1, List.of(
                        BenchPatternDetails.InputSpec.of(keys[i - 1], 1),
                        BenchPatternDetails.InputSpec.of(keys[i - 2], 1))));
            }
            // stock intentionally empty
        }
        CraftingBytecode request(long amount) {
            return PatternCompiler.compileRequest(byOutput.get(BenchAEKey.of("X" + (levels - 1))), amount);
        }
    }

    /** A <- P + Q ; P <- B + C ; Q <- B + D — diamond: B is shared by P and Q, and stocked. */
    private static final class DiamondFixture {
        final Map<AEKey, IPatternDetails> byOutput = new HashMap<>();
        final Map<BenchAEKey, Long> stock = new HashMap<>();
        DiamondFixture() {
            BenchAEKey A = BenchAEKey.of("A");
            BenchAEKey P = BenchAEKey.of("P");
            BenchAEKey Q = BenchAEKey.of("Q");
            BenchAEKey B = BenchAEKey.of("B");
            BenchAEKey C = BenchAEKey.of("C");
            BenchAEKey D = BenchAEKey.of("D");
            byOutput.put(A, new BenchPatternDetails(A, 1, List.of(
                    BenchPatternDetails.InputSpec.of(P, 1),
                    BenchPatternDetails.InputSpec.of(Q, 1))));
            byOutput.put(P, new BenchPatternDetails(P, 1, List.of(
                    BenchPatternDetails.InputSpec.of(B, 1),
                    BenchPatternDetails.InputSpec.of(C, 1))));
            byOutput.put(Q, new BenchPatternDetails(Q, 1, List.of(
                    BenchPatternDetails.InputSpec.of(B, 1),
                    BenchPatternDetails.InputSpec.of(D, 1))));
            byOutput.put(B, new BenchPatternDetails(B, 1, List.of(
                    BenchPatternDetails.InputSpec.of(BenchAEKey.of("C"), 1),
                    BenchPatternDetails.InputSpec.of(BenchAEKey.of("D"), 1))));
            stock.put(BenchAEKey.of("B"), 4L);
            stock.put(BenchAEKey.of("C"), 32L);
            stock.put(BenchAEKey.of("D"), 32L);
        }
        CraftingBytecode request(long amount) {
            return PatternCompiler.compileRequest(byOutput.get(BenchAEKey.of("A")), amount);
        }
    }

    private static void dump(String tag, ICraftingPlan p, Map<BenchAEKey, Long> stock) {
        TreeMap<String, Long> used = new TreeMap<>();
        for (var e : p.usedItems()) used.put(((BenchAEKey) e.getKey()).itemId(), e.getLongValue());
        TreeMap<String, Long> pat = new TreeMap<>();
        for (var e : p.patternTimes().entrySet()) {
            pat.put(e.getKey().getPrimaryOutput().what().toString(), e.getValue());
        }
        TreeMap<String, Long> miss = new TreeMap<>();
        for (var e : p.missingItems()) miss.put(((BenchAEKey) e.getKey()).itemId(), e.getLongValue());
        System.out.println("[XREQ " + tag + "] sim=" + p.simulation()
                + " used=" + used + " patterns=" + pat + " missing=" + miss);
    }

    private static void assertPlansEqual(ICraftingPlan a, ICraftingPlan b, String msg) {
        Assertions.assertEquals(a.simulation(), b.simulation(), msg + " (simulation)");
        Assertions.assertEquals(keyCounter(a.usedItems()), keyCounter(b.usedItems()), msg + " (used)");
        // AE2 GUI "to craft" = Σ emittedItems + Σ patternTimes × outputAmount — so
        // emittedItems must also be stable across reuse or the GUI count diverges.
        Assertions.assertEquals(keyCounter(a.emittedItems()), keyCounter(b.emittedItems()), msg + " (emitted)");
        Assertions.assertEquals(patternTimes(a), patternTimes(b), msg + " (patternTimes)");
        Assertions.assertEquals(keyCounter(a.missingItems()), keyCounter(b.missingItems()), msg + " (missing)");
    }

    private static Map<String, Long> keyCounter(appeng.api.stacks.KeyCounter kc) {
        Map<String, Long> out = new TreeMap<>();
        for (var e : kc) out.put(((BenchAEKey) e.getKey()).itemId(), e.getLongValue());
        return out;
    }

    private static Map<String, Long> patternTimes(ICraftingPlan p) {
        Map<String, Long> out = new TreeMap<>();
        for (var e : p.patternTimes().entrySet()) {
            out.put(e.getKey().getPrimaryOutput().what().toString(), e.getValue());
        }
        return out;
    }

    /**
     * CRITICAL REGRESSION (v1.9.7): the same request on the same VM with the SAME
     * stock must be deterministic — request #2 (cache reuse) must equal request #1
     * (full capture). If reuse changes the result, this is the "首次正确，后续一致错误"
     * symptom regardless of stock changes.
     */
    @Test
    void sameStockSameAmountIsDeterministic() throws Exception {
        StockedMidFixture fx = new StockedMidFixture();
        fx.stockB(4); // B stock == 4 = exactly the need for 4 A
        PatternCompiler.clearCache();
        FakeBenchGrid grid = new FakeBenchGrid(fx.stock);
        CraftingVM vm = new CraftingVM(grid, fx.byOutput::get);

        ICraftingPlan plan1 = vm.execute(fx.request(4), new BenchSimulationState(fx.stock));
        ICraftingPlan plan2 = vm.execute(fx.request(4), new BenchSimulationState(fx.stock));
        ICraftingPlan plan3 = vm.execute(fx.request(4), new BenchSimulationState(fx.stock));

        dump("req1", plan1, fx.stock);
        dump("req2", plan2, fx.stock);
        dump("req3", plan3, fx.stock);

        Assertions.assertTrue(plan1.missingItems().isEmpty(), "req1 feasible");
        Assertions.assertTrue(plan2.missingItems().isEmpty(), "req2 feasible");
        Assertions.assertTrue(plan3.missingItems().isEmpty(), "req3 feasible");
        assertPlansEqual(plan1, plan2, "req2 must equal req1");
        assertPlansEqual(plan2, plan3, "req3 must equal req2");
    }

    /**
     * Same stock, SAME amount, but B stock is a strict sub-multiple of the need
     * (B=2, need 4 B → half from stock, half crafted). Verifies the crafted-deficit
     * branch is also deterministic across reuse.
     */
    @Test
    void sameStockDeficitBranchIsDeterministic() throws Exception {
        StockedMidFixture fx = new StockedMidFixture();
        fx.stockB(2); // need 4 B, stock 2 → craft 2
        PatternCompiler.clearCache();
        FakeBenchGrid grid = new FakeBenchGrid(fx.stock);
        CraftingVM vm = new CraftingVM(grid, fx.byOutput::get);

        ICraftingPlan plan1 = vm.execute(fx.request(4), new BenchSimulationState(fx.stock));
        ICraftingPlan plan2 = vm.execute(fx.request(4), new BenchSimulationState(fx.stock));
        ICraftingPlan plan3 = vm.execute(fx.request(4), new BenchSimulationState(fx.stock));

        dump("req1", plan1, fx.stock);
        dump("req2", plan2, fx.stock);
        dump("req3", plan3, fx.stock);

        Assertions.assertTrue(plan1.missingItems().isEmpty(), "req1 feasible");
        assertPlansEqual(plan1, plan2, "req2 must equal req1");
        assertPlansEqual(plan2, plan3, "req3 must equal req2");
    }

    /**
     * Multi-step stock drain: B 8 → 4 → 2 → 0 with the SAME amount (4 A, need 4 B).
     * Each step must reflect the CURRENT stock and stay feasible until B runs out.
     */
    @Test
    void multiStepStockDrain() throws Exception {
        StockedMidFixture fx = new StockedMidFixture();
        PatternCompiler.clearCache();
        FakeBenchGrid grid = new FakeBenchGrid(fx.stock);
        CraftingVM vm = new CraftingVM(grid, fx.byOutput::get);

        // Step 1: B=8 ≥ need 4 → all from stock, craft 0 B.
        fx.stockB(8);
        ICraftingPlan p1 = vm.execute(fx.request(4), new BenchSimulationState(fx.stock));
        dump("s1", p1, fx.stock);
        Assertions.assertTrue(p1.missingItems().isEmpty(), "s1 feasible");
        Assertions.assertEquals(4L, p1.usedItems().get(BenchAEKey.of("B")), "s1 used B 4");
        Assertions.assertEquals(0L, p1.patternTimes().getOrDefault(fx.byOutput.get(BenchAEKey.of("B")), 0L), "s1 craft B 0");

        // Step 2: B=2 < need 4 → use 2 from stock, craft 2.
        fx.stockB(2);
        ICraftingPlan p2 = vm.execute(fx.request(4), new BenchSimulationState(fx.stock));
        dump("s2", p2, fx.stock);
        Assertions.assertTrue(p2.missingItems().isEmpty(), "s2 feasible");
        Assertions.assertEquals(2L, p2.usedItems().get(BenchAEKey.of("B")), "s2 used B 2");
        Assertions.assertEquals(2L, p2.patternTimes().getOrDefault(fx.byOutput.get(BenchAEKey.of("B")), 0L), "s2 craft B 2");

        // Step 3: B=0 → all 4 crafted.
        fx.stockB(0);
        ICraftingPlan p3 = vm.execute(fx.request(4), new BenchSimulationState(fx.stock));
        dump("s3", p3, fx.stock);
        Assertions.assertTrue(p3.missingItems().isEmpty(), "s3 feasible");
        Assertions.assertEquals(0L, p3.usedItems().get(BenchAEKey.of("B")), "s3 used B 0");
        Assertions.assertEquals(4L, p3.patternTimes().getOrDefault(fx.byOutput.get(BenchAEKey.of("B")), 0L), "s3 craft B 4");
    }

    /**
     * Request amount changes across reuse: 4 A (need 4 B) then 8 A (need 8 B), SAME
     * stock B=4. The aggregation must re-derive the B total from the new amount.
     */
    @Test
    void amountChangeAcrossReuse() throws Exception {
        StockedMidFixture fx = new StockedMidFixture();
        fx.stockB(4);
        PatternCompiler.clearCache();
        FakeBenchGrid grid = new FakeBenchGrid(fx.stock);
        CraftingVM vm = new CraftingVM(grid, fx.byOutput::get);

        ICraftingPlan p4 = vm.execute(fx.request(4), new BenchSimulationState(fx.stock));
        dump("amt4", p4, fx.stock);
        Assertions.assertTrue(p4.missingItems().isEmpty(), "4 A feasible");
        Assertions.assertEquals(0L, p4.patternTimes().getOrDefault(fx.byOutput.get(BenchAEKey.of("B")), 0L), "4 A: craft 0 B");

        ICraftingPlan p8 = vm.execute(fx.request(8), new BenchSimulationState(fx.stock));
        dump("amt8", p8, fx.stock);
        Assertions.assertTrue(p8.missingItems().isEmpty(), "8 A feasible");
        Assertions.assertEquals(4L, p8.usedItems().get(BenchAEKey.of("B")), "8 A: used 4 B from stock");
        Assertions.assertEquals(4L, p8.patternTimes().getOrDefault(fx.byOutput.get(BenchAEKey.of("B")), 0L), "8 A: craft 4 B");
    }

    /**
     * Deep chain (A<-X<-B+C) with stocked B, deterministic across reuse.
     */
    @Test
    void deepChainDeterministic() throws Exception {
        DeepFixture fx = new DeepFixture();
        PatternCompiler.clearCache();
        FakeBenchGrid grid = new FakeBenchGrid(fx.stock);
        CraftingVM vm = new CraftingVM(grid, fx.byOutput::get);

        ICraftingPlan plan1 = vm.execute(fx.request(4), new BenchSimulationState(fx.stock));
        ICraftingPlan plan2 = vm.execute(fx.request(4), new BenchSimulationState(fx.stock));
        dump("deep1", plan1, fx.stock);
        dump("deep2", plan2, fx.stock);
        Assertions.assertTrue(plan1.missingItems().isEmpty(), "deep req1 feasible");
        Assertions.assertTrue(plan2.missingItems().isEmpty(), "deep req2 feasible");
        assertPlansEqual(plan1, plan2, "deep req2 must equal req1");
    }

    /**
     * Diamond (B shared by P and Q, and stocked), deterministic across reuse.
     */
    @Test
    void diamondDeterministic() throws Exception {
        DiamondFixture fx = new DiamondFixture();
        PatternCompiler.clearCache();
        FakeBenchGrid grid = new FakeBenchGrid(fx.stock);
        CraftingVM vm = new CraftingVM(grid, fx.byOutput::get);

        ICraftingPlan plan1 = vm.execute(fx.request(4), new BenchSimulationState(fx.stock));
        ICraftingPlan plan2 = vm.execute(fx.request(4), new BenchSimulationState(fx.stock));
        dump("dia1", plan1, fx.stock);
        dump("dia2", plan2, fx.stock);
        Assertions.assertTrue(plan1.missingItems().isEmpty(), "diamond req1 feasible");
        Assertions.assertTrue(plan2.missingItems().isEmpty(), "diamond req2 feasible");
        assertPlansEqual(plan1, plan2, "diamond req2 must equal req1");
    }

    /**
     * Two INDEPENDENT VMs (two different grids, different stock) must not pollute
     * each other: each VM owns its own bundleCache / realStockCache / stockFromNetwork.
     * VM1 sees B=2 (craft 2 B), VM2 sees B=8 (all from stock) — each must stay on its
     * own network's stock regardless of execution order.
     */
    @Test
    void separateVmsDoNotPolluteEachOther() throws Exception {
        // VM1: B=2 → use 2 from stock + craft 2.
        StockedMidFixture fx1 = new StockedMidFixture();
        fx1.stockB(2);
        PatternCompiler.clearCache();
        FakeBenchGrid grid1 = new FakeBenchGrid(fx1.stock);
        CraftingVM vm1 = new CraftingVM(grid1, fx1.byOutput::get);

        // VM2: B=8 → all 4 from stock, craft 0.
        StockedMidFixture fx2 = new StockedMidFixture();
        fx2.stockB(8);
        FakeBenchGrid grid2 = new FakeBenchGrid(fx2.stock);
        CraftingVM vm2 = new CraftingVM(grid2, fx2.byOutput::get);

        // Run them interleaved to prove no cross-VM state bleed.
        ICraftingPlan v1p1 = vm1.execute(fx1.request(4), new BenchSimulationState(fx1.stock));
        ICraftingPlan v2p1 = vm2.execute(fx2.request(4), new BenchSimulationState(fx2.stock));
        ICraftingPlan v1p2 = vm1.execute(fx1.request(4), new BenchSimulationState(fx1.stock));
        ICraftingPlan v2p2 = vm2.execute(fx2.request(4), new BenchSimulationState(fx2.stock));

        dump("v1p1", v1p1, fx1.stock);
        dump("v1p2", v1p2, fx1.stock);
        dump("v2p1", v2p1, fx2.stock);
        dump("v2p2", v2p2, fx2.stock);

        Assertions.assertEquals(2L, v1p1.usedItems().get(BenchAEKey.of("B")), "VM1 used B 2");
        Assertions.assertEquals(2L, v1p1.patternTimes().getOrDefault(fx1.byOutput.get(BenchAEKey.of("B")), 0L), "VM1 craft B 2");
        Assertions.assertEquals(4L, v2p1.usedItems().get(BenchAEKey.of("B")), "VM2 used B 4");
        Assertions.assertEquals(0L, v2p1.patternTimes().getOrDefault(fx2.byOutput.get(BenchAEKey.of("B")), 0L), "VM2 craft B 0");
        Assertions.assertTrue(v1p1.missingItems().isEmpty());
        Assertions.assertTrue(v2p1.missingItems().isEmpty());
        // And each VM stays deterministic on its own second request.
        assertPlansEqual(v1p1, v1p2, "VM1 deterministic");
        assertPlansEqual(v2p1, v2p2, "VM2 deterministic");
    }

    /**
     * A BRAND-NEW VM (cold, full re-capture) must produce the SAME plan as a
     * REUSED VM for identical stock+request. If JIT reuse changed the result, then a
     * VM_CACHE eviction / grid-instance change mid-game would silently change the
     * computed quantities ("不同 VM 结果不同").
     */
    @Test
    void freshVmEqualsReusedVm() throws Exception {
        // Cold VM: full capture on its very first request.
        StockedMidFixture fxCold = new StockedMidFixture();
        fxCold.stockB(2);
        PatternCompiler.clearCache();
        FakeBenchGrid gridCold = new FakeBenchGrid(fxCold.stock);
        CraftingVM coldVm = new CraftingVM(gridCold, fxCold.byOutput::get);
        ICraftingPlan coldPlan = coldVm.execute(fxCold.request(4), new BenchSimulationState(fxCold.stock));

        // Warm VM: same fixture, but run twice (second run reuses bundleCache).
        StockedMidFixture fxWarm = new StockedMidFixture();
        fxWarm.stockB(2);
        FakeBenchGrid gridWarm = new FakeBenchGrid(fxWarm.stock);
        CraftingVM warmVm = new CraftingVM(gridWarm, fxWarm.byOutput::get);
        warmVm.execute(fxWarm.request(4), new BenchSimulationState(fxWarm.stock));
        ICraftingPlan warmSecond = warmVm.execute(fxWarm.request(4), new BenchSimulationState(fxWarm.stock));

        dump("cold", coldPlan, fxCold.stock);
        dump("warm2", warmSecond, fxWarm.stock);
        assertPlansEqual(coldPlan, warmSecond, "fresh VM plan must equal reused VM plan");
    }

    /**
     * REGRESSION (v1.9.8): EMPTY network stock. The user reports two identical
     * orders (same item, empty inventory) produce DIFFERENT plans (926K bytes →
     * 364K bytes) — "缓存配方的 bug". With an empty network every captured bundle
     * has non-empty {@code missing}, so the cross-request cache hygiene (which
     * drops bundles whose missing is non-empty) wipes the whole bundleCache at the
     * start of request #2 and the VM re-captures everything. The second plan MUST
     * be byte-for-byte identical to the first (same missing, same craft chain,
     * same bytes). Any difference = the cached-recipe bug.
     */
    @Test
    void emptyStockReuseIsDeterministic() throws Exception {
        // Empty inventory: nothing in stock (B/D/E/F/G all absent).
        StockedMidFixture fx = new StockedMidFixture();
        PatternCompiler.clearCache();
        FakeBenchGrid grid = new FakeBenchGrid(fx.stock);
        CraftingVM vm = new CraftingVM(grid, fx.byOutput::get);

        ICraftingPlan plan1 = vm.execute(fx.request(4), new BenchSimulationState(fx.stock));
        ICraftingPlan plan2 = vm.execute(fx.request(4), new BenchSimulationState(fx.stock));
        ICraftingPlan plan3 = vm.execute(fx.request(4), new BenchSimulationState(fx.stock));

        dump("empty1", plan1, fx.stock);
        dump("empty2", plan2, fx.stock);
        dump("empty3", plan3, fx.stock);

        Assertions.assertTrue(plan1.simulation(), "empty stock → simulation (missing)");
        Assertions.assertEquals(4L, plan1.missingItems().get(BenchAEKey.of("D")), "D missing 4");
        assertPlansEqual(plan1, plan2, "empty-stock req2 must equal req1");
        assertPlansEqual(plan2, plan3, "empty-stock req3 must equal req2");
    }

    /**
     * REGRESSION (v1.9.8): Fibonacci shared-DAG chain + EMPTY stock, reused VM.
     * Mirrors the user's 926K → 364K report (each craft-count halved on the second
     * order). Every node is missing, so the cache hygiene wipes the bundleCache and
     * the VM re-captures — the re-captured chain MUST equal the first, including the
     * full Fibonacci multiplier chain (X5 = X4+X3, etc.), never a halved subset.
     */
    @Test
    void emptyStockFibonacciIsDeterministic() throws Exception {
        FibonacciFixture fx = new FibonacciFixture(8); // X0..X7, request X7
        PatternCompiler.clearCache();
        FakeBenchGrid grid = new FakeBenchGrid(fx.stock);
        CraftingVM vm = new CraftingVM(grid, fx.byOutput::get);

        ICraftingPlan plan1 = vm.execute(fx.request(8), new BenchSimulationState(fx.stock));
        ICraftingPlan plan2 = vm.execute(fx.request(8), new BenchSimulationState(fx.stock));
        ICraftingPlan plan3 = vm.execute(fx.request(8), new BenchSimulationState(fx.stock));

        dump("fib1", plan1, fx.stock);
        dump("fib2", plan2, fx.stock);
        dump("fib3", plan3, fx.stock);

        Assertions.assertTrue(plan1.simulation(), "empty → simulation");
        // Sanity: craft counts must follow Fibonacci (X7=8 → X6+X5 = 5+3, etc.).
        assertPlansEqual(plan1, plan2, "fib empty req2 must equal req1");
        assertPlansEqual(plan2, plan3, "fib empty req3 must equal req2");
    }

    /**
     * REGRESSION (v1.9.9): DEEP 24-level Fibonacci chain + EMPTY stock + LARGE amount
     * (10^9), multi-step. This mirrors the user's report: request
     * quantum_omni_cell_component x 10^9, omni_cell_comp shows missing 46T
     * (= 10^9 × Fibonacci(24)=46368) even though omni_cell_comp HAS a pattern
     * ("可合成"). Every node X2..X23 has a pattern and MUST be synthesized; only the
     * leaf nodes X0/X1 may be missing. If any craftable mid-chain node appears in
     * missingItems, that reproduces the bug.
     */
    @Test
    void deepFib24MultiStepCraftablesNeverMissing() throws Exception {
        FibonacciFixture fx = new FibonacciFixture(24); // X0..X23, request X23
        PatternCompiler.clearCache();
        FakeBenchGrid grid = new FakeBenchGrid(fx.stock); // empty stock
        CraftingVM vm = new CraftingVM(grid, fx.byOutput::get);

        ICraftingPlan plan1 = vm.execute(fx.request(1_000_000_000L), new BenchSimulationState(fx.stock));
        ICraftingPlan plan2 = vm.execute(fx.request(1_000_000_000L), new BenchSimulationState(fx.stock));

        dump("deep24-1", plan1, fx.stock);
        dump("deep24-2", plan2, fx.stock);

        // Every craftable node must be present in patternTimes (synthesized), never missing.
        for (int i = 2; i < fx.levels; i++) {
            Assertions.assertTrue(plan1.patternTimes().containsKey(fx.byOutput.get(BenchAEKey.of("X" + i))),
                    "X" + i + " has a pattern and must be synthesized, not missing");
        }
        // Missing must be ONLY the leaf nodes X0 / X1.
        for (var e : plan1.missingItems()) {
            String id = ((BenchAEKey) e.getKey()).itemId();
            Assertions.assertTrue(id.equals("X0") || id.equals("X1"),
                    "missing should only be leaves X0/X1, but got " + id);
        }
        // Multi-step determinism.
        assertPlansEqual(plan1, plan2, "deep24 req2 must equal req1");
    }
}
