package com.ae2vm.addon;

import static com.ae2vm.addon.TestAeStacks.stack;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import appeng.api.config.Actionable;
import appeng.api.networking.crafting.ICraftingPatternDetails;
import appeng.api.storage.data.IAEItemStack;
import appeng.api.storage.data.IAEStack;

import com.ae2vm.addon.compiler.PatternCompiler;
import com.ae2vm.addon.v8.V8PatternDetails;
import com.ae2vm.addon.vm.CraftingBytecode;
import com.ae2vm.addon.vm.CraftingVM;
import com.ae2vm.shim.api.crafting.IPatternDetails;
import com.ae2vm.shim.api.networking.crafting.ICraftingPlan;
import com.ae2vm.shim.api.stacks.AEItemKey;
import com.ae2vm.shim.api.stacks.AEKey;
import com.ae2vm.shim.api.storage.data.MixedStackList;
import com.ae2vm.shim.crafting.inv.ChildCraftingSimulationState;
import com.ae2vm.shim.crafting.inv.CraftingSimulationState;

/**
 * 1.12.2-nova：按用户给的场景做**标准单元测试**（不启动游戏）。
 *
 * <pre>
 *   原木 --1→4--&gt; 木板 --4→1--&gt; 工作台
 *   第 1 轮：网络 0 原木、1 木板 → 下单 100 工作台 → 只许报"缺 100 原木"；
 *            木板是本次计划自己要造的中间品，不许同时报缺木板。
 *   第 2 轮：补进 100 原木，**同一个 VM 实例 + 同一份字节码**再算一次
 *            → 上一轮的缺料旧账不许跟着缓存带过来（"原料补进来了却仍报缺料"）。
 * </pre>
 *
 * <p>跑的是生产代码：样板过真实适配器 {@link V8PatternDetails}（uel 的数组版
 * {@code ICraftingPatternDetails}），编译过 {@link PatternCompiler}，演算过
 * {@link CraftingVM#execute}。假的只有栈/列表/库存（{@link TestAeStacks} + {@link BenchStock}），
 * 原因实测记在 {@link TestAeStacks} 的类注释里。网络键传 {@code String} —— 即
 * {@code CraftingVM} 里明写的 bench 模式（非 IGrid 键时 {@code realStockOf()} 返回 0，
 * 库存一律由 simulation 提供）。
 */
public class VmMissingAfterStockRefillTest {

    private static final String LOG = "log";
    private static final String PLANKS = "planks";
    private static final String TABLE = "crafting_table";

    private Map<String, Long> stock;
    private BenchStock sim;

    @BeforeEach
    public void resetCaches() throws Exception {
        // 打开 debugLogging，让 CraftingVM 的 SHORTFALL 账本进 stdout（测试报告可查）
        java.lang.reflect.Field dbg = com.ae2vm.addon.config.AE2VMConfig.class
                .getDeclaredField("debugLogging");
        dbg.setAccessible(true);
        dbg.set(null, Boolean.TRUE);
        PatternCompiler.clearCache();
        PatternCompiler.clearFuzzyGroups();
        stock = new LinkedHashMap<String, Long>();
        sim = new BenchStock(stock);
    }

    /**
     * ⚠ 必须还原：debugLogging 是 AE2VMConfig 的**私有静态字段**，整套基准的 230 条用例共用一个 JVM。
     * 不还原时，凡在本类之后跑的类都继承着"调试日志开"。1.10.2 fork 换用自己的 BenchRunner 后实测到
     * 后果：本类序 1、PerformanceBenchmark 序 22，于是每次 execute() 都同步打日志
     * （整轮 390,726 行 [AE2-VM] SANDBOX，修后 52 行），温热中位数从 ~100ns 劣化到 ~4000ns，
     * 6 条性能用例假红 —— 症状只报"中位数超阈值"，不报"日志开着"，极容易误诊成引擎回归。
     * 产品侧无影响：测试类不进产物 jar，游戏里这个值只来自 config/AE2VM.cfg（默认 false）。
     */
    @AfterEach
    public void restoreConfigDefaults() throws Exception {
        java.lang.reflect.Field dbg = com.ae2vm.addon.config.AE2VMConfig.class
                .getDeclaredField("debugLogging");
        dbg.setAccessible(true);
        dbg.set(null, Boolean.FALSE);
    }

