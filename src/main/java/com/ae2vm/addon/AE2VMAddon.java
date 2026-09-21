package com.ae2vm.addon;

import com.ae2vm.addon.config.AE2VMConfig;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.forgespi.language.IModInfo;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

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
    // ⚠️ Logger 选型（MIGRATION-PATTERNS §6，每个 MC 版本都不同）：
    //   1.20.x  → com.mojang.logging.LogUtils.getLogger()      （launcher 自带 com.mojang:logging）
    //   1.18.x  → org.slf4j.LoggerFactory.getLogger(...)         （launcher 自带 slf4j-api + log4j-slf4j18-impl）
    //   1.16.5  → org.apache.logging.log4j.LogManager.getLogger(...)  ← 本 fork
    // 1.16.5 的 launcher libraries **没有 slf4j-api**（只有 log4j-slf4j18-impl 这个绑定器，
    // 不含 org.slf4j.LoggerFactory 类），沿用 1.17.1 的 SLF4J 写法会在 LOADING 阶段
    // 抛 ClassNotFoundException: org.slf4j.LoggerFactory 直接崩。1.16.5 只有 log4j-api:2.15.0，
    // 所以跟 1.10.2 fork 一样直连 log4j2。
    public static final Logger LOGGER = LogManager.getLogger(AE2VMAddon.class);

    /** 1.15.2 没有 FML 的 [[mixins]] 支持，这个配置由 {@link #registerMixinConfig()} 自己注册。 */
    private static final String MIXIN_CONFIG = "ae2vm.mixins.json";
    
    /**
     * Mods by this author (fish1145 / fish_dan — DataEnergistics family) that are NOT
     * allowed to run together with AE2VMAddon. When any of these is loaded, AE2VMAddon
     * deliberately crashes the game at startup. Remove any of these to continue.
     * Note: mekenergistics (通用数据 / Mek Energistics) and soulplied_energistics
     * (Soulplied Energistics, by Buuz135) are no longer blocked — they are compatible.
     */
    private static final Set<String> BLOCKED_MOD_IDS = new java.util.HashSet<>(java.util.Arrays.asList(
        "data_energistics"       // DataEnergistics — authors: fish_dan, QiuYe, TedXenon (confirmed)
    ));
    
    /** 运行模式: 'crash'(默认) → 检测到该作者 mod 游戏闪退；'warn' → 只警告不闪退。 */
    private final String blockedMode = readBlockedMode();
    
    public AE2VMAddon() {
        registerMixinConfig();
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();
        modEventBus.addListener(this::commonSetup);
        // 注册 COMMON 配置（TOML）：config/ae2vm-common.toml，Configured 可游戏内编辑
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, AE2VMConfig.COMMON_SPEC);
        
        checkBlockedMods(); // crash（或 warn）if a blocked author mod is loaded
        // 启动横幅日志已移除（v1.10.8）——仅保留计算耗时日志，见 CraftingServiceMixin / CraftingVM。
    }
    
    /**
     * Forge 1.15.2 没有原生 mixin 支持（mods.toml 的 {@code [[mixins]]} 是 1.16.1 才有的），
     * 所以 {@value #MIXIN_CONFIG} 得我们自己注册。时机只有这里可行：
     *
     * <p>mixin 0.8.2 的 modlauncher 实现全程用**线程上下文类加载器**取东西 ——
     * {@code MixinServiceModLauncher.getResourceAsStream} 读配置、
     * {@code MixinLaunchPlugin.getClassNode} 读 mixin 类的字节码。而启动期三个阶段
     * （onLoad / initialize / beginScanning）的 TCCL 实测全是
     * {@code sun.misc.Launcher$AppClassLoader}，两样都看不到本 mod jar（它只在
     * modlauncher 自己的 TransformerClassLoader 里），所以在启动服务里注册会炸：
     * <pre>
     * IllegalArgumentException: The specified resource 'ae2vm.mixins.json' was invalid or could not be read
     * InvalidMixinException: The specified mixin 'com.ae2vm.addon.mixin.CraftingGridCacheMixin' was not found
     * </pre>
     * 也别想用"自带 ITransformationService 在启动期引导"绕过去：Forge 1.15.2 的
     * {@code ModDirTransformerDiscoverer} 认领 mods 目录里带该 services 文件的 jar 之后，
     * {@code ModsFolderLocator} 会按 {@code allExcluded()} 把它**从 mod 加载里排除**，
     * @Mod 构造函数压根不跑（实测 debug.log 里 "Considering mod file candidate" 没有本 jar）。
     * mixin 的引导本身不需要我们操心：mixin-0.8.2.jar 自己声明了
     * {@code MixinTransformationService} + {@code MixinLaunchPlugin}，modlauncher 会发现。
     *
     * <p>到本构造函数这里，加载本 mod 的类加载器两样都看得到，所以把 TCCL 换成它。
     * mixin 的 PREPARE 是**惰性**的（推迟到下一个类被 transform 时），那时 TCCL 未必还是
     * 本 mod 的加载器，所以注册完立刻用一个 AE2 的 API 接口把 PREPARE 逼在当前 TCCL 里跑完：
     * {@code initialize=false} 不触发 AE2 的静态初始化，接口本身也无副作用，而
     * {@code CraftingGridCache} 这些真正的挂载点要等网络形成才加载，一定晚于这里。
     */
    private void registerMixinConfig() {
        ClassLoader modLoader = AE2VMAddon.class.getClassLoader();
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        thread.setContextClassLoader(modLoader);
        try {
            org.spongepowered.asm.launch.MixinBootstrap.init();
            org.spongepowered.asm.mixin.Mixins.addConfiguration(MIXIN_CONFIG);
            Class.forName("appeng.api.storage.data.IAEItemStack", false, modLoader);
            LOGGER.info("[AE2-VM] {} registered + mixin config prepared via {} (launcher TCCL={} "
                    + "could not see this jar)", MIXIN_CONFIG, modLoader, previous);
        } catch (Throwable t) {
            LOGGER.error("[AE2-VM] mixin config registration FAILED — VM will NOT hook crafting", t);
        } finally {
            thread.setContextClassLoader(previous);
        }
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
                // (Java 8) InputStream#readAllBytes 是 Java 9+；1.16.5 必须自己读。
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

    private void commonSetup(final FMLCommonSetupEvent event) {
        checkBlockedMods(); // re-check once the mod list is fully populated
        if (com.ae2vm.addon.config.AE2VMConfig.isDebugLogging()) {
        AE2VMAddon.LOGGER.info(
                "[AE2-VM] Config loaded from config/ae2vm-common.toml (proxy.enabled={}) — in-game editing via Configured (if installed)",
                AE2VMConfig.isProxyEnabled());
        }

        // AdvancedAE 兼容确认（只打印一次）：AdvancedAE 只接管 submitJob 的 CPU 分配层，
        // 我们的 beginCraftingCalculation 规划层仍由 VM 计算 —— 装了 AdvancedAE 也走我们的算法。
        // Forge 32.0.108（MC 1.16.1）的 FMLCommonSetupEvent 还没有 enqueueWork（1.16.4 的
        // 35.1.37 / 1.16.5 的 36.2.x 都有，那两个 fork 同一处就是走 event.enqueueWork 的）。
        // 这里直接同步调用：logCompatibilityIfPresent() 只读 isLoaded() + 打一条日志，
        // 不碰游戏状态、没有线程亲和性要求，行为与延迟到主线程等价。
        com.ae2vm.addon.compat.advancedae.AdvancedAECompat.logCompatibilityIfPresent();
    }
}
