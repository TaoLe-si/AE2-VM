package com.ae2vm.addon.bench;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.CalculationStrategy;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import com.ae2vm.addon.api.AE2VMCrafting;
import com.ae2vm.addon.compiler.PatternCompiler;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * (v1.15.x IV CHAIN) Full-chain, dynamic, concurrent, competing-ORDER benchmark for
 * the iv_energy_input_hatch_4a chain the player just ordered.
 *
 * <p>Game log (08:18): VANILLA plan = 36 patterns including
 * {@code minecraft:iron_ingot x19(*1=19)} synthesized from {@code gtceu:iron_dust=19}
 * (1:1 smelt), using the 1 iron_ingot already in stock for the remaining need of 20.
 * The VM plan = 35 patterns — the iron_ingot synthesis was MISSING — and reported
 * {@code missing[minecraft:iron_ingot=19]} even though the pattern exists
 * (resolve: candidates=1). Root cause: the iron_ingot pattern's iron_dust input slot
 * reads EMPTY on the FIRST {@code getPossibleInputs()} (GTL pattern-buffer lazily
 * builds its recipe cache) and non-empty afterwards; {@code compilePattern}'s
 * single-pass snapshot saw the empty slot and failed the whole compile
 * ("partial-empty guard"), so CALL_BY_KEY dropped the sub-craft to missing.
 *
 * <p>This benchmark mirrors that FULL iv chain: every one of the 36 pattern outputs
 * from the VANILLA plan is registered (the intermediate dependency edges use the
 * leaves the game used list exposes; the iron_ingot→iron_dust smelt edge is EXACT),
 * and the iron_ingot pattern's input is lazy (first read empty, later reads
 * iron_dust) exactly like the GTL pattern-buffer state. The plan must synthesize
 * iron_ingot x19 and report NO missing — same as vanilla.
 */
public class IvChainCompetitionBenchmark {

