package com.ae2vm.addon;

import com.ae2vm.addon.compat.thunderbolt.ThunderboltCompat;
import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforgespi.language.IModInfo;
import org.slf4j.Logger;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * AE2 VM Crafting Accelerator - Main Mod Class
 * 
 * Replaces AE2's recursive crafting calculation with a stack-based virtual machine.
 * 
 * PERFORMANCE IMPROVEMENTS:
 * 1. Patterns are compiled ONCE to flat bytecode (no per-request recursion)
 * 2. Execution is simple linear bytecode interpretation (O(n) not O(recursion depth))
 * 3. No stack overflow for deep crafting trees (37+ patterns deep)
 * 4. No repeated pattern lookups during calculation
 * 5. VM instances are cached and reused
 * 
 * Architecture:
 * - PatternCompiler: traverses pattern tree ONCE, inlines all sub-patterns
 * - CraftingBytecode: flat, serializable instruction list
 * - CraftingVM: stack-based interpreter, executes bytecode in tight loop
 * - Mixin: hooks into ICraftingService.beginCraftingCalculation()
 */
@Mod(AE2VMAddon.MOD_ID)
public class AE2VMAddon {
    public static final String MOD_ID = "ae2vm";
    public static final Logger LOGGER = LogUtils.getLogger();
    
    /**
     * Mods by this author (fish1145 / fish_dan — DataEnergistics family) that are NOT
     * allowed to run together with AE2VMAddon. When any of these is loaded, AE2VMAddon
     * deliberately crashes the game at startup. Remove any of these to continue.
     * Note: mekenergistics (通用数据 / Mek Energistics) and soulplied_energistics
     * (Soulplied Energistics, by Buuz135) are no longer blocked — they are compatible.
     */
    private static final Set<String> BLOCKED_MOD_IDS = Set.of(
        "data_energistics"       // DataEnergistics — authors: fish_dan, QiuYe, TedXenon (confirmed)
    );
    
    /** 运行模式: 'crash'(默认) → 检测到该作者 mod 游戏闪退；'warn' → 只警告不闪退。 */
    private final String blockedMode = readBlockedMode();
    
    public AE2VMAddon(IEventBus modEventBus) {
        modEventBus.addListener(this::commonSetup);
        
        checkBlockedMods(); // crash（或 warn）if a blocked author mod is loaded
        
        // 第三方（Thunderbolt-Core）引擎路由：通过 CraftingPlanningEngines.register(AE2VMBatchCraftingPlanner, 900, false)
        // 注册到 Thunderbolt 的多引擎体系中。如果 Thunderbolt 未安装则不注册，Mixin 直接接管；
        // 如果 Thunderbolt 已安装但玩家未选中 ae2vm 引擎，则我们的 CraftingServiceMixin 让出控制权，
        // 由 Thunderbolt 的 CraftingCalculationMixin 路由到选中的引擎（包括原生 AE2）。
        // 实际注册在 commonSetup.enqueueWork 中执行（与 Thunderbolt 同帧初始化）。

        // Startup banner
        LOGGER.info("");
        LOGGER.info("╔══════════════════════════════════════════════════════════════╗");
        LOGGER.info("║       AE2 VM Crafting Accelerator v1.9.0 Loaded!            ║");
        LOGGER.info("║  Replacing recursive crafting with stack-based VM engine    ║");
        LOGGER.info("╠══════════════════════════════════════════════════════════════╣");
        LOGGER.info("║  • Patterns compiled to bytecode at ENCODE time             ║");
        LOGGER.info("║  • Craft times compiled to bytecode per request             ║");
        LOGGER.info("║  • CALL_BY_KEY: lazy sub-pattern resolution at runtime      ║");
        LOGGER.info("║  • 10-100x faster for deep crafting trees                   ║");
        LOGGER.info("║  • Eliminates stack overflow from 30+ pattern depth         ║");
        LOGGER.info("║  • Linear bytecode execution - NO RECURSION                 ║");
        LOGGER.info("╚══════════════════════════════════════════════════════════════╝");
        LOGGER.info("");
        LOGGER.info("[AE2-VM] 斐波那契式指数递归链：已通过 O(patterns) 需求传播聚合支持，不再指数爆炸");
        LOGGER.info("[AE2-VM] Fibonacci-style exponential chains: supported via O(patterns) demand-propagation aggregation — no exponential blowup");
    }
    
