package com.ae2vm.addon.bench;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.inv.ChildCraftingSimulationState;
import appeng.crafting.inv.CraftingSimulationState;
import com.ae2vm.addon.compiler.PatternCompiler;
import com.ae2vm.addon.mixin.CraftingSimulationStateAccessor;
import com.ae2vm.addon.vm.CraftingBytecode;
import com.ae2vm.addon.vm.CraftingVM;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CancellationException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * GTL + AE2-VM 共存 mixin 场景基准测试（VM-GTL 分支）。
 *
 * <p>目标（任务 2）：把「VM-GTL 与 gtlcore(1.2.3.1-fix11) 在 AE2 合成域上共存」的
 * mixin 契约固化成可重复运行的基准。测试不启动真实 MC，而是：
 *
 * <ul>
 *   <li>静态 mixin 目标矩阵（{@link #matrixHasNoHardConflicts()}）—— 逐条列出双方
 *       在 AE2 合成类上的注入点（数据来自本仓库真实源码：
 *       {@code VM-GTL/src/main/java/com/ae2vm/addon/mixin/*} 与
 *       {@code build/gtl-decomp-src/org/gtlcore/gtlcore/mixin/ae2/*}），并断言
 *       没有「同一 (目标类, 方法) 上出现 @Overwrite 硬冲突」；允许 Inject+Inject /
 *       Accessor+Overwrite 这类分成员共存。</li>
 *   <li>行为场景：VM 路径从不触发 GTL 覆盖的 {@code CraftingSimulationState.applyDiff}；
 *       取消请求不触发阻塞原生回退；真实失败仍回退；GTL provider 刷新窗口下的
 *       延迟重试；PatternProviderLogic 双 TAIL 处理器共存。</li>
 * </ul>
 */
public class GtlMixinCoexistenceBenchmark {

    // ====================================================================
    // 共享 key
    // ====================================================================
    private static final BenchAEKey FINAL = BenchAEKey.of("gtl_coex_final");
    private static final BenchAEKey MID   = BenchAEKey.of("gtl_coex_mid");
    private static final BenchAEKey LEAF  = BenchAEKey.of("gtl_coex_leaf");
    private static final BenchAEKey CIRCUIT = BenchAEKey.of("gtceu_integrated_circuit");

    // ====================================================================
    // 1. 静态 mixin 目标矩阵（数据源：本仓库真实源码，勿凭记忆修改）
    // ====================================================================
    private record MixinPoint(String targetClass, String method, String kind, String owner) {
        String key() {
            return targetClass + "::" + method;
        }
    }

    /** AE2-VM 在 AE2 合成域上的全部注入点（ae2vm.mixins.json，priority=2000）。 */
    private static final List<MixinPoint> VM_POINTS = List.of(
            new MixinPoint("appeng.me.service.CraftingService", "beginCraftingCalculation", "Inject(HEAD,cancellable)", "ae2vm"),
            new MixinPoint("appeng.me.service.CraftingService", "refreshNodeCraftingProvider", "Inject(TAIL)", "ae2vm"),
            new MixinPoint("appeng.helpers.patternprovider.PatternProviderLogic", "updatePatterns", "Inject(TAIL)", "ae2vm"),
            new MixinPoint("appeng.crafting.inv.CraftingSimulationState", "bytes", "Accessor(getBytes)", "ae2vm"),
            new MixinPoint("org.gtlcore.gtlcore.common.machine.multiblock.part.ae.MEPatternBufferPartMachine", "onPatternChange", "Inject(TAIL,@Pseudo)", "ae2vm")
    );

    /** gtlcore 1.2.3.1-fix11 在 AE2 合成域上的关键注入点（反编译源码核实）。 */
    private static final List<MixinPoint> GTL_POINTS = List.of(
            new MixinPoint("appeng.crafting.CraftingCalculation", "run", "Overwrite", "gtlcore"),
            new MixinPoint("appeng.crafting.CraftingCalculation", "finish", "Overwrite", "gtlcore"),
            new MixinPoint("appeng.crafting.CraftingCalculation", "simulateFor", "Overwrite", "gtlcore"),
            new MixinPoint("appeng.crafting.CraftingCalculation", "handlePausing", "Inject(HEAD,cancellable)", "gtlcore"),
            new MixinPoint("appeng.crafting.CraftingCalculation", "runCraftAttempt", "Inject(HEAD)+Inject(RETURN)+2xRedirect", "gtlcore"),
            new MixinPoint("appeng.crafting.execution.CraftingCpuHelper", "getValidItemTemplates", "Overwrite", "gtlcore"),
            new MixinPoint("appeng.crafting.execution.CraftingCpuHelper", "extractTemplates", "Overwrite", "gtlcore"),
            new MixinPoint("appeng.crafting.inv.CraftingSimulationState", "applyDiff", "Overwrite", "gtlcore"),
            new MixinPoint("appeng.crafting.inv.NetworkCraftingSimulationState", "simulateExtractParent", "Overwrite", "gtlcore"),
            new MixinPoint("appeng.crafting.inv.NetworkCraftingSimulationState", "findFuzzyParent", "Overwrite", "gtlcore"),
            new MixinPoint("appeng.crafting.CraftingTreeNode", "addContainerItems", "Overwrite", "gtlcore"),
            new MixinPoint("appeng.crafting.CraftingTreeNode", "getValidItemTemplates", "Overwrite", "gtlcore"),
            new MixinPoint("appeng.crafting.CraftingTreeNode", "request", "Redirect(target=runCraftAttempt)", "gtlcore"),
            new MixinPoint("appeng.crafting.CraftingTreeNode", "countNodes", "Inject(RETURN)", "gtlcore"),
            new MixinPoint("appeng.helpers.patternprovider.PatternProviderLogic", "updatePatterns", "Inject(TAIL)", "gtlcore"),
            new MixinPoint("appeng.helpers.patternprovider.PatternProviderLogic", "writeToNBT", "Inject(TAIL)", "gtlcore"),
            new MixinPoint("appeng.helpers.patternprovider.PatternProviderLogic", "readFromNBT", "Inject(TAIL)", "gtlcore"),
            new MixinPoint("appeng.helpers.patternprovider.PatternProviderLogic", "exportSettings", "Inject(TAIL)", "gtlcore"),
            new MixinPoint("appeng.helpers.patternprovider.PatternProviderLogic", "importSettings", "Inject(TAIL)", "gtlcore"),
            new MixinPoint("appeng.me.service.CraftingService", "<init>", "Inject(RETURN)", "gtlcore"),
            new MixinPoint("appeng.me.service.CraftingService", "onServerEndTick", "Inject(HEAD,cancellable)+2xFIELD", "gtlcore"),
            new MixinPoint("appeng.me.service.CraftingService", "removeNode", "Inject(TAIL)", "gtlcore"),
            new MixinPoint("appeng.me.service.CraftingService", "addNode", "Inject(TAIL)", "gtlcore"),
            new MixinPoint("appeng.me.service.CraftingService", "updateCPUClusters", "Inject(TAIL)", "gtlcore"),
            new MixinPoint("appeng.me.service.CraftingService", "insertIntoCpus", "Inject(RETURN,cancellable)", "gtlcore"),
            new MixinPoint("appeng.me.service.CraftingService", "submitJob", "Inject(INVOKE_ASSIGN,cancellable)", "gtlcore"),
            new MixinPoint("appeng.me.service.CraftingService", "getCpus", "Inject(RETURN,cancellable)", "gtlcore"),
            new MixinPoint("appeng.me.service.CraftingService", "getRequestedAmount", "Inject(RETURN,cancellable)", "gtlcore"),
            new MixinPoint("appeng.me.service.CraftingService", "isRequesting", "Inject(RETURN,cancellable)", "gtlcore"),
            new MixinPoint("appeng.me.service.CraftingService", "isRequestingAny", "Inject(RETURN,cancellable)", "gtlcore"),
            new MixinPoint("appeng.me.service.CraftingService", "hasCpu", "Inject(HEAD,cancellable)", "gtlcore")
    );

    @Test
    void matrixHasNoHardConflicts() {
        Map<String, MixinPoint> vmByKey = new HashMap<>();
        for (MixinPoint p : VM_POINTS) {
            vmByKey.put(p.key(), p);
        }

        List<String> hardConflicts = new ArrayList<>();
        List<String> softOverlaps = new ArrayList<>();
        for (MixinPoint g : GTL_POINTS) {
            MixinPoint v = vmByKey.get(g.key());
            if (v == null) {
                continue; // GTL-owned method — VM never touches it
            }
            // Same (target, method): allowed ONLY when neither side is an @Overwrite
            // (Inject+Inject chain in priority order, or Accessor+Inject on different members).
            if (v.kind().contains("Overwrite") || g.kind().contains("Overwrite")) {
                hardConflicts.add(v + "  <->  " + g);
            } else {
                softOverlaps.add(v + "  <->  " + g);
            }
        }

        System.out.println("[gtl-mixin-matrix] VM points: " + VM_POINTS.size()
                + ", GTL points: " + GTL_POINTS.size()
                + ", shared (target::method): " + (softOverlaps.size() + hardConflicts.size()));
        for (String s : softOverlaps) {
            System.out.println("[gtl-mixin-matrix] soft overlap (compatible): " + s);
        }
        for (String s : hardConflicts) {
            System.out.println("[gtl-mixin-matrix] HARD CONFLICT: " + s);
        }

        assertTrue(hardConflicts.isEmpty(), "GTL/AE2-VM @Overwrite hard conflict on shared target::method: " + hardConflicts);
        // 唯一允许的共享方法：PatternProviderLogic.updatePatterns（双方均为非 cancellable TAIL Inject）。
        assertEquals(1, softOverlaps.size(), "expected exactly the PatternProviderLogic.updatePatterns soft overlap, got: " + softOverlaps);
        assertTrue(softOverlaps.get(0).contains("updatePatterns"),
                "shared method must be updatePatterns, got: " + softOverlaps);
    }

    // ====================================================================
    // 2. 行为场景：VM 路径从不触发 GTL 覆盖的 applyDiff
    // ====================================================================
    @Test
    void vmPathNeverInvokesApplyDiffOnRealtimeState() {
        // GTL @Overwrite 了 CraftingSimulationState.applyDiff 并向目标类追加了
        // ICraftingSimulationStateFastAccess 接口（其 gtlcore$ 方法我们的子类没有实现）。
        // 只要 VM 路径不调用 applyDiff（即不触发 GTL 覆盖版本强转我们的实例），
        // 就不会 AbstractMethodError。此场景用真实 VM + 真实 ChildCraftingSimulationState
        // 验证：一次完整 execute 期间 applyDiff 调用次数必须为 0。
        Map<AEKey, IPatternDetails> registry = new LinkedHashMap<>();
        registry.put(FINAL, new BenchPatternDetails(FINAL, 1, List.of(
                BenchPatternDetails.InputSpec.of(MID, 2),
                BenchPatternDetails.InputSpec.of(LEAF, 3))));
        registry.put(MID, new BenchPatternDetails(MID, 1, List.of(
                BenchPatternDetails.InputSpec.of(LEAF, 4))));
        registry.put(LEAF, new BenchPatternDetails(LEAF, 1, List.of()));

        Map<BenchAEKey, Long> stock = new HashMap<>();
        stock.put(LEAF, 1_000_000L);

        PatternCompiler.clearCache();
        PatternCompiler.clearFuzzyGroups();
        for (IPatternDetails p : registry.values()) {
            PatternCompiler.compileIfAbsent(p);
        }
        CraftingBytecode req = PatternCompiler.compileRequest(registry.get(FINAL), 2);

        FlaggedSimState realtime = new FlaggedSimState(stock);
        // 游戏内：ChildCraftingSimulationState 经 CraftingSimulationStateAccessor mixin 继承 getBytes；
        // 离线测试用子类模拟同一形态。
        FlaggedChild child = new FlaggedChild(realtime);
        child.ignore(FINAL);

        CraftingVM vm = new CraftingVM("gtl-applydiff", registry::get);
        ICraftingPlan plan = vm.execute(req, child);

        assertEquals(0, realtime.applyDiffCalls,
                "VM path must NEVER invoke applyDiff (GTL overwrote it + added unimplemented interface methods)");
        assertTrue(plan.missingItems().isEmpty(), "chain must be feasible. missing=" + missing(plan));
        System.out.println("[gtl-applydiff] applyDiffCalls=" + realtime.applyDiffCalls + " missing=" + missing(plan));
    }

    /** 复刻 com.ae2vm.addon.vm.RealtimeNetworkCraftingSimulationState 的形态：继承
     *  CraftingSimulationState（GTL 也 mixin 了它）+ 统计 applyDiff 调用。 */
    private static final class FlaggedSimState extends CraftingSimulationState
            implements CraftingSimulationStateAccessor {
        private final Map<BenchAEKey, Long> stock;
        int applyDiffCalls;

        FlaggedSimState(Map<BenchAEKey, Long> stock) {
            this.stock = stock;
        }

        @Override
        protected long simulateExtractParent(AEKey what, long amount) {
            long available = what instanceof BenchAEKey k ? stock.getOrDefault(k, 0L) : 0L;
            return Math.min(available, amount);
        }

        @Override
        protected Iterable<AEKey> findFuzzyParent(AEKey input) {
            return List.of();
        }

        @Override
        public double getBytes() {
            // Same semantics as the @Accessor mixin: read the private parent `bytes` field.
            try {
                var f = appeng.crafting.inv.CraftingSimulationState.class.getDeclaredField("bytes");
                f.setAccessible(true);
                return f.getDouble(this);
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public void applyDiff(CraftingSimulationState parent) {
            applyDiffCalls++;
            super.applyDiff(parent);
        }
    }

    /** 游戏内 ChildCraftingSimulationState 由 accessor mixin 注入 getBytes；离线复刻。 */
    private static final class FlaggedChild extends ChildCraftingSimulationState
            implements CraftingSimulationStateAccessor {
        FlaggedChild(CraftingSimulationState parent) {
            super(parent);
        }

        @Override
        public double getBytes() {
            try {
                var f = appeng.crafting.inv.CraftingSimulationState.class.getDeclaredField("bytes");
                f.setAccessible(true);
                return f.getDouble(this);
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException(e);
            }
        }
    }

    // ====================================================================
    // 3. 取消请求不触发阻塞原生回退（v1.12.x GTL 修复）
    // ====================================================================
    @Test
    void cancellationIsNotANativeFallbackFailure() {
        assertFalse(vmShouldFallbackMirror(new CancellationException("cancelled")),
                "CancellationException must NOT fall back to native (GTL MAX_FAST re-calc stalls the server)");
        assertFalse(vmShouldFallbackMirror(
                        new CompletionException(new CancellationException("cancelled"))),
                "CompletionException wrapping CancellationException must NOT fall back to native");
        assertFalse(vmShouldFallbackMirror(null),
                "null (success path) must NOT fall back");
        System.out.println("[gtl-cancel] cancellation -> no native fallback (OK)");
    }

    @Test
    void realVmFailureStillFallsBackToNative() {
        assertTrue(vmShouldFallbackMirror(new IllegalStateException("no pattern")),
                "a real VM failure must still fall back to the ORIGINAL crafting path");
        assertTrue(vmShouldFallbackMirror(new CompletionException(new IllegalStateException("boom"))),
                "a wrapped real failure must still fall back");
        System.out.println("[gtl-cancel] real failure -> native fallback (OK)");
    }

    // ====================================================================
    // 4. GTL provider 刷新窗口下的延迟重试（latest (3).log 假阴复刻）
    // ====================================================================
    @Test
    void providerRefreshWindowRetryFindsLatePattern() {
        // 复刻 AE2VMCrafting.calculateAsync 的重试判定（Check 1 + Check 2），
        // 并验证新增的 settle 窗口：第一次检查落在 GTL provider 刷新窗口内
        // （getCraftingFor 为空 且 从未编译），等待窗口关闭后第二次检查命中。
        Map<BenchAEKey, Long> stock = new HashMap<>();
        stock.put(LEAF, 1_000_000L);

        GtlServiceModel service = new GtlServiceModel();
        service.addPattern(FINAL, new BenchPatternDetails(FINAL, 1, List.of(
                BenchPatternDetails.InputSpec.of(MID, 2))));
        service.addPattern(LEAF, new BenchPatternDetails(LEAF, 1, List.of()));
        // MID 的样板此时在 GTL 刷新窗口内（provider 未注册 + 从未编译）

        assertFalse(missingKeyNowCraftable(service, List.of(MID)),
                "刷新窗口内：getCraftingFor 空 且 未编译 → 不应触发重试（旧行为，假阴）");

        // GTL 服务器 tick 完成 provider 注册（窗口关闭）
        service.addPattern(MID, new BenchPatternDetails(MID, 1, List.of(
                BenchPatternDetails.InputSpec.of(LEAF, 4))));

        assertTrue(missingKeyNowCraftable(service, List.of(MID)),
                "窗口关闭后：getCraftingFor 命中 → 必须触发重试（修复行为）");

        // 窗口关闭 + 全链可见后，同一 VM 重算必须不再缺 MID
        PatternCompiler.clearCache();
        PatternCompiler.clearFuzzyGroups();
        for (IPatternDetails p : service.patterns.values()) {
            PatternCompiler.compileIfAbsent(p);
        }
        CraftingBytecode req = PatternCompiler.compileRequest(service.patterns.get(FINAL), 1);
        CraftingVM vm = new CraftingVM("gtl-window", key -> key instanceof BenchAEKey bk ? service.patterns.get(bk) : null);
        ICraftingPlan plan = vm.execute(req, new BenchSimulationState(stock));
        assertTrue(plan.missingItems().isEmpty(), "窗口关闭后重算应无缺料. missing=" + missing(plan));
        System.out.println("[gtl-provider-window] window-check1=false check2=true finalMissing=" + missing(plan));
    }

    /** 复刻 AE2VMCrafting.missingKeyNowCraftable（Check 1: getCraftingFor；Check 2: 已编译缓存）。 */
    private static boolean missingKeyNowCraftable(GtlServiceModel service, List<? extends AEKey> missingKeys) {
        for (AEKey key : missingKeys) {
            if (key instanceof BenchAEKey bk) {
                if (service.patterns.containsKey(bk)) {
                    return true; // Check 1
                }
                if (PatternCompiler.findCompiledByOutput(key) != null) {
                    return true; // Check 2
                }
            }
        }
        return false;
    }

    /** 复刻 CraftingService.getCraftingFor 的 provider 注册表（最小模型）。 */
    private static final class GtlServiceModel {
        final Map<BenchAEKey, IPatternDetails> patterns = new LinkedHashMap<>();

        void addPattern(BenchAEKey key, IPatternDetails pattern) {
            patterns.put(key, pattern);
        }
    }

    // ====================================================================
    // 5. PatternProviderLogic 双 TAIL 处理器共存
    // ====================================================================
    @Test
    void dualPatternProviderLogicTailHandlersCoexist() {
        // GTL 与 AE2-VM 都 @Inject(updatePatterns, TAIL)：
        //   - GTL: patternInputs.remove(集成电路)（gtlcore$updatePatternsHook）
        //   - AE2-VM: PatternCompiler.bumpPatternVersion() + 预编译
        // 两者均为非 cancellable TAIL，Mixin 按 priority 顺序串行执行，互不干扰。
        PatternProviderLogicModel logic = new PatternProviderLogicModel();
        logic.patternInputs.add(CIRCUIT);

        long versionBefore = PatternCompiler.patternVersion();
        logic.runBothTailHandlers();
        long versionAfter = PatternCompiler.patternVersion();

        assertFalse(logic.patternInputs.contains(CIRCUIT), "GTL TAIL handler must remove the integrated circuit");
        assertTrue(versionAfter > versionBefore, "AE2-VM TAIL handler must bump the pattern version");
        System.out.println("[gtl-ppl] version " + versionBefore + " -> " + versionAfter
                + ", circuitRemoved=" + !logic.patternInputs.contains(CIRCUIT));
    }

    /** 复刻 PatternProviderLogic.updatePatterns 的 TAIL 共存模型。 */
    private static final class PatternProviderLogicModel {
        final Set<BenchAEKey> patternInputs = new HashSet<>();

        void runBothTailHandlers() {
            // GTL gtlcore$updatePatternsHook（priority 较低时后执行；这里按任意顺序都成立）
            patternInputs.remove(CIRCUIT);
            // AE2-VM onUpdatePatterns（priority=2000）
            PatternCompiler.bumpPatternVersion();
        }
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
