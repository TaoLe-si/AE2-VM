package com.ae2vm.addon.compat.advancedae;

import com.ae2vm.addon.AE2VMAddon;
import cpw.mods.fml.common.Loader;

/**
 * AdvancedAE ({@code advanced_ae}) 弱依赖门面。
 *
 * <p>AdvancedAE 与 AE2 VM 在合成计算链路中天然分层兼容：
 * <ul>
 *   <li>AE2 VM 拦截 {@code CraftingService.beginCraftingCalculation}（HEAD, order=100），
 *       用栈式 VM 计算合成计划 —— 这是 <b>规划层</b>。</li>
 *   <li>AdvancedAE 的 {@code cpu.MixinCraftingService} 只注入 {@code submitJob} 的
 *       {@code findSuitableCraftingCPU}（INVOKE_ASSIGN，cancellable），把现成的
 *       {@code ICraftingPlan} 路由到 {@code AdvCraftingCPUCluster} 执行 —— 这是
 *       <b>CPU 分配/执行层</b>，不触碰 {@code beginCraftingCalculation}。</li>
 * </ul>
 * 因此安装 AdvancedAE 后，AE2 VM 的计算逻辑<b>仍然生效</b>：VM 算出的 plan 原样流入
 * AdvancedAE 的 CPU 集群执行（复用原版 CraftingCpuHelper / provider.pushPattern 机制）。
 * 本门面提供检测 + 一次性启动确认日志，作为显式的兼容声明与未来 AdvancedAE 行为变化的
 * 适配点。
 */
public final class AdvancedAECompat {

    /** AdvancedAE 的 mod id（字面量，静态初始化不触碰 AdvancedAE 类）。 */
    public static final String MOD_ID = "advanced_ae";

    private static volatile boolean logged = false;

    private AdvancedAECompat() {
    }

    /** AdvancedAE 是否已安装。始终安全调用：使用 modId 字符串字面量检测（1.12.2 走 Loader.isModLoaded）。 */
    public static boolean isLoaded() {
        try {
            return Loader.isModLoaded(MOD_ID);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 安装确认日志（幂等，只打印一次）：AdvancedAE 存在时确认 AE2 VM 的计算逻辑仍接管
     * 规划层（beginCraftingCalculation），AdvancedAE 仅负责 CPU 分配/执行层。
     * 在 commonSetup 的 enqueueWork 中调用（此时 mod 列表已完整）。
     */
    public static void logCompatibilityIfPresent() {
        if (!isLoaded() || logged) {
            return;
        }
        logged = true;
        if (com.ae2vm.addon.config.AE2VMConfig.isDebugLogging()) {
            AE2VMAddon.LOGGER.info(
                    "[AE2-VM] AdvancedAE detected ({}) \u2014 AE2 VM computation remains active: "
                            + "VM computes plans (beginCraftingCalculation layer); AdvancedAE only "
                            + "routes them to AdvCraftingCPUCluster for execution (submitJob layer). "
                            + "No conflict, no fallback needed.",
                    MOD_ID);
        }
    }
}
