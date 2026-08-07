package com.ae2vm.addon.bench;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.stacks.AEKey;
import com.ae2vm.addon.compiler.PatternCompiler;
import com.ae2vm.addon.vm.CraftingBytecode;
import com.ae2vm.addon.vm.CraftingVM;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

/**
 * BOUNDARY capability suite (v1.9.13): the NAST server report — "合成一个物品或液
 * 体，1 个/1b 缺失无法合成，但 2 个/100b 正常" + "有样板却报缺失". These are the
 * quantity-boundary and fuzzy-replacement-stock scenarios the original 33-case
 * suite (String-keyed {@link Ae2VmReferencePlanner}, where {@code realStockOf}
 * always returns 0) can NEVER exercise. This suite drives the VM through a real
 * {@link FakeBenchGrid} so the stock-aware aggregation and the fuzzy-group
 * substitute-stock path (the v1.9.13 fix) are actually validated.
 *
 * <p>Every case asserts {@code expectedFeasible} and prints one
 * {@code [reference-boundary]} row + a trailing summary, so the boundary surface
 * is pinned exactly like the main reference suite.
 */
public class Ae2VmBoundaryCapabilitySuiteTest {

    /** One boundary case: id + target key + fixture builder + requested amount + expected feasibility. */
    private record BoundaryCase(
            String id, BenchAEKey target, java.util.function.Consumer<Fixture> build,
            long amount, boolean expectedFeasible) {
    }

    /** Outcome record for the summary. */
    private record Outcome(String id, boolean feasible, boolean ok, Map<String, Long> missing,
                           long elapsedMs) {
    }

    private static final ConcurrentLinkedQueue<Outcome> OUTCOMES = new ConcurrentLinkedQueue<>();

    private static final class Fixture {
        final Map<AEKey, IPatternDetails> byOutput = new HashMap<>();
        final Map<BenchAEKey, Long> stock = new HashMap<>();

        Fixture fuzzyCraftablePrimary(boolean grayCraftable) {
            BenchAEKey product = BenchAEKey.of("product");
            BenchAEKey gray = BenchAEKey.of("gray_wool");
            BenchAEKey white = BenchAEKey.of("white_wool");
            BenchAEKey black = BenchAEKey.of("black_wool");
            byOutput.put(product, new BenchPatternDetails(product, 1, List.of(
                    BenchPatternDetails.InputSpec.fuzzy(gray, 1, white))));
            if (grayCraftable) {
                byOutput.put(gray, new BenchPatternDetails(gray, 1, List.of(
                        BenchPatternDetails.InputSpec.of(black, 1))));
            }
            return this;
        }

        Fixture deepChainMidStock(int levels, String midId, long midStock) {
            BenchAEKey[] keys = new BenchAEKey[levels];
            for (int i = 0; i < levels; i++) keys[i] = BenchAEKey.of("N" + i);
            for (int i = 1; i < levels; i++) {
                byOutput.put(keys[i], new BenchPatternDetails(keys[i], 1, List.of(
                        BenchPatternDetails.InputSpec.of(keys[i - 1], 1))));
            }
            stock.put(BenchAEKey.of("N0"), 1_000_000L);
            stock.put(BenchAEKey.of(midId), midStock);
            return this;
        }

        Fixture craftableFluidPartialStock() {
            BenchAEKey blank = BenchAEKey.of("blank_pattern");
            BenchAEKey board = BenchAEKey.of("circuit_board");
            BenchAEKey fluid = BenchAEKey.of("fluid_x");
            BenchAEKey raw = BenchAEKey.of("raw");
            BenchAEKey water = BenchAEKey.of("water");
            byOutput.put(blank, new BenchPatternDetails(blank, 1, List.of(
                    BenchPatternDetails.InputSpec.of(board, 1),
                    BenchPatternDetails.InputSpec.of(fluid, 1000L))));
            byOutput.put(board, new BenchPatternDetails(board, 1, List.of(
                    BenchPatternDetails.InputSpec.of(raw, 1))));
            byOutput.put(fluid, new BenchPatternDetails(fluid, 1000L, List.of(
                    BenchPatternDetails.InputSpec.of(water, 1))));
            stock.put(raw, 1_000_000L);
            stock.put(water, 1_000_000L);
            stock.put(fluid, 500L); // partial: 500 of 1000 per craft
            return this;
        }
    }

