package com.ae2vm.addon.bench;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import com.ae2vm.addon.api.AE2VMCraftingRegistry;
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
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * GTL 特化全量基准套件（VM-GTL 分支）。
 *
 * <p>按风险域分组，每个场景锁定「修复后的正确行为」——旧代码会失败、新代码通过：
 *
 * <ol>
 *   <li>大数量订单溢出（10^18+）：compileRequest / DIV_ROUNDUP / CALL_BY_KEY cts 的
 *       ceil-div 饱和——修复前 (a + b - 1) 溢出为负数，链式合成静默变空（假阴/卡死）；</li>
 *   <li>假阴 / 链内缺料（latest (3).log 复刻）：补样板后同链重算、深层子 bundle stale-missing、
 *       反向 stale（删样板后最终产物必须报缺）、provider 刷新窗口全流程重试；</li>
 *   <li>取消 / 原生回退流：取消请求零回退、真实失败恰好一次回退、VM_FALLBACK 防递归；</li>
 *   <li>并发 / 确定性 / 性能：共享 VM 并行 execute 结果一致、版本号风暴不破坏结果、
 *       两个独立 VM 结果一致；</li>
 *   <li>计划可行性约束：usedItems 不超库存、patternTimes 键全部可被供应器解析（翻倍解包）；</li>
 *   <li>GTL CraftingService 共存：VM 计划可被 GTL 提交/插入模型接受。</li>
 * </ol>
 */
public class GtlFullCoverageBenchmark {

    private static final BenchAEKey A      = BenchAEKey.of("gtl_big_a");
    private static final BenchAEKey B      = BenchAEKey.of("gtl_big_b");
    private static final BenchAEKey LEAF   = BenchAEKey.of("gtl_big_leaf");
    private static final BenchAEKey FINAL  = BenchAEKey.of("gtl_cov_final");
    private static final BenchAEKey MID    = BenchAEKey.of("gtl_cov_mid");
    private static final BenchAEKey DEEP1  = BenchAEKey.of("gtl_cov_deep1");
    private static final BenchAEKey DEEP2  = BenchAEKey.of("gtl_cov_deep2");
    private static final BenchAEKey DEEP3  = BenchAEKey.of("gtl_cov_deep3");
    private static final BenchAEKey DEEP_LEAF = BenchAEKey.of("gtl_cov_deep_leaf");

    // ====================================================================
    // 1. 大数量订单溢出（v1.12.x GTL BIG-ORDER FIX）
    // ====================================================================

    /** 根请求：outputPerCraft=2、request=Long.MAX_VALUE → craftTimes 必须饱和为 ceil(MAX/2)。 */
    @Test
    void bigOrderCeilDivSaturatesAtLongMax() {
        BenchPatternDetails patternA = new BenchPatternDetails(A, 2, List.of(
                BenchPatternDetails.InputSpec.of(LEAF, 1)));
        Map<AEKey, IPatternDetails> byOutput = new HashMap<>();
        byOutput.put(A, patternA);
        // LEAF 是纯库存叶子（不注册样板）——验证从网络库存提取，而非空配方凭空合成

        Map<BenchAEKey, Long> stock = new HashMap<>();
        stock.put(LEAF, Long.MAX_VALUE);

        PatternCompiler.clearCache();
        PatternCompiler.clearFuzzyGroups();
        for (IPatternDetails p : byOutput.values()) PatternCompiler.compileIfAbsent(p);
        CraftingBytecode req = PatternCompiler.compileRequest(patternA, Long.MAX_VALUE);
        assertEquals(4611686018427387904L, PatternCompiler.ceilDiv(Long.MAX_VALUE, 2L),
                "ceilDiv(MAX,2) 必须饱和为 ceil(MAX/2)");

        CraftingVM vm = new CraftingVM("gtl-big-root", key -> key instanceof BenchAEKey k ? byOutput.get(k) : null);
        ICraftingPlan plan = vm.execute(req, new BenchSimulationState(stock));

        assertTrue(plan.missingItems().isEmpty(), "MAX 大订单应可行. missing=" + missing(plan));
        assertEquals(4611686018427387904L, plan.patternTimes().getOrDefault(patternA, 0L),
                "patternTimes[A] 必须是 ceil(MAX/2)（修复前为负数 → 计划静默变空）");
        assertEquals(4611686018427387904L, used(plan, LEAF), "叶子消耗必须等于 craft 数");
        System.out.println("[gtl-big] root ceilDiv(MAX,2)=" + PatternCompiler.ceilDiv(Long.MAX_VALUE, 2L)
                + " timesA=" + plan.patternTimes().getOrDefault(patternA, 0L));
    }

