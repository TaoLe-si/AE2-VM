package com.ae2vm.addon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.Collections;

import org.junit.jupiter.api.Test;

import appeng.api.networking.crafting.ICraftingPatternDetails;
import appeng.api.storage.data.IAEItemStack;

import com.ae2vm.shim.api.crafting.IPatternDetails;
import com.ae2vm.addon.v8.V8PatternDetails;

/**
 * 1.16.1：把 **AE2 v8 → shim v9 桥接层**的两条契约钉成单元测试。
 *
 * <p>契约 A（§22 量纲）：内核 {@code PatternCompiler} 按
 * {@code totalPerCraft = multiplier × max(1, possibleInputs[0].amount())} 计算，
 * 所以数量只能放在 {@code multiplier} 一处，候选项的 amount 必须是 1。
 * 曾把同一个聚合数量塞进两处 → 工作台算成 4×4=16。
 *
 * <p>契约 B（候选枚举）——**本版本的例外**：AE2 8.0.0 的 {@code ICraftingPatternDetails}
 * 上没有 {@code getSubstituteInputs(int)}（8.2.0 才加），且
 * {@code appeng.helpers.CraftingPatternDetails} 的字段只有
 * {@code inputs/outputs/sparseInputs/sparseOutputs/canSubstitute}、不含任何
 * {@code net.minecraft.item.crafting.Ingredient}（javap 实测）——
 * 即这一版的样板根本没有"逐槽多选输入"可枚举。所以桥接层的候选恒为
 * {@code [template]} 一个，{@code canSubstitute()} 只是个标记位；模糊维度由下游
 * {@code KeyCounter.findFuzzy(IGNORE_ALL)} 负责，与 AE2 自家
 * {@code CraftingTreeNode.request()} 在 canSubstitute 分支的做法一致。
 * 这里把该例外钉死，免得有人照 1.16.4/1.16.5 的样子加候选枚举：那要么编译不过，
 * 要么让"候选数 > 1"的下游分支在本版本上永不触发而无人察觉。
 *
 * <p>刻意不碰 MC/AE2 运行时（不 {@code new ItemStack(Blocks.X)}、不 {@code AEItemStack.fromItemStack}
 * —— 那需要 {@code GameData} 引导，见 MIGRATION-PATTERNS §5），改用 {@link Proxy} 造
 * {@code IAEItemStack}/{@code ICraftingPatternDetails} 的假象，只驱动桥接层自己的代码。
 * 假栈的 equals/hashCode 按"物品+damage、不看数量"实现 —— 与 AE2
 * {@code AEItemStack.isSameType → AESharedItemStack.equals}（只比 item/damage/NBT）一致。
 */
public class V8BridgeQuantityTest {

    /** §22 的公式，抄自 PatternCompiler：per-craft = multiplier × max(1, 候选[0].amount)。 */
    private static long perCraft(IPatternDetails.IInput in) {
        long mult = in.getMultiplier();
        appeng.api.storage.data.IAEStack[] cand = in.getPossibleInputs();
        assertNotNull(cand, "候选输入不能为 null");
        assertTrue(cand.length >= 1, "至少要有一个候选");
        long amt = cand[0].getStackSize();
        return mult * Math.max(1L, amt);
    }

    /** 候选只是变体标识：每一条的 amount 都必须归一成 1（否则 §22 会重复乘）。 */
    private static void assertCandidatesAreIdentifiersOnly(IPatternDetails.IInput in) {
        for (appeng.api.storage.data.IAEStack c : in.getPossibleInputs()) {
            assertEquals(1L, c.getStackSize(), "候选输入只是变体标识，amount 必须归一成 1");
        }
    }

    @Test
    public void plankPatternKeepsQuantityInMultiplierOnly() {
        // 1 原木 → 4 木板
        ICraftingPatternDetails delegate = exactPattern("appliedenergistics2:planks",
                new long[]{1L},                    // 聚合输入: log ×1
                new long[]{4L});                   // 聚合输出: planks ×4
        V8PatternDetails p = new V8PatternDetails(delegate);

        assertEquals(1, p.getInputs().length, "聚合输入只有一条");
        assertEquals(1L, p.getInputs()[0].getMultiplier(), "单次消耗量必须由 multiplier 承载");
        assertEquals(1L, p.getInputs()[0].getPossibleInputs()[0].getStackSize(),
                "候选输入只是变体标识，amount 必须归一成 1");
        assertEquals(1L, perCraft(p.getInputs()[0]), "per-craft 必须等于 1，不是 1×1 以外的值");
        assertEquals(4L, p.getOutputs()[0].getStackSize(), "单次产量必须是 4");
    }