    private static ICraftingPlan runTarget(Fixture fx, BenchAEKey target, long amount) {
        PatternCompiler.clearCache();
        PatternCompiler.clearFuzzyGroups();
        for (IPatternDetails p : fx.byOutput.values()) {
            PatternCompiler.compileIfAbsent(p);
        }
        IPatternDetails top = fx.byOutput.get(target);
        CraftingBytecode req = PatternCompiler.compileRequest(top, amount);
        FakeBenchGrid grid = new FakeBenchGrid(new HashMap<>(fx.stock));
        CraftingVM vm = new CraftingVM(grid, fx.byOutput::get);
        return vm.execute(req, new BenchSimulationState(fx.stock));
    }

    private static Map<String, Long> missing(ICraftingPlan p) {
        TreeMap<String, Long> out = new TreeMap<>();
        for (var e : p.missingItems()) out.put(((BenchAEKey) e.getKey()).itemId(), e.getLongValue());
        return out;
    }

    private static void runCase(BoundaryCase c) {
        long start = System.nanoTime();
        Fixture fx = new Fixture();
        c.build().accept(fx);
        ICraftingPlan plan = runTarget(fx, c.target(), c.amount());
        Map<String, Long> miss = missing(plan);
        boolean feasible = miss.isEmpty();
        boolean ok = feasible == c.expectedFeasible();
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
        OUTCOMES.add(new Outcome(c.id(), feasible, ok, miss, elapsedMs));
        System.out.println("[reference-boundary] engine=ae2vm id=" + c.id()
                + " amount=" + c.amount()
                + " expectedFeasible=" + c.expectedFeasible()
                + " feasible=" + feasible
                + " ok=" + ok
                + " missing=" + miss
                + " elapsedMs=" + elapsedMs);
    }