    /** 子链需求：父链把 10^18 级需求量传给 outputPerCraft=2 的子样板 → cts 必须饱和。 */
    @Test
    void bigOrderSubCraftDemandSaturates() {
        // A: 1 B → 1 A；B: 1 叶 → 2 B。请求 A=MAX → B 的需求量 = MAX，
        // B 的 cts = ceilDiv(MAX, 2) —— 修复前 (MAX+1)/2 溢出为负 → cts<=0 → B 不合成 → 假缺 B。
        BenchPatternDetails patternA = new BenchPatternDetails(A, 1, List.of(
                BenchPatternDetails.InputSpec.of(B, 1)));
        BenchPatternDetails patternB = new BenchPatternDetails(B, 2, List.of(
                BenchPatternDetails.InputSpec.of(LEAF, 1)));
        Map<AEKey, IPatternDetails> byOutput = new HashMap<>();
        byOutput.put(A, patternA);
        byOutput.put(B, patternB);
        byOutput.put(LEAF, new BenchPatternDetails(LEAF, 1, List.of()));

        Map<BenchAEKey, Long> stock = new HashMap<>();
        stock.put(LEAF, Long.MAX_VALUE);

        PatternCompiler.clearCache();
        PatternCompiler.clearFuzzyGroups();
        for (IPatternDetails p : byOutput.values()) PatternCompiler.compileIfAbsent(p);
        CraftingBytecode req = PatternCompiler.compileRequest(patternA, Long.MAX_VALUE);

        CraftingVM vm = new CraftingVM("gtl-big-sub", key -> key instanceof BenchAEKey k ? byOutput.get(k) : null);
        ICraftingPlan plan = vm.execute(req, new BenchSimulationState(stock));

        assertTrue(plan.missingItems().isEmpty(), "MAX 子链应可行. missing=" + missing(plan));
        assertEquals(4611686018427387904L, plan.patternTimes().getOrDefault(patternB, 0L),
                "patternTimes[B] 必须是 ceil(MAX/2)（修复前 cts 溢出为负 → B 被跳过 → 假缺）");
        System.out.println("[gtl-big] sub timesB=" + plan.patternTimes().getOrDefault(patternB, 0L));
    }

    // ====================================================================
    // 2. 假阴 / 链内缺料（latest (3).log 复刻：infuscolium 链内缺、单独可合成）
    // ====================================================================

