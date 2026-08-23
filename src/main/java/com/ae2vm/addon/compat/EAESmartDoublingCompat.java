package com.ae2vm.addon.compat;

import appeng.api.crafting.IPatternDetails;
import appeng.me.service.CraftingService;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * (v1.12.18 EAE+ ADAPT) ExtendedAE Plus "smart doubling"（智能翻倍 / 样板翻倍）兼容层。
 *
 * <p>EAE+ 的翻倍是在<b>计划构建阶段</b>完成的：{@code CraftingSimulationStateMixin}
 * 把每一份合成计数 (pattern → count) 中实现了 {@code ISmartDoublingAwarePattern} 且
 * {@code eap$allowScaling() == true} 的条目，按 EAE+ 规则重分批为
 * {@code ScaledProcessingPattern} / {@code ScaledMolecularAssemblerPattern} 包装样板
 * （输入 ×N、输出 ×N，倍率只影响单次推送规模、不影响总量）。由于 AE2 VM 自建
 * {@code CraftingPlan}（不经 {@code CraftingSimulationState.buildCraftingPlan}），
 * EAE+ 的这一重分批对 VM 计算的请求完全不会执行——VM 计划里只有原始 ×1 样板，
 * 智能翻倍被静默丢失（CPU 退化为每份小批量推送）。
 *
 * <p>本类在 {@code CraftingVM.buildPlan} 对最终的 {@code patternTimes} 应用与 EAE+
 * 完全一致的镜像逻辑（provider 轮询分配、全局/供应器级翻倍上限、super matrix
 * 十次分包），让 VM 计划携带与原生 AE2+EAE+ 相同的 Scaled 包装条目。总用量
 * （used/missing/emitted）不变：Scaled(P, n) × k 与 P × (k·n) 的输入输出完全等价。
 *
 * <p>gtlcore 的「样板翻倍」是另一条路径：{@code PatternModifier} 直接把样板物品
 * <b>重编码</b>为 ×N 的 {@code AEProcessingPattern}（不是包装类），VM 经由接口
 * {@code getInputs()/getOutputs()} 天然按 ×N 折算；且 gtlcore 样板不实现
 * {@code ISmartDoublingAwarePattern}（allowScaling 恒为 false），本类不会触碰它们，
 * gtlcore 的 {@code CraftingCpuLogicMixin} autoExpand 路径保持原样。
 *
 * <p>SOFT-FAIL（他们即使不安装，我们也正常运行）：所有 EAE+ 类只经反射加载；
 * EAE+ 未安装或任何反射步骤失败时 {@link #rebatch} 原样返回输入 map，VM 行为
 * 与未适配前完全一致。
 */
public final class EAESmartDoublingCompat {

    /** EAE+ super matrix 最多十次发配即可覆盖完整订单。 */
    private static final int MAX_SUPER_MATRIX_DISPATCHES = 10;

    // ---- 反射句柄（惰性缓存，全部 EAE+ 类） ----
    private static final boolean AVAILABLE;
    private static Class<?> AWARE_IFACE;       // ISmartDoublingAwarePattern
    private static Method ALLOW_SCALING_M;     // eap$allowScaling()
    private static Method MULTIPLIER_LIMIT_M;  // eap$getMultiplierLimit()
    private static Method CREATE_SCALED_M;     // PatternScaler.createScaled(IPatternDetails, long)
    private static Class<?> STRICT_MA_CLASS;   // StrictMolecularAssemblerPattern
    private static Object CONFIG_INSTANCE;     // ModConfig.INSTANCE
    private static Field SMART_SCALING_MAX_MUL_F;
    private static Field PROVIDER_ROUND_ROBIN_F;

    static {
        boolean ok = false;
        try {
            AWARE_IFACE = Class.forName("com.extendedae_plus.api.smartDoubling.ISmartDoublingAwarePattern");
            ALLOW_SCALING_M = AWARE_IFACE.getMethod("eap$allowScaling");
            MULTIPLIER_LIMIT_M = AWARE_IFACE.getMethod("eap$getMultiplierLimit");
            Class<?> scaler = Class.forName("com.extendedae_plus.util.smartDoubling.PatternScaler");
            CREATE_SCALED_M = scaler.getMethod("createScaled", IPatternDetails.class, long.class);
            STRICT_MA_CLASS = Class.forName("com.extendedae_plus.util.crafting.StrictMolecularAssemblerPattern");
            Class<?> config = Class.forName("com.extendedae_plus.config.ModConfig");
            CONFIG_INSTANCE = config.getField("INSTANCE").get(null);
            SMART_SCALING_MAX_MUL_F = config.getField("smartScalingMaxMultiplier");
            PROVIDER_ROUND_ROBIN_F = config.getField("providerRoundRobinEnable");
            ok = true;
        } catch (Throwable t) {
            AWARE_IFACE = null;
            ALLOW_SCALING_M = null;
            MULTIPLIER_LIMIT_M = null;
            CREATE_SCALED_M = null;
            STRICT_MA_CLASS = null;
            CONFIG_INSTANCE = null;
            SMART_SCALING_MAX_MUL_F = null;
            PROVIDER_ROUND_ROBIN_F = null;
        }
        AVAILABLE = ok;
    }

    private EAESmartDoublingCompat() {
    }

    /** EAE+ 智能翻倍运行时是否可用（类 + 方法完整加载）。 */
    public static boolean isAvailable() {
        return AVAILABLE;
    }

    /** 重分批后的一个条目：倍率 multiplier、该倍率的计划条目数 count。 */
    public static final class Batch {
        public final long multiplier;
        public final long count;

        Batch(long multiplier, long count) {
            this.multiplier = multiplier;
            this.count = count;
        }
    }

    /**
     * EAE+ {@code CraftingSimulationStateMixin} 分批算术的纯函数镜像（可在无 EAE+
     * 类路径下离线测试）。
     *
     * @param totalAmount   该样板的合成计数
     * @param perCraftLimit 每份最大倍率（&lt;=0 表示不限制；已包含全局上限回填）
     * @param roundRobin    是否启用 provider 轮询分配
     * @param providerCount 轮询模式下的 provider 数量（&lt;=1 时退化为单份）
     * @return (倍率, 条目数) 列表，恒满足 Σ multiplier × count == totalAmount
     */
    static List<Batch> computeBatches(long totalAmount, long perCraftLimit, boolean roundRobin, int providerCount) {
        List<Batch> out = new ArrayList<>(4);
        if (perCraftLimit <= 0) {
            if (roundRobin && providerCount > 1) {
                if (totalAmount < providerCount) {
                    providerCount = (int) totalAmount;
                }
                long base = totalAmount / providerCount;
                long remainder = totalAmount % providerCount;
                if (remainder > 0) {
                    out.add(new Batch(base + 1, remainder));
                }
                long countBase = providerCount - remainder;
                if (countBase > 0) {
                    out.add(new Batch(base, countBase));
                }
            } else {
                out.add(new Batch(totalAmount, 1L));
            }
        } else {
            long fullCrafts = totalAmount / perCraftLimit;
            long remainder = totalAmount % perCraftLimit;
            if (fullCrafts > 0) {
                out.add(new Batch(perCraftLimit, fullCrafts));
            }
            if (remainder > 0) {
                out.add(new Batch(remainder, 1L));
            }
        }
        return out;
    }

    /**
     * 对 VM 计划构建前的 {@code patternTimes} 应用 EAE+ 智能翻倍重分批。
     * 任何 EAE+ 缺失 / 反射失败都原样返回输入（软失败）。
     *
     * @param patternTimes VM 聚合后的 (pattern → craft count)
     * @param service      当前网格的 {@link CraftingService}（轮询计数用；可为 null）
     * @return 重分批后的新 map（条目为 EAE+ Scaled 包装样板），失败时返回输入引用
     */
    public static Map<IPatternDetails, Long> rebatch(Map<IPatternDetails, Long> patternTimes,
                                                     CraftingService service) {
        if (!AVAILABLE || patternTimes == null || patternTimes.isEmpty()) {
            return patternTimes;
        }
        try {
            boolean roundRobin = PROVIDER_ROUND_ROBIN_F.getBoolean(CONFIG_INSTANCE) && service != null;
            long globalMax = SMART_SCALING_MAX_MUL_F.getLong(CONFIG_INSTANCE);
            Map<IPatternDetails, Long> finalCrafts = new LinkedHashMap<>();
            Map<IPatternDetails, Integer> providerCountCache = new HashMap<>();

            for (Map.Entry<IPatternDetails, Long> entry : patternTimes.entrySet()) {
                IPatternDetails details = entry.getKey();
                long totalAmount = entry.getValue();
                // 非 EAE+ aware 样板（含 gtlcore ×N 重编码样板、普通 AE2 样板）原样保留。
                if (details == null || !AWARE_IFACE.isInstance(details)) {
                    finalCrafts.put(details, totalAmount);
                    continue;
                }
                boolean allowScaling = (Boolean) ALLOW_SCALING_M.invoke(details);
                long perCraftLimit;
                if (STRICT_MA_CLASS.isInstance(details)) {
                    // 装配矩阵严格样板：按目标数量动态分包，最多十次发配覆盖完整订单。
                    perCraftLimit = getSuperMatrixDispatchSize(totalAmount);
                } else {
                    perCraftLimit = ((Number) MULTIPLIER_LIMIT_M.invoke(details)).longValue();
                }
                if (!allowScaling || totalAmount <= 1) {
                    finalCrafts.put(details, totalAmount);
                    continue;
                }
                // 全局最大倍率回填（优先级低于样板自身限制）。
                if (perCraftLimit <= 0 && globalMax > 0) {
                    perCraftLimit = globalMax;
                }
                int providerCount = 0;
                if (perCraftLimit <= 0 && roundRobin) {
                    providerCount = Math.max(providerCountCache.computeIfAbsent(details,
                            key -> countProvidersUpTo(service.getProviders(key), totalAmount)), 1);
                }
                for (Batch b : computeBatches(totalAmount, perCraftLimit, roundRobin, providerCount)) {
                    IPatternDetails scaled = (IPatternDetails) CREATE_SCALED_M.invoke(null, details, b.multiplier);
                    finalCrafts.merge(scaled, b.count, Long::sum);
                }
            }
            return finalCrafts;
        } catch (Throwable t) {
            // SOFT-FAIL：EAE+ 逻辑异常绝不影响 VM 主流程。
            return patternTimes;
        }
    }

    /** ceil(total / 10) —— EAE+ super matrix 分包大小。 */
    static long getSuperMatrixDispatchSize(long targetAmount) {
        return targetAmount / MAX_SUPER_MATRIX_DISPATCHES
                + (targetAmount % MAX_SUPER_MATRIX_DISPATCHES == 0 ? 0 : 1);
    }

    private static int countProvidersUpTo(Iterable<appeng.api.networking.crafting.ICraftingProvider> providers,
                                          long maxNeeded) {
        int count = 0;
        int limit = maxNeeded <= 0 ? Integer.MAX_VALUE
                : maxNeeded >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) maxNeeded;
        for (var ignored : providers) {
            count++;
            if (count >= limit) {
                break;
            }
        }
        return count;
    }
}
