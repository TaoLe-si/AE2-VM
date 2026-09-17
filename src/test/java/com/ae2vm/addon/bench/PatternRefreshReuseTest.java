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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reproduces the 1.20.1 report: a NEWLY written pattern is not recognised by the AE
 * network as an INTERMEDIATE product (while it IS recognised as a final request),
 * until a game restart.
 *
 * <p>Root cause hypothesis (verified against the VM's JIT bundleCache): when an
 * intermediate key {@code X} had NO pattern while the parent chain was first captured,
 * the parent's cached bundle records {@code X} as a capture-time {@code missing} leaf.
 * The JIT {@code bundleCache} persists across requests (per-grid VM reuse). When the
 * player then adds the {@code X} pattern, the cached parent bundle is reused WITHOUT
 * re-resolving {@code X} (the realtime missing re-check only re-tests stock, never
 * re-dispatches a newly added pattern), so {@code X} stays "missing" until a restart
 * clears the cache. Directly requesting {@code X} works because the top-level
 * {@code getCraftingFor(X)} resolves the new pattern fresh.
 *
 * <p>This test drives a SINGLE reused VM through two requests: first with {@code X}
 * un-patterned (captures the chain), then after {@code X}'s pattern is added. If the
 * stale bundle is reused, the second plan still reports {@code X} missing → the bug is
 * present on this version.
 */
public class PatternRefreshReuseTest {

    private static final BenchAEKey G = BenchAEKey.of("G");
    private static final BenchAEKey F = BenchAEKey.of("F");
    private static final BenchAEKey H = BenchAEKey.of("H");
    private static final BenchAEKey X = BenchAEKey.of("X");
    private static final BenchAEKey Y = BenchAEKey.of("Y");

    private static IPatternDetails pat(String out, String... ins) {
        var inputs = new java.util.ArrayList<BenchPatternDetails.InputSpec>();
        for (String in : ins) {
            inputs.add(BenchPatternDetails.InputSpec.of(BenchAEKey.of(in), 1));
        }
        return new BenchPatternDetails(BenchAEKey.of(out), 1, inputs, List.of(), null);
    }

    @Test
    void newlyAddedIntermediatePatternIsRecognizedByReusedVm() {
        // G = F + H ; F = X + Y. X initially has NO pattern (stocked Y covers both).
        IPatternDetails patG = pat("G", "F", "H");
        IPatternDetails patF = pat("F", "X", "Y");
        IPatternDetails patX = pat("X", "Y");

        Map<BenchAEKey, IPatternDetails> patterns = new HashMap<>();
        patterns.put(G, patG);
        patterns.put(F, patF);
        // X pattern deliberately NOT registered yet.

        PatternCompiler.clearCache();
        PatternCompiler.clearFuzzyGroups();
        PatternCompiler.compileIfAbsent(patG);
        PatternCompiler.compileIfAbsent(patF);
        CraftingBytecode reqG = PatternCompiler.compileRequest(patG, 1);

        CraftingVM vm = new CraftingVM("test", patterns::get);

        Map<BenchAEKey, Long> stock = new HashMap<>();
        stock.put(H, 1L);
        stock.put(Y, 200L);

        // 1) First request: X has no pattern → capture the chain; X is a missing leaf.
        ICraftingPlan p1 = vm.execute(reqG, new BenchSimulationState(new HashMap<>(stock)));
        assertTrue(p1.missingItems().get(X) > 0,
                "precondition: X must be missing while it has no pattern, missing=" + p1.missingItems());

        // 2) Player writes the X pattern and puts it into the provider (same grid/VM reused).
        //    PatternProviderLogic.updatePatterns fires → PatternCompiler.bumpPatternVersion().
        patterns.put(X, patX);
        PatternCompiler.compileIfAbsent(patX);
        PatternCompiler.bumpPatternVersion();
        vm.setPatternResolver(patterns::get);

        // 3) Second request on the SAME VM: X now has a pattern → must be recognised and
        //    crafted, NOT reported missing from a stale cached parent bundle.
        ICraftingPlan p2 = vm.execute(reqG, new BenchSimulationState(new HashMap<>(stock)));
        assertEquals(0L, p2.missingItems().get(X),
                "newly added intermediate pattern X must be recognised after the parent chain was "
                        + "cached (no stale bundle reuse), missing=" + p2.missingItems());
    }
}
