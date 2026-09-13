package com.ae2vm.addon.bench;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.stacks.AEKey;
import com.ae2vm.addon.api.AE2VMCrafting;
import com.ae2vm.addon.compiler.PatternCompiler;
import com.ae2vm.addon.vm.CraftingBytecode;
import com.ae2vm.addon.vm.CraftingVM;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * REGRESSION (v1.12.58 MULTI-JOB STALL) — "下多个合成任务会卡住，取消重新下单又能合成"。
 *
 * <p><b>Reproduction of the GTL failure mode.</b> GTL's pattern buffers (ME 样板总成 /
 * MESuperPatternBufferPartMachine FOA 模式 / gtlcore 倍率切换) re-encode their patterns into
 * <b>fresh {@link IPatternDetails} instances</b> whenever the machine's recipe cache or
 * output multiplier is rebuilt, and GTL <b>batches</b> its provider refresh
 * ({@code ae2CraftingServiceUpdateInterval = 4 ticks}), so
 * {@code PatternCompiler.bumpPatternVersion()} has NOT fired yet when the next order is
 * placed. Inside that window:
 * <ol>
 *   <li>the memoized fast path ({@code CraftingVM.tryFastPath}) replays the PREVIOUS plan
 *       verbatim — including its {@code patternTimes} keys, i.e. the OLD instances;</li>
 *   <li>the JIT bundles are reused as well, because {@code patternsEquivalent()} compares
 *       by CONTENT and a re-encoded instance looks "unchanged".</li>
 * </ol>
 * The plan therefore reports feasible while {@code CraftingService.getProviders()} no
 * longer knows those instances → the CPU accepts the job, no provider ever picks it up,
 * and the job sits at 0% forever. Cancelling and re-ordering works because by then the
 * batched refresh landed, the version was bumped, and a fresh capture bound the live
 * instances.
 *
 * <p>These tests pin both halves: the VM-level staleness (documented, still true) and the
 * deliverability guard that must neutralise it before the plan reaches the CPU.</p>
 */
public class GtlStalePatternInstanceStallTest {

    private static final BenchAEKey A = BenchAEKey.of("A");
    private static final BenchAEKey B = BenchAEKey.of("B");
    private static final BenchAEKey C = BenchAEKey.of("C");
    private static final BenchAEKey D = BenchAEKey.of("D");
    private static final BenchAEKey E = BenchAEKey.of("E");

    /** A <- B + C ; B <- D ; C <- E. D/E are pure leaves with stock. */
    private static Map<BenchAEKey, IPatternDetails> buildPatterns() {
        Map<BenchAEKey, IPatternDetails> byOutput = new HashMap<>();
        byOutput.put(A, new BenchPatternDetails(A, 1, List.of(
                BenchPatternDetails.InputSpec.of(B, 1),
                BenchPatternDetails.InputSpec.of(C, 1))));
        byOutput.put(B, new BenchPatternDetails(B, 1, List.of(
                BenchPatternDetails.InputSpec.of(D, 1))));
        byOutput.put(C, new BenchPatternDetails(C, 1, List.of(
                BenchPatternDetails.InputSpec.of(E, 1))));
        return byOutput;
    }

    private static Map<BenchAEKey, Long> buildStock() {
        Map<BenchAEKey, Long> stock = new HashMap<>();
        stock.put(D, 4L);
        stock.put(E, 4L);
        return stock;
    }

    /** A "live pattern registry" that mimics {@code CraftingService#getCraftingFor}. */
    private static Function<AEKey, Collection<IPatternDetails>> lookup(
            Map<BenchAEKey, IPatternDetails> byOutput) {
        return k -> {
            IPatternDetails p = byOutput.get(k);
            if (p == null) return List.of();
            Collection<IPatternDetails> out = new ArrayList<>(1);
            out.add(p);
            return out;
        };
    }

    private static boolean containsInstance(ICraftingPlan plan, IPatternDetails p) {
        return plan.patternTimes().keySet().stream().anyMatch(x -> x == p);
    }

