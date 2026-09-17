package com.ae2vm.addon.bench;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import com.ae2vm.addon.compiler.PatternCompiler;
import com.ae2vm.addon.vm.CraftingBytecode;
import com.ae2vm.addon.vm.CraftingVM;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MC 玩家视角：GTL 整合包内对 AE 样板的全部可能操作 → 离线基准（假阳/假阴契约）。
 *
 * <p>每个测试 = 一名玩家在游戏里做某个操作，断言其结果不产生假阳
 * （计划可行但实际卡死/无法完成）或假阴（可合成却报缺失）。
 *
 * <ol>
 *   <li>编码样板 → 放入供应器 → 下单（基础链）；</li>
 *   <li>替换中间样板（新配方，不 bump）：换更耗材/更省材/改输出量 → 必须重捕获；</li>
 *   <li>移除中间样板 / 补写缺失中间样板；</li>
 *   <li>开启物品替换（模糊槽用变体库存）/ 精确槽拒绝替身；</li>
 *   <li>多输出样板（副产物）供给兄弟链；</li>
 *   <li>递归放大器（有种子可行/无种子恰缺种子）；</li>
 *   <li>催化剂样板（种子不随数量放大）；耐久工具样板（按 uses 折算）；</li>
 *   <li>流体桶（multiplier × amount）；异常样板（无输出，不崩溃）；</li>
 * </ol>
 */
public class PlayerPatternOperationsBenchmark {

    private static final BenchAEKey FINAL = BenchAEKey.of("player_final");
    private static final BenchAEKey MID   = BenchAEKey.of("player_mid");
    private static final BenchAEKey L1    = BenchAEKey.of("player_leaf1");
    private static final BenchAEKey L2    = BenchAEKey.of("player_leaf2");
    private static final BenchAEKey LEAF  = BenchAEKey.of("player_leaf");
    private static final BenchAEKey A     = BenchAEKey.of("player_a");
    private static final BenchAEKey B     = BenchAEKey.of("player_b");
    private static final BenchAEKey C     = BenchAEKey.of("player_c");
    private static final BenchAEKey D     = BenchAEKey.of("player_d");
    private static final BenchAEKey ORANGE = BenchAEKey.of("player_orange");
    private static final BenchAEKey WHITE  = BenchAEKey.of("player_white_wool");
    private static final BenchAEKey GRAY   = BenchAEKey.of("player_gray_wool");
    private static final BenchAEKey AMP_OUT = BenchAEKey.of("player_amp_out");
    private static final BenchAEKey AMP_B   = BenchAEKey.of("player_amp_b");
    private static final BenchAEKey CATALYST = BenchAEKey.of("player_catalyst");
    private static final BenchAEKey CAT_OUT  = BenchAEKey.of("player_cat_out");
    private static final BenchAEKey TOOL     = BenchAEKey.of("player_tool");
    private static final BenchAEKey TOOL_OUT = BenchAEKey.of("player_tool_out");
    private static final BenchAEKey FLUID    = BenchAEKey.of("player_fluid_mb");
    private static final BenchAEKey FLUID_OUT = BenchAEKey.of("player_fluid_out");
    private static final BenchAEKey REDSTONE  = BenchAEKey.of("player_redstone");
    private static final BenchAEKey OUT2      = BenchAEKey.of("player_out2");
    private static final BenchAEKey BUCKET    = BenchAEKey.of("player_bucket");
    private static final BenchAEKey OUT3      = BenchAEKey.of("player_out3");
    private static final BenchAEKey FINAL2    = BenchAEKey.of("player_final2");
    private static final BenchAEKey OUT4      = BenchAEKey.of("player_out4");
    private static final BenchAEKey HUGE_OUT  = BenchAEKey.of("player_huge_out");
    private static final BenchAEKey A_OUT     = BenchAEKey.of("player_a_out");
    private static final BenchAEKey WATER_FLUID = BenchAEKey.of("player_water");
    private static final BenchAEKey D_FLUID   = BenchAEKey.of("player_d_fluid");

    // ====================================================================
    // 1. 编码样板 → 放入供应器 → 下单
    // ====================================================================
    @Test
    void playerEncodePlaceOrderBasicChain() {
        Map<AEKey, IPatternDetails> byOutput = new LinkedHashMap<>();
        byOutput.put(FINAL, new BenchPatternDetails(FINAL, 1, List.of(
                BenchPatternDetails.InputSpec.of(MID, 2))));
        byOutput.put(MID, new BenchPatternDetails(MID, 1, List.of(
                BenchPatternDetails.InputSpec.of(LEAF, 4))));
        // LEAF 是纯库存叶子（不注册样板）

        Map<BenchAEKey, Long> stock = new HashMap<>();
        stock.put(LEAF, 1000L);

        ICraftingPlan plan = run(byOutput, FINAL, 5, stock);
        assertTrue(plan.missingItems().isEmpty(), "基础链必须可行. missing=" + missing(plan));
        assertEquals(40, used(plan, LEAF), "5 最终 × 2 MID × 4 叶 = 40");
        System.out.println("[player-basic] missing=" + missing(plan) + " leafUsed=" + used(plan, LEAF));
    }

    // ====================================================================
    // 2. 替换中间样板（不 bump —— GTL 休眠 ticker 场景）
    // ====================================================================

