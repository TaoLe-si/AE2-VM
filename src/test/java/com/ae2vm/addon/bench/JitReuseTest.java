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
import java.util.function.Function;

/**
 * Verifies cross-request JIT bundleCache reuse: the SAME CraftingVM instance is
 * executed repeatedly (as in AE2VMCrafting's per-grid VM cache). The first request
 * captures every sub-pattern (mostly JIT misses); subsequent requests should reuse
 * the captured bundles (high hit-rate) and still produce identical, correct plans.
 */
public class JitReuseTest {

    /** A<-B+C ; B<-D+E ; C<-F+G with stock D,E,F,G=4 → craft 4 A. */
    private static final class Fixture {
        final Map<AEKey, IPatternDetails> byOutput = new HashMap<>();
        final Map<BenchAEKey, Long> stock = new HashMap<>();

        Fixture() {
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
            stock.put(D, 4L);
            stock.put(E, 4L);
            stock.put(F, 4L);
            stock.put(G, 4L);
        }

        CraftingBytecode request(long amount) {
            IPatternDetails top = byOutput.get(BenchAEKey.of("A"));
            return PatternCompiler.compileRequest(top, amount);
        }
    }

    @Test
    void reuseVmAcrossRequests() throws Exception {
        Fixture fx = new Fixture();
        Function<AEKey, IPatternDetails> resolver = fx.byOutput::get;
        PatternCompiler.clearCache();

        // Same VM reused across many requests — mirrors AE2VMCrafting's per-grid cache.
        CraftingVM vm = new CraftingVM("jit-reuse-bench", resolver);

        ICraftingPlan first = null;
        for (int i = 0; i < 5; i++) {
            long amount = 4;
            ICraftingPlan plan = vm.execute(fx.request(amount), new BenchSimulationState(fx.stock));
            if (i == 0) {
                first = plan;
            }
            System.out.println("[JIT-REUSE] request=" + i
                    + " sim=" + plan.simulation()
                    + " usedD=" + plan.usedItems().get(BenchAEKey.of("D"))
                    + " usedE=" + plan.usedItems().get(BenchAEKey.of("E"))
                    + " usedF=" + plan.usedItems().get(BenchAEKey.of("F"))
                    + " usedG=" + plan.usedItems().get(BenchAEKey.of("G"))
                    + " missing=" + plan.missingItems());
            Assertions.assertTrue(plan.missingItems().isEmpty(), "no missing on request " + i);
            Assertions.assertEquals(4L, plan.usedItems().get(BenchAEKey.of("D")));
            Assertions.assertEquals(4L, plan.usedItems().get(BenchAEKey.of("E")));
            Assertions.assertEquals(4L, plan.usedItems().get(BenchAEKey.of("F")));
            Assertions.assertEquals(4L, plan.usedItems().get(BenchAEKey.of("G")));
        }
        Assertions.assertNotNull(first);
    }

    /**
     * Correctness under reuse when the network is later short on an ingredient: the
     * reused bundle claims a per-craft need, but the deficit-aware apply must still
     * report the shortfall as missing (never invent stock from a stale bundle).
     */
    @Test
    void reuseVmWithLaterShortfall() throws Exception {
        Fixture fx = new Fixture();
        Function<AEKey, IPatternDetails> resolver = fx.byOutput::get;
        PatternCompiler.clearCache();
        CraftingVM vm = new CraftingVM("jit-reuse-bench", resolver);

        // First request: full stock → clean capture of every sub-pattern.
        ICraftingPlan ok = vm.execute(fx.request(4), new BenchSimulationState(fx.stock));
        Assertions.assertTrue(ok.missingItems().isEmpty(), "first request should be feasible");

        // Second request: stock now only covers 2 A worth (D,E=2 ; F,G=2).
        Map<BenchAEKey, Long> shortStock = new HashMap<>();
        shortStock.put(BenchAEKey.of("D"), 2L);
        shortStock.put(BenchAEKey.of("E"), 2L);
        shortStock.put(BenchAEKey.of("F"), 2L);
        shortStock.put(BenchAEKey.of("G"), 2L);
        ICraftingPlan shortPlan = vm.execute(fx.request(4), new BenchSimulationState(shortStock));
        System.out.println("[JIT-REUSE-SHORT] sim=" + shortPlan.simulation()
                + " missing=" + shortPlan.missingItems()
                + " usedD=" + shortPlan.usedItems().get(BenchAEKey.of("D")));
        // With reused bundles, the deficit-aware apply must report the shortfall:
        // need 4 D, only 2 in stock → used 2, missing 2.
        Assertions.assertTrue(shortPlan.simulation(), "short request should be simulation (missing)");
        Assertions.assertEquals(2L, shortPlan.usedItems().get(BenchAEKey.of("D")),
                "D used should be the 2 actually extractable");
        Assertions.assertEquals(2L, shortPlan.missingItems().get(BenchAEKey.of("D")),
                "D missing should be the 2 shortfall");
    }

