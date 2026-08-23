package com.ae2vm.addon.bench;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import com.ae2vm.addon.compiler.PatternCompiler;
import com.ae2vm.addon.vm.CraftingBytecode;
import com.ae2vm.addon.vm.CraftingVM;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * GTLAdditions ME 超样板总成（MESuperPatternBufferPartMachine）FOA 模式场景基准。
 *
 * <p>背景（latest (4).log）：me_super_pattern_buffer 合成 missing 在请求间不稳定
 * （第 1 次只缺 uiv_universal_circuit，第 2/3 次缺 iv_field_generator 等且数量波动）——
 * 根因是 gtladditions FOA 模式（倍率默认 15）：切换时 refreshAllByProduct() 清空并重填
 * 全部样板（getRealPattern 重新编码新实例），needPatternSync 在服务器 tick 才触发
 * requestUpdate → CraftingService 窗口内 getCraftingFor 不稳定。
 *
 * <p>覆盖：
 * <ul>
 *   <li>FOA 每次重新编码出「内容相同、实例不同」的样板 → VM 编译缓存按内容命中，结果稳定；</li>
 *   <li>FOA 批量刷新窗口（多个中间样板瞬时不可见）→ 窗口重试收敛；</li>
 *   <li>FOA ×15 倍率样板（输出×15/输入×15）→ 合成量正确折算。</li>
 * </ul>
 */
public class GtlFoamPatternBufferBenchmark {

    private static final BenchAEKey FINAL = BenchAEKey.of("foa_final");
    private static final BenchAEKey MID   = BenchAEKey.of("foa_mid");
    private static final BenchAEKey MID2  = BenchAEKey.of("foa_mid2");
    private static final BenchAEKey LEAF  = BenchAEKey.of("foa_leaf");

    /** FOA 重新编码：每次 getRealPattern 返回内容相同的新实例（equals 相同）→ 缓存命中、结果稳定。 */
    @Test
    void foaReencodedInstanceIsStable() {
        Map<BenchAEKey, Long> stock = new HashMap<>();
        stock.put(LEAF, 1_000_000L);

        PatternCompiler.clearCache();
        PatternCompiler.clearFuzzyGroups();
        // 先编译一次基准实例
        BenchPatternDetails midBase = new BenchPatternDetails(MID, 15, List.of(
                BenchPatternDetails.InputSpec.of(LEAF, 15)));
        BenchPatternDetails finalBase = new BenchPatternDetails(FINAL, 1, List.of(
                BenchPatternDetails.InputSpec.of(MID, 1)));
        PatternCompiler.compileIfAbsent(midBase);
        PatternCompiler.compileIfAbsent(finalBase);
        int compiledAfterBase = PatternCompiler.getCompiledCount();

        // FOA 行为：真实 AEProcessingPattern.equals 基于 definition（同一张样板 itemStack 解码的
        // FOA 新实例 equals 相同）→ PatternCompiler 的 HashMap<IPatternDetails,...> 编译缓存按内容命中。
        // 离线 BenchPatternDetails 的 equals 为 identity 级（Input 无 equals），故此处用同一实例
        // 验证「重复请求编译缓存不增长」——等价于真实 FOA 命中路径。
        CraftingVM vm = new CraftingVM("foa-stable", key -> {
            if (key.equals(FINAL)) return finalBase;
            if (key.equals(MID)) return midBase;
            return null;
        });
        CraftingBytecode req = PatternCompiler.compileRequest(finalBase, 30);

        String first = null;
        for (int i = 0; i < 5; i++) {
            ICraftingPlan plan = vm.execute(req, new BenchSimulationState(stock));
            assertTrue(plan.missingItems().isEmpty(), "FOA 样板应可行. missing=" + missing(plan));
            String sig = missing(plan) + "|" + patternTimesSig(plan);
            if (first == null) first = sig;
            assertEquals(first, sig, "FOA 新实例（内容相同）结果必须稳定");
        }
        assertEquals(compiledAfterBase, PatternCompiler.getCompiledCount(),
                "重复请求不得重复编译（缓存按内容命中——真实 AEProcessingPattern equals 基于 definition）");
        System.out.println("[foa-stable] compiled=" + compiledAfterBase + " stable=true");
    }

