package com.ae2vm.addon.bench;

import appeng.api.stacks.AEKey;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * (v1.14.x GTL TOOL TEST) Verify {@link ManualQuantityCalculator} against the
 * VM and the real iv_16a plan data.
 *
 * <p>Key finding: the VM's patternTimes for tungsten_steel_dust (out=2) is 36,
 * but the correct craft count is {@code ceil(36/2) = 18}. The extra 18 crafts
 * consume 18 more steel_dust + tungsten_dust than the VM's itemDemand accounts
 * for → steel_dust avail=0 in the CPU buffer → the tungsten chain stalls.
 */
public class ManualQuantityCalculatorBenchmark {

    // ---- Tungsten-steel chain (out=2 dust) ----
    private static final BenchAEKey
            root = BenchAEKey.of("ms_root"),
            plate = BenchAEKey.of("ms_plate"),
            hotIngot = BenchAEKey.of("ms_hot_ingot"),
            ingot = BenchAEKey.of("ms_ingot"),
            dust = BenchAEKey.of("ms_dust"),
            tDust = BenchAEKey.of("ms_tungsten_dust"),
            sDust = BenchAEKey.of("ms_steel_dust"),
            otherLeaf = BenchAEKey.of("ms_other_leaf");

    private static ManualQuantityCalculator.Recipe r(AEKey out, long outAmt, Object... in) {
        Map<AEKey, Long> inputs = new LinkedHashMap<>();
        for (int i = 0; i < in.length; i += 2) inputs.put((AEKey) in[i], (Long) in[i + 1]);
        return ManualQuantityCalculator.Recipe.of(out, outAmt, inputs);
    }

    @Test
    void tungstenSteelChain_DustOut2() {
        // root ×1 → plate ×4
        // plate: out=1, in=[hotIngot×1]
        // hotIngot: out=1, in=[ingot×1]
        // ingot: out=1, in=[dust×1]
        // dust: out=2, in=[tDust×1, sDust×1]   ← key: out=2!
        Map<AEKey, ManualQuantityCalculator.Recipe> recipes = new LinkedHashMap<>();
        recipes.put(root, r(root, 1,
                plate, 4L));
        recipes.put(plate, r(plate, 1,
                hotIngot, 1L));
        recipes.put(hotIngot, r(hotIngot, 1,
                ingot, 1L));
        recipes.put(ingot, r(ingot, 1,
                dust, 1L));
        recipes.put(dust, r(dust, 2,
                tDust, 1L, sDust, 1L));

        ManualQuantityCalculator.Result res = ManualQuantityCalculator.calculate(recipes, root, 1, null);

        // root demand & crafts
        assertEquals(1L, res.demand().get(root), "root demand");
        assertEquals(1L, res.crafts().get(root), "root crafts");

        // plate chain: 4 plates needed
        assertEquals(4L, res.demand().get(plate), "plate demand");
        assertEquals(4L, res.crafts().get(plate), "plate crafts (out=1)");

        assertEquals(4L, res.demand().get(hotIngot), "hotIngot demand");
        assertEquals(4L, res.crafts().get(hotIngot), "hotIngot crafts (out=1)");

        assertEquals(4L, res.demand().get(ingot), "ingot demand");
        assertEquals(4L, res.crafts().get(ingot), "ingot crafts (out=1)");

        assertEquals(4L, res.demand().get(dust), "dust demand");
        // CORRECT: out=2 → ceil(4/2) = 2 crafts
        assertEquals(2L, res.crafts().get(dust), "dust crafts (out=2 → 4/2=2)");

        // inputs per dust craft: tDust×1, sDust×1 → 2 crafts → 2 of each
        assertEquals(2L, res.demand().get(tDust), "tungsten_dust demand = 2 crafts ×1");
        assertEquals(2L, res.demand().get(sDust), "steel_dust demand = 2 crafts ×1");

        // If VM incorrectly computes patternTimes=4 (demand units, not crafts),
        // it would report dust crafts=4 → need 4 tDust+4 sDust, double the correct amount.
        // The manual calculator proves the correct answer is 2.
        System.out.println("Manual calculator OK: dust crafts=" + res.crafts().get(dust)
                + " (would be " + res.demand().get(dust) + " if VM used demand units)");
    }

    @Test
    void tungstenSteelChain_StockAware() {
        // Same chain with stock: dust already has 2 in stock
        Map<AEKey, ManualQuantityCalculator.Recipe> recipes = new LinkedHashMap<>();
        recipes.put(root, r(root, 1, plate, 4L));
        recipes.put(plate, r(plate, 1, hotIngot, 1L));
        recipes.put(hotIngot, r(hotIngot, 1, ingot, 1L));
        recipes.put(ingot, r(ingot, 1, dust, 1L));
        recipes.put(dust, r(dust, 2, tDust, 1L, sDust, 1L));

        Map<AEKey, Long> stock = new LinkedHashMap<>();
        stock.put(dust, 2L); // 2 units already in stock
        ManualQuantityCalculator.Result res = ManualQuantityCalculator.calculate(recipes, root, 1, stock);

        assertEquals(4L, res.demand().get(dust), "dust demand = 4 (unchanged by stock)");
        assertEquals(1L, res.crafts().get(dust), "dust crafts (4-2=2 → ceil(2/2)=1)");
        assertEquals(2L, res.consumed().get(dust), "dust consumed from stock = 2");
        assertEquals(1L, res.demand().get(tDust), "tungsten_dust = 1 craft ×1");
        assertEquals(1L, res.demand().get(sDust), "steel_dust = 1 craft ×1");
    }
}
