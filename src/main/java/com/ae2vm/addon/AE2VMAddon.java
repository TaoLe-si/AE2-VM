package com.ae2vm.addon;

import com.ae2vm.addon.config.AE2VMConfig;
import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.ModContainer;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
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
 * - Mixin: hooks into CraftingGridCache.beginCraftingJob / CraftingCPUCluster.submitJob
 *           / DualityInterface.updateCraftingList
 *
 * <p>本 fork 的代码基座是 1.15.2 fork（现代架构：v9 API shim + v8 后端桥），但 FML 生命周期
 * 用的是 1.12.2 那一代的 API：{@code IEventBus} / {@code FMLJavaModLoadingContext} /
 * {@code ModList} / {@code ForgeConfigSpec} / {@code forgespi.language.IModInfo} 在 1.12.2
 * 全不存在（都是 1.13+ 的），所以这里走 {@code @Mod(modid=...)} + {@code @Mod.EventHandler} +
 * {@code Loader} + {@code Configuration}。
 *
 * <p>mixin 的引导由 LaunchWrapper coremod {@code com.ae2vm.addon.coremod.AE2VMCoreMod} 负责
 * （它的构造函数里已经 MixinBootstrap.init() + Mixins.addConfiguration），因此 1.15.2 那份
 * "在 mod 构造期注册配置" 的写法在这里必须去掉，否则同一份配置注册两次。
 */
@Mod(modid = AE2VMAddon.MOD_ID, name = AE2VMAddon.MOD_NAME, version = AE2VMAddon.VERSION,
        dependencies = "required-after:appliedenergistics2")
public class AE2VMAddon {
    public static final String MOD_ID = "ae2vm";
    public static final String MOD_NAME = "AE2 VM";
    public static final String VERSION = "1.14.1";

    // ⚠️ Logger 选型（MIGRATION-PATTERNS §6，每个 MC 版本都不同）：
    //   1.20.x  → com.mojang.logging.LogUtils.getLogger()      （launcher 自带 com.mojang:logging）
    //   1.18.x  → org.slf4j.LoggerFactory.getLogger(...)         （launcher 自带 slf4j-api + log4j-slf4j18-impl）
    //   1.16.x / 1.12.2 → org.apache.logging.log4j.LogManager.getLogger(...)  ← 本 fork
    // 1.12.2 的启动器只有 log4j 1.2 / log4j-core，既没有 slf4j-api 也没有 mojang-logging。
    public static final Logger LOGGER = LogManager.getLogger(MOD_NAME);

    /**
     * Mods by this author (fish1145 / fish_dan — DataEnergistics family) that are NOT
     * allowed to run together with AE2VMAddon. When any of these is loaded, AE2VMAddon
     * deliberately crashes the game at startup. Remove any of these to continue.
     * Note: mekenergistics (通用数据 / Mek Energistics) and soulplied_energistics
     * (Soulplied Energistics, by Buuz135) are no longer blocked — they are compatible.
     */
    private static final Set<String> BLOCKED_MOD_IDS = new HashSet<>(Arrays.asList(
        "data_energistics"       // DataEnergistics — authors: fish_dan, QiuYe, TedXenon (confirmed)
    ));

    /** 运行模式: 'crash'(默认) → 检测到该作者 mod 游戏闪退；'warn' → 只警告不闪退。 */
    private final String blockedMode = readBlockedMode();

    public AE2VMAddon() {
        checkBlockedMods(); // crash（或 warn）if a blocked author mod is loaded
        // 启动横幅日志已移除（v1.10.8）——仅保留计算耗时日志，见 CraftingCPUClusterMixin / CraftingVM。
    }

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        // 1.12.2 的配置读写发生在预初始化：config/AE2VM.cfg（Forge 原生 Configuration，
        // 对应 1.16+ 各 fork 的 config/ae2vm-common.toml）。
        AE2VMConfig.load(event.getSuggestedConfigurationFile());
        checkBlockedMods(); // re-check once the mod list is fully populated
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        if (AE2VMConfig.isDebugLogging()) {
            LOGGER.info("[AE2-VM] Config loaded from config/AE2VM.cfg (proxy.enabled={})",
                    AE2VMConfig.isProxyEnabled());
        }
        // AdvancedAE 兼容确认（只打印一次）：AdvancedAE 只接管 submitJob 的 CPU 分配层，
        // 我们的规划层仍由 VM 计算 —— 装了 AdvancedAE 也走我们的算法。
        // 这里直接同步调用：logCompatibilityIfPresent() 只读 isLoaded() + 打一条日志，
        // 不碰游戏状态、没有线程亲和性要求。
        com.ae2vm.addon.compat.advancedae.AdvancedAECompat.logCompatibilityIfPresent();
    }

    private void checkBlockedMods() {
        String foundId = null;
        String foundName = null;
        try {
            List<ModContainer> active = Loader.instance().getActiveModList();
            if (active != null) {
                for (ModContainer container : active) {
                    String id = container == null ? null : container.getModId();
                    if (id != null && BLOCKED_MOD_IDS.contains(id)) {
                        foundId = id;
                        foundName = container.getName();
                        break;
                    }
                }
            }
        } catch (Throwable t) {
            LOGGER.warn("[AE2-VM] Could not scan mod list: {}", t.toString());
        }
        if (foundId != null) {
            if ("warn".equalsIgnoreCase(blockedMode)) {
                // 无针对检测版：只警告，不闪退
                LOGGER.warn("[AE2-VM] 检测到该作者的 mod '{}' ({}) —— 无针对检测版：仅警告，不闪退", foundId, foundName);
                LOGGER.warn("[AE2-VM] Detected blocked author mod '{}' ({}) — warn mode: NOT crashing.", foundId, foundName);
                return;
            }
            LOGGER.error("[AE2-VM] 检测到该作者的 mod '{}' ({}) —— 游戏闪退", foundId, foundName);
            LOGGER.error("[AE2-VM] Detected blocked author mod '{}' ({}) — crashing on purpose.", foundId, foundName);
            throw new RuntimeException(
                "AE2VMAddon refuses to run with mod '" + foundId + "' (" + foundName
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
                // (Java 8) InputStream#readAllBytes 是 Java 9+，必须自己读。
                String mode = readAll(in, StandardCharsets.UTF_8).trim();
                if ("crash".equalsIgnoreCase(mode) || "warn".equalsIgnoreCase(mode)) {
                    return mode.toLowerCase();
                }
            }
        } catch (Exception e) {
            LOGGER.warn("[AE2-VM] Could not read blockedmode.txt, defaulting to crash: {}", e.toString());
        }
        return "crash";
    }

    /** (Java 8) {@code InputStream#readAllBytes} 的等价实现。 */
    private static String readAll(InputStream in, java.nio.charset.Charset cs) throws java.io.IOException {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int r;
        while ((r = in.read(buf)) > 0) {
            bos.write(buf, 0, r);
        }
        return new String(bos.toByteArray(), cs);
    }
}