    @TestFactory
    Stream<DynamicTest> boundaryCapabilities() {
        List<BoundaryCase> cases = new ArrayList<>();

        // --- Quantity boundary: gray craftable + WHITE stocked. Request 1 vs 2 vs 100.
        // Player: "1x missing but 2x/100x works" — the 1-craft boundary. With white=1
        // stock the old code crafted ALL gray AND demanded ALL white -> false missing.
        for (long amount : new long[] { 1L, 2L, 100L }) {
            final long amt = amount;
            cases.add(new BoundaryCase(
                    "quantity/craftable-primary-white-stock/" + amount,
                    BenchAEKey.of("product"),
                    fx -> {
                        fx.fuzzyCraftablePrimary(true);
                        fx.stock.put(BenchAEKey.of("white_wool"), 1L);
                        fx.stock.put(BenchAEKey.of("black_wool"), 1000L);
                    },
                    amt, true));
        }

        // --- Same, with MORE substitute stock (10 / 100) — must still never over-craft.
        for (long amount : new long[] { 1L, 2L, 100L }) {
            final long amt = amount;
            cases.add(new BoundaryCase(
                    "quantity/craftable-primary-white-stock10/" + amount,
                    BenchAEKey.of("product"),
                    fx -> {
                        fx.fuzzyCraftablePrimary(true);
                        fx.stock.put(BenchAEKey.of("white_wool"), 10L);
                        fx.stock.put(BenchAEKey.of("black_wool"), 1000L);
                    },
                    amt, true));
        }

        // --- Craftable primary with PARTIAL primary stock (gray=40 of 100 needed) +
        //     black stocked: the v1.9.12 regression — must still schedule gray's sub-craft.
        for (long amount : new long[] { 1L, 2L, 100L }) {
            final long amt = amount;
            cases.add(new BoundaryCase(
                    "quantity/craftable-primary-partial-gray/" + amount,
                    BenchAEKey.of("product"),
                    fx -> {
                        fx.fuzzyCraftablePrimary(true);
                        fx.stock.put(BenchAEKey.of("gray_wool"), 40L);
                        fx.stock.put(BenchAEKey.of("black_wool"), 1000L);
                    },
                    amt, true));
        }

        // --- Craftable primary, NO variant stock, black stocked: must craft gray fully.
        for (long amount : new long[] { 1L, 2L, 100L }) {
            final long amt = amount;
            cases.add(new BoundaryCase(
                    "quantity/craftable-primary-no-variant-stock/" + amount,
                    BenchAEKey.of("product"),
                    fx -> {
                        fx.fuzzyCraftablePrimary(true);
                        fx.stock.put(BenchAEKey.of("black_wool"), 1000L);
                    },
                    amt, true));
        }

        // --- Fuzzy leaf primary + white stocked: replacement must satisfy the slot.
        for (long amount : new long[] { 1L, 2L, 100L }) {
            final long amt = amount;
            cases.add(new BoundaryCase(
                    "quantity/fuzzy-leaf-white-stock/" + amount,
                    BenchAEKey.of("product"),
                    fx -> {
                        fx.fuzzyCraftablePrimary(false);
                        fx.stock.put(BenchAEKey.of("white_wool"), 1000L);
                    },
                    amt, true));
        }

        // --- Deep chain + mid partial stock: request 1 (the NAST 1m-item boundary).
        //     levels 10 & 20, mid stock 0 / 1 / 5.
        for (int levels : new int[] { 10, 20 }) {
            for (long midStock : new long[] { 0L, 1L, 5L }) {
                for (long amount : new long[] { 1L, 2L, 100L }) {
                    final int lv = levels;
                    final long ms = midStock;
                    final long amt = amount;
                    cases.add(new BoundaryCase(
                            "quantity/deep-chain-mid-stock-l" + levels + "-s" + midStock + "/" + amount,
                            BenchAEKey.of("N" + (levels - 1)),
                            fx -> fx.deepChainMidStock(lv, "N" + (lv - 2), ms),
                            amt, true));
                }
            }
        }

        // --- Craftable fluid + partial fluid stock: request 1 vs 2 (1b vs 2b boundary).
        for (long amount : new long[] { 1L, 2L, 100L }) {
            final long amt = amount;
            cases.add(new BoundaryCase(
                    "quantity/craftable-fluid-partial/" + amount,
                    BenchAEKey.of("blank_pattern"),
                    fx -> fx.craftableFluidPartialStock(),
                    amt, true));
        }

        // --- Genuinely infeasible (sanity: the suite must not over-report feasibility):
        // gray NOT craftable, NO white stock -> request 1 is truly missing.
        cases.add(new BoundaryCase(
                "quantity/infeasible-no-variant-stock/1",
                BenchAEKey.of("product"),
                fx -> fx.fuzzyCraftablePrimary(false),
                1L, false));

        List<DynamicTest> tests = new ArrayList<>(cases.size() + 1);
        for (BoundaryCase c : cases) {
            tests.add(DynamicTest.dynamicTest(c.id(), () -> runCase(c)));
        }
        tests.add(DynamicTest.dynamicTest("boundary-summary", () -> printSummary()));
        return tests.stream();
    }

    private static void printSummary() {
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger feasible = new AtomicInteger();
        AtomicLong totalMs = new AtomicLong();
        for (Outcome o : OUTCOMES) {
            if (o.ok()) ok.incrementAndGet();
            if (o.feasible()) feasible.incrementAndGet();
            totalMs.addAndGet(o.elapsedMs());
        }
        System.out.println("[reference-boundary] engine=ae2vm SUMMARY cases=" + OUTCOMES.size()
                + " ok=" + ok.get()
                + " feasible=" + feasible.get()
                + " totalElapsedMs=" + totalMs.get());
        org.junit.jupiter.api.Assertions.assertEquals(OUTCOMES.size(), ok.get(),
                "every boundary case must match its expected feasibility");
    }
}