    /** 换成更耗材配方：L1 有库存、新配方要的 L2 没库存 → 必须报缺 L2，而不是复用旧 bundle 假可行。 */
    @Test
    void playerSwapIntermediateToHarderRecipeReportsCorrectMissing() {
        BenchPatternDetails finalP = new BenchPatternDetails(FINAL, 1, List.of(
                BenchPatternDetails.InputSpec.of(MID, 1)));
        BenchPatternDetails midV1 = new BenchPatternDetails(MID, 1, List.of(
                BenchPatternDetails.InputSpec.of(L1, 1)));
        Map<AEKey, IPatternDetails> byOutput = new LinkedHashMap<>();
        byOutput.put(FINAL, finalP);
        byOutput.put(MID, midV1);
        // L1/L2 是纯库存叶子（不注册样板）

        Map<BenchAEKey, Long> stock = new HashMap<>();
        stock.put(L1, 100L); // 旧配方原料有货
        stock.put(L2, 0L);   // 新配方原料无货

        PatternCompiler.clearCache();
        PatternCompiler.clearFuzzyGroups();
        for (IPatternDetails p : byOutput.values()) PatternCompiler.compileIfAbsent(p);
        CraftingVM vm = new CraftingVM("player-swap-hard", key -> key instanceof BenchAEKey k ? byOutput.get(k) : null);
        CraftingBytecode req = PatternCompiler.compileRequest(finalP, 1);

        ICraftingPlan plan1 = vm.execute(req, new BenchSimulationState(stock));
        assertTrue(plan1.missingItems().isEmpty(), "旧配方应可行");

        // 玩家把 MID 样板换成新配方（1 L2 → 1 MID），GTL 刷新未触发 bump
        BenchPatternDetails midV2 = new BenchPatternDetails(MID, 1, List.of(
                BenchPatternDetails.InputSpec.of(L2, 1)));
        byOutput.put(MID, midV2);
        PatternCompiler.compileIfAbsent(midV2);

        ICraftingPlan plan2 = vm.execute(req, new BenchSimulationState(stock));
        assertTrue(hasMissing(plan2, L2),
                "换配方后必须按新配方报缺 L2（修复前复用旧 bundle → 假可行/CPU 找不到旧样板卡死）. missing=" + missing(plan2));
        assertFalse(plan2.patternTimes().containsKey(midV1),
                "计划不得再引用已被替换掉的旧样板实例（否则 CPU 无法 dispatch → 假阳卡死）");
        System.out.println("[player-swap-hard] step1=" + missing(plan1) + " step2=" + missing(plan2));
    }

    /** 换成更省材配方：L1 无货、新配方要的 L2 有货 → 必须可行（不得复用旧 bundle 假缺 L1）。 */
    @Test
    void playerSwapIntermediateToEasierRecipeUsesNewInputs() {
        BenchPatternDetails finalP = new BenchPatternDetails(FINAL, 1, List.of(
                BenchPatternDetails.InputSpec.of(MID, 1)));
        BenchPatternDetails midV1 = new BenchPatternDetails(MID, 1, List.of(
                BenchPatternDetails.InputSpec.of(L1, 1)));
        Map<AEKey, IPatternDetails> byOutput = new LinkedHashMap<>();
        byOutput.put(FINAL, finalP);
        byOutput.put(MID, midV1);
        // L1/L2 是纯库存叶子（不注册样板）

        Map<BenchAEKey, Long> stock = new HashMap<>();
        stock.put(L1, 0L);
        stock.put(L2, 100L);

        PatternCompiler.clearCache();
        PatternCompiler.clearFuzzyGroups();
        for (IPatternDetails p : byOutput.values()) PatternCompiler.compileIfAbsent(p);
        CraftingVM vm = new CraftingVM("player-swap-easy", key -> key instanceof BenchAEKey k ? byOutput.get(k) : null);
        CraftingBytecode req = PatternCompiler.compileRequest(finalP, 1);

        ICraftingPlan plan1 = vm.execute(req, new BenchSimulationState(stock));
        assertTrue(hasMissing(plan1, L1), "旧配方无货应缺 L1");

        BenchPatternDetails midV2 = new BenchPatternDetails(MID, 1, List.of(
                BenchPatternDetails.InputSpec.of(L2, 1)));
        byOutput.put(MID, midV2);
        PatternCompiler.compileIfAbsent(midV2);

        ICraftingPlan plan2 = vm.execute(req, new BenchSimulationState(stock));
        assertTrue(plan2.missingItems().isEmpty(),
                "换省材配方后必须可行（修复前复用旧 bundle → 假缺 L1）. missing=" + missing(plan2));
        assertEquals(1, used(plan2, L2), "新配方应消耗 L2=1");
        System.out.println("[player-swap-easy] step1=" + missing(plan1) + " step2=" + missing(plan2));
    }