    /**
     * REGRESSION (v1.9.5): a reused VM must NOT carry realStockCache across requests.
     * realStockOf() lazily snapshots the network inventory once per execute; with the
     * per-grid VM reuse the snapshot from request #1 was reused by request #2, so a
     * stock-aware aggregation read the OLD stock and reported wrong quantities
     * ("数量出错了"). Uses a real IGrid (FakeBenchGrid) so realStockOf() observes
     * the actual stock, and a MUTABLE stock map shared by grid + simulation so the
     * second request genuinely sees less inventory.
     */
    @Test
    void reuseVmMustRefreshStockSnapshot() throws Exception {
        Fixture fx = new Fixture();
        Function<AEKey, IPatternDetails> resolver = fx.byOutput::get;
        PatternCompiler.clearCache();

        // Mutable stock shared by the grid (realStockOf) and the simulation.
        Map<BenchAEKey, Long> stock = new HashMap<>();
        stock.put(BenchAEKey.of("D"), 4L);
        stock.put(BenchAEKey.of("E"), 4L);
        stock.put(BenchAEKey.of("F"), 4L);
        stock.put(BenchAEKey.of("G"), 4L);
        FakeBenchGrid grid = new FakeBenchGrid(stock);
        CraftingVM vm = new CraftingVM(grid, resolver);

        // Request 1: full stock → clean capture; realStockCache snapshot = 4.
        ICraftingPlan ok = vm.execute(fx.request(4), new BenchSimulationState(stock));
        Assertions.assertTrue(ok.missingItems().isEmpty(), "first request should be feasible");
        Assertions.assertEquals(4L, ok.usedItems().get(BenchAEKey.of("D")));

        // Simulate the first craft consuming the network: only 2 D left now.
        stock.put(BenchAEKey.of("D"), 2L);
        stock.put(BenchAEKey.of("E"), 2L);
        stock.put(BenchAEKey.of("F"), 2L);
        stock.put(BenchAEKey.of("G"), 2L);

        // Request 2 on the SAME VM: must re-snapshot (realStockOf returns 2, not 4).
        // If realStockCache leaks, the stock-aware aggregation sees D=4 and plans 4
        // crafts without missing → wrong quantities.
        ICraftingPlan plan2 = vm.execute(fx.request(4), new BenchSimulationState(stock));
        System.out.println("[JIT-REUSE-STOCK] req2 usedD=" + plan2.usedItems().get(BenchAEKey.of("D"))
                + " missing=" + plan2.missingItems() + " sim=" + plan2.simulation());
        Assertions.assertEquals(2L, plan2.usedItems().get(BenchAEKey.of("D")),
                "used[D] must reflect the refreshed stock (2), not the stale 4");
        Assertions.assertTrue(plan2.simulation(), "req2 must be a simulation (missing)");
        Assertions.assertEquals(2L, plan2.missingItems().get(BenchAEKey.of("D")),
                "missing[D] must be the 2 shortfall after stock dropped to 2");
    }

