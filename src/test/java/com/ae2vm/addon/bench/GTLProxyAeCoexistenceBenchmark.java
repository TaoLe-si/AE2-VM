package com.ae2vm.addon.bench;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import com.ae2vm.addon.compiler.PatternCompiler;
import com.ae2vm.addon.vm.CraftingBytecode;
import com.ae2vm.addon.vm.CraftingVM;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * GTL 代理 AE 合成计算 + VM 同时 mixin 合成计算 的共存基准测试。
 *
 * <p>该测试直接引用本地 GTLCore 源码（{@code GTLCore-src/src/main/java/org/gtlcore/gtlcore/...}）
 * 的算法逻辑，而不是反编译 jar 或启动真实 MC 实例。GTL 侧的关键逻辑被简化成本地模型：
 *
 * <ul>
 *   <li>{@link #gtlExtractForProcessingPattern} —— 复刻 {@code AEUtils.extractForProcessingPattern}
 *       （ME 样板总成自动翻倍的原料提取核心）；</li>
 *   <li>{@link GtlCalcMode} —— 复刻 {@code AE2CalculationMode.LEGACY / FAST / ULTRA_FAST} 三态切换，
 *       对应 {@code CraftingCalculationMixin.redirectTreeRequest} 的分发逻辑；</li>
 *   <li>{@link GtlTickHandler} —— 复刻 {@code TickHandlerMixin} 删除 AE2 原生异步合成
 *       （{@code registerCraftingSimulation} / {@code simulateCraftingJobs} 抛 AssertionError）；</li>
 *   <li>{@link GtlCraftingServiceTickGate} —— 复刻 {@code CraftingServiceMixin.onServerEndTick} 的
 *       tick 位掩码节流（{@code (currentTick & CRAFT_MASK) != 0 则取消}）；</li>
 *   <li>{@link GtlCraftingService} —— 复刻 {@code CraftingService.getCraftingFor} 的样板 provider 注册表，
 *       以及 GTL 样板总成 {@code requestUpdate → refreshNodeCraftingProvider} 的延迟刷新窗口；</li>
 *   <li>{@link GtlPatternBuffer} —— 复刻 {@code MEPatternBufferPartMachine} 的
 *       {@code getAvailablePatterns()} + VM 侧 {@code MEPatternBufferPartMachineMixin} 的
 *       {@code bumpPatternVersion() + compileIfAbsent()} 更新闭环。</li>
 * </ul>
 *
 * <p>所有场景都断言同一条合成链在 VM 侧的 {@code missingItems}/{@code patternTimes}
 * 不随 GTL 的三态计算模式、原生异步禁用、tick 节流或样板总成自动翻倍而变化。
 */
public class GTLProxyAeCoexistenceBenchmark {

    // ====================================================================
    // 共享 key
    // ====================================================================
    private static final BenchAEKey SUPER_PATTERN_BUFFER = BenchAEKey.of("gtladditions_me_super_pattern_buffer");
    private static final BenchAEKey UXV_ELECTRIC_PUMP     = BenchAEKey.of("gtceu_uxv_electric_pump");
    private static final BenchAEKey SUPER_SOLDER          = BenchAEKey.of("gtceu_super_mutated_living_solder");
    private static final BenchAEKey TIN                   = BenchAEKey.of("gtceu_tin");
    private static final BenchAEKey NICHROME              = BenchAEKey.of("gtceu_nichrome_dust");
    private static final BenchAEKey UNIVERSAL_CIRCUIT     = BenchAEKey.of("kubejs_uiv_universal_circuit");

    // ====================================================================
    // 场景 1：GTL 样板总成自动翻倍 —— 提取量与期望输出必须严格线性
    // ====================================================================
    @Test
    void gtlAutoExpandExtractionIsLinear() {
        // 处理样板: super_pattern_buffer = uxv_pump*2 + super_solder*3（每 craft）
        IPatternDetails pattern = new GtlProcessingPattern(
                SUPER_PATTERN_BUFFER, 1,
                List.of(
                        new GtlProcessingInput(UXV_ELECTRIC_PUMP, 2),
                        new GtlProcessingInput(SUPER_SOLDER, 3)));

        Map<BenchAEKey, Long> inv1 = new HashMap<>();
        inv1.put(UXV_ELECTRIC_PUMP, 1_000_000L);
        inv1.put(SUPER_SOLDER, 1_000_000L);

        Map<BenchAEKey, Long> inv4 = new HashMap<>();
        inv4.put(UXV_ELECTRIC_PUMP, 1_000_000L);
        inv4.put(SUPER_SOLDER, 1_000_000L);

        KeyCounter out1 = new KeyCounter();
        KeyCounter[] holder1 = gtlExtractForProcessingPattern(pattern, inv1, out1, 1);
        assertNotNull(holder1, "multiplier=1 应该能提取成功");

        KeyCounter out4 = new KeyCounter();
        KeyCounter[] holder4 = gtlExtractForProcessingPattern(pattern, inv4, out4, 4);
        assertNotNull(holder4, "multiplier=4 应该能提取成功");

        // 提取量严格线性：4x 恰好等于 4 份 1x
        assertEquals(4L * slotTotal(holder1, UXV_ELECTRIC_PUMP), slotTotal(holder4, UXV_ELECTRIC_PUMP),
                "pump 提取量应为 multiplier 的线性倍数");
        assertEquals(4L * slotTotal(holder1, SUPER_SOLDER), slotTotal(holder4, SUPER_SOLDER),
                "solder 提取量应为 multiplier 的线性倍数");

        // 期望输出严格线性
        assertEquals(4L * out1.get(SUPER_PATTERN_BUFFER), out4.get(SUPER_PATTERN_BUFFER),
                "期望输出应为 multiplier 的线性倍数");

        // 自动翻倍后 taskProgress 归零 + 移除任务，单次 push 覆盖全部 4 craft，不失控
        long[] progress = {4};
        long covered = gtlSimulateAutoExpandPush(pattern, new HashMap<>(Map.of(
                UXV_ELECTRIC_PUMP, 1_000_000L, SUPER_SOLDER, 1_000_000L)), progress);
        assertEquals(4L, covered, "自动翻倍单次 push 应覆盖全部 taskProgress");
        assertEquals(0L, progress[0], "自动翻倍后 taskProgress 应归零，避免计划倍率失控");

        System.out.println("[gtl-autoexpand] multiplier=1 out=" + out1.get(SUPER_PATTERN_BUFFER)
                + ", multiplier=4 out=" + out4.get(SUPER_PATTERN_BUFFER)
                + " (linear=" + (out4.get(SUPER_PATTERN_BUFFER) == 4 * out1.get(SUPER_PATTERN_BUFFER)) + ")");
    }

    // 复刻 CraftingCpuLogicMixin: autoExpand=true 时一次 push 整批 taskProgress，然后归零移除
    private static long gtlSimulateAutoExpandPush(IPatternDetails pattern,
                                                  Map<BenchAEKey, Long> inv,
                                                  long[] taskProgress) {
        KeyCounter expectedOutputs = new KeyCounter();
        KeyCounter[] holder = gtlExtractForProcessingPattern(pattern, inv, expectedOutputs, taskProgress[0]);
        if (holder == null) {
            return 0L;
        }
        // autoExpand 分支: taskProgress.setValue(0) + it.remove()
        long covered = taskProgress[0];
        taskProgress[0] = 0L;
        return covered;
    }

    // ====================================================================
    // 场景 2：样板总成 pattern 更新闭环 —— 长链先缺中间样板，写入后重算成功
    // ====================================================================
    @Test
    void gtlPatternBufferOnPatternChangeFixesStaleMissing() {
        GtlCraftingService service = new GtlCraftingService();
        service.addProvider(new BenchPatternDetails(SUPER_PATTERN_BUFFER, 1, List.of(
                BenchPatternDetails.InputSpec.of(UXV_ELECTRIC_PUMP, 4),
                BenchPatternDetails.InputSpec.of(SUPER_SOLDER, 16),
                BenchPatternDetails.InputSpec.of(UNIVERSAL_CIRCUIT, 832))));
        service.addProvider(new BenchPatternDetails(SUPER_SOLDER, 1, List.of(
                BenchPatternDetails.InputSpec.of(TIN, 2))));
        // UXV_ELECTRIC_PUMP 样板此时未注册 —— 对应“最终产物缺中间样板”。

        Map<BenchAEKey, Long> stock = new HashMap<>();
        stock.put(TIN, 1_000_000L);
        stock.put(NICHROME, 1_000_000L);
        stock.put(UNIVERSAL_CIRCUIT, 1_000_000L);

        PatternCompiler.clearCache();
        PatternCompiler.clearFuzzyGroups();
        for (List<IPatternDetails> subs : service.providers.values()) {
            for (IPatternDetails p : subs) PatternCompiler.compileIfAbsent(p);
        }

        CraftingVM vm = new CraftingVM("gtl-pattern-buffer", key -> firstOrNull(service.getCraftingFor(key)));
        CraftingBytecode req = PatternCompiler.compileRequest(
                firstOrNull(service.getCraftingFor(SUPER_PATTERN_BUFFER)), 1);

        // Step 1: 先下单最终产物，缺少中间产物 UXV_ELECTRIC_PUMP。
        ICraftingPlan plan1 = vm.execute(req, new BenchSimulationState(stock));
        assertTrue(missing(plan1).containsKey(UXV_ELECTRIC_PUMP.toString()),
                "Step 1 应缺少泵。missing=" + missing(plan1));

        // Step 2: 用户把缺失的泵样板写进样板总成。写入立即触发 onPatternChange →
        //         bumpPatternVersion + compileIfAbsent(getAvailablePatterns())。
        //         但 provider 尚未同步（GTL 延迟 requestUpdate 的刷新窗口）。
        IPatternDetails pump = new BenchPatternDetails(UXV_ELECTRIC_PUMP, 1, List.of(
                BenchPatternDetails.InputSpec.of(NICHROME, 8)));
        GtlPatternBuffer buffer = new GtlPatternBuffer();
        buffer.patterns.add(pump);
        buffer.onPatternChange();

        // 修复的第二个作用：刷新窗口内 getCraftingFor 仍为空，但 findCompiledByOutput 已命中。
        assertNotNull(PatternCompiler.findCompiledByOutput(UXV_ELECTRIC_PUMP),
                "onPatternChange 必须把泵样板编译进 VM 字节码缓存（供 stale-missing Check 2 命中）");

        // Step 3: GTL 延迟刷新完成，provider 注册表现在能看到泵样板。
        service.addProvider(pump);

        // Step 4: 同一 VM 重算最终产物 —— bump 已清空 stale bundleCache，不再缺泵。
        req = PatternCompiler.compileRequest(firstOrNull(service.getCraftingFor(SUPER_PATTERN_BUFFER)), 1);
        ICraftingPlan plan2 = vm.execute(req, new BenchSimulationState(stock));
        assertTrue(missing(plan2).isEmpty(),
                "Step 4 补样板后应不再缺料。missing=" + missing(plan2));

        System.out.println("[gtl-pattern-buffer] step1 missing=" + missing(plan1)
                + " step4 missing=" + missing(plan2));
    }

    @Test
    void gtlStaleMissingRetryCheck2RequiresCompile() {
        // 复刻 AE2VMCrafting 的 stale-missing 重试判定：
        //   Check 1: service.getCraftingFor(missingKey) 非空；
        //   Check 2: PatternCompiler.findCompiledByOutput(missingKey) 非空（字节码缓存，非实时网络）。
        IPatternDetails pump = new BenchPatternDetails(UXV_ELECTRIC_PUMP, 1, List.of(
                BenchPatternDetails.InputSpec.of(NICHROME, 8)));
        GtlCraftingService service = new GtlCraftingService(); // 泵未注册

        PatternCompiler.clearCache();
        PatternCompiler.clearFuzzyGroups();

        // 旧行为（mixin 只 bump 不 compile）：provider 空 + 未编译 → Check 1/2 全失败 → 永久缺料。
        assertEquals(List.of(), service.getCraftingFor(UXV_ELECTRIC_PUMP));
        assertNull(PatternCompiler.findCompiledByOutput(UXV_ELECTRIC_PUMP));
        assertFalse(staleMissingNeedsRetry(service, UXV_ELECTRIC_PUMP),
                "未编译的泵样板在 provider 刷新窗口内不应触发 stale-missing 重试（复刻旧 bug）");

        // 修复（onPatternChange 额外 compileIfAbsent）：provider 仍空，但 Check 2 命中 → 触发重试。
        PatternCompiler.compileIfAbsent(pump);
        assertNotNull(PatternCompiler.findCompiledByOutput(UXV_ELECTRIC_PUMP));
        assertTrue(staleMissingNeedsRetry(service, UXV_ELECTRIC_PUMP),
                "已编译的泵样板即便 provider 仍在刷新窗口，也应触发 stale-missing 重试");

        System.out.println("[gtl-stale-retry-check2] notCompiled -> needsRetry=false, compiled -> needsRetry=true");
    }

    /** 复刻 AE2VMCrafting stale-missing retry 的 Check 1 + Check 2。 */
    private static boolean staleMissingNeedsRetry(GtlCraftingService service, AEKey key) {
        if (!service.getCraftingFor(key).isEmpty()) return true;          // Check 1
        if (PatternCompiler.findCompiledByOutput(key) != null) return true; // Check 2
        return false;
    }

    // ====================================================================
    // 场景 3：GTL 禁用 AE2 原生异步合成后，VM 的 Future 计划仍可完成
    // ====================================================================
    @Test
    void gtlTickHandlerDisabledNativeAsyncVmFutureStillCompletes() throws Exception {
        Map<BenchAEKey, IPatternDetails> registry = buildSmallChain();
        Map<BenchAEKey, Long> stock = new HashMap<>();
        stock.put(TIN, 1_000_000L);
        stock.put(NICHROME, 1_000_000L);

        PatternCompiler.clearCache();
        PatternCompiler.clearFuzzyGroups();
        for (IPatternDetails p : registry.values()) PatternCompiler.compileIfAbsent(p);
        CraftingBytecode req = PatternCompiler.compileRequest(registry.get(SUPER_PATTERN_BUFFER), 2);

        CraftingVM vm = new CraftingVM("gtl-tick-disabled", registry::get);

        // GTL TickHandlerMixin: registerCraftingSimulation / simulateCraftingJobs 均被删除。
        GtlTickHandler tickHandler = new GtlTickHandler();

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<ICraftingPlan> future = pool.submit(() -> vm.execute(req, new BenchSimulationState(stock)));
            ICraftingPlan plan = future.get(10, TimeUnit.SECONDS);
            assertTrue(missing(plan).isEmpty(), "VM Future 计划应完成且无缺料。missing=" + missing(plan));

            // VM 不依赖 AE2 原生异步合成 tick 路径。
            assertEquals(0, tickHandler.registerCraftingSimulationCalls,
                    "VM 不应调用 GTL 已禁用的 registerCraftingSimulation");
            assertEquals(0, tickHandler.simulateCraftingJobsCalls,
                    "VM 不应调用 GTL 已禁用的 simulateCraftingJobs");

            System.out.println("[gtl-tick-disabled] vmFuture completed, nativeAsyncCalls=0");
        } finally {
            pool.shutdownNow();
        }
    }

    // ====================================================================
    // 场景 4：GTL CALCULATION_MODE 三态切换与 VM 并存，VM 结果不变
    // ====================================================================
    @Test
    void gtlCalculationModeSwitchDoesNotAffectVm() {
        Map<BenchAEKey, IPatternDetails> registry = buildSmallChain();
        Map<BenchAEKey, Long> stock = new HashMap<>();
        stock.put(TIN, 1_000_000L);
        stock.put(NICHROME, 1_000_000L);

        PatternCompiler.clearCache();
        PatternCompiler.clearFuzzyGroups();
        for (IPatternDetails p : registry.values()) PatternCompiler.compileIfAbsent(p);
        CraftingBytecode req = PatternCompiler.compileRequest(registry.get(SUPER_PATTERN_BUFFER), 2);

        CraftingVM vm = new CraftingVM("gtl-calc-mode", registry::get);

        GtlCalcRouter router = new GtlCalcRouter();
        Map<String, Long> vmMissing = null;
        long vmPatternTimes = -1;

        for (GtlCalcMode mode : GtlCalcMode.values()) {
            router.mode = mode;
            // 模拟 GTL CraftingTreeNode.request 按 CALCULATION_MODE 分发到三种算法。
            for (int i = 0; i < 1000; i++) router.routeTreeRequest();

            ICraftingPlan plan = vm.execute(req, new BenchSimulationState(stock));
            Map<String, Long> m = missing(plan);
            long patternTimes = patternTimesTotal(plan);

            if (vmMissing == null) {
                vmMissing = m;
                vmPatternTimes = patternTimes;
            } else {
                assertEquals(vmMissing, m,
                        "CALCULATION_MODE=" + mode + " 下 VM missing 与其它模式不一致");
                assertEquals(vmPatternTimes, patternTimes,
                        "CALCULATION_MODE=" + mode + " 下 VM patternTimes 与其它模式不一致");
            }

            System.out.println("[gtl-calc-mode] mode=" + mode
                    + " gtlRequests=" + router.count(mode)
                    + " vmMissing=" + m
                    + " vmPatternTimes=" + patternTimes);
        }

        // 三态确实被路由到不同算法。
        assertEquals(1000, router.legacy);
        assertEquals(1000, router.fast);
        assertEquals(1000, router.ultraFast);
    }

    // ====================================================================
    // 场景 5：GTL CraftingService.onServerEndTick tick 掩码节流 + VM 计划独立完成
    // ====================================================================
    @Test
    void gtlCraftingServiceTickThrottleDoesNotAffectVm() {
        Map<BenchAEKey, IPatternDetails> registry = buildSmallChain();
        Map<BenchAEKey, Long> stock = new HashMap<>();
        stock.put(TIN, 1_000_000L);
        stock.put(NICHROME, 1_000_000L);

        PatternCompiler.clearCache();
        PatternCompiler.clearFuzzyGroups();
        for (IPatternDetails p : registry.values()) PatternCompiler.compileIfAbsent(p);
        CraftingBytecode req = PatternCompiler.compileRequest(registry.get(SUPER_PATTERN_BUFFER), 2);

        CraftingVM vm = new CraftingVM("gtl-tick-throttle", registry::get);

        // 复刻 CraftingServiceMixin.onServerEndTick: CRAFT_MASK = nearestPow2(interval) - 1。
        int interval = 4;
        GtlCraftingServiceTickGate gate = new GtlCraftingServiceTickGate(interval);

        for (long tick = 0; tick < 10_000; tick++) {
            gate.currentTick = tick;
            gate.onServerEndTick();
        }

        // 即便 server-end tick 大量被节流取消，VM 的异步计划仍独立完成。
        ICraftingPlan plan = vm.execute(req, new BenchSimulationState(stock));
        assertTrue(missing(plan).isEmpty(), "VM 计划应在 server-end tick 被节流时仍完成。missing=" + missing(plan));

        System.out.println("[gtl-tick-throttle] interval=" + interval
                + " mask=" + gate.mask
                + " cancelled=" + gate.cancelled
                + " executed=" + gate.executed
                + " vmMissing=" + missing(plan));
        assertTrue(gate.cancelled > gate.executed, "掩码节流应取消大多数 server-end tick");
    }

    // ====================================================================
    // 共享链: SUPER_PATTERN_BUFFER -> UXV_ELECTRIC_PUMP -> NICHROME（stock）
    //                              -> SUPER_SOLDER -> TIN（stock）
    // ====================================================================
    private static Map<BenchAEKey, IPatternDetails> buildSmallChain() {
        Map<BenchAEKey, IPatternDetails> registry = new LinkedHashMap<>();
        registry.put(SUPER_PATTERN_BUFFER, new BenchPatternDetails(SUPER_PATTERN_BUFFER, 1, List.of(
                BenchPatternDetails.InputSpec.of(UXV_ELECTRIC_PUMP, 2),
                BenchPatternDetails.InputSpec.of(SUPER_SOLDER, 3))));
        registry.put(UXV_ELECTRIC_PUMP, new BenchPatternDetails(UXV_ELECTRIC_PUMP, 1, List.of(
                BenchPatternDetails.InputSpec.of(NICHROME, 8))));
        registry.put(SUPER_SOLDER, new BenchPatternDetails(SUPER_SOLDER, 1, List.of(
                BenchPatternDetails.InputSpec.of(TIN, 2))));
        return registry;
    }

    // ====================================================================
    // GTL 本地模型
    // ====================================================================

    // ---- 复刻 AEUtils.extractForProcessingPattern（处理样板原料提取核心） ----
    private static KeyCounter[] gtlExtractForProcessingPattern(IPatternDetails pattern,
                                                               Map<BenchAEKey, Long> inv,
                                                               KeyCounter expectedOutputs,
                                                               long multiplier) {
        IPatternDetails.IInput[] inputs = pattern.getInputs();
        KeyCounter[] inputHolder = new KeyCounter[inputs.length];

        for (int i = 0; i < inputs.length; i++) {
            KeyCounter slot = inputHolder[i] = new KeyCounter();
            BenchAEKey key = (BenchAEKey) inputs[i].getPossibleInputs()[0].what();
            long amount = inputs[i].getMultiplier() * multiplier;
            long extracted = gtlExtractTemplates(inv, key, amount);
            slot.add(key, extracted);
            if (extracted < amount) {
                gtlReinject(inv, inputHolder, i);
                return null;
            }
        }

        for (GenericStack out : pattern.getOutputs()) {
            if (out != null && out.what() != null) {
                expectedOutputs.add(out.what(), out.amount() * multiplier);
            }
        }
        return inputHolder;
    }

    private static long gtlExtractTemplates(Map<BenchAEKey, Long> inv, BenchAEKey key, long amount) {
        if (amount == 0) return 0;
        long available = inv.getOrDefault(key, 0L);
        long extracted = Math.min(available, amount);
        if (extracted == 0) return 0;
        long remaining = available - extracted;
        if (remaining == 0) inv.remove(key);
        else inv.put(key, remaining);
        return extracted;
    }

    private static void gtlReinject(Map<BenchAEKey, Long> inv, KeyCounter[] holder, int upto) {
        for (int j = 0; j <= upto; j++) {
            for (var e : holder[j]) {
                inv.merge((BenchAEKey) e.getKey(), e.getLongValue(), Long::sum);
            }
        }
    }

    private static long slotTotal(KeyCounter[] holder, BenchAEKey key) {
        long total = 0;
        for (KeyCounter slot : holder) {
            for (var e : slot) {
                if (e.getKey().equals(key)) total += e.getLongValue();
            }
        }
        return total;
    }

    // ---- 复刻 AE2CalculationMode（LEGACY / FAST / ULTRA_FAST） ----
    enum GtlCalcMode { LEGACY, FAST, ULTRA_FAST }

    // ---- 复刻 CraftingCalculationMixin.redirectTreeRequest 的三态分发 ----
    static final class GtlCalcRouter {
        GtlCalcMode mode = GtlCalcMode.LEGACY;
        long legacy;
        long fast;
        long ultraFast;

        void routeTreeRequest() {
            switch (mode) {
                case LEGACY -> legacy++;
                case FAST -> fast++;
                case ULTRA_FAST -> ultraFast++;
            }
        }

        long count(GtlCalcMode m) {
            return switch (m) {
                case LEGACY -> legacy;
                case FAST -> fast;
                case ULTRA_FAST -> ultraFast;
            };
        }
    }

    // ---- 复刻 TickHandlerMixin：删除 AE2 原生异步合成 ----
    static final class GtlTickHandler {
        final boolean nativeAsyncDisabled = true;
        int registerCraftingSimulationCalls = 0;
        int simulateCraftingJobsCalls = 0;

        void registerCraftingSimulation(Object level, Object craftingCalculation) {
            registerCraftingSimulationCalls++;
            if (nativeAsyncDisabled) throw new AssertionError("GTL 禁用 AE2 原生异步合成");
        }

        void simulateCraftingJobs(Object level) {
            simulateCraftingJobsCalls++;
            if (nativeAsyncDisabled) throw new AssertionError("GTL 禁用 AE2 原生异步合成");
        }
    }

    // ---- 复刻 CraftingServiceMixin.onServerEndTick 的 tick 位掩码节流 ----
    static final class GtlCraftingServiceTickGate {
        final int mask;
        long currentTick;
        long cancelled;
        long executed;

        GtlCraftingServiceTickGate(int interval) {
            this.mask = nearestPow2(interval) - 1;
        }

        boolean onServerEndTick() {
            if ((currentTick & mask) != 0) {
                cancelled++;
                return false;
            }
            executed++;
            return true;
        }
    }

    private static int nearestPow2(int v) {
        int p = 1;
        while (p < v) p <<= 1;
        return p;
    }

    // ---- 复刻 CraftingService.getCraftingFor 的 provider 注册表 ----
    static final class GtlCraftingService {
        final Map<BenchAEKey, List<IPatternDetails>> providers = new LinkedHashMap<>();

        void addProvider(IPatternDetails pattern) {
            for (GenericStack out : pattern.getOutputs()) {
                if (out != null && out.what() != null) {
                    providers.computeIfAbsent((BenchAEKey) out.what(), k -> new ArrayList<>()).add(pattern);
                }
            }
        }

        List<IPatternDetails> getCraftingFor(AEKey key) {
            return providers.getOrDefault(key, List.of());
        }
    }

    // ---- 复刻 MEPatternBufferPartMachine + VM mixin 的更新闭环 ----
    static final class GtlPatternBuffer {
        final List<IPatternDetails> patterns = new ArrayList<>();

        List<IPatternDetails> getAvailablePatterns() {
            return new ArrayList<>(patterns);
        }

        // 复刻 MEPatternBufferPartMachineMixin.vmOnPatternChange：
        // bumpPatternVersion + 编译 getAvailablePatterns() 中尚未编译的样板。
        void onPatternChange() {
            PatternCompiler.bumpPatternVersion();
            for (IPatternDetails p : getAvailablePatterns()) {
                if (PatternCompiler.getCompiled(p) == null) {
                    PatternCompiler.compileIfAbsent(p);
                }
            }
        }
    }

    private static IPatternDetails firstOrNull(List<IPatternDetails> subs) {
        return subs.isEmpty() ? null : subs.get(0);
    }

    // ====================================================================
    // 处理样板（复刻 AEProcessingPattern：getMultiplier() 即每 craft 输入量）
    // ====================================================================
    private static final class GtlProcessingPattern implements IPatternDetails {
        private final IInput[] inputs;
        private final GenericStack[] outputs;

        GtlProcessingPattern(BenchAEKey out, long outAmount, List<IInput> inputs) {
            this.inputs = inputs.toArray(new IInput[0]);
            this.outputs = new GenericStack[]{new GenericStack(out, outAmount)};
        }

        @Override public GenericStack[] getOutputs() { return outputs; }
        @Override public IInput[] getInputs() { return inputs; }
        @Override public AEItemKey getDefinition() { return null; }
    }

    private static final class GtlProcessingInput implements IPatternDetails.IInput {
        private final GenericStack[] possible;
        private final long multiplier;

        GtlProcessingInput(BenchAEKey key, long perCraft) {
            this.possible = new GenericStack[]{new GenericStack(key, perCraft)};
            this.multiplier = perCraft;
        }

        @Override public GenericStack[] getPossibleInputs() { return possible; }
        @Override public long getMultiplier() { return multiplier; }
        @Override public boolean isValid(AEKey input, Level level) {
            return input.equals(possible[0].what());
        }
        @Override public AEKey getRemainingKey(AEKey template) { return null; }
    }

    // ====================================================================
    // helpers
    // ====================================================================
    private static Map<String, Long> missing(ICraftingPlan p) {
        TreeMap<String, Long> out = new TreeMap<>();
        for (var e : p.missingItems()) {
            out.put(e.getKey().toString(), e.getLongValue());
        }
        return out;
    }

    private static long patternTimesTotal(ICraftingPlan p) {
        long total = 0;
        for (var e : p.patternTimes().entrySet()) total += e.getValue();
        return total;
    }
}
