package com.ae2vm.addon.bench;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import com.ae2vm.addon.compiler.PatternCompiler;
import com.ae2vm.addon.vm.CraftingBytecode;
import com.ae2vm.addon.vm.CraftingVM;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * GTL 样板翻倍（AE样板倍乘器 / UselessMod smart-doubling）支持审计测试（VM-GTL 分支）。
 *
 * <p>两种“样板翻倍”形态：
 *
 * <ul>
 *   <li><b>GTL {@code PatternModifier}（AE样板倍乘器）</b>：shift 右键把样板 itemStack
 *       <b>重新编码</b>成新的 {@code AEProcessingPattern}——输入与输出都乘以 scale
 *       （{@code Ae2BaseProcessingPatternHelper.multiplyScale}）。它不是虚拟包装类，
 *       就是一张普通处理样板，VM 应把它当作普通样板编译执行；plan 的
 *       {@code patternTimes} 键就是这张倍乘后的真实样板（AE2 CPU / 样板总成都能识别）。</li>
 *   <li><b>UselessMod {@code ScaledProcessingPattern}</b>：运行时虚拟包装类（不重新编码），
 *       {@code PatternCompiler.unwrapScaled} 递归解包到原始样板后再编译（既有
 *       {@link ScaledPatternReproTest} 已覆盖）。</li>
 * </ul>
 *
 * <p>本测试覆盖 GTL 重新编码形态：倍乘后的样板按每 craft 的输入×N / 输出×N 计算，
 * 消耗总量线性正确、缺料按倍乘单位上报、patternTimes 键为倍乘样板本身，
 * 并验证倍乘样板进入 ME 样板总成（{@code onPatternChange} 编译闭环）后链式合成可用。
 */
public class GtlPatternMultiplierReproTest {

    private static final BenchAEKey ORANGE = BenchAEKey.of("gtl_orange");
    private static final BenchAEKey SAND   = BenchAEKey.of("gtl_sand");
    private static final BenchAEKey DYE    = BenchAEKey.of("gtl_dye");
    private static final BenchAEKey FINAL  = BenchAEKey.of("gtl_final_scaled_chain");
    private static final BenchAEKey MID    = BenchAEKey.of("gtl_mid_scaled");

    /** 复刻 GTL 倍乘器 ×N：一张重新编码的处理样板（输入×N，输出×N，每 craft 产 N 个）。 */
    private static BenchPatternDetails gtlScaledPattern(long scale) {
        return new BenchPatternDetails(ORANGE, scale, List.of(
                BenchPatternDetails.InputSpec.of(SAND, scale),
                BenchPatternDetails.InputSpec.of(DYE, scale)));
    }

    private static ICraftingPlan run(BenchAEKey target,
                                     Map<AEKey, IPatternDetails> byOutput,
                                     Map<BenchAEKey, Long> stock,
                                     long amount) {
        PatternCompiler.clearCache();
        PatternCompiler.clearFuzzyGroups();
        for (IPatternDetails p : byOutput.values()) {
            PatternCompiler.compileIfAbsent(p);
        }
        IPatternDetails top = byOutput.get(target);
        CraftingBytecode req = PatternCompiler.compileRequest(top, amount);
        CraftingVM vm = new CraftingVM("gtl-scaled", key -> key instanceof BenchAEKey k ? byOutput.get(k) : null);
        return vm.execute(req, new BenchSimulationState(stock));
    }

    private static long used(ICraftingPlan plan, AEKey key) {
        return plan.usedItems().get(key);
    }

    private static long missing(ICraftingPlan plan, AEKey key) {
        return plan.missingItems().get(key);
    }

    private static Map<String, Long> missingAll(ICraftingPlan p) {
        TreeMap<String, Long> out = new TreeMap<>();
        for (var e : p.missingItems()) {
            out.put(e.getKey().toString(), e.getLongValue());
        }
        return out;
    }

    /**
     * ×4 样板（4 沙 + 4 染料 → 4 橙）：请求 8 个橙 → 2 craft → 恰好消耗 8 沙 + 8 染料，
     * 无缺料。修复前若把倍乘量当成“每 craft 输出 4”却按原始输入（1 沙）计算，会少算消耗。
     */
    @Test
    void gtlScaledPatternConsumesLinearTotals() {
        Map<AEKey, IPatternDetails> byOutput = new HashMap<>();
        byOutput.put(ORANGE, gtlScaledPattern(4));

        Map<BenchAEKey, Long> stock = new HashMap<>();
        stock.put(SAND, 10_000L);
        stock.put(DYE, 10_000L);

        ICraftingPlan plan = run(ORANGE, byOutput, stock, 8);
        assertTrue(plan.missingItems().isEmpty(), "×4 样板 8 橙应可行. missing=" + missingAll(plan));
        assertEquals(8, used(plan, SAND), "每 craft 消耗 4 沙 × 2 craft = 8 沙");
        assertEquals(8, used(plan, DYE), "每 craft 消耗 4 染料 × 2 craft = 8 染料");
        System.out.println("[gtl-scaled] x4 request=8 usedSand=" + used(plan, SAND) + " usedDye=" + used(plan, DYE));
    }