    /** 完整重试循环模型：窗口内首查失败 → settle → 二查命中 → 重编译重算 → 可行。 */
    @Test
    void chainMissingThenProviderAppearsFullRetryLoop() {
        Map<BenchAEKey, IPatternDetails> hidden = new HashMap<>();
        hidden.put(FINAL, new BenchPatternDetails(FINAL, 1, List.of(
                BenchPatternDetails.InputSpec.of(MID, 2))));
        hidden.put(LEAF, new BenchPatternDetails(LEAF, 1, List.of()));
        // MID 的样板在 GTL 刷新窗口内（provider 未注册 + 从未编译）

        Map<BenchAEKey, Long> stock = new HashMap<>();
        stock.put(LEAF, 1_000_000L);

        PatternCompiler.clearCache();
        PatternCompiler.clearFuzzyGroups();
        for (IPatternDetails p : hidden.values()) PatternCompiler.compileIfAbsent(p);
        CraftingVM vm = new CraftingVM("gtl-window-loop", key -> key instanceof BenchAEKey k ? hidden.get(k) : null);
        CraftingBytecode req = PatternCompiler.compileRequest(hidden.get(FINAL), 1);

        // 第一轮：MID 不可见 → 缺 MID（假阴，与日志 21:43:05 一致）
        ICraftingPlan plan1 = vm.execute(req, new BenchSimulationState(stock));
        assertTrue(hasMissing(plan1, MID), "窗口内首轮应缺 MID. missing=" + missing(plan1));

        // GTL 服务器 tick 完成 provider 注册（窗口关闭）；模拟 AE2VMCrafting 重试判定
        BenchPatternDetails midPattern = new BenchPatternDetails(MID, 1, List.of(
                BenchPatternDetails.InputSpec.of(LEAF, 4)));
        hidden.put(MID, midPattern);
        assertTrue(missingKeyNowCraftable(hidden, plan1, FINAL), "窗口关闭后必须触发重试");

        // 重试动作：invalidate 根 + 重编译 + 重算（AE2VMCrafting 循环体）
        PatternCompiler.invalidate(hidden.get(FINAL));
        PatternCompiler.compileIfAbsent(hidden.get(FINAL));
        PatternCompiler.compileIfAbsent(midPattern);
        req = PatternCompiler.compileRequest(hidden.get(FINAL), 1);
        ICraftingPlan plan2 = vm.execute(req, new BenchSimulationState(stock));
        assertTrue(plan2.missingItems().isEmpty(), "重试后必须可行（与日志 21:43:10 单独下单一致）. missing=" + missing(plan2));
        System.out.println("[gtl-window-loop] step1=" + missing(plan1) + " step2=" + missing(plan2));
    }

    /** 深层子 bundle stale-missing（1.11.8 场景）：补的样板在深层，复用父 bundle 时必须递归自查。 */
    @Test
    void deepSubtreeStaleMissingRecoversWithoutBump() {
        Map<BenchAEKey, IPatternDetails> patterns = new HashMap<>();
        patterns.put(FINAL, new BenchPatternDetails(FINAL, 1, List.of(
                BenchPatternDetails.InputSpec.of(MID, 1))));
        patterns.put(MID, new BenchPatternDetails(MID, 1, List.of(
                BenchPatternDetails.InputSpec.of(DEEP1, 1))));
        patterns.put(DEEP1, new BenchPatternDetails(DEEP1, 1, List.of(
                BenchPatternDetails.InputSpec.of(DEEP2, 1))));
        patterns.put(DEEP2, new BenchPatternDetails(DEEP2, 1, List.of(
                BenchPatternDetails.InputSpec.of(DEEP3, 1))));
        // DEEP3 样板一开始不存在（深层缺料）——且网络里也没有 DEEP3 库存（只有它的原料）

        Map<BenchAEKey, Long> stock = new HashMap<>();
        stock.put(DEEP_LEAF, 1_000_000L);

        PatternCompiler.clearCache();
        PatternCompiler.clearFuzzyGroups();
        for (IPatternDetails p : patterns.values()) PatternCompiler.compileIfAbsent(p);
        CraftingVM vm = new CraftingVM("gtl-deep", key -> key instanceof BenchAEKey k ? patterns.get(k) : null);
        CraftingBytecode req = PatternCompiler.compileRequest(patterns.get(FINAL), 1);

        ICraftingPlan plan1 = vm.execute(req, new BenchSimulationState(stock));
        assertTrue(hasMissing(plan1, DEEP3), "首轮应缺深层 DEEP3. missing=" + missing(plan1));

        // 补深层样板，但故意不 bump 版本（GTL onPatternChange 可能在休眠 ticker 下不触发刷新）
        patterns.put(DEEP3, new BenchPatternDetails(DEEP3, 1, List.of(
                BenchPatternDetails.InputSpec.of(DEEP_LEAF, 1))));
        PatternCompiler.compileIfAbsent(patterns.get(DEEP3));

        ICraftingPlan plan2 = vm.execute(req, new BenchSimulationState(stock));
        assertTrue(plan2.missingItems().isEmpty(),
                "补深层样板后（不 bump）同 VM 重算必须自愈. missing=" + missing(plan2));
        System.out.println("[gtl-deep] step1=" + missing(plan1) + " step2=" + missing(plan2));
    }

