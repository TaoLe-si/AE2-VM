package com.ae2vm.addon.bench;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.stacks.AEKey;
import com.ae2vm.addon.compiler.PatternCompiler;
import com.ae2vm.addon.vm.CraftingBytecode;
import com.ae2vm.addon.vm.CraftingVM;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Spike: verifies AE2 classes load and the VM runs offline against a
 * {@link BenchSimulationState} (no IGrid / Minecraft world), mirroring the
 * production path in {@code AE2VMCrafting.calculateAsync} but with fake
 * pattern details + in-memory stock.
 */
public class VmBridgeSpikeTest {

    @Test
    public void spikeDispersedDag() {
        // A <- B + C; B <- D + E; C <- F + G; stock D,E,F,G = 4 each; craft 4 A.
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

        Map<BenchAEKey, Long> stock = new HashMap<>();
        stock.put(D, 4L);
        stock.put(E, 4L);
        stock.put(F, 4L);
        stock.put(G, 4L);

        Function<AEKey, IPatternDetails> resolver = byOutput::get;

        IPatternDetails top = byOutput.get(A);
        PatternCompiler.compileIfAbsent(top);
        CraftingBytecode req = PatternCompiler.compileRequest(top, 4);

        long start = System.nanoTime();
        CraftingVM vm = new CraftingVM("bench", resolver);
        ICraftingPlan plan = vm.execute(req, new BenchSimulationState(stock));
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        System.out.println("[SPIKE] plan output=" + plan.finalOutput().what() + " x "
                + plan.finalOutput().amount()
                + " sim=" + plan.simulation()
                + " used=" + plan.usedItems()
                + " missing=" + plan.missingItems()
                + " elapsedMs=" + elapsedMs);

        org.junit.jupiter.api.Assertions.assertTrue(plan.missingItems().isEmpty(), "expected no missing");
        org.junit.jupiter.api.Assertions.assertEquals(4L, plan.usedItems().get(D));
        org.junit.jupiter.api.Assertions.assertEquals(4L, plan.usedItems().get(E));
        org.junit.jupiter.api.Assertions.assertEquals(4L, plan.usedItems().get(F));
        org.junit.jupiter.api.Assertions.assertEquals(4L, plan.usedItems().get(G));
    }
}