    // =========================================================================
    // 1) The VM-level staleness this fix exists for (documents the bug).
    // =========================================================================
    @Test
    void warmFastPathReplaysPreviousPatternInstances() {
        Map<BenchAEKey, IPatternDetails> byOutput = buildPatterns();
        Map<BenchAEKey, Long> stock = buildStock();
        Function<AEKey, IPatternDetails> resolver = k -> byOutput.get(k);
        PatternCompiler.clearCache();

        CraftingVM vm = new CraftingVM("stale-instance-bench", resolver);
        ICraftingPlan plan1 = vm.execute(PatternCompiler.compileRequest(byOutput.get(A), 4),
                new BenchSimulationState(stock));
        Assertions.assertTrue(plan1.missingItems().isEmpty(), "first order must be feasible");
        Assertions.assertTrue(containsInstance(plan1, byOutput.get(A)), "plan1 uses the live A pattern");

        // GTL re-encodes: brand-new instances, IDENTICAL content, NO version bump
        // (the batched provider refresh has not landed yet).
        IPatternDetails oldA = byOutput.get(A);
        byOutput.put(A, new BenchPatternDetails(A, 1, List.of(
                BenchPatternDetails.InputSpec.of(B, 1),
                BenchPatternDetails.InputSpec.of(C, 1))));
        byOutput.put(B, new BenchPatternDetails(B, 1, List.of(
                BenchPatternDetails.InputSpec.of(D, 1))));
        byOutput.put(C, new BenchPatternDetails(C, 1, List.of(
                BenchPatternDetails.InputSpec.of(E, 1))));
        IPatternDetails liveA = byOutput.get(A);

        // Same VM, same output + amount → the memoized fast path replays plan1 verbatim.
        ICraftingPlan plan2 = vm.execute(PatternCompiler.compileRequest(liveA, 4),
                new BenchSimulationState(stock));
        System.out.println("[STALE-INSTANCE] plan2 uses OLD A instance = "
                + containsInstance(plan2, oldA) + ", LIVE A instance = "
                + containsInstance(plan2, liveA));

        // This is exactly the stall seed: a warm plan still bound to the pre-re-encode
        // instance. The deliverability guard (test 2) is what makes it harmless.
        Assertions.assertNotNull(plan2);
        Assertions.assertTrue(plan2.missingItems().isEmpty(), "warm replay still reports feasible");
    }

    // =========================================================================
    // 2) The fix: rebind every dispatched pattern to the LIVE instance.
    // =========================================================================
    @Test
    void rebindReplacesReencodedInstancesWithLiveOnes() {
        Map<BenchAEKey, IPatternDetails> byOutput = buildPatterns();
        Map<BenchAEKey, Long> stock = buildStock();
        Function<AEKey, IPatternDetails> resolver = k -> byOutput.get(k);
        PatternCompiler.clearCache();

        CraftingVM vm = new CraftingVM("stale-rebind-bench", resolver);
        ICraftingPlan plan1 = vm.execute(PatternCompiler.compileRequest(byOutput.get(A), 4),
                new BenchSimulationState(stock));
        Assertions.assertTrue(plan1.missingItems().isEmpty());

        // GTL re-encode without a version bump, same content.
        IPatternDetails oldA = byOutput.get(A);
        IPatternDetails oldB = byOutput.get(B);
        IPatternDetails oldC = byOutput.get(C);
        byOutput.put(A, new BenchPatternDetails(A, 1, List.of(
                BenchPatternDetails.InputSpec.of(B, 1),
                BenchPatternDetails.InputSpec.of(C, 1))));
        byOutput.put(B, new BenchPatternDetails(B, 1, List.of(
                BenchPatternDetails.InputSpec.of(D, 1))));
        byOutput.put(C, new BenchPatternDetails(C, 1, List.of(
                BenchPatternDetails.InputSpec.of(E, 1))));

        ICraftingPlan warm = vm.execute(PatternCompiler.compileRequest(byOutput.get(A), 4),
                new BenchSimulationState(stock));
        ICraftingPlan deliverable = AE2VMCrafting.rebindStalePatterns(lookup(byOutput), warm);

        Assertions.assertNotNull(deliverable,
                "a content-equal live instance exists → the plan must be deliverable");
        Assertions.assertFalse(containsInstance(deliverable, oldA),
                "stale A instance must NOT reach the CPU");
        Assertions.assertFalse(containsInstance(deliverable, oldB),
                "stale B instance must NOT reach the CPU");
        Assertions.assertFalse(containsInstance(deliverable, oldC),
                "stale C instance must NOT reach the CPU");
        for (var e : deliverable.patternTimes().entrySet()) {
            var out = e.getKey().getPrimaryOutput();
            Collection<IPatternDetails> live = lookup(byOutput).apply(out.what());
            boolean liveInstance = false;
            for (var c : live) {
                if (c == e.getKey()) { liveInstance = true; break; }
            }
            Assertions.assertTrue(liveInstance,
                    "every delivered pattern must be the live instance for " + out.what());
        }
        Assertions.assertEquals(4L, deliverable.usedItems().get(D), "used[D] preserved by rebind");
        Assertions.assertEquals(4L, deliverable.usedItems().get(E), "used[E] preserved by rebind");
        Assertions.assertEquals(warm.patternTimes().size(), deliverable.patternTimes().size(),
                "rebind must not drop or merge craft entries");
    }