    /**
     * Scans all installed mods; if any belongs to the blocked author, deliberately
     * crashes the game (闪退). Runs at mod load and again during common setup.
     */
    private void checkBlockedMods() {
        IModInfo found = null;
        try {
            ModList modList = ModList.get();
            if (modList != null) {
                for (IModInfo info : modList.getMods()) {
                    String id = info.getModId();
                    if (id != null && BLOCKED_MOD_IDS.contains(id)) {
                        found = info;
                        break;
                    }
                }
            }
        } catch (Throwable t) {
            LOGGER.warn("[AE2-VM] Could not scan mod list: {}", t.toString());
        }
        if (found != null) {
            String id = found.getModId();
            String name = found.getDisplayName();
            if ("warn".equalsIgnoreCase(blockedMode)) {
                // 无针对检测版：只警告，不闪退
                LOGGER.warn("[AE2-VM] 检测到该作者的 mod '{}' ({}) —— 无针对检测版：仅警告，不闪退", id, name);
                LOGGER.warn("[AE2-VM] Detected blocked author mod '{}' ({}) — warn mode: NOT crashing.", id, name);
                return;
            }
            LOGGER.error("[AE2-VM] 检测到该作者的 mod '{}' ({}) —— 游戏闪退", id, name);
            LOGGER.error("[AE2-VM] Detected blocked author mod '{}' ({}) — crashing on purpose.", id, name);
            throw new RuntimeException(
                "AE2VMAddon refuses to run with mod '" + id + "' (" + name
                + ") — mods by this author (fish1145/fish_dan) are incompatible. Remove the mod and restart.");
        }
    }

    /**
     * 从打包进 jar 的 /ae2vm/blockedmode.txt 读取运行模式（crash/warn），
     * 由构建时 -PblockedMode=... 决定，默认 crash。故意闪退的是游戏运行时，不是编译器。
     */
    private static String readBlockedMode() {
        try (InputStream in = AE2VMAddon.class.getResourceAsStream("/ae2vm/blockedmode.txt")) {
            if (in != null) {
                String mode = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
                if ("crash".equalsIgnoreCase(mode) || "warn".equalsIgnoreCase(mode)) {
                    return mode.toLowerCase();
                }
            }
        } catch (Exception e) {
            LOGGER.warn("[AE2-VM] Could not read blockedmode.txt, defaulting to crash: {}", e.toString());
        }
        return "crash";
    }
    
    private void commonSetup(final FMLCommonSetupEvent event) {
        checkBlockedMods(); // re-check once the mod list is fully populated
        com.ae2vm.addon.config.AE2VMConfig.tryRegister(); // 可选 Cloth Config：注册 config/ae2vm.json（proxy.enabled 开关）

        // Thunderbolt 引擎注册：必须在 enqueueWork 中调用，与 ThunderboltCore.onCommonSetup 同期执行。
        // ThunderboltCompat.registerIfPresent() 在构造函数中调用会因 mod 加载顺序问题导致
        // CraftingPlanningEngines.register() 在 Thunderbolt 完成注册前去重导致后续节点 provider 看不到我们。
        // 故改为在 commonSetup 的 enqueueWork 中统一注册（与 Thunderbolt 同帧）。
        event.enqueueWork(() -> {
            ThunderboltCompat.registerIfPresent();
            // AdvancedAE 兼容确认（只打印一次）：AdvancedAE 只接管 submitJob 的 CPU 分配层，
            // 我们的 beginCraftingCalculation 规划层仍由 VM 计算 —— 安装 AdvancedAE 也走我们的计算逻辑。
            com.ae2vm.addon.compat.advancedae.AdvancedAECompat.logCompatibilityIfPresent();
        });

        LOGGER.info("[AE2-VM] Common setup complete - VM engine active, monitoring crafting requests");
        LOGGER.info("[AE2-VM] All crafting calculations will be logged with timing information");
    }
}