    // ---- iv_4a chain keys (real game ids from the VANILLA plan) ----
    private static final BenchAEKey IV_4A = BenchAEKey.of("gtceu_iv_energy_input_hatch_4a");
    private static final BenchAEKey IRON_INGOT = BenchAEKey.of("minecraft_iron_ingot");
    private static final BenchAEKey IRON_DUST = BenchAEKey.of("gtceu_iron_dust");
    private static final BenchAEKey STEEL_INGOT = BenchAEKey.of("gtceu_steel_ingot");
    private static final BenchAEKey STEEL_DUST = BenchAEKey.of("gtceu_steel_dust");
    private static final BenchAEKey TUNGSTEN_STEEL_INGOT = BenchAEKey.of("gtceu_tungsten_steel_ingot");
    private static final BenchAEKey TUNGSTEN_STEEL_DUST = BenchAEKey.of("gtceu_tungsten_steel_dust");
    private static final BenchAEKey HOT_TUNGSTEN_STEEL_INGOT = BenchAEKey.of("gtceu_hot_tungsten_steel_ingot");
    private static final BenchAEKey TUNGSTEN_DUST = BenchAEKey.of("gtceu_tungsten_dust");
    private static final BenchAEKey PLATINUM_INGOT = BenchAEKey.of("gtceu_platinum_ingot");
    private static final BenchAEKey PLATINUM_DUST = BenchAEKey.of("gtceu_platinum_dust");
    private static final BenchAEKey BATTERY_ALLOY = BenchAEKey.of("gtceu_battery_alloy");
    private static final BenchAEKey BATTERY_ALLOY_INGOT = BenchAEKey.of("gtceu_battery_alloy_ingot");
    private static final BenchAEKey BATTERY_ALLOY_BLOCK = BenchAEKey.of("gtceu_battery_alloy_block");
    private static final BenchAEKey DOUBLE_BATTERY_ALLOY_PLATE = BenchAEKey.of("gtceu_double_battery_alloy_plate");
    private static final BenchAEKey IV_MACHINE_HULL = BenchAEKey.of("gtceu_iv_machine_hull");
    private static final BenchAEKey IV_MACHINE_CASING = BenchAEKey.of("gtceu_iv_machine_casing");
    private static final BenchAEKey IV_ENERGY_INPUT_HATCH = BenchAEKey.of("gtceu_iv_energy_input_hatch");
    private static final BenchAEKey IV_ENERGY_INPUT_HATCH_16A = BenchAEKey.of("gtceu_iv_energy_input_hatch_16a");
    private static final BenchAEKey IV_TRANSFORMER_1A = BenchAEKey.of("gtceu_iv_transformer_1a");
    private static final BenchAEKey IV_VOLTAGE_COIL = BenchAEKey.of("gtceu_iv_voltage_coil");
    private static final BenchAEKey NANO_PROCESSOR = BenchAEKey.of("gtceu_nano_processor");
    private static final BenchAEKey NANO_PROCESSOR_ASSEMBLY = BenchAEKey.of("gtceu_nano_processor_assembly");
    private static final BenchAEKey NANO_PROCESSOR_COMPUTER = BenchAEKey.of("gtceu_nano_processor_computer");
    private static final BenchAEKey HPIC_CHIP = BenchAEKey.of("gtceu_hpic_chip");
    private static final BenchAEKey HPIC_WAFER = BenchAEKey.of("gtceu_hpic_wafer");
    private static final BenchAEKey EPOXY_PCB = BenchAEKey.of("gtceu_epoxy_printed_circuit_board");
    private static final BenchAEKey EPOXY_PLATE = BenchAEKey.of("gtceu_epoxy_plate");
    private static final BenchAEKey ELECTRUM_FOIL = BenchAEKey.of("gtceu_electrum_foil");
    private static final BenchAEKey ELECTRUM_INGOT = BenchAEKey.of("gtceu_electrum_ingot");
    private static final BenchAEKey FINE_ELECTRUM_WIRE = BenchAEKey.of("gtceu_fine_electrum_wire");
    private static final BenchAEKey PLATINUM_SINGLE_WIRE = BenchAEKey.of("gtceu_platinum_single_wire");
    private static final BenchAEKey PLATINUM_SINGLE_CABLE = BenchAEKey.of("gtceu_platinum_single_cable");
    private static final BenchAEKey PLATINUM_QUADRUPLE_WIRE = BenchAEKey.of("gtceu_platinum_quadruple_wire");
    private static final BenchAEKey PLATINUM_QUADRUPLE_CABLE = BenchAEKey.of("gtceu_platinum_quadruple_cable");
    private static final BenchAEKey TUNGSTEN_SINGLE_CABLE = BenchAEKey.of("gtceu_tungsten_single_cable");
    private static final BenchAEKey TUNGSTEN_SINGLE_WIRE = BenchAEKey.of("gtceu_tungsten_single_wire");
    private static final BenchAEKey TUNGSTEN_QUADRUPLE_WIRE = BenchAEKey.of("gtceu_tungsten_quadruple_wire");
    private static final BenchAEKey TUNGSTEN_OCTAL_WIRE = BenchAEKey.of("gtceu_tungsten_octal_wire");
    private static final BenchAEKey TUNGSTEN_STEEL_PLATE = BenchAEKey.of("gtceu_tungsten_steel_plate");
    private static final BenchAEKey RED_ALLOY_SINGLE_CABLE = BenchAEKey.of("gtceu_red_alloy_single_cable");
    private static final BenchAEKey RED_ALLOY_SINGLE_WIRE = BenchAEKey.of("gtceu_red_alloy_single_wire");
    private static final BenchAEKey ADVANCED_ENERGY_DETECTOR_COVER = BenchAEKey.of("gtceu_advanced_energy_detector_cover");
    private static final BenchAEKey ENERGY_DETECTOR_COVER = BenchAEKey.of("gtceu_energy_detector_cover");
    private static final BenchAEKey IV_WIRELESS_RX_COVER = BenchAEKey.of("gtmthings_iv_wireless_energy_receive_cover");
    private static final BenchAEKey IV_4A_WIRELESS_RX_COVER = BenchAEKey.of("gtmthings_iv_4a_wireless_energy_receive_cover");
    private static final BenchAEKey IV_16A_HATCH = BenchAEKey.of("gtmthings_iv_16a_wireless_energy_input_hatch");