    @Test
    public void intermediateCraftIsNotReportedMissingAndRefillClearsIt() {
        IPatternDetails logToPlanks = pattern("log->planks",
                new IAEItemStack[]{stack(PLANKS, 0, 4)},
                new IAEItemStack[]{stack(LOG, 0, 1)},
                new IAEItemStack[]{stack(LOG, 0, 1)}, false);
        // 工作台：逐槽 4 个木板(各 ×1)、condensed 成 1 条 ×4 —— 与实机 PATTERN NBT 同形状
        IPatternDetails planksToTable = pattern("planks->table",
                new IAEItemStack[]{stack(TABLE, 0, 1)},
                new IAEItemStack[]{stack(PLANKS, 0, 4)},
                new IAEItemStack[]{stack(PLANKS, 0, 1), stack(PLANKS, 0, 1),
                        stack(PLANKS, 0, 1), stack(PLANKS, 0, 1)}, false);

        final Map<AEKey, IPatternDetails> byOutput = new LinkedHashMap<AEKey, IPatternDetails>();
        byOutput.put(keyOf(logToPlanks), logToPlanks);
        byOutput.put(keyOf(planksToTable), planksToTable);

        stock.put(PLANKS + ":0", 1L);                       // 第 1 轮：1 木板、0 原木

        CraftingVM vm = new CraftingVM(TestAeStacks.benchGrid(stock), new java.util.function.Function<AEKey, IPatternDetails>() {
            @Override
            public IPatternDetails apply(AEKey k) {
                return byOutput.get(k);
            }
        });

        PatternCompiler.compileIfAbsent(planksToTable);
        CraftingBytecode request = PatternCompiler.compileRequest(planksToTable, 100L);

        ICraftingPlan first = vm.execute(request, new ChildCraftingSimulationState(sim));
        System.out.println("[REPRO] round1 missing=" + dump(first.missingItems())
                + " used=" + dump(first.usedItems()) + " times=" + timesText(first.patternTimes()));
        assertEquals(100L, amount(first.missingItems(), LOG, 0), "第一轮应缺 100 原木");
        assertEquals(0L, amount(first.missingItems(), PLANKS, 0),
                "木板是本次计划自己造的中间品，不该进 missing");
        assertEquals(100L, craft(first.patternTimes(), PLANKS), "木板样板应派工 100 次(=400 木板)");

        stock.put(LOG + ":0", 100L);                        // 第 2 轮：补 100 原木
        ICraftingPlan second = vm.execute(request, new ChildCraftingSimulationState(sim));
        System.out.println("[REPRO] round2 missing=" + dump(second.missingItems())
                + " used=" + dump(second.usedItems()) + " times=" + timesText(second.patternTimes()));
        assertTrue(second.missingItems().isEmpty(),
                "补齐原料后不该再报任何缺料（旧账被缓存带过来了）: " + dump(second.missingItems()));
        assertEquals(100L, craft(second.patternTimes(), PLANKS), "第二轮木板样板仍派工 100 次");
        assertEquals(100L, amount(second.usedItems(), LOG, 0), "第二轮该吃网络里的 100 原木");
    }

    /**
     * 判据实验：把木板的样板撤掉（纯叶子），网络里留 1 个木板，下单 100 工作台。
     * 正确的需求是 400 → 应报缺 <b>399</b> 木板。若报 99，说明"工作台吃几个木板"
     * 在 used/missing 账上被读成了 1/次（而 {@code patternTimes} 那边读的是 4/次）。
     */
    @Test
    public void leafDemandForHundredTablesIsFourHundred() {
        IPatternDetails planksToTable = pattern("planks->table",
                new IAEItemStack[]{stack(TABLE, 0, 1)},
                new IAEItemStack[]{stack(PLANKS, 0, 4)},
                new IAEItemStack[]{stack(PLANKS, 0, 1), stack(PLANKS, 0, 1),
                        stack(PLANKS, 0, 1), stack(PLANKS, 0, 1)}, false);
        final Map<AEKey, IPatternDetails> byOutput = new LinkedHashMap<AEKey, IPatternDetails>();
        byOutput.put(keyOf(planksToTable), planksToTable);          // 只有工作台样板

        stock.put(PLANKS + ":0", 1L);
        CraftingVM vm = new CraftingVM(TestAeStacks.benchGrid(stock), new java.util.function.Function<AEKey, IPatternDetails>() {
            @Override
            public IPatternDetails apply(AEKey k) {
                return byOutput.get(k);
            }
        });
        PatternCompiler.compileIfAbsent(planksToTable);
        ICraftingPlan plan = vm.execute(PatternCompiler.compileRequest(planksToTable, 100L),
                new ChildCraftingSimulationState(sim));
        System.out.println("[LEAF] missing=" + dump(plan.missingItems()) + " used=" + dump(plan.usedItems())
                + " emitted=" + dump(plan.emittedItems()) + " times=" + timesText(plan.patternTimes()));
        assertEquals(399L, amount(plan.missingItems(), PLANKS, 0),
                "100 工作台该要 400 木板、库存 1 → 缺 399（实测值见 [LEAF] 行）");
    }

    // ------------------------------------------------------------------
    // 读数
    // ------------------------------------------------------------------

    private static String dump(MixedStackList list) {
        StringBuilder sb = new StringBuilder("{");
        for (IAEStack s : list) {
            if (sb.length() > 1) {
                sb.append(", ");
            }
            sb.append(s.getStackSize()).append('x').append(TestAeStacks.idOf(s));
        }
        return sb.append('}').toString();
    }

    private static long amount(MixedStackList list, String id, int damage) {
        for (IAEStack s : list) {
            if (id.equals(TestAeStacks.idOf(s)) && damage == TestAeStacks.damageOf(s)) {
                return s.getStackSize();
            }
        }
        return 0L;
    }