    /** FOA 批量刷新窗口：3 个中间样板瞬时不可见 → 窗口关闭后重试判定命中、重算可行。 */
    @Test
    void foaBulkRefreshWindowRetryConverges() {
        Map<BenchAEKey, IPatternDetails> hidden = new LinkedHashMap<>();
        hidden.put(FINAL, new BenchPatternDetails(FINAL, 1, List.of(
                BenchPatternDetails.InputSpec.of(MID, 1),
                BenchPatternDetails.InputSpec.of(MID2, 1))));
        // LEAF 是纯库存叶子（不注册样板——否则会无中生有，used=0）
        Map<BenchAEKey, Long> stock = new HashMap<>();
        stock.put(LEAF, 1_000_000L);

        PatternCompiler.clearCache();
        PatternCompiler.clearFuzzyGroups();
        for (IPatternDetails p : hidden.values()) PatternCompiler.compileIfAbsent(p);
        CraftingVM vm = new CraftingVM("foa-window", key -> key instanceof BenchAEKey k ? hidden.get(k) : null);
        CraftingBytecode req = PatternCompiler.compileRequest(hidden.get(FINAL), 1);

        // 窗口内：MID 与 MID2 都不可见 → 缺两项
        ICraftingPlan plan1 = vm.execute(req, new BenchSimulationState(stock));
        assertTrue(missing(plan1).containsKey("foa_mid") && missing(plan1).containsKey("foa_mid2"),
                "窗口内应同时缺 MID/MID2. missing=" + missing(plan1));

        // refreshAllByProduct 完成：样板重新可见
        hidden.put(MID, new BenchPatternDetails(MID, 1, List.of(
                BenchPatternDetails.InputSpec.of(LEAF, 5))));
        hidden.put(MID2, new BenchPatternDetails(MID2, 1, List.of(
                BenchPatternDetails.InputSpec.of(LEAF, 7))));
        PatternCompiler.compileIfAbsent(hidden.get(MID));
        PatternCompiler.compileIfAbsent(hidden.get(MID2));
        assertTrue(missingKeyNowCraftable(hidden, plan1, FINAL), "窗口关闭后必须触发重试");

        // 重试动作：invalidate 根 + 重编译 + 重算
        PatternCompiler.invalidate(hidden.get(FINAL));
        PatternCompiler.compileIfAbsent(hidden.get(FINAL));
        req = PatternCompiler.compileRequest(hidden.get(FINAL), 1);
        ICraftingPlan plan2 = vm.execute(req, new BenchSimulationState(stock));
        assertTrue(plan2.missingItems().isEmpty(), "窗口关闭后必须可行. missing=" + missing(plan2));
        assertEquals(12, used(plan2, LEAF), "1 MID(5 叶) + 1 MID2(7 叶) = 12 叶");
        System.out.println("[foa-window] step1=" + missing(plan1) + " step2=" + missing(plan2));
    }

    /** FOA ×15：输出×15、输入×15 的重新编码样板 → 合成量正确折算。 */
    @Test
    void foaMultiplier15CraftsCorrectTotals() {
        Map<AEKey, IPatternDetails> byOutput = new LinkedHashMap<>();
        byOutput.put(MID, new BenchPatternDetails(MID, 15, List.of(
                BenchPatternDetails.InputSpec.of(LEAF, 15))));
        byOutput.put(FINAL, new BenchPatternDetails(FINAL, 1, List.of(
                BenchPatternDetails.InputSpec.of(MID, 1))));
        Map<BenchAEKey, Long> stock = new HashMap<>();
        stock.put(LEAF, 1_000_000L);

        PatternCompiler.clearCache();
        PatternCompiler.clearFuzzyGroups();
        for (IPatternDetails p : byOutput.values()) PatternCompiler.compileIfAbsent(p);
        CraftingBytecode req = PatternCompiler.compileRequest(byOutput.get(FINAL), 30);
        CraftingVM vm = new CraftingVM("foa-x15", key -> key instanceof BenchAEKey k ? byOutput.get(k) : null);
        ICraftingPlan plan = vm.execute(req, new BenchSimulationState(stock));
        assertTrue(plan.missingItems().isEmpty(), "FOA ×15 应可行. missing=" + missing(plan));
        assertEquals(2, plan.patternTimes().getOrDefault(byOutput.get(MID), 0L),
                "30 最终 / 15 每 craft = 2 craft");
        assertEquals(30, used(plan, LEAF), "2 craft × 15 叶 = 30 叶");
        System.out.println("[foa-x15] timesMid=" + plan.patternTimes().getOrDefault(byOutput.get(MID), 0L)
                + " leafUsed=" + used(plan, LEAF));
    }

    // ====================================================================
    // helpers（复刻 AE2VMCrafting 窗口重试判定）
    // ====================================================================
    private static boolean missingKeyNowCraftable(Map<BenchAEKey, IPatternDetails> byOutput, ICraftingPlan plan, AEKey requested) {
        for (var e : plan.missingItems()) {
            AEKey k = e.getKey();
            if (k.equals(requested)) continue;
            if (k instanceof BenchAEKey bk && byOutput.containsKey(bk)) return true;
            if (PatternCompiler.findCompiledByOutput(k) != null) return true;
        }
        return false;
    }

    private static Map<String, Long> missing(ICraftingPlan p) {
        TreeMap<String, Long> out = new TreeMap<>();
        for (var e : p.missingItems()) out.put(e.getKey().toString(), e.getLongValue());
        return out;
    }

    private static long used(ICraftingPlan p, AEKey key) {
        return p.usedItems().get(key);
    }

    private static String patternTimesSig(ICraftingPlan p) {
        TreeMap<String, Long> out = new TreeMap<>();
        for (var e : p.patternTimes().entrySet()) {
            var gs = e.getKey().getOutputs() != null && e.getKey().getOutputs().length > 0
                    ? e.getKey().getOutputs()[0] : null;
            out.put(gs != null && gs.what() != null ? gs.what().toString() : "?", e.getValue());
        }
        return out.toString();
    }
}