    @Test
    public void craftingTablePatternAsksFourPlanksNotSixteen() {
        // 4 木板 → 1 工作台：§22 那个 16-vs-4 缺陷的最小复现形状
        ICraftingPatternDetails delegate = exactPattern("minecraft:crafting_table",
                new long[]{4L},
                new long[]{1L});
        V8PatternDetails p = new V8PatternDetails(delegate);

        assertEquals(4L, p.getInputs()[0].getMultiplier(), "工作台每次吃 4 个木板");
        assertEquals(1L, p.getInputs()[0].getPossibleInputs()[0].getStackSize(),
                "候选数量归一，否则内核会算成 4×4=16");
        assertEquals(4L, perCraft(p.getInputs()[0]), "per-craft=4（曾经错成 16 的那个用例）");
    }

    @Test
    public void condensedSumIsNotDoubleCounted() {
        // 3 木板 + 2 原木 → 1 东西：两条聚合输入各自独立承载自己的数量
        ICraftingPatternDetails delegate = exactPattern("ae2vm:test_multi",
                new long[]{3L, 2L},
                new long[]{1L});
        V8PatternDetails p = new V8PatternDetails(delegate);

        assertEquals(2, p.getInputs().length);
        assertEquals(3L, perCraft(p.getInputs()[0]));
        assertEquals(2L, perCraft(p.getInputs()[1]));
        long total = 0;
        for (IPatternDetails.IInput in : p.getInputs()) {
            total += perCraft(in);
        }
        assertEquals(5L, total, "整张样板的单次总消耗 = 各输入之和，不能被重复乘");
    }

    // ------------------------------------------------------------------
    // 契约 B：本版本没有逐槽候选可枚举 → 候选恒为 1
    // ------------------------------------------------------------------

    /**
     * 工作台样板：逐槽 4 个 {@code planks:0}，聚合成 1 条 ×4。
     * 即使 {@code canSubstitute()} 为真，AE2 8.0.0 也没给出可枚举的替代项 ——
     * 候选必须仍然只有主变体一个，且数量契约不受影响。
     */
    @Test
    public void substituteFlagAloneDoesNotAddCandidates() {
        IAEItemStack[] sparse = {
                stack("planks", 0, 1), stack("planks", 0, 1),
                stack("planks", 0, 1), stack("planks", 0, 1),
                null, null, null, null, null};                 // 3x3 网格的空槽
        IAEItemStack[] condensed = {stack("planks", 0, 4)};

        V8PatternDetails p = new V8PatternDetails(
                fakePattern("minecraft:crafting_table", sparse, condensed, true));
        IPatternDetails.IInput in = p.getInputs()[0];

        assertEquals(1, p.getInputs().length, "聚合后只剩一条");
        assertEquals(4L, in.getMultiplier(), "单次消耗量仍由 multiplier 承载");
        assertEquals(1, in.getPossibleInputs().length, "8.1.0 没有替代项可枚举，候选恒为 1");
        assertCandidatesAreIdentifiersOnly(in);
        assertEquals(4L, perCraft(in), "候选只有一个也不能把 per-craft 算小");
        assertEquals("planks", idOf(in.getPossibleInputs()[0]), "候选[0] 必须是该槽自己的主变体");
        assertTrue(in.isValid(stack("planks", 0, 1), null), "主变体必须能顶替自己");
        // 模糊维度不在桥接层：其它 meta 由下游 KeyCounter.findFuzzy(IGNORE_ALL) 收
        assertFalse(in.isValid(stack("planks", 3, 1), null), "桥接层不做模糊匹配，spruce 木板不该在这里通过");
    }