    // =========================================================================
    // 3) Re-encoded with DIFFERENT content and no live equivalent → undeliverable.
    // =========================================================================
    @Test
    void rebindReturnsNullWhenReencodedWithoutLiveEquivalent() {
        Map<BenchAEKey, IPatternDetails> byOutput = buildPatterns();

        // A plan still bound to a pattern the network no longer knows: the machine was
        // re-encoded with DIFFERENT content (B <- 3D) and no content-equal successor is
        // registered any more. Such a plan must never reach the CPU (it would stall), so
        // the guard reports it undeliverable and the caller re-captures.
        IPatternDetails deadB = new BenchPatternDetails(B, 1, List.of(
                BenchPatternDetails.InputSpec.of(D, 3)));
        ICraftingPlan planWithDeadPattern = new appeng.crafting.CraftingPlan(
                new appeng.api.stacks.GenericStack(A, 4), 88, false, false,
                new appeng.api.stacks.KeyCounter(), new appeng.api.stacks.KeyCounter(),
                new appeng.api.stacks.KeyCounter(),
                java.util.Map.of(deadB, 1L));

        ICraftingPlan deliverable =
                AE2VMCrafting.rebindStalePatterns(lookup(byOutput), planWithDeadPattern);
        Assertions.assertNull(deliverable,
                "no live content-equal instance → the plan is undeliverable and must be re-captured");
    }

    // =========================================================================
    // 4) GTL provider-refresh window (getCraftingFor empty) must NOT force a re-capture.
    // =========================================================================
    @Test
    void rebindKeepsPlanDuringProviderRefreshWindow() {
        Map<BenchAEKey, IPatternDetails> byOutput = buildPatterns();
        Map<BenchAEKey, Long> stock = buildStock();
        Function<AEKey, IPatternDetails> resolver = k -> byOutput.get(k);
        PatternCompiler.clearCache();

        CraftingVM vm = new CraftingVM("stale-rebind-window", resolver);
        ICraftingPlan plan1 = vm.execute(PatternCompiler.compileRequest(byOutput.get(A), 4),
                new BenchSimulationState(stock));

        // Between removeProvider and addProvider, getCraftingFor is transiently empty.
        ICraftingPlan deliverable = AE2VMCrafting.rebindStalePatterns(k -> List.of(), plan1);
        Assertions.assertSame(plan1, deliverable,
                "an empty provider window is not evidence of a dead pattern — keep the plan");
    }

    // =========================================================================
    // 5) End-to-end: after a GTL re-encode the delivered plan is always schedulable.
    // =========================================================================
    @Test
    void secondOrderAfterGtlReencodeIsStillSchedulable() {
        Map<BenchAEKey, IPatternDetails> byOutput = buildPatterns();
        Map<BenchAEKey, Long> stock = buildStock();
        Function<AEKey, IPatternDetails> resolver = k -> byOutput.get(k);
        PatternCompiler.clearCache();

        CraftingVM vm = new CraftingVM("stale-flow", resolver);

        // Order #1 — normal.
        ICraftingPlan plan1 = vm.execute(PatternCompiler.compileRequest(byOutput.get(A), 4),
                new BenchSimulationState(stock));
        Assertions.assertTrue(plan1.missingItems().isEmpty(), "order #1 feasible");

        // The GTL machine re-encodes its patterns (same content) with no version bump.
        byOutput.put(A, new BenchPatternDetails(A, 1, List.of(
                BenchPatternDetails.InputSpec.of(B, 1),
                BenchPatternDetails.InputSpec.of(C, 1))));
        byOutput.put(B, new BenchPatternDetails(B, 1, List.of(
                BenchPatternDetails.InputSpec.of(D, 1))));
        byOutput.put(C, new BenchPatternDetails(C, 1, List.of(
                BenchPatternDetails.InputSpec.of(E, 1))));

        // Order #2 — warm replay, then the deliverability guard (mirrors AE2VMCrafting).
        ICraftingPlan warm = vm.execute(PatternCompiler.compileRequest(byOutput.get(A), 4),
                new BenchSimulationState(stock));
        ICraftingPlan deliverable = AE2VMCrafting.rebindStalePatterns(lookup(byOutput), warm);
        if (deliverable == null) {
            vm.clearBundleCache();
            Map<AEKey, Object> rcache = vm.getResolverCache();
            if (rcache != null) rcache.clear();
            PatternCompiler.invalidate(byOutput.get(A));
            PatternCompiler.compileIfAbsent(byOutput.get(A));
            CraftingBytecode req = PatternCompiler.compileRequest(byOutput.get(A), 4);
            deliverable = AE2VMCrafting.rebindStalePatterns(lookup(byOutput),
                    vm.execute(req, new BenchSimulationState(stock)));
        }
        Assertions.assertNotNull(deliverable, "order #2 must end up deliverable");
        for (var e : deliverable.patternTimes().entrySet()) {
            var out = e.getKey().getPrimaryOutput();
            Collection<IPatternDetails> live = lookup(byOutput).apply(out.what());
            boolean liveInstance = false;
            for (var c : live) if (c == e.getKey()) { liveInstance = true; break; }
            Assertions.assertTrue(liveInstance,
                    "order #2 dispatched a pattern the network no longer knows: " + out.what());
        }
        Assertions.assertTrue(deliverable.missingItems().isEmpty(), "order #2 feasible");
    }
}
