package com.ae2vm.addon.compat.thunderbolt;

import com.ae2vm.addon.AE2VMAddon;
import com.moakiee.thunderbolt.api.crafting.CraftingPlanningEngines;
import net.neoforged.fml.ModList;

/**
 * Thunderbolt-Core 弱依赖（可选前置）门面。
 *
 * <p>本类始终位于 AE2VMAddon 的 classpath 上。对 Thunderbolt 类
 * ({@code CraftingPlanningEngines}、{@code AE2VMBatchCraftingPlanner}) 的全部引用
 * 都发生在被 {@link #isThunderboltLoaded()} 守卫的方法体内（懒加载）——未安装
 * Thunderbolt 时不会触发 {@code NoClassDefFoundError}，AE2VMAddon 保持原有行为。
 *
 * <p>行为约定：
 * <ul>
 *   <li>装了 Thunderbolt：引擎选择/路由完全交给 Thunderbolt。玩家选中
 *       {@code ae2vm} → {@link AE2VMBatchCraftingPlanner} 被调用走 VM；
 *       未选中 → 我们不接管，由 Thunderbolt 走原版/其它引擎。</li>
 *   <li>没装 Thunderbolt：AE2VMAddon 的 {@link com.ae2vm.addon.mixin.CraftingServiceMixin}
 *       按原有逻辑直接接管所有请求。</li>
 * </ul>
 */
public final class ThunderboltCompat {

    /** Thunderbolt-Core 的 mod id（字面量，静态初始化不触碰 Thunderbolt 类）。 */
    public static final String MOD_ID = "thunderbolt";

    /** 我们（AE2 VM）在 Thunderbolt 多引擎体系中的引擎 id。 */
    public static final String ENGINE_ID = AE2VMBatchCraftingPlanner.ENGINE_ID;

    private static volatile boolean registered = false;

    private ThunderboltCompat() {
    }

    /**
     * Thunderbolt-Core 是否已安装。
     *
     * <p>始终安全调用：使用 ModList 的字符串字面量检测，不触碰任何 Thunderbolt 类。
     */
    public static boolean isThunderboltLoaded() {
        try {
            return ModList.get() != null && ModList.get().isLoaded(MOD_ID);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 当前是否选中了我们的引擎（{@code /thunderbolt engine ae2vm}）。
     *
     * <p>未装 Thunderbolt 恒为 false（我们的 mixin 将直接接管）。
     *
     * <p>Thunderbolt 的引擎选择通过 {@link CraftingPlanningEngines} 的优先级链确定。
     * 我们注册时使用较高优先级确保在原生 AE2 之前被尝试。
     */
    public static boolean isEngineSelected() {
        if (!isThunderboltLoaded()) {
            return false;
        }
        try {
            // 检查 ae2vm 引擎是否在当前可用的引擎列表中。
            // 如果 ae2vm 在列表里且当前请求的节点提供了它，则表示选中。
            // 由于我们以 publicAlgorithm=false 注册，ae2vm 只会出现在 selectablesFor(provided)
            // 列表中（当 provided == ae2vm 时）。Thunderbolt 的 CraftingCalculationMixin
            // 通过 resolve() 获取候选引擎链，只有当节点provider显式选择了 ae2vm 时才会被调用。
            // 这里用 registry 中是否存在来判断是否"可选"，但实际选择由 Thunderbolt 的
            // CraftingAlgorithmResolver 和节点 provider 决定。
            // 为简化判断：如果 ae2vm 已注册且 Thunderbolt 已加载，我们认为它可能已被选中。
            // CraftingServiceMixin 会通过更精细的逻辑判断是否真的轮到我们。
            var id = AE2VMBatchCraftingPlanner.INSTANCE.id();
            return CraftingPlanningEngines.get(id) != null;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 装了 Thunderbolt 就把我们的引擎注册进 {@link CraftingPlanningEngines}（幂等，只注册一次）。
     *
     * <p>我们在 commonSetup 的 enqueueWork 中调用（异步，避免堵塞 mod 加载线程）。
     *
     * @see AE2VMAddon#commonSetup
     */
    public static void registerIfPresent() {
        if (!isThunderboltLoaded() || registered) {
            return;
        }
        try {
            // 优先级 900：比 Thunderbolt V2(1000) 低，比原生 AE2 vanillla(min) 高。
            // 这样 ae2vm 在 Thunderbolt V2 之后被尝试，但如果玩家在 GUI 中选择了 ae2vm
            //（private 算法，需要节点provider显式提供），它会优先于 vanilla 被使用。
            CraftingPlanningEngines.register(
                    AE2VMBatchCraftingPlanner.INSTANCE,
                    900,           // algorithmPriority
                    false          // publicAlgorithm = false（需要 provider 节点才能选择）
            );
            registered = true;
            AE2VMAddon.LOGGER.info(
                    "[AE2-VM] Registered AE2 VM engine with Thunderbolt-Core (id={}, priority=900). "
                            + "Select it via the crafting CPU algorithm menu or: /thunderbolt engine {}",
                    ENGINE_ID, ENGINE_ID);
        } catch (Throwable t) {
            AE2VMAddon.LOGGER.warn("[AE2-VM] Could not register with Thunderbolt-Core: {}", t.toString());
        }
    }
}
