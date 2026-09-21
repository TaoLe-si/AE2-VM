package com.ae2vm.addon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

import appeng.api.networking.crafting.ICraftingPatternDetails;
import appeng.api.storage.data.IAEItemStack;

import com.ae2vm.shim.api.crafting.IPatternDetails;
import com.ae2vm.addon.v8.V8PatternDetails;

/**
 * 1.16.5：把 **AE2 v8 → shim v9 桥接层**的两条契约钉成单元测试。
 *
 * <p>契约 A（§22 量纲）：内核 {@code PatternCompiler} 按
 * {@code totalPerCraft = multiplier × max(1, possibleInputs[0].amount())} 计算，
 * 所以数量只能放在 {@code multiplier} 一处，候选项的 amount 必须是 1。
 * 曾把同一个聚合数量塞进两处 → 工作台算成 4×4=16。
 *
 * <p>契约 B（多选候选）：字典/替代样板靠 {@code canSubstitute() + getSubstituteInputs(槽位)}
 * 枚举"任一木板"这类候选。桥接层必须把它们如实交给内核，否则
 * {@code PatternCompiler.getFuzzyGroup} 退化成单键，网络里的其它 meta 变体一律算成缺料。
 *
 * <p>契约 B 还钉住**槽位下标语义**：v8 的 {@code getSubstituteInputs(int)} 下标是**逐槽**
 * {@code getSparseInputs()} 的，而桥接层拿数量用的是**聚合**表 {@code getInputs()} ——
 * 合并过的样板两者不同序，拿聚合下标去问 sparse 就会拿到别的槽的候选。
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
    // 契约 B：字典替代（1.12.2 的 meta 变体）
    // ------------------------------------------------------------------

    /**
     * 工作台样板：逐槽 4 个 {@code planks:0}（4 个槽都是同一个字典项），
     * 聚合成 1 条 ×4。开了替代后，候选必须是全部 6 个变体，且 per-craft 仍然是 4。
     */
    @Test
    public void substitutePatternExposesAllMetaVariants() {
        IAEItemStack[] sparse = {
                stack("planks", 0, 1), stack("planks", 0, 1),
                stack("planks", 0, 1), stack("planks", 0, 1),
                null, null, null, null, null};                 // 3x3 网格的空槽
        IAEItemStack[] condensed = {stack("planks", 0, 4)};
        // AE2: getSubstituteInputs(i) = [sparseInputs[i]] + 该槽 Ingredient 的全部匹配项
        List<IAEItemStack>[] subs = substituteTable(sparse, 4, "planks", 6);

        V8PatternDetails p = new V8PatternDetails(
                fakePattern("minecraft:crafting_table", sparse, condensed, subs, true, false));
        IPatternDetails.IInput in = p.getInputs()[0];

        assertEquals(1, p.getInputs().length, "聚合后只剩一条");
        assertEquals(4L, in.getMultiplier(), "单次消耗量仍由 multiplier 承载");
        assertEquals(6, in.getPossibleInputs().length, "6 个木板 meta 都要成为候选");
        assertCandidatesAreIdentifiersOnly(in);
        assertEquals(4L, perCraft(in), "候选变多不能把 per-craft 乘大");
        assertEquals("planks", idOf(in.getPossibleInputs()[0]),
                "候选[0] 必须还是该槽自己的主变体");
        // 内核 getFuzzyGroup 依赖 length>1 才会把变体当库存
        assertTrue(in.isValid(stack("planks", 3, 1), null), "替代开启时 spruce 木板必须能顶替");
    }

    /** 替代没开（rv6 的 substitute 是玩家勾选项）→ 候选仍只有主变体，别的 meta 不能顶替。 */
    @Test
    public void exactPatternKeepsSingleCandidate() {
        IAEItemStack[] sparse = {stack("planks", 0, 1), stack("planks", 0, 1),
                stack("planks", 0, 1), stack("planks", 0, 1)};
        IAEItemStack[] condensed = {stack("planks", 0, 4)};
        List<IAEItemStack>[] subs = substituteTable(sparse, 4, "planks", 6);

        V8PatternDetails p = new V8PatternDetails(
                fakePattern("minecraft:crafting_table", sparse, condensed, subs, false, false));
        IPatternDetails.IInput in = p.getInputs()[0];

        assertEquals(1, in.getPossibleInputs().length, "没开替代时不得凭空接受变体");
        assertEquals(4L, perCraft(in));
        assertFalse(in.isValid(stack("planks", 3, 1), null), "精确槽不能吃别的 meta");
    }

    /**
     * 聚合表与逐槽表**不同序**时，候选仍然要各归各的槽。
     * 这条钉住 sparse 下标的反查：拿聚合下标直接喂 getSubstituteInputs 会串槽。
     */
    @Test
    public void candidatesFollowTheSparseSlotNotTheCondensedPosition() {
        IAEItemStack wool = stack("wool", 0, 1);        // sparse[0]：白色羊毛
        IAEItemStack plankA = stack("planks", 0, 1);    // sparse[1]、sparse[2]：同类合并
        IAEItemStack[] sparse = {wool, plankA, plankA};
        // 聚合表故意反序：木板在前、羊毛在后
        IAEItemStack[] condensed = {stack("planks", 0, 2), stack("wool", 0, 1)};
        List<IAEItemStack>[] subs = new List[]{
                variants("wool", 4),                    // 槽 0：羊毛的 4 个变体
                variants("planks", 6),                  // 槽 1：木板的 6 个变体
                variants("planks", 6)};                 // 槽 2：同上

        V8PatternDetails p = new V8PatternDetails(
                fakePattern("ae2vm:test_mixed", sparse, condensed, subs, true, false));

        IPatternDetails.IInput planks = p.getInputs()[0];
        IPatternDetails.IInput wools = p.getInputs()[1];
        assertEquals(2L, planks.getMultiplier());
        assertEquals(1L, wools.getMultiplier());
        assertEquals(6, planks.getPossibleInputs().length, "木板槽拿到 6 个木板变体");
        assertEquals(4, wools.getPossibleInputs().length, "羊毛槽拿到 4 个羊毛变体");
        for (appeng.api.storage.data.IAEStack c : planks.getPossibleInputs()) {
            assertEquals("planks", idOf(c), "木板槽串进了非木板候选");
        }
        for (appeng.api.storage.data.IAEStack c : wools.getPossibleInputs()) {
            assertEquals("wool", idOf(c), "羊毛槽串进了非羊毛候选");
        }
        assertCandidatesAreIdentifiersOnly(planks);
        assertCandidatesAreIdentifiersOnly(wools);
        assertEquals(2L, perCraft(planks), "候选数量不能污染 per-craft");
        assertEquals(1L, perCraft(wools));
    }

    /** 第三方样板实现可能在旧接口上编译（AbstractMethodError）—— 桥接层不能因此崩。 */
    @Test
    public void throwingSubstituteLookupFallsBackToPrimary() {
        IAEItemStack[] sparse = {stack("planks", 0, 1)};
        IAEItemStack[] condensed = {stack("planks", 0, 1)};
        V8PatternDetails p = new V8PatternDetails(
                fakePattern("ae2vm:test_boom", sparse, condensed, new List[0], true, true));
        IPatternDetails.IInput in = p.getInputs()[0];

        assertEquals(1, in.getPossibleInputs().length, "候选枚举失败时退回主变体，不能抛出去");
        assertEquals(1L, perCraft(in));
    }

    // ------------------------------------------------------------------
    // 假对象：只用 Proxy，不碰 MC/AE2 的构造与引导
    // ------------------------------------------------------------------

    /** 老三条用例的形状：逐槽表与聚合表同一批对象、关闭替代。 */
    private static ICraftingPatternDetails exactPattern(String name, long[] inSizes, long[] outSizes) {
        IAEItemStack[] ins = new IAEItemStack[inSizes.length];
        for (int i = 0; i < inSizes.length; i++) {
            ins[i] = stack(name + "-in" + i, 0, inSizes[i]);
        }
        IAEItemStack[] outs = new IAEItemStack[outSizes.length];
        for (int i = 0; i < outSizes.length; i++) {
            outs[i] = stack(name + "-out" + i, 0, outSizes[i]);
        }
        return fakePattern(name, ins, ins, new List[ins.length], false, false, outs);
    }

    /** {@code sparse[0..slotCount)} 每个槽的候选 = 该槽自身 + 同一物品的 variants 个变体。 */
    private static List<IAEItemStack>[] substituteTable(IAEItemStack[] sparse, int slotCount,
            String id, int variants) {
        List<IAEItemStack>[] all = new List[sparse.length];
        for (int i = 0; i < sparse.length; i++) {
            if (i < slotCount && sparse[i] != null) {
                List<IAEItemStack> l = new ArrayList<IAEItemStack>();
                l.add(sparse[i]);   // AE2 把该槽自身放在第一位
                l.addAll(variants(id, variants));
                all[i] = l;
            }
        }
        return all;
    }

    private static List<IAEItemStack> variants(String id, int variants) {
        List<IAEItemStack> l = new ArrayList<IAEItemStack>();
        for (int d = 0; d < variants; d++) {
            l.add(stack(id, d, 1));
        }
        return l;
    }

    private static ICraftingPatternDetails fakePattern(String name, IAEItemStack[] sparse,
            IAEItemStack[] condensed, List<IAEItemStack>[] subs, boolean canSubstitute, boolean boom) {
        return fakePattern(name, sparse, condensed, subs, canSubstitute, boom,
                new IAEItemStack[]{stack(name + "-out", 0, 1)});
    }

    private static ICraftingPatternDetails fakePattern(final String name, final IAEItemStack[] sparse,
            final IAEItemStack[] condensed, final List<IAEItemStack>[] subs, final boolean canSubstitute,
            final boolean boom, final IAEItemStack[] outs) {
        InvocationHandler h = new InvocationHandler() {
            @Override
            public Object invoke(Object proxy, Method m, Object[] args) {
                switch (m.getName()) {
                    // v8：getInputs()/getOutputs() 是**聚合**表（List），
                    // getSparseInputs()/getSparseOutputs() 才是逐槽数组 ——
                    // getSubstituteInputs(int) 的下标基准是后者。
                    case "getInputs":
                        return java.util.Arrays.asList(condensed);
                    case "getOutputs":
                        return java.util.Arrays.asList(outs);
                    case "getSparseInputs":
                        return sparse;
                    case "getSparseOutputs":
                        return outs;
                    case "isCraftable":
                        return Boolean.TRUE;
                    case "canSubstitute":
                        return Boolean.valueOf(canSubstitute);
                    case "getSubstituteInputs":
                        if (boom) {
                            throw new AbstractMethodError();
                        }
                        int idx = ((Integer) args[0]).intValue();
                        return idx < subs.length && subs[idx] != null
                                ? subs[idx] : Collections.emptyList();
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