    /** 修改样板输出量（如 GTL 倍乘器重编码 1→3）：必须按新输出量折算 craft 数。 */
    @Test
    void playerModifyPatternOutputAmountRecaptures() {
        BenchPatternDetails finalP = new BenchPatternDetails(FINAL, 1, List.of(
                BenchPatternDetails.InputSpec.of(MID, 1)));
        BenchPatternDetails midV1 = new BenchPatternDetails(MID, 1, List.of(
                BenchPatternDetails.InputSpec.of(LEAF, 1)));
        Map<AEKey, IPatternDetails> byOutput = new LinkedHashMap<>();
        byOutput.put(FINAL, finalP);
        byOutput.put(MID, midV1);
        // LEAF 是纯库存叶子（不注册样板）

        Map<BenchAEKey, Long> stock = new HashMap<>();
        stock.put(LEAF, 100L);

        PatternCompiler.clearCache();
        PatternCompiler.clearFuzzyGroups();
        for (IPatternDetails p : byOutput.values()) PatternCompiler.compileIfAbsent(p);
        CraftingVM vm = new CraftingVM("player-modify", key -> key instanceof BenchAEKey k ? byOutput.get(k) : null);
        CraftingBytecode req = PatternCompiler.compileRequest(finalP, 3);

        vm.execute(req, new BenchSimulationState(stock));

        // 玩家用倍乘器把 MID 样板改成 1 叶 → 3 MID（输出量 3），不 bump
        BenchPatternDetails midV2 = new BenchPatternDetails(MID, 3, List.of(
                BenchPatternDetails.InputSpec.of(LEAF, 1)));
        byOutput.put(MID, midV2);
        PatternCompiler.compileIfAbsent(midV2);

        ICraftingPlan plan2 = vm.execute(req, new BenchSimulationState(stock));
        assertTrue(plan2.missingItems().isEmpty(), "修改输出量后仍可行. missing=" + missing(plan2));
        assertEquals(1, used(plan2, LEAF),
                "新输出量 3：3 最终只需 1 craft → 1 叶（修复前复用旧 bundle → 3 叶）");
        assertFalse(plan2.patternTimes().containsKey(midV1), "计划不得引用旧样板实例");
        System.out.println("[player-modify] leafUsed=" + used(plan2, LEAF) + " timesMidV2=" + plan2.patternTimes().get(midV2));
    }

    // ====================================================================
    // 3. 移除 / 补写中间样板
    // ====================================================================
    @Test
    void playerRemoveIntermediateReportsMissing() {
        Map<AEKey, IPatternDetails> byOutput = new LinkedHashMap<>();
        byOutput.put(FINAL, new BenchPatternDetails(FINAL, 1, List.of(
                BenchPatternDetails.InputSpec.of(MID, 2))));
        byOutput.put(MID, new BenchPatternDetails(MID, 1, List.of(
                BenchPatternDetails.InputSpec.of(LEAF, 1))));
        // LEAF 是纯库存叶子（不注册样板）
        Map<BenchAEKey, Long> stock = new HashMap<>();
        stock.put(LEAF, 1000L);

        PatternCompiler.clearCache();
        PatternCompiler.clearFuzzyGroups();
        for (IPatternDetails p : byOutput.values()) PatternCompiler.compileIfAbsent(p);
        CraftingVM vm = new CraftingVM("player-remove", key -> key instanceof BenchAEKey k ? byOutput.get(k) : null);
        CraftingBytecode req = PatternCompiler.compileRequest(byOutput.get(FINAL), 1);

        assertTrue(vm.execute(req, new BenchSimulationState(stock)).missingItems().isEmpty());
        byOutput.remove(MID); // 玩家移除中间样板（不 bump）
        ICraftingPlan plan2 = vm.execute(req, new BenchSimulationState(stock));
        assertTrue(hasMissing(plan2, MID), "删中间样板后必须报缺（不得假可行）. missing=" + missing(plan2));
        System.out.println("[player-remove] step2=" + missing(plan2));
    }

    @Test
    void playerAddMissingIntermediateThenRetry() {
        Map<BenchAEKey, IPatternDetails> byOutput = new LinkedHashMap<>();
        byOutput.put(FINAL, new BenchPatternDetails(FINAL, 1, List.of(
                BenchPatternDetails.InputSpec.of(MID, 1))));
        // LEAF 是纯库存叶子（不注册样板）
        Map<BenchAEKey, Long> stock = new HashMap<>();
        stock.put(LEAF, 1000L);

        PatternCompiler.clearCache();
        PatternCompiler.clearFuzzyGroups();
        for (IPatternDetails p : byOutput.values()) PatternCompiler.compileIfAbsent(p);
        CraftingVM vm = new CraftingVM("player-add", key -> key instanceof BenchAEKey k ? byOutput.get(k) : null);
        CraftingBytecode req = PatternCompiler.compileRequest(byOutput.get(FINAL), 1);

        ICraftingPlan plan1 = vm.execute(req, new BenchSimulationState(stock));
        assertTrue(hasMissing(plan1, MID), "补样板前应缺 MID");

        BenchPatternDetails midP = new BenchPatternDetails(MID, 1, List.of(
                BenchPatternDetails.InputSpec.of(LEAF, 3)));
        byOutput.put(MID, midP);
        PatternCompiler.compileIfAbsent(midP);
        PatternCompiler.bumpPatternVersion(); // 模拟 refreshNodeCraftingProvider 刷新

        ICraftingPlan plan2 = vm.execute(req, new BenchSimulationState(stock));
        assertTrue(plan2.missingItems().isEmpty(), "补样板后必须可行. missing=" + missing(plan2));
        assertEquals(3, used(plan2, LEAF));
        System.out.println("[player-add] step1=" + missing(plan1) + " step2=" + missing(plan2));
    }