    private static BenchPatternDetails.InputSpec in(BenchAEKey k, long n) {
        return BenchPatternDetails.InputSpec.of(k, n);
    }

    private static IPatternDetails pat(BenchAEKey out, long outAmt, BenchPatternDetails.InputSpec... ins) {
        return new BenchPatternDetails(out, outAmt, List.of(ins));
    }

    /** The FULL 36-output pattern list from the game VANILLA plan. Intermediate
     *  edges that the game log does not expose are wired to their leaf inputs
     *  (abundant in stock); the EXACT edges — iron_ingot→iron_dust smelt and the
     *  iv_4a→iron_ingot(20) edge — reproduce the bug. */
    private static final IPatternDetails P_IRON_INGOT_LAZY = lazyIronIngot();

    private static final Map<AEKey, List<IPatternDetails>> FULL_IV_PATTERNS = fullIvPatterns();

    private static IPatternDetails lazyIronIngot() {
        return new LazyInputPattern(IRON_INGOT, 1, new LazyInput(IRON_DUST, 1));
    }

    private static Map<AEKey, List<IPatternDetails>> fullIvPatterns() {
        Map<AEKey, List<IPatternDetails>> m = new LinkedHashMap<>();
        // === top: iv_energy_input_hatch_4a — needs iron_ingot×20 (EXACT game edge) ===
        m.put(IV_4A, List.of(pat(IV_4A, 1,
                in(IRON_INGOT, 20),
                in(STEEL_INGOT, 20),
                in(IV_ENERGY_INPUT_HATCH, 1),
                in(IV_TRANSFORMER_1A, 1),
                in(IV_VOLTAGE_COIL, 9),
                in(TUNGSTEN_STEEL_PLATE, 39),
                in(BATTERY_ALLOY, 144))));
        // === iron_ingot smelt: LAZY input (the bug) ===
        m.put(IRON_INGOT, List.of(P_IRON_INGOT_LAZY));
        // === rest of the 36-output chain: leaf-fed (abundant stock) ===
        m.put(STEEL_INGOT, List.of(pat(STEEL_INGOT, 1, in(IRON_DUST, 1))));
        m.put(STEEL_DUST, List.of(pat(STEEL_DUST, 1, in(IRON_DUST, 1))));
        m.put(TUNGSTEN_STEEL_INGOT, List.of(pat(TUNGSTEN_STEEL_INGOT, 1, in(TUNGSTEN_DUST, 1), in(STEEL_INGOT, 1))));
        m.put(TUNGSTEN_STEEL_DUST, List.of(pat(TUNGSTEN_STEEL_DUST, 2, in(TUNGSTEN_DUST, 1), in(STEEL_DUST, 1))));
        m.put(TUNGSTEN_STEEL_PLATE, List.of(pat(TUNGSTEN_STEEL_PLATE, 1, in(TUNGSTEN_STEEL_INGOT, 1))));
        m.put(HOT_TUNGSTEN_STEEL_INGOT, List.of(pat(HOT_TUNGSTEN_STEEL_INGOT, 1, in(TUNGSTEN_STEEL_DUST, 1))));
        m.put(PLATINUM_INGOT, List.of(pat(PLATINUM_INGOT, 1, in(PLATINUM_DUST, 1))));
        m.put(BATTERY_ALLOY, List.of(pat(BATTERY_ALLOY, 720, in(BenchAEKey.of("gtceu_lead_dust"), 4), in(BenchAEKey.of("gtceu_antimony_dust"), 1))));
        m.put(BATTERY_ALLOY_INGOT, List.of(pat(BATTERY_ALLOY_INGOT, 9, in(BATTERY_ALLOY, 1))));
        m.put(BATTERY_ALLOY_BLOCK, List.of(pat(BATTERY_ALLOY_BLOCK, 1, in(BATTERY_ALLOY_INGOT, 9))));
        m.put(DOUBLE_BATTERY_ALLOY_PLATE, List.of(pat(DOUBLE_BATTERY_ALLOY_PLATE, 1, in(BATTERY_ALLOY_INGOT, 18))));
        m.put(IV_MACHINE_HULL, List.of(pat(IV_MACHINE_HULL, 1, in(IV_MACHINE_CASING, 1))));
        m.put(IV_MACHINE_CASING, List.of(pat(IV_MACHINE_CASING, 1, in(TUNGSTEN_STEEL_PLATE, 6))));
        m.put(IV_ENERGY_INPUT_HATCH, List.of(pat(IV_ENERGY_INPUT_HATCH, 1, in(IV_MACHINE_HULL, 1), in(IV_VOLTAGE_COIL, 1))));
        m.put(IV_ENERGY_INPUT_HATCH_16A, List.of(pat(IV_ENERGY_INPUT_HATCH_16A, 1, in(IV_ENERGY_INPUT_HATCH, 1))));
        m.put(IV_TRANSFORMER_1A, List.of(pat(IV_TRANSFORMER_1A, 1, in(IV_VOLTAGE_COIL, 2))));
        m.put(IV_VOLTAGE_COIL, List.of(pat(IV_VOLTAGE_COIL, 1, in(TUNGSTEN_SINGLE_WIRE, 2), in(ELECTRUM_FOIL, 4))));
        m.put(NANO_PROCESSOR, List.of(pat(NANO_PROCESSOR, 2, in(EPOXY_PCB, 1))));
        m.put(NANO_PROCESSOR_ASSEMBLY, List.of(pat(NANO_PROCESSOR_ASSEMBLY, 2, in(NANO_PROCESSOR, 2))));
        m.put(NANO_PROCESSOR_COMPUTER, List.of(pat(NANO_PROCESSOR_COMPUTER, 1, in(NANO_PROCESSOR_ASSEMBLY, 2))));
        m.put(HPIC_CHIP, List.of(pat(HPIC_CHIP, 2, in(HPIC_WAFER, 1))));
        m.put(EPOXY_PCB, List.of(pat(EPOXY_PCB, 8, in(EPOXY_PLATE, 1))));
        m.put(ELECTRUM_FOIL, List.of(pat(ELECTRUM_FOIL, 4, in(ELECTRUM_INGOT, 1))));
        m.put(FINE_ELECTRUM_WIRE, List.of(pat(FINE_ELECTRUM_WIRE, 8, in(ELECTRUM_FOIL, 1))));
        m.put(PLATINUM_SINGLE_WIRE, List.of(pat(PLATINUM_SINGLE_WIRE, 2, in(PLATINUM_INGOT, 1))));
        m.put(PLATINUM_SINGLE_CABLE, List.of(pat(PLATINUM_SINGLE_CABLE, 2, in(PLATINUM_SINGLE_WIRE, 1))));
        m.put(PLATINUM_QUADRUPLE_WIRE, List.of(pat(PLATINUM_QUADRUPLE_WIRE, 1, in(PLATINUM_SINGLE_WIRE, 4))));
        m.put(PLATINUM_QUADRUPLE_CABLE, List.of(pat(PLATINUM_QUADRUPLE_CABLE, 1, in(PLATINUM_QUADRUPLE_WIRE, 4))));
        m.put(TUNGSTEN_SINGLE_CABLE, List.of(pat(TUNGSTEN_SINGLE_CABLE, 2, in(TUNGSTEN_SINGLE_WIRE, 1))));
        m.put(TUNGSTEN_QUADRUPLE_WIRE, List.of(pat(TUNGSTEN_QUADRUPLE_WIRE, 1, in(TUNGSTEN_SINGLE_WIRE, 4))));
        m.put(RED_ALLOY_SINGLE_CABLE, List.of(pat(RED_ALLOY_SINGLE_CABLE, 2, in(RED_ALLOY_SINGLE_WIRE, 1))));
        m.put(ADVANCED_ENERGY_DETECTOR_COVER, List.of(pat(ADVANCED_ENERGY_DETECTOR_COVER, 1, in(ENERGY_DETECTOR_COVER, 1))));
        m.put(IV_WIRELESS_RX_COVER, List.of(pat(IV_WIRELESS_RX_COVER, 1, in(ADVANCED_ENERGY_DETECTOR_COVER, 1))));
        m.put(IV_4A_WIRELESS_RX_COVER, List.of(pat(IV_4A_WIRELESS_RX_COVER, 1, in(IV_WIRELESS_RX_COVER, 1))));
        m.put(IV_16A_HATCH, List.of(pat(IV_16A_HATCH, 1, in(IV_ENERGY_INPUT_HATCH_16A, 1))));
        return m;
    }