    private static String timesText(Map<IPatternDetails, Long> times) {
        StringBuilder sb = new StringBuilder("{");
        for (Map.Entry<IPatternDetails, Long> e : times.entrySet()) {
            if (sb.length() > 1) {
                sb.append(", ");
            }
            IAEStack[] outs = e.getKey() == null ? null : e.getKey().getOutputs();
            sb.append(outs == null || outs.length == 0 ? "?" : TestAeStacks.idOf(outs[0]))
              .append('=').append(e.getValue());
        }
        return sb.append('}').toString();
    }

    private static long craft(Map<IPatternDetails, Long> times, String outId) {
        long total = 0L;
        for (Map.Entry<IPatternDetails, Long> e : times.entrySet()) {
            IAEStack[] outs = e.getKey() == null ? null : e.getKey().getOutputs();
            if (outs != null && outs.length > 0 && outs[0] != null
                    && outId.equals(TestAeStacks.idOf(outs[0]))) {
                total += e.getValue().longValue();
            }
        }
        return total;
    }

    private static AEKey keyOf(IPatternDetails p) {
        return AEItemKey.wrap((IAEItemStack) p.getOutputs()[0]);
    }

    // ------------------------------------------------------------------
    // 样板：Proxy 造 uel 的数组版 ICraftingPatternDetails，再套真实适配器
    // ------------------------------------------------------------------

    private static IPatternDetails pattern(final String tag, final IAEItemStack[] condensedOut,
            final IAEItemStack[] condensedIn, final IAEItemStack[] sparseIn, final boolean substitute) {
        InvocationHandler h = new InvocationHandler() {
            @Override
            public Object invoke(Object proxy, Method m, Object[] args) {
                String n = m.getName();
                if ("getCondensedInputs".equals(n)) {
                    return condensedIn;
                }
                if ("getCondensedOutputs".equals(n)) {
                    return condensedOut;
                }
                if ("getInputs".equals(n)) {
                    return sparseIn;
                }
                if ("getOutputs".equals(n)) {
                    return condensedOut;
                }
                if ("isCraftable".equals(n)) {
                    return Boolean.TRUE;
                }
                if ("canSubstitute".equals(n)) {
                    return Boolean.valueOf(substitute);
                }
                if ("getSubstituteInputs".equals(n)) {
                    return Collections.emptyList();
                }
                if ("getPrimaryOutput".equals(n)) {
                    return condensedOut[0];
                }
                if ("getPattern".equals(n)) {
                    return null;
                }
                if ("hashCode".equals(n)) {
                    return Integer.valueOf(System.identityHashCode(proxy));
                }
                if ("equals".equals(n)) {
                    return Boolean.valueOf(proxy == args[0]);
                }
                if ("toString".equals(n)) {
                    return "FakePattern(" + tag + ")";
                }
                return TestAeStacks.defaultValue(m.getReturnType());
            }
        };
        ICraftingPatternDetails raw = (ICraftingPatternDetails) Proxy.newProxyInstance(
                ICraftingPatternDetails.class.getClassLoader(),
                new Class<?>[]{ICraftingPatternDetails.class}, h);
        return new V8PatternDetails(raw);
    }

    /**
     * 内存版网络库存。语义照抄 {@code RealtimeNetworkCraftingSimulationState}：
     * {@code extractItems} 只报"能取多少"、**不扣减**（生产实现就是恒定快照，
     * 自产中间品由 VM 的 {@code simInternal} 记账），注入不入账。
     */
    private static final class BenchStock extends CraftingSimulationState {
        private final Map<String, Long> amounts;

        BenchStock(Map<String, Long> amounts) {
            this.amounts = amounts;
        }

        @Override
        protected IAEStack simulateExtractParent(IAEStack input) {
            return simulateExtractParent(input, Actionable.SIMULATE);
        }

        @Override
        protected IAEStack simulateExtractParent(IAEStack input, Actionable mode) {
            String id = TestAeStacks.identity(input);
            Long have = id == null ? null : amounts.get(id);
            if (have == null || have.longValue() <= 0L) {
                return null;
            }
            long take = Math.min(input.getStackSize(), have.longValue());
            if (take <= 0L) {
                return null;
            }
            if (mode == Actionable.MODULATE) {
                amounts.put(id, Long.valueOf(have.longValue() - take));   // 与生产同语义：真取走就扣减
            }
            IAEStack got = input.copy();
            got.setStackSize(take);
            return got;
        }

        @Override
        protected java.util.Collection<IAEStack> findFuzzyParent(IAEStack input) {
            String id = TestAeStacks.identity(input);
            if (id == null) {
                return Collections.emptyList();
            }
            String item = id.substring(0, id.indexOf(':'));
            java.util.Collection<IAEStack> out = new java.util.ArrayList<IAEStack>();
            for (Map.Entry<String, Long> e : amounts.entrySet()) {
                if (e.getKey().startsWith(item + ":") && e.getValue().longValue() > 0L) {
                    IAEStack c = input.copy();
                    c.setStackSize(e.getValue().longValue());
                    out.add(c);
                }
            }
            return out;
        }

        @Override
        public void injectItems(IAEStack input, Actionable mode) {
            // 与生产实现一致：注入不入账
        }
    }
}