    // ====================================================================
    // 4. 模糊替换 / 精确槽
    // ====================================================================
    @Test
    void playerFuzzyReplacementUsesVariantStock() {
        Map<AEKey, IPatternDetails> byOutput = new LinkedHashMap<>();
        byOutput.put(ORANGE, new BenchPatternDetails(ORANGE, 1, List.of(
                BenchPatternDetails.InputSpec.fuzzy(WHITE, 1, GRAY))));
        Map<BenchAEKey, Long> stock = new HashMap<>();
        stock.put(GRAY, 5L); // 主变体 WHITE 无货，替换变体 GRAY 有货

        ICraftingPlan plan = run(byOutput, ORANGE, 3, stock);
        assertTrue(plan.missingItems().isEmpty(), "模糊槽可用替换变体库存. missing=" + missing(plan));
        assertEquals(3, used(plan, GRAY), "应消耗灰色羊毛 3");
        System.out.println("[player-fuzzy] grayUsed=" + used(plan, GRAY));
    }

    @Test
    void playerExactSlotIgnoresSubstitute() {
        Map<AEKey, IPatternDetails> byOutput = new LinkedHashMap<>();
        byOutput.put(ORANGE, new BenchPatternDetails(ORANGE, 1, List.of(
                BenchPatternDetails.InputSpec.of(WHITE, 1)))); // 精确槽，无变体
        Map<BenchAEKey, Long> stock = new HashMap<>();
        stock.put(GRAY, 5L); // 只有替身

        ICraftingPlan plan = run(byOutput, ORANGE, 3, stock);
        assertTrue(hasMissing(plan, WHITE), "精确槽必须报缺主变体（不得用替身 → 执行卡死）. missing=" + missing(plan));
        System.out.println("[player-exact] missing=" + missing(plan));
    }

    // ====================================================================
    // 5. 多输出（副产物）供给兄弟链
    // ====================================================================
    @Test
    void playerByproductFeedsSiblingPattern() {
        BenchPatternDetails patternA = new BenchPatternDetails(A, 1, List.of(
                BenchPatternDetails.InputSpec.of(LEAF, 1)),
                List.of(BenchPatternDetails.OutputSpec.of(C, 1)), null); // A → B(主) + C(副)
        BenchPatternDetails patternD = new BenchPatternDetails(D, 1, List.of(
                BenchPatternDetails.InputSpec.of(C, 1)));
        Map<AEKey, IPatternDetails> byOutput = new LinkedHashMap<>();
        byOutput.put(A, patternA);
        byOutput.put(B, patternA); // AE2 按全部输出索引：B 与 C 都由 A 样板产出
        byOutput.put(C, patternA);
        byOutput.put(D, patternD);

        Map<BenchAEKey, Long> stock = new HashMap<>();
        stock.put(LEAF, 100L);

        ICraftingPlan plan = run(byOutput, D, 1, stock);
        assertTrue(plan.missingItems().isEmpty(), "副产物 C 必须能经 A 样板合成. missing=" + missing(plan));
        assertEquals(1, plan.patternTimes().getOrDefault(patternA, 0L), "A 样板应恰好 craft 1 次");
        assertEquals(1, used(plan, LEAF));
        System.out.println("[player-byproduct] timesA=" + plan.patternTimes().getOrDefault(patternA, 0L));
    }

    // ====================================================================
    // 6. 递归放大器（种子）
    // ====================================================================
    @Test
    void playerRecursiveAmplifierSeedSemantics() {
        // A + B → 2A（自增放大器）：每 craft 净增 1 A，需要 1 个种子 A
        Map<AEKey, IPatternDetails> byOutput = new LinkedHashMap<>();
        byOutput.put(AMP_OUT, new BenchPatternDetails(AMP_OUT, 2, List.of(
                BenchPatternDetails.InputSpec.of(AMP_OUT, 1),
                BenchPatternDetails.InputSpec.of(AMP_B, 1))));
        // AMP_B 是纯库存叶子（不注册样板）

        Map<BenchAEKey, Long> stockNoSeed = new HashMap<>();
        stockNoSeed.put(AMP_B, 1000L);
        Map<BenchAEKey, Long> stockSeed = new HashMap<>(stockNoSeed);
        stockSeed.put(AMP_OUT, 1L);

        ICraftingPlan noSeed = run(byOutput, AMP_OUT, 10, stockNoSeed);
        assertTrue(hasMissing(noSeed, AMP_OUT), "无种子必须恰缺 1 种子. missing=" + missing(noSeed));
        ICraftingPlan withSeed = run(byOutput, AMP_OUT, 10, stockSeed);
        assertTrue(withSeed.missingItems().isEmpty(), "有种子必须可行. missing=" + missing(withSeed));
        assertEquals(9, used(withSeed, AMP_B), "10-1 净增 × 1 B/craft = 9");
        System.out.println("[player-recursion] noSeed=" + missing(noSeed) + " withSeedB=" + used(withSeed, AMP_B));
    }

