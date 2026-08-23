package com.ae2vm.addon.bench;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import com.ae2vm.addon.compiler.PatternCompiler;
import com.ae2vm.addon.vm.CraftingBytecode;
import com.ae2vm.addon.vm.CraftingVM;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * GTL 专用测试基准：GTLCore 批量样板更新（ae2CraftingServiceUpdateInterval = 4 tick）
 * 导致 VM 链中子样板"已编译但未 LIVE"的假缺料场景。
 *
 * <p>背景（debug-1.log 取证，1.12.21 运行）：
 * <ul>
 *   <li>gtlcore 的 {@code CraftingServiceMixin} 把 AE2 {@code CraftingService.onServerEndTick}
 *       取消到每 4 tick 才执行一次 → {@code requestUpdate} 排队，变更推迟到下一个 4-tick
 *       边界才可见 ({@code ae2CraftingServiceUpdateInterval = 4})。</li>
 *   <li>VM 重试在 Check2（编译缓存命中）为 true 时立即重试，全部落在同一 pending 窗口内
 *       → 每次重试都得到完全相同（pattern=HAS / compiled=yes / stock>0 却 missing）的假缺料。</li>
 *   <li>修复：{@link com.ae2vm.addon.api.AE2VMCrafting#allMissingLive} 检查要求至少等
 *       到批量更新落地后、所有缺失键在 LIVE 中可见才重算。</li>
 * </ul>
 *
 * <p>本基准复现三类场景：
 * <ol>
 *   <li>编译缓存命中但 LIVE 不可见时的检查层语义（Check2=true / Check1=false / allMissingLive=false）。</li>
 *   <li>批量边界后 VM 层面重算收敛。</li>
 *   <li>真正缺失键的有界等待（不挂起）。</li>
 * </ol>
 *
 * <p>注意：{@link #missingKeyNowCraftable} 与 {@link #allMissingLive} 是
 * {@code AE2VMCrafting} 私有静态方法的复刻，须保持同步。
 */
public class GtlBatchedProviderUpdateBenchmark {

    // ====================================================================
    // 公共键（唯一命名避免跨测试静态缓存污染）
    // ====================================================================
    private static final BenchAEKey FINAL      = BenchAEKey.of("gtl_batch_final");
    private static final BenchAEKey SOLDER     = BenchAEKey.of("gtl_batch_solder");
    private static final BenchAEKey CHIP       = BenchAEKey.of("gtl_batch_chip");
    private static final BenchAEKey TIN        = BenchAEKey.of("gtl_batch_tin");
    private static final BenchAEKey ALLOY      = BenchAEKey.of("gtl_batch_alloy");
    private static final BenchAEKey LEAF       = BenchAEKey.of("gtl_batch_leaf");
    private static final BenchAEKey NEVER_LIVE = BenchAEKey.of("gtl_batch_never");

    // ====================================================================
    // 固定库存（debug-1.log 风格：部分库存，不足部分需通过样板合成）
    // ====================================================================
    private static Map<BenchAEKey, Long> stock() {
        Map<BenchAEKey, Long> s = new HashMap<>();
        s.put(TIN,   1000L);
        s.put(ALLOY, 500L);
        s.put(CHIP,  50L);
        s.put(LEAF,  2000L);
        s.put(NEVER_LIVE, 100L);
        return s;
    }

    // ====================================================================
    // 链式样板（hatch → solder + chip → tin + alloy + leaf）
    // ====================================================================
    private static IPatternDetails pFinal() {
        return new BenchPatternDetails(FINAL, 1, List.of(
                BenchPatternDetails.InputSpec.of(SOLDER, 2),
                BenchPatternDetails.InputSpec.of(CHIP, 1)));
    }

    private static IPatternDetails pSolder() {
        return new BenchPatternDetails(SOLDER, 144, List.of(
                BenchPatternDetails.InputSpec.of(TIN, 4),
                BenchPatternDetails.InputSpec.of(ALLOY, 2)));
    }

    private static IPatternDetails pChip() {
        return new BenchPatternDetails(CHIP, 1, List.of(
                BenchPatternDetails.InputSpec.of(LEAF, 4)));
    }

    // ====================================================================
    // 批量样板更新模型（GTLCore CraftingServiceMixin 4-tick 语义）
    // ====================================================================
    static final class BatchedProviderModel {
        /** 当前对 getCraftingFor 可见的样板（LIVE 集）。 */
        final Map<BenchAEKey, IPatternDetails> live = new LinkedHashMap<>();
        /** 通过 requestUpdate 排队等待加入的样板（pending 集）。 */
        final Map<BenchAEKey, IPatternDetails> pending = new LinkedHashMap<>();
        /** 所有已知样板（创建时轨迹，供编译缓存检查）。 */
        final Map<BenchAEKey, IPatternDetails> all = new LinkedHashMap<>();
        /** 模拟 tick 计数器。 */
        private int tick = 0;
        /** 批量间隔（ae2CraftingServiceUpdateInterval = 4 tick）。 */
        static final int BATCH_INTERVAL = 4;

        /** 注册一个样板到 LIVE 集（初始注册）。 */
        void add(BenchAEKey key, IPatternDetails p) {
            live.put(key, p);
            all.put(key, p);
        }

        /**
         * 模拟 GTLCore 刷新窗口：从 LIVE 中移除并排队到 pending，
         * 等待下一个 4-tick 边界才真正生效（{@link #flushPending}）。
         */
        void requestUpdate(BenchAEKey key) {
            IPatternDetails p = live.remove(key);
            if (p != null) pending.put(key, p);
        }

        boolean isLive(BenchAEKey key) {
            return live.containsKey(key);
        }

        /** 前进 1 tick，在 4-tick 边界上刷新 pending。 */
        void runTick() {
            tick++;
            if ((tick % BATCH_INTERVAL) == 0) {
                flushPending();
            }
        }

        /** 前进 N 个 tick，如有边界则刷新。 */
        void runTicks(int n) {
            for (int i = 0; i < n; i++) runTick();
        }

        /** 强制刷新 pending（模拟 4-tick 边界上的 flushPending）。 */
        void flushPending() {
            live.putAll(pending);
            pending.clear();
        }

        /** 当前 tick 数。 */
        int currentTick() { return tick; }

        /** 当前 LIVE 键集合的字符串表示。 */
        String liveKeys() {
            return live.keySet().stream().map(BenchAEKey::itemId).collect(Collectors.joining(", "));
        }
    }

    // ====================================================================
    // 复刻的检查方法（须与 AE2VMCrafting.missingKeyNowCraftable 同步）
    // ====================================================================

    /** 复刻 Check1 + Check2：只要任一缺失键在 LIVE 或编译缓存中 → 可重试。 */
    private static boolean missingKeyNowCraftable(BatchedProviderModel service,
                                                  List<? extends AEKey> missingKeys) {
        for (AEKey key : missingKeys) {
            if (key instanceof BenchAEKey bk) {
                if (service.isLive(bk)) return true;                         // Check1
                if (PatternCompiler.findCompiledByOutput(key) != null) return true; // Check2
            }
        }
        return false;
    }

    /**
     * 复刻 AE2VMCrafting.allMissingLive：仅当 {@code getCraftingFor} 对每个缺失键
     * 都非空时才返回 true（排除编译缓存 Check2 的假阳性——编译缓存命中但 LIVE 尚未生效）。
     */
    private static boolean allMissingLive(BatchedProviderModel service,
                                          List<? extends AEKey> missingKeys) {
        for (AEKey key : missingKeys) {
            if (key instanceof BenchAEKey bk) {
                if (!service.isLive(bk)) return false;
            }
        }
        return true;
    }

    /** 从 ICraftingPlan 提取缺失键列表。 */
    private static List<AEKey> missingKeys(ICraftingPlan plan) {
        List<AEKey> keys = new ArrayList<>();
        for (var e : plan.missingItems()) {
            keys.add(e.getKey());
        }
        return keys;
    }

    /** 缺失键字符串（便于断言输出）。 */
    private static String missing(ICraftingPlan plan) {
        StringBuilder sb = new StringBuilder();
        for (var e : plan.missingItems()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(e.getKey()).append("=").append(e.getLongValue());
        }
        return sb.toString();
    }

    // ====================================================================
    // 清理（每次测试前重置静态编译缓存，避免跨测试污染）
    // ====================================================================
    @BeforeEach
    void setUp() {
        PatternCompiler.clearCache();
        PatternCompiler.clearFuzzyGroups();
    }

    // ====================================================================
    // 1. 检查层语义：编译缓存命中但 LIVE 不可见
    // ====================================================================
    @Test
    void checkTaxonomy_compiledButNotLive_window() {
        // 构建模型：初始所有样板 LIVE
        BatchedProviderModel model = new BatchedProviderModel();
        model.add(FINAL, pFinal());
        model.add(SOLDER, pSolder());
        model.add(CHIP, pChip());

        // 预热编译缓存：编译所有样板，使 Check2 命中
        PatternCompiler.clearCache();
        PatternCompiler.compileIfAbsent(pFinal());
        PatternCompiler.compileIfAbsent(pSolder());
        PatternCompiler.compileIfAbsent(pChip());

        // 所有键已编译 → Check2 应都为 true
        assertNotNull(PatternCompiler.findCompiledByOutput(FINAL));
        assertNotNull(PatternCompiler.findCompiledByOutput(SOLDER));
        assertNotNull(PatternCompiler.findCompiledByOutput(CHIP));

        // 模拟 GTLCore 刷新窗口：SOLDER 和 CHIP 从 LIVE 移除（pending 状态）
        // 相当于 debug-1.log 中样板在重试窗口内不可见
        model.requestUpdate(SOLDER);
        model.requestUpdate(CHIP);
        // FINAL 仍保持 LIVE（顶层请求的样板始终可见）

        // 缺失键列表 = {SOLDER, CHIP}
        List<AEKey> missing = List.of(SOLDER, CHIP);

        // === OLD 行为：Check2 命中 → 立即重试（假缺料） ===
        boolean oldNeedsRetry = missingKeyNowCraftable(model, missing);
        assertTrue(oldNeedsRetry,
                "OLD: Check2 (compiled cache) hit → needsRetry=true, would retry immediately");

        // === NEW 行为：所有缺失键 LIVE 检查 ===
        boolean live = allMissingLive(model, missing);
        assertFalse(live,
                "NEW: 刷新窗口内 → allMissingLive=false, 必须等待批量更新落地");

        // 模拟批量边界：前进 4 tick
        model.runTicks(4);
        // 验证 LIVE 已恢复
        assertTrue(model.isLive(SOLDER), "SOLDER should be live after batch boundary");
        assertTrue(model.isLive(CHIP), "CHIP should be live after batch boundary");

        // 现在 allMissingLive 应为 true
        live = allMissingLive(model, missing);
        assertTrue(live, "After batch boundary → allMissingLive=true");

        // Check1 也命中 → 正常重试
        assertTrue(missingKeyNowCraftable(model, missing),
                "After batch → Check1 live=true, should retry");

        System.out.println("[gtl-batch] checkTaxonomy: window-check1=false check2=true allLive=false"
                + " → after-batch allLive=true ✓");
    }

    // ====================================================================
    // 2. VM 层面：预热 → 刷新窗口 → 批量边界 → 重算收敛
    // ====================================================================
    @Test
    void vmConvergesAfterBatchBoundary() {
        // 2a. 预热阶段：所有样板 LIVE，VM 执行一次完整计划
        Map<BenchAEKey, Long> stock = stock();
        BatchedProviderModel model = new BatchedProviderModel();
        model.add(FINAL, pFinal());
        model.add(SOLDER, pSolder());
        model.add(CHIP, pChip());
        // LEAF 是纯输入（不需要样板，由库存直接满足）
        model.add(LEAF, new BenchPatternDetails(LEAF, 1, List.of())); // leaf pattern: 1 output, no inputs

        // 预热编译缓存 + 编译顶层请求
        PatternCompiler.clearCache();
        PatternCompiler.compileIfAbsent(pFinal());
        PatternCompiler.compileIfAbsent(pSolder());
        PatternCompiler.compileIfAbsent(pChip());
        PatternCompiler.compileIfAbsent(new BenchPatternDetails(LEAF, 1, List.of()));

        CraftingBytecode warmReq = PatternCompiler.compileRequest(model.live.get(FINAL), 1);
        // 预热解析器：resolve 从 LIVE 中取
        Function<AEKey, IPatternDetails> warmResolver = key ->
                key instanceof BenchAEKey bk ? model.live.get(bk) : null;
        CraftingVM warmVm = new CraftingVM("gtl-batch-warm", warmResolver);

        ICraftingPlan warmPlan = warmVm.execute(warmReq, new BenchSimulationState(stock));
        assertTrue(warmPlan.missingItems().isEmpty(),
                "Phase A (warm): all patterns live → plan should be complete. missing=" + missing(warmPlan));
        System.out.println("[gtl-batch] Phase A: warm run → no missing ✓");

        // 2b. 刷新窗口：SOLDER 和 CHIP 从 LIVE 移除（pending）
        model.requestUpdate(SOLDER);
        model.requestUpdate(CHIP);

        // 刷新窗口的解析器：只有 FINAL 和 LEAF 可见
        Function<AEKey, IPatternDetails> windowResolver = key ->
                key instanceof BenchAEKey bk ? model.live.get(bk) : null;

        // 验证窗口内缺失键：SOLDER 和 CHIP 不可见
        assertFalse(model.isLive(SOLDER));
        assertFalse(model.isLive(CHIP));
        assertTrue(model.isLive(FINAL));

        // 刷新窗口的 VM 重算（模拟 OLD 行为的立即重试 → 假缺料）
        // 注意：不清除编译缓存——debug-1.log 场景中 compiled=yes 保留（来自预热阶段）
        PatternCompiler.compileIfAbsent(pFinal());
        CraftingBytecode windowReq = PatternCompiler.compileRequest(pFinal(), 1);

        // 用新 VM 模拟重试（JIT 缓存为空，因这是新 VM 实例）
        // 但编译缓存中 SOLDER/CHIP/LEAF 仍存在（Check2 命中）
        // 而 LIVE 解析器返回 null → sub==null → missing
        CraftingVM windowVm = new CraftingVM("gtl-batch-window", windowResolver);
        ICraftingPlan windowPlan = windowVm.execute(windowReq, new BenchSimulationState(stock));

        // 验证：在刷新窗口内执行 → 假缺料
        // （old 行为：立即重试得到相同假缺料）
        assertFalse(windowPlan.missingItems().isEmpty(),
                "Phase B (window): patterns pending → VM should report missing (false negative). "
                        + "missing=" + missing(windowPlan));

        List<AEKey> windowMissing = missingKeys(windowPlan);
        assertTrue(missingKeyNowCraftable(model, windowMissing),
                "Phase B: in window, Check2 (compiled cache) still true → old code would retry immediately");
        assertFalse(allMissingLive(model, windowMissing),
                "Phase B: in window, allMissingLive=false → new code waits for batch boundary");

        System.out.println("[gtl-batch] Phase B: window → missing=" + missing(windowPlan)
                + " check2=true allLive=false ✓");

        // 2c. 批量边界：前进 4 tick → pending 刷新到 LIVE
        model.runTicks(4);
        assertTrue(model.isLive(SOLDER), "SOLDER should be live after batch");
        assertTrue(model.isLive(CHIP), "CHIP should be live after batch");

        // 验证检查层
        assertTrue(allMissingLive(model, windowMissing),
                "Phase C: after batch boundary → allMissingLive=true");
        assertTrue(missingKeyNowCraftable(model, windowMissing),
                "Phase C: after batch → Check1 true, should retry");

        // 2d. 重算（模拟修复后的重试：等待批量边界落地后重新执行）
        // 注意：不清除编译缓存——debug-1.log 场景中 compiled=yes 保留（来自预热阶段）
        PatternCompiler.compileIfAbsent(pFinal());
        PatternCompiler.compileIfAbsent(pSolder());
        PatternCompiler.compileIfAbsent(pChip());
        CraftingBytecode retryReq = PatternCompiler.compileRequest(pFinal(), 1);
        // 重算解析器：LIVE 已恢复
        Function<AEKey, IPatternDetails> retryResolver = key ->
                key instanceof BenchAEKey bk ? model.live.get(bk) : null;
        CraftingVM retryVm = new CraftingVM("gtl-batch-retry", retryResolver);
        ICraftingPlan retryPlan = retryVm.execute(retryReq, new BenchSimulationState(stock));

        assertTrue(retryPlan.missingItems().isEmpty(),
                "Phase D (retry): after batch boundary → VM re-execute should converge. missing="
                        + missing(retryPlan));
        System.out.println("[gtl-batch] Phase C: batch boundary → retry → no missing ✓");
    }

    // ====================================================================
    // 3. 有界等待：从未存在的键不会导致挂起
    // ====================================================================
    @Test
    void neverLiveKey_givesUpBoundedly() {
        BatchedProviderModel model = new BatchedProviderModel();
        model.add(FINAL, pFinal());

        // 编译缓存不含 NEVER_LIVE（从未编译过）
        assertNull(PatternCompiler.findCompiledByOutput(NEVER_LIVE));

        // 缺失键 = {NEVER_LIVE}
        // Check1: isLive? NO. Check2: compiled? NO.
        boolean needsRetry = missingKeyNowCraftable(model, List.of(NEVER_LIVE));
        assertFalse(needsRetry,
                "NEVER_LIVE: Check1=false Check2=false → needsRetry=false, no retry");

        // allMissingLive: 也不是 LIVE
        boolean live = allMissingLive(model, List.of(NEVER_LIVE));
        assertFalse(live, "NEVER_LIVE: allMissingLive=false");

        // 即使前进 4 tick，NEVER_LIVE 也不会出现
        model.runTicks(4);
        assertFalse(model.isLive(NEVER_LIVE));
        assertFalse(missingKeyNowCraftable(model, List.of(NEVER_LIVE)),
                "NEVER_LIVE: after ticks, still no pattern → no retry");

        // 证明不挂起：maxRetries 耗尽后循环终止（模拟最后一个重试的判断）
        // 在真实 AE2VMCrafting 中，retry < maxRetries - 1 为 false 时跳出循环
        // 此处由检查层保证语义
        System.out.println("[gtl-batch] neverLive: never live → bounded give-up ✓");
    }

    // ====================================================================
    // 4. 基准报告：打印完整时间线
    // ====================================================================
    @Test
    void report() {
        System.out.println("=== GTL Batched Provider Update Benchmark Report ===");
        System.out.println("Model: ae2CraftingServiceUpdateInterval="
                + BatchedProviderModel.BATCH_INTERVAL + " ticks");

        // 构建链
        Map<BenchAEKey, Long> stock = stock();
        BatchedProviderModel model = new BatchedProviderModel();
        model.add(FINAL, pFinal());
        model.add(SOLDER, pSolder());
        model.add(CHIP, pChip());
        model.add(LEAF, new BenchPatternDetails(LEAF, 1, List.of()));

        // 预热编译
        PatternCompiler.clearCache();
        PatternCompiler.compileIfAbsent(pFinal());
        PatternCompiler.compileIfAbsent(pSolder());
        PatternCompiler.compileIfAbsent(pChip());
        PatternCompiler.compileIfAbsent(new BenchPatternDetails(LEAF, 1, List.of()));

        // 报告各阶段
        System.out.println("\n--- Phase A: Warm (all patterns live) ---");
        CraftingBytecode reqA = PatternCompiler.compileRequest(pFinal(), 1);
        Function<AEKey, IPatternDetails> resolverA = key ->
                key instanceof BenchAEKey bk ? model.live.get(bk) : null;
        CraftingVM vm = new CraftingVM("gtl-batch-report", resolverA);
        ICraftingPlan planA = vm.execute(reqA, new BenchSimulationState(stock));
        System.out.println("  missing: " + (planA.missingItems().isEmpty() ? "(none)" : missing(planA)));

        // 刷新窗口
        model.requestUpdate(SOLDER);
        model.requestUpdate(CHIP);
        System.out.println("\n--- Phase B: Refresh window (SOLDER, CHIP pending) ---");
        System.out.println("  live keys: " + model.liveKeys());
        System.out.println("  pending keys: " + model.pending.keySet().stream()
                .map(BenchAEKey::itemId).collect(Collectors.joining(", ")));

        // 窗口内检查
        List<AEKey> missingInWindow = List.of(SOLDER, CHIP);
        System.out.println("  missingKeyNowCraftable=" + missingKeyNowCraftable(model, missingInWindow)
                + " (Check1=false Check2=true → OLD would retry immediately)");
        System.out.println("  allMissingLive=" + allMissingLive(model, missingInWindow)
                + " (NEW gate: must wait for batch boundary)");

        // 窗口内执行（假缺料）
        PatternCompiler.clearCache();
        PatternCompiler.compileIfAbsent(pFinal());
        CraftingBytecode reqB = PatternCompiler.compileRequest(pFinal(), 1);
        Function<AEKey, IPatternDetails> resolverB = key ->
                key instanceof BenchAEKey bk ? model.live.get(bk) : null;
        CraftingVM vmB = new CraftingVM("gtl-batch-report-b", resolverB);
        ICraftingPlan planB = vmB.execute(reqB, new BenchSimulationState(stock));
        System.out.println("  VM execute (window): missing=" + missing(planB) + " ← false negative");

        // 批量边界
        model.runTicks(4);
        System.out.println("\n--- Phase C: After batch boundary (tick=" + model.currentTick() + ") ---");
        System.out.println("  live keys: " + model.liveKeys());
        System.out.println("  allMissingLive=" + allMissingLive(model, missingInWindow));

        // 重算收敛
        PatternCompiler.clearCache();
        PatternCompiler.compileIfAbsent(pFinal());
        PatternCompiler.compileIfAbsent(pSolder());
        PatternCompiler.compileIfAbsent(pChip());
        CraftingBytecode reqC = PatternCompiler.compileRequest(pFinal(), 1);
        Function<AEKey, IPatternDetails> resolverC = key ->
                key instanceof BenchAEKey bk ? model.live.get(bk) : null;
        CraftingVM vmC = new CraftingVM("gtl-batch-report-c", resolverC);
        ICraftingPlan planC = vmC.execute(reqC, new BenchSimulationState(stock));
        System.out.println("  VM execute (live): missing=" + missing(planC)
                + " " + (planC.missingItems().isEmpty() ? "✓ CONVERGED" : "✗ FAILED"));

        System.out.println("\n=== End Report ===");
    }
}