    /**
     * REGRESSION (v1.9.5 realStockCache): a reused VM must RE-READ real network stock
     * of a CRAFTABLE sub-item on every request. This is the exact production symptom
     * ("首次下单正常，后续数量一致错误"): request #1 captures bundleCache AND lazily
     * snapshots realStockCache; if request #2 reuses that stale snapshot,
     * realStockOf(B) still returns request #1's stock and the stock-aware aggregation
     * under-crafts B (takes the stale amount from stock, never crafts the deficit).
     *
     * <p>Only a sub-item that HAS a pattern exercises realStockOf() — leaf items
     * (no pattern) never reach the stock-aware branch, they go through the bundle's
     * deficit-aware extract instead (which is what reuseVmMustRefreshStockSnapshot
     * actually verifies). So this fixture seeds B in stock AND gives B a recipe: B is
     * the stock-aware decision point.
     */
    @Test
    void reuseVmMustReReadStockForCraftableSubItem() throws Exception {
        // A <- B + C ; B <- D + E ; C <- F + G
        // Network stock: B=100 (craftable but plentiful), D/E/F/G=8, C=0 (must craft).
        BenchAEKey A = BenchAEKey.of("A");
        BenchAEKey B = BenchAEKey.of("B");
        BenchAEKey C = BenchAEKey.of("C");
        BenchAEKey D = BenchAEKey.of("D");
        BenchAEKey E = BenchAEKey.of("E");
        BenchAEKey F = BenchAEKey.of("F");
        BenchAEKey G = BenchAEKey.of("G");
        Map<AEKey, IPatternDetails> byOutput = new HashMap<>();
        byOutput.put(A, new BenchPatternDetails(A, 1, List.of(
                BenchPatternDetails.InputSpec.of(B, 1),
                BenchPatternDetails.InputSpec.of(C, 1))));
        byOutput.put(B, new BenchPatternDetails(B, 1, List.of(
                BenchPatternDetails.InputSpec.of(D, 1),
                BenchPatternDetails.InputSpec.of(E, 1))));
        byOutput.put(C, new BenchPatternDetails(C, 1, List.of(
                BenchPatternDetails.InputSpec.of(F, 1),
                BenchPatternDetails.InputSpec.of(G, 1))));
        Function<AEKey, IPatternDetails> resolver = byOutput::get;
        PatternCompiler.clearCache();

        // Mutable stock shared by the grid (realStockOf) and the simulation.
        Map<BenchAEKey, Long> stock = new HashMap<>();
        stock.put(B, 100L);
        stock.put(D, 8L);
        stock.put(E, 8L);
        stock.put(F, 8L);
        stock.put(G, 8L);
        FakeBenchGrid grid = new FakeBenchGrid(stock);
        CraftingVM vm = new CraftingVM(grid, resolver);

        // Request #1: 4 A. B is fully in stock → take B from stock (usedB=4), craft 0 B.
        ICraftingPlan plan1 = vm.execute(PatternCompiler.compileRequest(byOutput.get(A), 4),
                new BenchSimulationState(stock));
        System.out.println("[REUSE-B-STOCK] req1 usedB=" + plan1.usedItems().get(B)
                + " craftB=" + plan1.patternTimes().getOrDefault(byOutput.get(B), 0L)
                + " missing=" + plan1.missingItems() + " sim=" + plan1.simulation());
        Assertions.assertTrue(plan1.missingItems().isEmpty(), "req1 feasible");
        Assertions.assertEquals(4L, plan1.usedItems().get(B), "req1: B taken from stock");
        Assertions.assertEquals(0L, plan1.patternTimes().getOrDefault(byOutput.get(B), 0L),
                "req1: no B crafted (stock covers it)");

        // The craft consumed network B: only 2 left (D/E/F/G unchanged).
        stock.put(B, 2L);

        // Request #2 on the SAME VM: realStockOf(B) must return 2, not the stale 100.
        // Correct: take 2 from stock + craft 2 B. Leak: still sees 100 → takes 4, crafts 0.
        ICraftingPlan plan2 = vm.execute(PatternCompiler.compileRequest(byOutput.get(A), 4),
                new BenchSimulationState(stock));
        System.out.println("[REUSE-B-STOCK] req2 usedB=" + plan2.usedItems().get(B)
                + " craftB=" + plan2.patternTimes().getOrDefault(byOutput.get(B), 0L)
                + " missing=" + plan2.missingItems() + " sim=" + plan2.simulation());
        StringBuilder missDetail = new StringBuilder("[REUSE-B-STOCK] req2 MISSING DETAIL:");
        for (var e : plan2.missingItems()) {
            missDetail.append(" ").append(e.getLongValue()).append("x").append(e.getKey());
        }
        System.out.println(missDetail);
        StringBuilder usedDetail = new StringBuilder("[REUSE-B-STOCK] req2 USED DETAIL:");
        for (var e : plan2.usedItems()) {
            usedDetail.append(" ").append(e.getLongValue()).append("x").append(e.getKey());
        }
        System.out.println(usedDetail);
        Assertions.assertEquals(2L, plan2.usedItems().get(B),
                "req2: used[B] must be the refreshed stock (2), not the stale 100");
        Assertions.assertEquals(2L, plan2.patternTimes().getOrDefault(byOutput.get(B), 0L),
                "req2: must craft 2 B to cover the 2-craft shortfall");
        Assertions.assertTrue(plan2.missingItems().isEmpty(), "req2 feasible");
    }
}