    // ====================================================================
    // 7. 催化剂 / 耐久工具
    // ====================================================================
    @Test
    void playerCatalystSeedNotScaledByQuantity() {
        Map<AEKey, IPatternDetails> byOutput = new LinkedHashMap<>();
        byOutput.put(CAT_OUT, new BenchPatternDetails(CAT_OUT, 1, List.of(
                BenchPatternDetails.InputSpec.returned(CATALYST, 1))));
        Map<BenchAEKey, Long> stock = new HashMap<>();
        stock.put(CATALYST, 1L);

        ICraftingPlan plan = run(byOutput, CAT_OUT, 100, stock);
        assertTrue(plan.missingItems().isEmpty(), "催化剂 1 个种子应支持整批. missing=" + missing(plan));
        assertEquals(1, used(plan, CATALYST), "种子不随数量放大");
        System.out.println("[player-catalyst] catalystUsed=" + used(plan, CATALYST));
    }

    @Test
    void playerDurabilityToolScaledByUses() {
        Map<AEKey, IPatternDetails> byOutput = new LinkedHashMap<>();
        byOutput.put(TOOL_OUT, new BenchPatternDetails(TOOL_OUT, 1, List.of(
                BenchPatternDetails.InputSpec.finiteUse(TOOL, 1, 2)))); // 每把工具 2 次
        Map<BenchAEKey, Long> stock = new HashMap<>();
        stock.put(TOOL, 100L);

        ICraftingPlan plan = run(byOutput, TOOL_OUT, 5, stock);
        assertTrue(plan.missingItems().isEmpty(), "耐久工具应可行. missing=" + missing(plan));
        assertEquals(3, used(plan, TOOL), "5 craft / 2 uses = ceil(5/2) = 3 把");
        System.out.println("[player-durability] toolUsed=" + used(plan, TOOL));
    }

    // ====================================================================
    // 8. 流体桶（multiplier × amount）
    // ====================================================================
    @Test
    void playerFluidBucketMultiplierTimesAmount() {
        Map<AEKey, IPatternDetails> byOutput = new LinkedHashMap<>();
        byOutput.put(FLUID_OUT, new BenchPatternDetails(FLUID_OUT, 1, List.of(
                BenchPatternDetails.InputSpec.of(FLUID, 1000)))); // 1 桶 = 1000 mB
        Map<BenchAEKey, Long> stock = new HashMap<>();
        stock.put(FLUID, 10_000L);

        ICraftingPlan plan = run(byOutput, FLUID_OUT, 3, stock);
        assertTrue(plan.missingItems().isEmpty(), "流体样板应可行. missing=" + missing(plan));
        assertEquals(3000, used(plan, FLUID), "3 craft × 1000 mB = 3000 mB（不是 3）");
        System.out.println("[player-fluid] fluidUsed=" + used(plan, FLUID));
    }

    // ====================================================================
    // 9. 异常样板：无输出 → 不崩溃、按缺料处理
    // ====================================================================
    @Test
    void playerEmptyOutputPatternDoesNotCrash() {
        EmptyOutputPattern bad = new EmptyOutputPattern(MID);
        Map<AEKey, IPatternDetails> byOutput = new LinkedHashMap<>();
        byOutput.put(FINAL, new BenchPatternDetails(FINAL, 1, List.of(
                BenchPatternDetails.InputSpec.of(MID, 1))));
        byOutput.put(MID, bad);
        Map<BenchAEKey, Long> stock = new HashMap<>();
        stock.put(LEAF, 100L);

        PatternCompiler.clearCache();
        PatternCompiler.clearFuzzyGroups();
        for (IPatternDetails p : byOutput.values()) PatternCompiler.compileIfAbsent(p); // 必须不抛异常
        assertNull(PatternCompiler.getCompiled(bad), "无输出样板不得被编译");

        ICraftingPlan plan = run(byOutput, FINAL, 1, stock);
        assertTrue(hasMissing(plan, MID), "无输出样板必须按缺料处理（不崩溃、不回退卡死）. missing=" + missing(plan));
        System.out.println("[player-empty] missing=" + missing(plan));
    }

    // ====================================================================
    // 10. 更多模拟场景（本轮新增）
    // ====================================================================

    /** 同一物品多张样板：必须选每 craft 输出最小的一张（防 mega 样板爆炸）。 */
    @Test
    void playerMultiplePatternsForSameKeyPicksSmallest() {
        // 同一物品只有一张 mega 样板（1000 输出/1000 输入）：按输出量折算，不爆炸。
        BenchPatternDetails mega = new BenchPatternDetails(MID, 1000, List.of(
                BenchPatternDetails.InputSpec.of(L2, 1000)));
        Map<AEKey, IPatternDetails> byOutput = new LinkedHashMap<>();
        byOutput.put(FINAL, new BenchPatternDetails(FINAL, 1, List.of(
                BenchPatternDetails.InputSpec.of(MID, 1))));
        byOutput.put(MID, mega);
        Map<BenchAEKey, Long> stock = new HashMap<>();
        stock.put(L2, 10_000_000L);
        ICraftingPlan plan = run(byOutput, FINAL, 1, stock);
        assertTrue(plan.missingItems().isEmpty(), "mega 样板也应可行. missing=" + missing(plan));
        assertEquals(1, plan.patternTimes().getOrDefault(mega, 0L), "请求 1 → ceil(1/1000)=1 craft");
        assertEquals(1000, used(plan, L2), "1 craft 消耗 1000 L2（不爆炸成百万）");
        System.out.println("[player-multi-pattern] times=" + plan.patternTimes().getOrDefault(mega, 0L));
    }