    /** 缺料按倍乘单位上报：请求 8 → 缺 8 染料（不是缺 2 或 4）。 */
    @Test
    void gtlScaledPatternReportsMissingInScaledUnits() {
        Map<AEKey, IPatternDetails> byOutput = new HashMap<>();
        byOutput.put(ORANGE, gtlScaledPattern(4));

        Map<BenchAEKey, Long> stock = new HashMap<>();
        stock.put(SAND, 10_000L); // 染料全缺

        ICraftingPlan plan = run(ORANGE, byOutput, stock, 8);
        assertEquals(8, missing(plan, DYE), "缺料必须按倍乘后的每 craft 单位合计（8 染料）");
        System.out.println("[gtl-scaled] missingDye=" + missing(plan, DYE));
    }

    /**
     * patternTimes 键必须是倍乘后的真实样板本身（GTL 重新编码形态，不需要 unwrap）。
     * 若 VM 误把倍乘样板解包/换键，AE2 CPU 的 getProviders/pushPattern 将找不到对应样板，
     * 计划虽可行但提交后卡死。
     */
    @Test
    void gtlScaledPatternTimesKeyIsTheScaledPatternItself() {
        BenchPatternDetails scaled = gtlScaledPattern(4);
        Map<AEKey, IPatternDetails> byOutput = new HashMap<>();
        byOutput.put(ORANGE, scaled);

        Map<BenchAEKey, Long> stock = new HashMap<>();
        stock.put(SAND, 10_000L);
        stock.put(DYE, 10_000L);

        ICraftingPlan plan = run(ORANGE, byOutput, stock, 8);
        assertTrue(plan.missingItems().isEmpty());
        assertEquals(1, plan.patternTimes().size(), "只应触发一张样板（倍乘后的橙样板）");
        IPatternDetails key = plan.patternTimes().keySet().iterator().next();
        assertTrue(key instanceof BenchPatternDetails, "patternTimes 键必须是真实可识别的样板，got " + key.getClass().getName());
        assertEquals(ORANGE, key.getOutputs()[0].what());
        assertEquals(4L, key.getOutputs()[0].amount(), "键的样板必须保持倍乘后的输出量（4）");
        System.out.println("[gtl-scaled] patternTimesKey=" + key + " times=" + plan.patternTimes().get(key));
    }

    /**
     * 倍乘样板写入 GTL ME 样板总成后：onPatternChange → bumpPatternVersion +
     * compileIfAbsent(getAvailablePatterns())，随后链式合成（FINAL → 倍乘MID → LEAF）
     * 必须可用，且不会因为“倍乘样板是虚拟包装”而 miss。
     */
    @Test
    void gtlScaledPatternInsidePatternBufferChainCrafts() {
        BenchPatternDetails scaledMid = new BenchPatternDetails(MID, 3, List.of(
                BenchPatternDetails.InputSpec.of(LEAF_FOR_CHAIN, 6))); // ×3：每 craft 3 MID，耗 6 叶子
        BenchPatternDetails finalPattern = new BenchPatternDetails(FINAL, 1, List.of(
                BenchPatternDetails.InputSpec.of(MID, 2)));

        Map<AEKey, IPatternDetails> byOutput = new LinkedHashMap<>();
        byOutput.put(FINAL, finalPattern);
        // LEAF_FOR_CHAIN 是纯库存叶子（不注册样板）

        Map<BenchAEKey, Long> stock = new HashMap<>();
        stock.put(LEAF_FOR_CHAIN, 1_000_000L);

        // 模拟 GTL 样板总成：写入倍乘样板时 onPatternChange 编译闭环
        PatternCompiler.clearCache();
        PatternCompiler.clearFuzzyGroups();
        for (IPatternDetails p : byOutput.values()) {
            PatternCompiler.compileIfAbsent(p);
        }
        GtlBufferModel buffer = new GtlBufferModel();
        buffer.patterns.add(scaledMid);
        buffer.onPatternChange(); // bumpPatternVersion + compileIfAbsent(getAvailablePatterns())

        assertNotNull(PatternCompiler.getCompiled(scaledMid), "倍乘样板必须被编译进字节码缓存");
        byOutput.put(MID, scaledMid);

        ICraftingPlan plan = run(FINAL, byOutput, stock, 1);
        assertTrue(plan.missingItems().isEmpty(), "链式合成必须可行. missing=" + missingAll(plan));
        // 1 FINAL 需要 2 MID；每 craft 产 3 MID → 1 craft 的 ×3 样板即够，消耗 6 叶子
        assertEquals(6, used(plan, LEAF_FOR_CHAIN), "倍乘 MID 每 craft 耗 6 叶 × 1 craft = 6");
        System.out.println("[gtl-scaled-buffer] finalMissing=" + missingAll(plan) + " leafUsed=" + used(plan, LEAF_FOR_CHAIN));
    }

    private static final BenchAEKey LEAF_FOR_CHAIN = BenchAEKey.of("gtl_scaled_leaf");

    /** 复刻 GTL MEPatternBufferPartMachine 的 onPatternChange 编译闭环（VM mixin 侧）。 */
    private static final class GtlBufferModel {
        final List<IPatternDetails> patterns = new ArrayList<>();

        void onPatternChange() {
            // MEPatternBufferPartMachineMixin.vmOnPatternChange（TAIL）：
            PatternCompiler.bumpPatternVersion();
            for (IPatternDetails p : patterns) {
                if (PatternCompiler.getCompiled(p) == null) {
                    PatternCompiler.compileIfAbsent(p);
                }
            }
        }
    }
}