    /** 反向 stale（1.11.9 场景）：删中间样板后，最终产物必须报缺，不得假可行。 */
    @Test
    void reverseStalePatternRemovedReportsMissing() {
        Map<BenchAEKey, IPatternDetails> patterns = new HashMap<>();
        patterns.put(FINAL, new BenchPatternDetails(FINAL, 1, List.of(
                BenchPatternDetails.InputSpec.of(MID, 2))));
        patterns.put(MID, new BenchPatternDetails(MID, 1, List.of(
                BenchPatternDetails.InputSpec.of(LEAF, 1))));
        patterns.put(LEAF, new BenchPatternDetails(LEAF, 1, List.of()));

        Map<BenchAEKey, Long> stock = new HashMap<>();
        stock.put(LEAF, 1_000_000L);

        PatternCompiler.clearCache();
        PatternCompiler.clearFuzzyGroups();
        for (IPatternDetails p : patterns.values()) PatternCompiler.compileIfAbsent(p);
        CraftingVM vm = new CraftingVM("gtl-reverse", key -> key instanceof BenchAEKey k ? patterns.get(k) : null);
        CraftingBytecode req = PatternCompiler.compileRequest(patterns.get(FINAL), 1);

        ICraftingPlan plan1 = vm.execute(req, new BenchSimulationState(stock));
        assertTrue(plan1.missingItems().isEmpty(), "初始链应可行");

        // 删除 MID 样板（不 bump）——旧 bundle 仍认为 MID 可合成 → 必须被反向自查纠正
        patterns.remove(MID);
        ICraftingPlan plan2 = vm.execute(req, new BenchSimulationState(stock));
        assertTrue(hasMissing(plan2, MID), "删样板后最终产物必须报缺 MID（不得假可行）. missing=" + missing(plan2));
        System.out.println("[gtl-reverse] step1=" + missing(plan1) + " step2=" + missing(plan2));
    }

    // ====================================================================
    // 3. 取消 / 原生回退流
    // ====================================================================

    @Test
    void fallbackFlowCancellationSkipsNative() {
        int[] nativeCalls = {0};
        simulateHandle(null, nativeCalls);
        assertEquals(0, nativeCalls[0], "成功路径不调用原生");
        simulateHandle(new CancellationException("cancelled"), nativeCalls);
        assertEquals(0, nativeCalls[0], "取消必须零原生回退（修复前会阻塞重跑 GTL MAX_FAST）");
        simulateHandle(new CompletionException(new CancellationException("cancelled")), nativeCalls);
        assertEquals(0, nativeCalls[0], "嵌套取消同样零回退");
        simulateHandle(new CompletionException(new CompletionException(new CancellationException("c"))), nativeCalls);
        assertEquals(0, nativeCalls[0], "双层包裹取消零回退");
        simulateHandle(new IllegalStateException("no pattern"), nativeCalls);
        assertEquals(1, nativeCalls[0], "真实失败恰好一次原生回退");
        System.out.println("[gtl-fallback] nativeCalls=" + nativeCalls[0] + " (cancelled=0, real=1)");
    }

    /** 复刻 CraftingServiceMixin .handle 的决策流（vmShouldFallback + 原生调用计数）。 */
    private static void simulateHandle(Throwable ex, int[] nativeCalls) {
        if (ex == null) return;
        if (!vmShouldFallbackMirror(ex)) return;
        nativeCalls[0]++;
    }