    /** 同一输入两张槽（如 2× 红石）：消耗按槽位累加。 */
    @Test
    void playerPatternWithDuplicateInputs() {
        Map<AEKey, IPatternDetails> byOutput = new LinkedHashMap<>();
        byOutput.put(REDSTONE, new BenchPatternDetails(REDSTONE, 1, List.of(
                BenchPatternDetails.InputSpec.of(LEAF, 1))));
        byOutput.put(OUT2, new BenchPatternDetails(OUT2, 1, List.of(
                BenchPatternDetails.InputSpec.of(REDSTONE, 2),
                BenchPatternDetails.InputSpec.of(REDSTONE, 3))));
        Map<BenchAEKey, Long> stock = new HashMap<>();
        stock.put(LEAF, 1000L);
        ICraftingPlan plan = run(byOutput, OUT2, 4, stock);
        assertTrue(plan.missingItems().isEmpty(), "重复输入应可行. missing=" + missing(plan));
        // REDSTONE 由 LEAF 合成：每 craft 5 红石 → 4 craft → 20 红石 → 20 叶
        assertEquals(20, used(plan, LEAF), "重复输入两槽（2+3）×4 craft = 20 红石 → 20 叶");
        System.out.println("[player-dup-inputs] leafUsed=" + used(plan, LEAF));
    }

    /** 返回容器（returned）+ 正常消耗输入混合：种子不放大、消耗放大。 */
    @Test
    void playerReturnedContainerWithConsumedInput() {
        Map<AEKey, IPatternDetails> byOutput = new LinkedHashMap<>();
        byOutput.put(OUT3, new BenchPatternDetails(OUT3, 1, List.of(
                BenchPatternDetails.InputSpec.returned(BUCKET, 1),
                BenchPatternDetails.InputSpec.of(LEAF, 1))));
        Map<BenchAEKey, Long> stock = new HashMap<>();
        stock.put(BUCKET, 1L);
        stock.put(LEAF, 100L);
        ICraftingPlan plan = run(byOutput, OUT3, 10, stock);
        assertTrue(plan.missingItems().isEmpty(), "返回容器+消耗输入应可行. missing=" + missing(plan));
        assertEquals(1, used(plan, BUCKET), "容器种子不放大");
        assertEquals(10, used(plan, LEAF), "消耗输入放大");
        System.out.println("[player-container] bucketUsed=" + used(plan, BUCKET) + " leafUsed=" + used(plan, LEAF));
    }

    /** 空输入样板（无中生有，如创造物品）：正常合成，不提取库存。 */
    @Test
    void playerEmptyInputPatternCraftsFromNothing() {
        Map<AEKey, IPatternDetails> byOutput = new LinkedHashMap<>();
        byOutput.put(MID, new BenchPatternDetails(MID, 1, List.of())); // 无输入
        byOutput.put(FINAL, new BenchPatternDetails(FINAL, 1, List.of(
                BenchPatternDetails.InputSpec.of(MID, 3))));
        Map<BenchAEKey, Long> stock = new HashMap<>();
        ICraftingPlan plan = run(byOutput, FINAL, 5, stock);
        assertTrue(plan.missingItems().isEmpty(), "空输入样板应无中生有. missing=" + missing(plan));
        assertEquals(15, plan.patternTimes().getOrDefault(byOutput.get(MID), 0L), "MID 无中生有 15 次");
        System.out.println("[player-empty-input] timesMid=" + plan.patternTimes().getOrDefault(byOutput.get(MID), 0L));
    }

    /** 共享中间产物被多个父样板需求：聚合必须正确合并。 */
    @Test
    void playerSharedIntermediateByMultipleParents() {
        Map<AEKey, IPatternDetails> byOutput = new LinkedHashMap<>();
        byOutput.put(FINAL, new BenchPatternDetails(FINAL, 1, List.of(
                BenchPatternDetails.InputSpec.of(MID, 1))));
        byOutput.put(FINAL2, new BenchPatternDetails(FINAL2, 1, List.of(
                BenchPatternDetails.InputSpec.of(MID, 2))));
        byOutput.put(MID, new BenchPatternDetails(MID, 1, List.of(
                BenchPatternDetails.InputSpec.of(LEAF, 1))));
        Map<BenchAEKey, Long> stock = new HashMap<>();
        stock.put(LEAF, 1000L);
        // 3 FINAL + 2 FINAL2 → MID 需求 = 3×1 + 2×2 = 7
        ICraftingPlan planFinal = run(byOutput, FINAL, 3, stock);
        ICraftingPlan planFinal2 = run(byOutput, FINAL2, 2, stock);
        assertTrue(planFinal.missingItems().isEmpty() && planFinal2.missingItems().isEmpty(), "共享中间产物应可行");
        assertEquals(3, used(planFinal, LEAF), "FINAL×3 → 3 叶");
        assertEquals(4, used(planFinal2, LEAF), "FINAL2×2 → 4 叶");
        System.out.println("[player-shared-mid] finalLeaf=" + used(planFinal, LEAF) + " final2Leaf=" + used(planFinal2, LEAF));
    }