    /** 替代没开（substitute 是玩家勾选项）→ 候选同样只有主变体，别的 meta 不能顶替。 */
    @Test
    public void exactPatternKeepsSingleCandidate() {
        IAEItemStack[] sparse = {stack("planks", 0, 1), stack("planks", 0, 1),
                stack("planks", 0, 1), stack("planks", 0, 1)};
        IAEItemStack[] condensed = {stack("planks", 0, 4)};

        V8PatternDetails p = new V8PatternDetails(
                fakePattern("minecraft:crafting_table", sparse, condensed, false));
        IPatternDetails.IInput in = p.getInputs()[0];

        assertEquals(1, in.getPossibleInputs().length, "没开替代时不得凭空接受变体");
        assertEquals(4L, perCraft(in));
        assertFalse(in.isValid(stack("planks", 3, 1), null), "精确槽不能吃别的 meta");
    }

    /**
     * 聚合表与逐槽表**不同序**的混合样板：每条聚合输入各自保留自己的数量，
     * 候选各归各的物品（本版本每条只有一个候选，就是它自己的主变体）。
     */
    @Test
    public void eachCondensedInputKeepsItsOwnQuantityAndIdentity() {
        IAEItemStack wool = stack("wool", 0, 1);        // sparse[0]：白色羊毛
        IAEItemStack plankA = stack("planks", 0, 1);    // sparse[1]、sparse[2]：同类合并
        IAEItemStack[] sparse = {wool, plankA, plankA};
        // 聚合表故意反序：木板在前、羊毛在后
        IAEItemStack[] condensed = {stack("planks", 0, 2), stack("wool", 0, 1)};

        V8PatternDetails p = new V8PatternDetails(
                fakePattern("ae2vm:test_mixed", sparse, condensed, true));

        IPatternDetails.IInput planks = p.getInputs()[0];
        IPatternDetails.IInput wools = p.getInputs()[1];
        assertEquals(2L, planks.getMultiplier(), "木板那条要吃 2 个");
        assertEquals(1L, wools.getMultiplier(), "羊毛那条要吃 1 个");
        assertEquals(1, planks.getPossibleInputs().length);
        assertEquals(1, wools.getPossibleInputs().length);
        assertEquals("planks", idOf(planks.getPossibleInputs()[0]), "木板槽串进了非木板候选");
        assertEquals("wool", idOf(wools.getPossibleInputs()[0]), "羊毛槽串进了非羊毛候选");
        assertCandidatesAreIdentifiersOnly(planks);
        assertCandidatesAreIdentifiersOnly(wools);
        assertEquals(2L, perCraft(planks), "候选数量不能污染 per-craft");
        assertEquals(1L, perCraft(wools));
    }

    /**
     * 归一候选 amount 时**绝不能改 template** —— 它还是 {@code getMultiplier()} 的数据源。
     * 反复取候选必须幂等，否则每取一次数量就掉一档。
     */
    @Test
    public void normalizingCandidatesDoesNotMutateTheTemplate() {
        ICraftingPatternDetails delegate = exactPattern("ae2vm:test_copy",
                new long[]{4L}, new long[]{1L});
        V8PatternDetails p = new V8PatternDetails(delegate);
        IPatternDetails.IInput in = p.getInputs()[0];

        for (int i = 0; i < 3; i++) {
            assertEquals(1L, in.getPossibleInputs()[0].getStackSize(), "第 " + i + " 次取候选");
            assertEquals(4L, in.getMultiplier(), "第 " + i + " 次归一后 multiplier 被污染了");
            assertEquals(4L, perCraft(in), "第 " + i + " 次 per-craft 漂移");
        }
    }

    // ------------------------------------------------------------------
    // 假对象：只用 Proxy，不碰 MC/AE2 的构造与引导
    // ------------------------------------------------------------------

    /** 前三条用例的形状：逐槽表与聚合表同一批对象、关闭替代。 */
    private static ICraftingPatternDetails exactPattern(String name, long[] inSizes, long[] outSizes) {
        IAEItemStack[] ins = new IAEItemStack[inSizes.length];
        for (int i = 0; i < inSizes.length; i++) {
            ins[i] = stack(name + "-in" + i, 0, inSizes[i]);
        }
        IAEItemStack[] outs = new IAEItemStack[outSizes.length];
        for (int i = 0; i < outSizes.length; i++) {
            outs[i] = stack(name + "-out" + i, 0, outSizes[i]);
        }
        return fakePattern(name, ins, ins, false, outs);
    }