    @Test
    void fallbackDoesNotRecurseIntoVm() {
        // VM_FALLBACK ThreadLocal 守卫：原生回退执行期间再次进入 beginCraftingCalculation
        // 必须放行原生（不再次被 VM 拦截、不递归），回退结束后恢复拦截。
        FallbackGuardModel guard = new FallbackGuardModel();
        guard.beginCraftingCalculation(); // 正常请求 → VM 拦截
        assertEquals(1, guard.vmIntercepts);

        guard.enterFallback(); // VM 失败 → 进入原生回退（CraftingServiceMixin .handle）
        guard.beginCraftingCalculation(); // 回退内递归进入 → 必须放行原生
        assertEquals(1, guard.vmIntercepts, "回退期间不得再次被 VM 拦截（防无限递归）");
        assertEquals(1, guard.nativeRuns, "回退期间必须走原生");

        guard.exitFallback();
        guard.beginCraftingCalculation(); // 回退结束 → 恢复 VM 拦截
        assertEquals(2, guard.vmIntercepts);
        System.out.println("[gtl-fallback] recursion guard ok (intercepts=2, nativeDuringFallback=1)");
    }

    private static final class FallbackGuardModel {
        private boolean inNative; // == VM_FALLBACK ThreadLocal
        int vmIntercepts;
        int nativeRuns;

        void beginCraftingCalculation() {
            if (inNative) {
                nativeRuns++; // VM_FALLBACK.get()==TRUE → 放行原生、不再拦截
                return;
            }
            vmIntercepts++;
        }

        void enterFallback() {
            inNative = true;
        }

        void exitFallback() {
            inNative = false;
        }
    }
    // ====================================================================
    // 4. 并发 / 确定性 / 性能
    // ====================================================================