    /** null getInputs() 的异常样板：不崩溃、不编译、按缺料处理。 */
    @Test
    void playerNullInputsPatternDoesNotCrash() {
        NullInputsPattern bad = new NullInputsPattern(MID);
        Map<AEKey, IPatternDetails> byOutput = new LinkedHashMap<>();
        byOutput.put(FINAL, new BenchPatternDetails(FINAL, 1, List.of(
                BenchPatternDetails.InputSpec.of(MID, 1))));
        byOutput.put(MID, bad);
        Map<BenchAEKey, Long> stock = new HashMap<>();
        PatternCompiler.clearCache();
        PatternCompiler.clearFuzzyGroups();
        for (IPatternDetails p : byOutput.values()) PatternCompiler.compileIfAbsent(p); // 必须不抛
        ICraftingPlan plan = run(byOutput, FINAL, 1, stock);
        assertTrue(hasMissing(plan, MID), "null 输入样板按缺料处理. missing=" + missing(plan));
        System.out.println("[player-null-inputs] missing=" + missing(plan));
    }

    /** getInputs() 非空但某槽 getPossibleInputs() 为 null：不崩溃。 */
    @Test
    void playerNullPossibleInputsDoesNotCrash() {
        Map<AEKey, IPatternDetails> byOutput = new LinkedHashMap<>();
        byOutput.put(OUT4, new NullPossibleInputPattern(LEAF, OUT4));
        Map<BenchAEKey, Long> stock = new HashMap<>();
        stock.put(LEAF, 100L);
        PatternCompiler.clearCache();
        PatternCompiler.clearFuzzyGroups();
        for (IPatternDetails p : byOutput.values()) PatternCompiler.compileIfAbsent(p);
        ICraftingPlan plan = run(byOutput, OUT4, 1, stock);
        assertTrue(plan.missingItems().isEmpty(), "null possibleInputs 槽应跳过. missing=" + missing(plan));
        assertEquals(0, used(plan, LEAF));
        System.out.println("[player-null-possible] missing=" + missing(plan));
    }

    /** 零数量请求：不异常，计划为空。 */
    @Test
    void playerRequestZeroAmountProducesEmptyPlan() {
        Map<AEKey, IPatternDetails> byOutput = new LinkedHashMap<>();
        byOutput.put(FINAL, new BenchPatternDetails(FINAL, 1, List.of(
                BenchPatternDetails.InputSpec.of(LEAF, 1))));
        Map<BenchAEKey, Long> stock = new HashMap<>();
        stock.put(LEAF, 100L);
        PatternCompiler.clearCache();
        PatternCompiler.clearFuzzyGroups();
        for (IPatternDetails p : byOutput.values()) PatternCompiler.compileIfAbsent(p);
        ICraftingPlan plan = run(byOutput, FINAL, 0, stock);
        assertEquals(0, plan.missingItems().size(), "零数量无缺料");
        assertEquals(0, plan.usedItems().size(), "零数量无消耗");
        assertEquals(0, plan.patternTimes().size(), "零数量无样板执行");
        System.out.println("[player-zero] plan empty ok");
    }

    /** 单 craft 输出超大（10^9）：craftTimes 正确折算。 */
    @Test
    void playerHugeOutputPerCraftReducesCrafts() {
        Map<AEKey, IPatternDetails> byOutput = new LinkedHashMap<>();
        byOutput.put(HUGE_OUT, new BenchPatternDetails(HUGE_OUT, 1_000_000_000L, List.of(
                BenchPatternDetails.InputSpec.of(LEAF, 1))));
        Map<BenchAEKey, Long> stock = new HashMap<>();
        stock.put(LEAF, 2000L);
        ICraftingPlan plan = run(byOutput, HUGE_OUT, 3_000_000_000L, stock);
        assertTrue(plan.missingItems().isEmpty(), "超大输出量应可行. missing=" + missing(plan));
        assertEquals(3, used(plan, LEAF), "3e9 请求 / 1e9 每 craft = 3 craft → 3 叶");
        System.out.println("[player-huge-output] leafUsed=" + used(plan, LEAF));
    }

    /** 重新编码完全相同内容的样板（新实例同内容）：不应触发重捕获，结果保持不变。 */
    @Test
    void playerReEncodeIdenticalPatternNoRecapture() {
        BenchPatternDetails finalP = new BenchPatternDetails(FINAL, 1, List.of(
                BenchPatternDetails.InputSpec.of(MID, 1)));
        BenchPatternDetails midV1 = new BenchPatternDetails(MID, 1, List.of(
                BenchPatternDetails.InputSpec.of(LEAF, 1)));
        Map<AEKey, IPatternDetails> byOutput = new LinkedHashMap<>();
        byOutput.put(FINAL, finalP);
        byOutput.put(MID, midV1);
        Map<BenchAEKey, Long> stock = new HashMap<>();
        stock.put(LEAF, 100L);

        PatternCompiler.clearCache();
        PatternCompiler.clearFuzzyGroups();
        for (IPatternDetails p : byOutput.values()) PatternCompiler.compileIfAbsent(p);
        CraftingVM vm = new CraftingVM("player-reencode", key -> key instanceof BenchAEKey k ? byOutput.get(k) : null);
        CraftingBytecode req = PatternCompiler.compileRequest(finalP, 3);
        ICraftingPlan plan1 = vm.execute(req, new BenchSimulationState(stock));
        assertEquals(3, used(plan1, LEAF));

        // 玩家用倍乘器重新编码了 MID（新实例、内容相同：1 叶 → 1 MID），无 bump
        BenchPatternDetails midV2 = new BenchPatternDetails(MID, 1, List.of(
                BenchPatternDetails.InputSpec.of(LEAF, 1)));
        byOutput.put(MID, midV2);
        PatternCompiler.compileIfAbsent(midV2);

        ICraftingPlan plan2 = vm.execute(req, new BenchSimulationState(stock));
        assertEquals(3, used(plan2, LEAF), "同内容重编码不应改变结果");
        assertEquals(missing(plan1), missing(plan2), "同内容重编码 missing 必须一致");
        System.out.println("[player-reencode] leafUsed1=" + used(plan1, LEAF) + " leafUsed2=" + used(plan2, LEAF));
    }