    private static ICraftingPatternDetails fakePattern(String name, IAEItemStack[] sparse,
            IAEItemStack[] condensed, boolean canSubstitute) {
        return fakePattern(name, sparse, condensed, canSubstitute,
                new IAEItemStack[]{stack(name + "-out", 0, 1)});
    }

    private static ICraftingPatternDetails fakePattern(final String name, final IAEItemStack[] sparse,
            final IAEItemStack[] condensed, final boolean canSubstitute, final IAEItemStack[] outs) {
        InvocationHandler h = new InvocationHandler() {
            @Override
            public Object invoke(Object proxy, Method m, Object[] args) {
                switch (m.getName()) {
                    // v8：getInputs()/getOutputs() 是**聚合**表（List），
                    // getSparseInputs()/getSparseOutputs() 才是逐槽数组。
                    case "getInputs":
                        return Arrays.asList(condensed);
                    case "getOutputs":
                        return Arrays.asList(outs);
                    case "getSparseInputs":
                        return sparse;
                    case "getSparseOutputs":
                        return outs;
                    case "isCraftable":
                        return Boolean.TRUE;
                    case "canSubstitute":
                        return Boolean.valueOf(canSubstitute);
                    case "getPattern":
                        return null;
                    case "hashCode":
                        return System.identityHashCode(proxy);
                    case "equals":
                        return proxy == args[0];
                    case "toString":
                        return "FakePattern";
                    default:
                        return defaultValue(m.getReturnType());
                }
            }
        };
        return (ICraftingPatternDetails) Proxy.newProxyInstance(
                ICraftingPatternDetails.class.getClassLoader(),
                new Class<?>[]{ICraftingPatternDetails.class}, h);
    }

    /**
     * 可 copy / setStackSize 的最小 IAEItemStack 假象（{@code V8Input.getPossibleInputs} 会用到），
     * identity = 物品 + damage、不看数量（对齐 AE2 {@code AEItemStack.isSameType}）。
     */
    private static IAEItemStack stack(final String id, final int damage, final long size) {
        final Object[] holder = new Object[]{Long.valueOf(size)};
        InvocationHandler h = new InvocationHandler() {
            @Override
            public Object invoke(Object proxy, Method m, Object[] args) {
                String n = m.getName();
                if ("copy".equals(n)) {
                    return stack(id, damage, ((Long) holder[0]).longValue());
                }
                if ("setStackSize".equals(n)) {
                    holder[0] = args[0];
                    return proxy;
                }
                if ("getStackSize".equals(n)) {
                    return holder[0];
                }
                if ("isMeaningful".equals(n)) {
                    return Boolean.valueOf(((Long) holder[0]).longValue() > 0L);
                }
                if ("getItemDamage".equals(n)) {
                    return Integer.valueOf(damage);
                }
                if ("hashCode".equals(n)) {
                    return Integer.valueOf(keyOf(proxy).hashCode());
                }
                if ("equals".equals(n)) {
                    return Boolean.valueOf(args[0] != null && keyOf(proxy).equals(keyOf(args[0])));
                }
                if ("toString".equals(n)) {
                    return holder[0] + "x" + id + ":" + damage;
                }
                return defaultValue(m.getReturnType());
            }
        };
        IAEItemStack s = (IAEItemStack) Proxy.newProxyInstance(
                IAEItemStack.class.getClassLoader(), new Class<?>[]{IAEItemStack.class}, h);
        IDS.put(s, id + ":" + damage);
        return s;
    }

    /** 假栈的 identity 表（Proxy 本身没法带字段，只能在测试侧记账；按引用记账，不碰 hashCode）。 */
    private static final java.util.Map<Object, String> IDS =
            Collections.synchronizedMap(new java.util.IdentityHashMap<Object, String>());

    private static String keyOf(Object stackOrNull) {
        return stackOrNull == null ? null : IDS.get(stackOrNull);
    }

    private static String idOf(appeng.api.storage.data.IAEStack s) {
        String k = keyOf(s);
        return k == null ? "?" : k.substring(0, k.indexOf(':'));
    }

    private static Object defaultValue(Class<?> t) {
        if (!t.isPrimitive()) {
            return null;
        }
        if (t == boolean.class) {
            return Boolean.FALSE;
        }
        if (t == long.class) {
            return Long.valueOf(0L);
        }
        if (t == int.class) {
            return Integer.valueOf(0);
        }
        return null;
    }
}
