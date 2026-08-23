package com.ae2vm.addon.bench;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import com.ae2vm.addon.api.AE2VMCrafting;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * (v1.15.x GTL) Unit tests for {@link AE2VMCrafting#decomposeMissingRecursive}.
 * <p>
 * Reproduces the user's "cosmicneutronium 2万 reports missing self" bug as
 * an in-memory recipe graph — no AE2 network, no Minecraft. Each test
 * builds a small recipe graph (cosmic_recipe → plasma_cell → ... → leaf),
 * invokes the decomposer, and asserts the resulting missing list contains
 * the expected leaves rather than the target.
 * <p>
 * Run with {@code ./gradlew test --tests GtlDecomposeMissingTest}.
 */
class GtlDecomposeMissingTest {

    // ---------- Fixture keys ----------
    private static final BenchAEKey COSMIC    = BenchAEKey.of("gtceu:cosmicneutronium");
    private static final BenchAEKey PLASMA   = BenchAEKey.of("kubejs:cosmic_neutron_plasma_cell");
    private static final BenchAEKey DURABLE  = BenchAEKey.of("kubejs:extremely_durable_plasma_cell");
    private static final BenchAEKey UUMATTER = BenchAEKey.of("gtceu:uu_matter");
    private static final BenchAEKey DENSE    = BenchAEKey.of("gtceu:dense_neutron_plasma");
    // Leaves (no pattern registered)
    private static final BenchAEKey FORCE_GLASS  = BenchAEKey.of("kubejs:force_field_glass");
    private static final BenchAEKey DALISENITE   = BenchAEKey.of("gtceu:dalisenite");
    private static final BenchAEKey ECHOITE      = BenchAEKey.of("gtceu:echoite");
    private static final BenchAEKey UIV_CIRCUIT  = BenchAEKey.of("kubejs:uiv_universal_circuit");
    // Cycle (target consumes itself indirectly via ingot recipe)
    private static final BenchAEKey INGOT   = BenchAEKey.of("gtceu:cosmicneutronium_ingot");

    /** 1 plasma_cell → 1000 cosmic (the cycle-prone cosmic_recipe). */
    private final IPatternDetails cosmicRecipe;
    /** 5 plasma_cell ← 1 plasma_cell (out=5 per craft). */
    private final IPatternDetails plasmaRecipe;
    /** 15 inputs → 1 durable (modal — sub-graph rooted here). */
    private final IPatternDetails durableRecipe_real;
    /** Cosmic ingot recipe: 1 ingot = 1 cosmic (cycle back to target). */
    private final IPatternDetails ingotRecipe;
    /** Force-field-glass leaf: 1 = 1. */
    private final IPatternDetails forceGlassRecipe;

    private final Map<AEKey, List<IPatternDetails>> cand;
    private final Map<AEKey, Long> stock;

    GtlDecomposeMissingTest() {
        // Cosmic recipe: 1 plasma_cell → 1000 cosmic
        cosmicRecipe = pat(COSMIC, 1000L, List.of(in(PLASMA, 1L)));
        // Plasma recipe: 1 durable + 1 uu + 1 dense → 5 plasma_cell
        plasmaRecipe = pat(PLASMA, 5L,
                List.of(in(DURABLE, 1L), in(UUMATTER, 1L), in(DENSE, 1L)));
        // Durable recipe (REAL one — 15 inputs, mostly leaves)
        durableRecipe_real = pat(DURABLE, 1L, List.of(
                in(DALISENITE, 1L),
                in(ECHOITE, 1L),
                in(UIV_CIRCUIT, 1L),
                in(FORCE_GLASS, 1L),
                // Add some more dummy leaves for realism
                in(BenchAEKey.of("gtceu:infuscolium_nanoswarm"), 1L),
                in(BenchAEKey.of("gtceu:quantumchromodynamically_confined_matter_frame"), 1L),
                in(BenchAEKey.of("gtceu:fusion_coil"), 1L),
                in(BenchAEKey.of("gtceu:super_mutated_living_solder"), 1L),
                in(BenchAEKey.of("gtceu:taranium"), 1L),
                in(BenchAEKey.of("gtceu:double_adamantine_plate"), 1L),
                in(BenchAEKey.of("gtceu:double_celestialtungsten_plate"), 1L),
                in(BenchAEKey.of("gtceu:neutronium_plate"), 1L),
                in(BenchAEKey.of("gtceu:heavy_quark_degenerate_matter_large_fluid_pipe"), 1L),
                in(BenchAEKey.of("gtceu:uxv_field_generator"), 1L),
                in(BenchAEKey.of("gtceu:uxv_electric_pump"), 1L)
        ));
        // Cycle recipe: 1 cosmic_ingot → 1 cosmic (target consumes self indirectly)
        ingotRecipe = pat(INGOT, 1L, List.of(in(COSMIC, 1L)));
        // A trivial "leaf with pattern" that does nothing useful
        forceGlassRecipe = pat(FORCE_GLASS, 1L, List.of());

        cand = new HashMap<>();
        cand.put(COSMIC, List.of(cosmicRecipe));
        cand.put(PLASMA, List.of(plasmaRecipe));
        cand.put(DURABLE, List.of(durableRecipe_real));
        cand.put(INGOT, List.of(ingotRecipe));
        cand.put(FORCE_GLASS, List.of(forceGlassRecipe));

        stock = new HashMap<>();
        stock.put(COSMIC, 2040L);
    }

    /**
     * USER BUG: 2万 cosmicneutronium (20_000 units) requested.
     * cosmic_recipe is cycle-prone (its full chain eventually hits ingot
     * which consumes cosmic → self-reference). The decomposer should NOT
     * short-circuit at plasma_cell — it should walk into plasmaRecipe and
     * durableRecipe_real until it reaches leaves.
     * <p>
     * EXPECTED: the result must contain LEAF items like {@code dalisenite},
     * {@code echoite}, {@code force_field_glass} (the actual raw materials),
     * NOT {@code cosmic_neutron_plasma_cell} alone and NOT the target.
     */
    @Test
    void decompose_cosmicneutronium_20k_reaches_leaves() {
        long residual = 120648L; // 122688 - 2040 stock (matches user log)
        long outPerCraft = 1000L;
        long demand = residual / outPerCraft + (residual % outPerCraft == 0 ? 0 : 1);
        // demand = 121

        int[] hops = new int[]{0};
        KeyCounter result = AE2VMCrafting.decomposeMissingRecursive(
                cosmicRecipe, COSMIC, demand,
                patternLookup(), stockLookup(), hops);

        System.out.println("=== cosmicneutronium 20k decompose (residual=" + residual
                + ", demand=" + demand + ", hops=" + hops[0] + ") ===");
        for (var e : result) {
            System.out.println("  " + e.getLongValue() + "x " + e.getKey());
        }

        // Must reach the leaves — at least one of the durable sub-items.
        assertTrue(result.get(DALISENITE) > 0,
                "expected dalisenite to appear (full chain expansion), got: " + dump(result));
        assertTrue(result.get(ECHOITE) > 0,
                "expected echoite to appear (full chain expansion), got: " + dump(result));
        assertTrue(result.get(FORCE_GLASS) > 0,
                "expected force_field_glass to appear, got: " + dump(result));
        // Target itself MUST NOT be in the result.
        assertEquals(0L, result.get(COSMIC),
                "target cosmicneutronium should NOT be in decomposed missing");
        // Intermediate surface entries ARE allowed (cycle-prone plasma_cell kept).
        // But if the decomposer ran past plasma_cell, plasma_cell should be REMOVED.
        assertEquals(0L, result.get(PLASMA),
                "intermediate plasma_cell should be replaced by its inputs (recursive), got: " + dump(result));
        // Hops counter should reflect we walked past at least one level.
        assertTrue(hops[0] >= 2, "expected ≥2 hops (plasma→durable→leaves), got " + hops[0]);
    }

    /**
     * Sanity: simple 1-hop decomposer (target → one intermediate → leaves).
     * The result should contain the leaves, with multiplied demand.
     */
    @Test
    void decompose_simpleChain_walksOneLevel() {
        // Use plasma_recipe as target's first hop — its inputs are uu_matter/dense/durable.
        // durable has a recipe with leaves. Walk 1 hop from PLASMA's recipe.
        long demand = 10L; // need 10 plasma cells
        int[] hops = new int[]{0};
        KeyCounter result = AE2VMCrafting.decomposeMissingRecursive(
                plasmaRecipe, PLASMA, demand,
                patternLookup(), stockLookup(), hops);
        System.out.println("=== simple 1-level (plasmaRecipe, demand=10, hops=" + hops[0] + ") ===");
        for (var e : result) {
            System.out.println("  " + e.getLongValue() + "x " + e.getKey());
        }
        // plasma should be REMOVED (replaced by its inputs at 1 hop).
        assertEquals(0L, result.get(PLASMA));
        // durable/uumatter/dense should appear with multiplied demand.
        assertTrue(result.get(UUMATTER) > 0, "uumatter expected");
        assertEquals(10L, result.get(UUMATTER), "uumatter wrong");
        // durable is NOT a leaf — has a recipe → should be further decomposed.
        // After 2nd hop, durable is replaced by 15 leaves (× 10 demand = 150 each).
        assertEquals(0L, result.get(DURABLE),
                "durable should be further decomposed to its leaves");
        // Leaves should appear.
        assertTrue(result.get(DALISENITE) > 0);
    }

    /**
     * Cycle-prone intermediate is kept as surface — the bug-driven design.
     * If a sub-recipe's input chain loops back to target, the decomposer
     * leaves that intermediate as a surface entry so the user sees the
     * cycle culprit rather than the missing list silently looping.
     */
    @Test
    void decompose_cycleProneIntermediate_keptAsSurface() {
        // Build a recipe graph where the sub-craft's chain loops back to target.
        // cosmicRecipe: PLASMA → COSMIC  (target=COSMIC, input=PLASMA)
        // selfLoopRecipe: PLASMA ← COSMIC  (cosmicRecipe's input chain returns to target)
        // chainReachesTarget: walking from PLASMA via selfLoopRecipe → input COSMIC → reached target → cycle-prone
        IPatternDetails selfLoopRecipe = pat(PLASMA, 5L, List.of(in(COSMIC, 1L)));
        Map<AEKey, List<IPatternDetails>> c = new HashMap<>();
        c.put(PLASMA, List.of(selfLoopRecipe));
        c.put(COSMIC, List.of(cosmicRecipe)); // include cosmicRecipe so walking from PLASMA via selfLoopRecipe→COSMIC finds cosmicRecipe

        Function<AEKey, Collection<IPatternDetails>> lookup = k -> c.getOrDefault(k, List.of());
        Function<AEKey, Long> stock = k -> 0L;

        // Decompose cosmicRecipe: cosmicRecipe.getInputs() = [PLASMA]. The decomposer
        // walks PLASMA → vmCraftingFor(PLASMA) returns [selfLoopRecipe] → input COSMIC
        // (target!) → chainReachesTarget returns TRUE → cycle-prone → kept as surface.
        int[] hops = new int[]{0};
        KeyCounter result = AE2VMCrafting.decomposeMissingRecursive(
                cosmicRecipe, COSMIC, 5L, lookup, stock, hops);
        System.out.println("=== cycle-prone intermediate (hops=" + hops[0] + ") ===");
        for (var e : result) {
            System.out.println("  " + e.getLongValue() + "x " + e.getKey());
        }
        // PLASMA is cycle-prone → kept as surface (not further decomposed).
        assertTrue(result.get(PLASMA) > 0,
                "cycle-prone intermediate plasma should be kept as surface, got: " + dump(result));
    }

    // ---------- helpers ----------
    private Function<AEKey, Collection<IPatternDetails>> patternLookup() {
        return k -> cand.getOrDefault(k, List.of());
    }

    private Function<AEKey, Long> stockLookup() {
        return k -> stock.getOrDefault(k, 0L);
    }

    private static IPatternDetails pat(BenchAEKey out, long outCount, List<BenchPatternDetails.InputSpec> ins) {
        return new BenchPatternDetails(out, outCount, ins);
    }

    private static BenchPatternDetails.InputSpec in(BenchAEKey k, long n) {
        return BenchPatternDetails.InputSpec.of(k, n);
    }

    private static String dump(KeyCounter kc) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (var e : kc) {
            if (!first) sb.append(", ");
            first = false;
            sb.append(e.getLongValue()).append("x ").append(e.getKey());
        }
        sb.append("}");
        return sb.toString();
    }
}