    /** 流体副产物供给兄弟链：A 产出物品+流体，D 消费流体。 */
    @Test
    void playerFluidByproductChain() {
        BenchPatternDetails patternA = new BenchPatternDetails(A_OUT, 1, List.of(
                BenchPatternDetails.InputSpec.of(LEAF, 1)),
                List.of(BenchPatternDetails.OutputSpec.of(WATER_FLUID, 1000)), null); // A_OUT + 1000 水
        BenchPatternDetails patternD = new BenchPatternDetails(D_FLUID, 1, List.of(
                BenchPatternDetails.InputSpec.of(WATER_FLUID, 1000)));
        Map<AEKey, IPatternDetails> byOutput = new LinkedHashMap<>();
        byOutput.put(A_OUT, patternA);
        byOutput.put(WATER_FLUID, patternA); // 水由 A 样板副产出
        byOutput.put(D_FLUID, patternD);
        Map<BenchAEKey, Long> stock = new HashMap<>();
        stock.put(LEAF, 100L);
        ICraftingPlan plan = run(byOutput, D_FLUID, 3, stock);
        assertTrue(plan.missingItems().isEmpty(), "流体副产物链应可行. missing=" + missing(plan));
        assertEquals(3, plan.patternTimes().getOrDefault(patternA, 0L), "A 应 craft 3 次供 3000 水");
        assertEquals(3, used(plan, LEAF));
        System.out.println("[player-fluid-byproduct] timesA=" + plan.patternTimes().getOrDefault(patternA, 0L));
    }

    // ====================================================================
    // helpers
    // ====================================================================

    private static ICraftingPlan run(Map<AEKey, IPatternDetails> byOutput, BenchAEKey target, long amount,
                                     Map<BenchAEKey, Long> stock) {
        PatternCompiler.clearCache();
        PatternCompiler.clearFuzzyGroups();
        for (IPatternDetails p : byOutput.values()) PatternCompiler.compileIfAbsent(p);
        IPatternDetails top = byOutput.get(target);
        CraftingBytecode req = PatternCompiler.compileRequest(top, amount);
        CraftingVM vm = new CraftingVM("player-op", key -> key instanceof BenchAEKey k ? byOutput.get(k) : null);
        return vm.execute(req, new BenchSimulationState(stock));
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

    /** 无输出的异常样板（getPrimaryOutput 默认实现会 AIOOBE）。 */
    private static final class EmptyOutputPattern implements IPatternDetails {
        private final AEKey what;

        EmptyOutputPattern(AEKey what) {
            this.what = what;
        }

        @Override
        public AEItemKey getDefinition() {
            return null;
        }

        @Override
        public IInput[] getInputs() {
            return new IInput[0];
        }

        @Override
        public GenericStack[] getOutputs() {
            return new GenericStack[0];
        }

        @Override
        public String toString() {
            return "EmptyOutputPattern[" + what + "]";
        }
    }

    /** getInputs() 返回 null 的异常样板。 */
    private static final class NullInputsPattern implements IPatternDetails {
        private final AEKey what;

        NullInputsPattern(AEKey what) {
            this.what = what;
        }

        @Override
        public AEItemKey getDefinition() {
            return null;
        }

        @Override
        public IInput[] getInputs() {
            return null; // 异常：null 输入列表
        }

        @Override
        public GenericStack[] getOutputs() {
            return new GenericStack[]{new GenericStack(what, 1)};
        }
    }

    /** 某槽 getPossibleInputs() 返回 null 的异常样板。 */
    private static final class NullPossibleInputPattern implements IPatternDetails {
        private final AEKey input;
        private final AEKey output;

        NullPossibleInputPattern(AEKey input, AEKey output) {
            this.input = input;
            this.output = output;
        }

        @Override
        public AEItemKey getDefinition() {
            return null;
        }

        @Override
        public IInput[] getInputs() {
            return new IInput[]{new IInput() {
                @Override
                public GenericStack[] getPossibleInputs() {
                    return null; // 异常：null 可能输入
                }

                @Override
                public long getMultiplier() {
                    return 1;
                }

                @Override
                public boolean isValid(AEKey key, Level level) {
                    return key != null && key.equals(input);
                }

                @Override
                public AEKey getRemainingKey(AEKey template) {
                    return null;
                }
            }};
        }

        @Override
        public GenericStack[] getOutputs() {
            return new GenericStack[]{new GenericStack(output, 1)};
        }
    }
}