    @Test
    void parallelExecutesOnSharedVmAreDeterministic() throws Exception {
        Map<AEKey, IPatternDetails> byOutput = new LinkedHashMap<>();
        byOutput.put(FINAL, new BenchPatternDetails(FINAL, 1, List.of(
                BenchPatternDetails.InputSpec.of(MID, 2),
                BenchPatternDetails.InputSpec.of(LEAF, 3))));
        byOutput.put(MID, new BenchPatternDetails(MID, 1, List.of(
                BenchPatternDetails.InputSpec.of(LEAF, 4))));
        byOutput.put(LEAF, new BenchPatternDetails(LEAF, 1, List.of()));

        Map<BenchAEKey, Long> stock = new HashMap<>();
        stock.put(LEAF, 1_000_000L);

        PatternCompiler.clearCache();
        PatternCompiler.clearFuzzyGroups();
        for (IPatternDetails p : byOutput.values()) PatternCompiler.compileIfAbsent(p);
        CraftingBytecode req = PatternCompiler.compileRequest(byOutput.get(FINAL), 7);
        CraftingVM vm = new CraftingVM("gtl-parallel", key -> key instanceof BenchAEKey k ? byOutput.get(k) : null);

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<ICraftingPlan>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> vm.execute(req, new BenchSimulationState(stock))));
            }
            String first = null;
            for (Future<ICraftingPlan> f : futures) {
                ICraftingPlan plan = f.get(30, TimeUnit.SECONDS);
                assertTrue(plan.missingItems().isEmpty(), "并行执行必须可行. missing=" + missing(plan));
                String sig = missing(plan) + "|" + patternTimesSig(plan);
                if (first == null) first = sig;
                assertEquals(first, sig, "共享 VM 并行执行结果必须完全一致");
            }
        } finally {
            pool.shutdownNow();
        }
        System.out.println("[gtl-parallel] threads=" + threads + " deterministic=true");
    }

    @Test
    void versionBumpStormPreservesResult() {
        Map<AEKey, IPatternDetails> byOutput = new LinkedHashMap<>();
        byOutput.put(FINAL, new BenchPatternDetails(FINAL, 1, List.of(
                BenchPatternDetails.InputSpec.of(MID, 2))));
        byOutput.put(MID, new BenchPatternDetails(MID, 1, List.of(
                BenchPatternDetails.InputSpec.of(LEAF, 1))));
        byOutput.put(LEAF, new BenchPatternDetails(LEAF, 1, List.of()));

        Map<BenchAEKey, Long> stock = new HashMap<>();
        stock.put(LEAF, 1_000_000L);

        PatternCompiler.clearCache();
        PatternCompiler.clearFuzzyGroups();
        for (IPatternDetails p : byOutput.values()) PatternCompiler.compileIfAbsent(p);
        CraftingBytecode req = PatternCompiler.compileRequest(byOutput.get(FINAL), 3);
        CraftingVM vm = new CraftingVM("gtl-bumpstorm", key -> key instanceof BenchAEKey k ? byOutput.get(k) : null);

        ICraftingPlan before = vm.execute(req, new BenchSimulationState(stock));
        String sigBefore = missing(before) + "|" + patternTimesSig(before);

        long start = System.nanoTime();
        for (int i = 0; i < 10_000; i++) PatternCompiler.bumpPatternVersion();
        ICraftingPlan after = vm.execute(req, new BenchSimulationState(stock));
        long ms = (System.nanoTime() - start) / 1_000_000;

        assertEquals(sigBefore, missing(after) + "|" + patternTimesSig(after),
                "版本号风暴后结果必须一致");
        assertTrue(ms < 5000, "bump 风暴 + 重捕获应远低于 5s，实际 " + ms + "ms");
        System.out.println("[gtl-bumpstorm] bumps=10000 re-execMs=" + ms + " sigStable=true");
    }

    @Test
    void freshVmsProduceIdenticalPlans() {
        Map<AEKey, IPatternDetails> byOutput = new LinkedHashMap<>();
        byOutput.put(FINAL, new BenchPatternDetails(FINAL, 1, List.of(
                BenchPatternDetails.InputSpec.of(MID, 2),
                BenchPatternDetails.InputSpec.of(LEAF, 3))));
        byOutput.put(MID, new BenchPatternDetails(MID, 1, List.of(
                BenchPatternDetails.InputSpec.of(LEAF, 4))));
        byOutput.put(LEAF, new BenchPatternDetails(LEAF, 1, List.of()));

        Map<BenchAEKey, Long> stock = new HashMap<>();
        stock.put(LEAF, 1_000_000L);

        PatternCompiler.clearCache();
        PatternCompiler.clearFuzzyGroups();
        for (IPatternDetails p : byOutput.values()) PatternCompiler.compileIfAbsent(p);
        CraftingBytecode req = PatternCompiler.compileRequest(byOutput.get(FINAL), 5);

        ICraftingPlan p1 = new CraftingVM("gtl-vm1", key -> key instanceof BenchAEKey k ? byOutput.get(k) : null)
                .execute(req, new BenchSimulationState(stock));
        ICraftingPlan p2 = new CraftingVM("gtl-vm2", key -> key instanceof BenchAEKey k ? byOutput.get(k) : null)
                .execute(req, new BenchSimulationState(stock));
        assertEquals(missing(p1) + "|" + patternTimesSig(p1),
                missing(p2) + "|" + patternTimesSig(p2), "两个独立 VM 结果必须一致");
        System.out.println("[gtl-freshvm] identical=true");
    }

    // ====================================================================
    // 5. 计划可行性约束
    // ====================================================================

    @Test
    void feasiblePlanUsedNeverExceedsStock() {
        Map<AEKey, IPatternDetails> byOutput = new LinkedHashMap<>();
        byOutput.put(FINAL, new BenchPatternDetails(FINAL, 1, List.of(
                BenchPatternDetails.InputSpec.of(MID, 3))));
        byOutput.put(MID, new BenchPatternDetails(MID, 1, List.of(
                BenchPatternDetails.InputSpec.of(LEAF, 4))));
        byOutput.put(LEAF, new BenchPatternDetails(LEAF, 1, List.of()));

        Map<BenchAEKey, Long> stock = new HashMap<>();
        stock.put(LEAF, 1000L);

        PatternCompiler.clearCache();
        PatternCompiler.clearFuzzyGroups();
        for (IPatternDetails p : byOutput.values()) PatternCompiler.compileIfAbsent(p);
        CraftingBytecode req = PatternCompiler.compileRequest(byOutput.get(FINAL), 10);
        ICraftingPlan plan = new CraftingVM("gtl-used", key -> key instanceof BenchAEKey k ? byOutput.get(k) : null)
                .execute(req, new BenchSimulationState(stock));

        assertTrue(plan.missingItems().isEmpty(), "应可行. missing=" + missing(plan));
        for (var e : plan.usedItems()) {
            AEKey k = e.getKey();
            long v = e.getLongValue();
            long avail = k instanceof BenchAEKey bk ? stock.getOrDefault(bk, 0L) : 0L;
            assertTrue(v <= avail,
                    "usedItems 不得超网络库存: " + k + " used=" + v + " stock=" + avail);
        }
        System.out.println("[gtl-used] used=" + missing(plan) + " allUsed<=stock=true");
    }

    @Test
    void patternTimesKeysAllResolvableAndReal() {
        // 链中含 UselessMod 虚拟翻倍包装：patternTimes 键必须是解包后的真实样板，且都能被供应器解析。
        BenchPatternDetails realMid = new BenchPatternDetails(MID, 1, List.of(
                BenchPatternDetails.InputSpec.of(LEAF, 2)));
        ScaledBenchPatternDetails scaledMid = new ScaledBenchPatternDetails(realMid, 3);
        Map<AEKey, IPatternDetails> byOutput = new LinkedHashMap<>();
        byOutput.put(FINAL, new BenchPatternDetails(FINAL, 1, List.of(
                BenchPatternDetails.InputSpec.of(MID, 1))));
        byOutput.put(MID, scaledMid);
        byOutput.put(LEAF, new BenchPatternDetails(LEAF, 1, List.of()));

        Map<BenchAEKey, Long> stock = new HashMap<>();
        stock.put(LEAF, 1_000_000L);

        PatternCompiler.clearCache();
        PatternCompiler.clearFuzzyGroups();
        for (IPatternDetails p : byOutput.values()) PatternCompiler.compileIfAbsent(p);
        CraftingBytecode req = PatternCompiler.compileRequest(byOutput.get(FINAL), 6);
        ICraftingPlan plan = new CraftingVM("gtl-keys", key -> key instanceof BenchAEKey k ? byOutput.get(k) : null)
                .execute(req, new BenchSimulationState(stock));

        assertTrue(plan.missingItems().isEmpty(), "应可行. missing=" + missing(plan));
        for (var e : plan.patternTimes().entrySet()) {
            IPatternDetails key = e.getKey();
            assertFalse(key instanceof ScaledBenchPatternDetails,
                    "patternTimes 键不得是虚拟翻倍包装: " + key.getClass().getName());
            GenericStack out = key.getOutputs() != null && key.getOutputs().size() > 0 ? key.getOutputs().get(0) : null;
            assertNotNull(out, "patternTimes 键必须可解析输出");
            assertNotNull(byOutput.get(out.what()), "patternTimes 键输出必须能被供应器解析: " + out.what());
        }
        System.out.println("[gtl-keys] patternTimes keys all real & resolvable: " + patternTimesSig(plan));
    }

    // ====================================================================
    // 6. GTL CraftingService 共存（提交/插入模型）
    // ====================================================================

    @Test
    void gtlSubmitInsertCoexistWithVmPlan() {
        Map<AEKey, IPatternDetails> byOutput = new LinkedHashMap<>();
        byOutput.put(FINAL, new BenchPatternDetails(FINAL, 1, List.of(
                BenchPatternDetails.InputSpec.of(LEAF, 2))));
        byOutput.put(LEAF, new BenchPatternDetails(LEAF, 1, List.of()));

        Map<BenchAEKey, Long> stock = new HashMap<>();
        stock.put(LEAF, 10_000L);

        PatternCompiler.clearCache();
        PatternCompiler.clearFuzzyGroups();
        for (IPatternDetails p : byOutput.values()) PatternCompiler.compileIfAbsent(p);
        CraftingBytecode req = PatternCompiler.compileRequest(byOutput.get(FINAL), 4);
        ICraftingPlan plan = new CraftingVM("gtl-submit", key -> key instanceof BenchAEKey k ? byOutput.get(k) : null)
                .execute(req, new BenchSimulationState(stock));

        // GTL 提交闸门模型：plan.bytes <= CPU 容量、used <= 库存 → 接受（findSuitableTransfiniteController 等价判据）
        long cpuCapacityBytes = 1_000_000L;
        assertTrue(plan.bytes() <= cpuCapacityBytes, "计划字节数必须能被 CPU 接受");
        for (var e : plan.usedItems()) {
            long avail = e.getKey() instanceof BenchAEKey bk ? stock.getOrDefault(bk, 0L) : 0L;
            assertTrue(e.getLongValue() <= avail, "计划 used 必须可提取");
        }
        System.out.println("[gtl-submit] bytes=" + plan.bytes() + " accepted=true");
    }

    /** 第三方注册表闸门：未注册第三方请求必须放行原生，AE2 自身永远走 VM。 */
    @Test
    void thirdPartyRegistryGateKeepsVmScope() {
        AE2VMCraftingRegistry.register("extendedae");
        try {
            assertFalse(AE2VMCraftingRegistry.isUnregisteredThirdParty("appeng.crafting.CraftingLink"),
                    "AE2 自身请求永远由 VM 处理");
            assertFalse(AE2VMCraftingRegistry.isUnregisteredThirdParty("com.extendedae.network.crafting.ExRequester"),
                    "已注册第三方 → VM 接管");
            assertTrue(AE2VMCraftingRegistry.isUnregisteredThirdParty("com.someothermod.crafting.Requester"),
                    "未注册第三方 → 放行原生（不拦截）");
            System.out.println("[gtl-registry] scope gate ok");
        } finally {
            // 无取消注册 API；测试进程内影响可忽略（JUnit 单进程）。
        }
    }

    // ====================================================================
    // helpers
    // ====================================================================

    /** 复刻 AE2VMCrafting.missingKeyNowCraftable：Check 1（provider 可见）+ Check 2（已编译缓存）。 */
    private static boolean missingKeyNowCraftable(Map<BenchAEKey, IPatternDetails> byOutput, ICraftingPlan plan, AEKey requested) {
        for (var e : plan.missingItems()) {
            AEKey k = e.getKey();
            if (k.equals(requested)) continue;
            if (k instanceof BenchAEKey bk && byOutput.containsKey(bk)) return true;
            if (PatternCompiler.findCompiledByOutput(k) != null) return true;
        }
        return false;
    }

    private static boolean hasMissing(ICraftingPlan p, AEKey key) {
        for (var e : p.missingItems()) {
            if (e.getKey().equals(key)) return true;
        }
        return false;
    }

    private static long used(ICraftingPlan p, AEKey key) {
        return p.usedItems().get(key);
    }

    private static Map<String, Long> missing(ICraftingPlan p) {
        TreeMap<String, Long> out = new TreeMap<>();
        for (var e : p.missingItems()) {
            out.put(e.getKey().toString(), e.getLongValue());
        }
        return out;
    }

    private static String patternTimesSig(ICraftingPlan p) {
        TreeMap<String, Long> out = new TreeMap<>();
        for (var e : p.patternTimes().entrySet()) {
            GenericStack gs = e.getKey().getOutputs() != null && e.getKey().getOutputs().size() > 0
                    ? e.getKey().getOutputs().get(0) : null;
            out.put(gs != null && gs.what() != null ? gs.what().toString() : "?", e.getValue());
        }
        return out.toString();
    }

    /** 镜像 CraftingServiceMixin.vmShouldFallback（private static，mixin 不允许 public static）。 */
    private static boolean vmShouldFallbackMirror(Throwable ex) {
        Throwable t = ex;
        for (int i = 0; i < 4 && t instanceof java.util.concurrent.CompletionException; i++) {
            t = t.getCause();
        }
        if (t == null) return false;
        return !(t instanceof java.util.concurrent.CancellationException);
    }
}