    /** Leaf stock mirrors the game VANILLA used list (iron_ingot=1 is the key). */
    private static Map<BenchAEKey, Long> stock() {
        Map<BenchAEKey, Long> s = new LinkedHashMap<>();
        s.put(IRON_INGOT, 1L);      // game: 1 in stock, 19 must be synthesized
        s.put(BenchAEKey.of("gtceu_lead_dust"), 1000L);
        s.put(BenchAEKey.of("gtceu_antimony_dust"), 1000L);
        s.put(BenchAEKey.of("gtceu_lead_dust"), 1000L);
        s.put(IRON_DUST, 19L);      // game: exactly enough for the 19 smelts
        s.put(STEEL_INGOT, 1000L);
        s.put(TUNGSTEN_DUST, 1000L);
        s.put(PLATINUM_DUST, 1000L);
        s.put(ELECTRUM_INGOT, 1000L);
        s.put(EPOXY_PLATE, 1000L);
        s.put(HPIC_WAFER, 1000L);
        s.put(ENERGY_DETECTOR_COVER, 1000L);
        s.put(RED_ALLOY_SINGLE_WIRE, 1000L);
        s.put(TUNGSTEN_SINGLE_WIRE, 1000L);
        s.put(TUNGSTEN_OCTAL_WIRE, 1000L);
        // every other leaf the game used list names (abundant)
        for (String k : new String[]{
                "gtceu:hv_sensor", "gtceu:nor_memory_chip", "gtceu:rubber_plate", "gtceu:smd_diode",
                "gtceu:tin", "gtceu:fine_iridium_wire", "gtceu:styrene_butadiene_rubber", "gtceu:lubricant",
                "gtceu:ender_pearl_plate", "gtceu:sodium_potassium", "gtceu:oxygen", "gtceu:iron_iii_chloride",
                "gtceu:iv_emitter", "gtceu:smd_inductor", "gtceu:sulfuric_acid", "gtceu:polytetrafluoroethylene_plate",
                "gtceu:smd_resistor", "gtceu:ram_chip", "gtceu:lead_dust", "gtceu:niobium_titanium_single_cable",
                "gtceu:antimony_dust", "gtceu:advanced_smd_inductor", "gtceu:smd_capacitor", "gtceu:soldering_alloy",
                "gtceu:magnetic_neodymium_rod", "gtceu:iv_sensor", "gtceu:nano_cpu_chip", "gtceu:smd_transistor",
                "gtceu:polyvinyl_chloride_foil"}) {
            s.put(BenchAEKey.of(k), 1000_000L);
        }
        return s;
    }

    private static final class FakeCraftingService extends appeng.me.service.CraftingService {
        final Map<AEKey, List<IPatternDetails>> patterns;
        FakeCraftingService(FakeBenchGrid grid, Map<AEKey, List<IPatternDetails>> patterns) {
            super(grid, grid.getService(appeng.api.networking.storage.IStorageService.class), null);
            this.patterns = patterns;
        }
        @Override public java.util.Collection<IPatternDetails> getCraftingFor(AEKey what) {
            return patterns.getOrDefault(what, List.of());
        }
    }

    private static FakeBenchGrid grid() {
        PatternCompiler.clearCache();
        PatternCompiler.clearFuzzyGroups();
        FakeBenchGrid g = new FakeBenchGrid(stock());
        g.setCraftingService(new FakeCraftingService(g, FULL_IV_PATTERNS));
        return g;
    }

    private static long ironIngotCount(ICraftingPlan plan) {
        return plan.patternTimes().getOrDefault(P_IRON_INGOT_LAZY, 0L);
    }

    // ============ TEST 1: full iv chain expands the lazy iron_ingot synthesis ============
    @Test
    void fullIvChainExpandsLazyIronIngotLikeVanilla() {
        ICraftingPlan plan = AE2VMCrafting.calculate(grid(), null, IV_4A, 1,
                CalculationStrategy.REPORT_MISSING_ITEMS).join();

        assertTrue(plan.missingItems().isEmpty(),
                "the iron_ingot pattern EXISTS — the plan must synthesize it, not report it missing. missing="
                        + plan.missingItems());
        assertEquals(19L, ironIngotCount(plan),
                "1 iron_ingot in stock + 19 smelted = 20 needed by iv_4a. patterns="
                        + plan.patternTimes().keySet());
        System.out.println("full iv chain: iron_ingot x" + ironIngotCount(plan)
                + " synthesized, missing=" + plan.missingItems());
    }

    // ============ TEST 2: concurrent competing orders all expand, no pollution ============
    @Test
    void concurrentCompetingIvOrdersAllExpand() throws Exception {
        FakeBenchGrid g = grid();
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<ICraftingPlan>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return AE2VMCrafting.calculate(g, null, IV_4A, 1,
                        CalculationStrategy.REPORT_MISSING_ITEMS).join();
            }));
        }
        start.countDown();
        for (Future<ICraftingPlan> f : futures) {
            ICraftingPlan plan = f.get();
            assertTrue(plan.missingItems().isEmpty(),
                    "every concurrent order must synthesize iron_ingot (no missing). missing="
                            + plan.missingItems());
            assertEquals(19L, ironIngotCount(plan),
                    "concurrent order must still synthesize iron_ingot x19");
        }
        pool.shutdown();
        System.out.println("concurrent iv orders: " + threads + " × iron_ingot x19 synthesized, no pollution");
    }

    // ============ lazy input: first read EMPTY, later reads the real variant ============
    /** GTL pattern-buffer lazily builds its recipe cache: the first
     *  {@code getPossibleInputs()} for an input slot is EMPTY, later reads return
     *  the real variants. The VM must retry the read instead of failing the
     *  compile (that failure dropped iron_ingot to missing in the game). */
    private static final class LazyInput implements IPatternDetails.IInput {
        private final GenericStack[] possible;
        private boolean firstRead = true;
        LazyInput(AEKey key, long amount) {
            this.possible = new GenericStack[]{new GenericStack(key, amount)};
        }
        @Override public synchronized GenericStack[] getPossibleInputs() {
            if (firstRead) {
                firstRead = false;
                return new GenericStack[0]; // empty on first read (lazy cache not built)
            }
            return possible;
        }
        @Override public long getMultiplier() { return 1; }
        @Override public boolean isValid(AEKey input, Level level) { return input.equals(possible[0].what()); }
        @Override public AEKey getRemainingKey(AEKey template) { return null; }
    }

    /** Minimal pattern with a custom (lazy) input list. */
    private static final class LazyInputPattern implements IPatternDetails {
        private final IPatternDetails.IInput[] inputs;
        private final GenericStack[] outputs;
        LazyInputPattern(BenchAEKey out, long outAmt, IPatternDetails.IInput... inputList) {
            this.inputs = inputList;
            this.outputs = new GenericStack[]{new GenericStack(out, outAmt)};
        }
        @Override public GenericStack[] getOutputs() { return outputs; }
        @Override public IPatternDetails.IInput[] getInputs() { return inputs; }
        @Override public appeng.api.stacks.AEItemKey getDefinition() { return null; }
    }
}
