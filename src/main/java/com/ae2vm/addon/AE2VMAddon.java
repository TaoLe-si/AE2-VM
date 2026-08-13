package com.ae2vm.addon;

import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.ModContainer;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.HashSet;

/**
 * AE2 VM Crafting Accelerator - Main Mod Class (MC 1.10.2 / AE2 rv4 port).
 *
 * <p>Replaces AE2's recursive crafting calculation with a stack-based virtual machine.
 *
 * <p>PERFORMANCE IMPROVEMENTS:
 * <ol>
 *   <li>Patterns are compiled ONCE to flat bytecode (no per-request recursion)</li>
 *   <li>Execution is simple linear bytecode interpretation (O(n) not O(recursion depth))</li>
 *   <li>No stack overflow for deep crafting trees (37+ patterns deep)</li>
 *   <li>No repeated pattern lookups during calculation</li>
 *   <li>VM instances are cached and reused</li>
 * </ol>
 *
 * <p>1.10.2 notes: loaded via a legacy Mixin coremod ({@code AE2VMCoreMod}); AE2 rv4
 * has no {@code beginCraftingCalculation} — the hook lives in
 * {@code appeng.me.cache.CraftingGridCache} (see {@code CraftingGridCacheMixin}).
 */
@Mod(modid = AE2VMAddon.MOD_ID, name = AE2VMAddon.MOD_NAME, version = AE2VMAddon.VERSION,
        dependencies = "required-after:appliedenergistics2")
public class AE2VMAddon {
    public static final String MOD_ID = "ae2vm";
    public static final String MOD_NAME = "AE2 VM";
    public static final String VERSION = "1.10.8";
    public static final Logger LOGGER = LogManager.getLogger(MOD_NAME);

    /**
     * Mods by this author (fish1145 / fish_dan — DataEnergistics family) that are NOT
     * allowed to run together with AE2VMAddon. When any of these is loaded, AE2VMAddon
     * deliberately crashes the game at startup. Remove any of these to continue.
     */
    private static final Set<String> BLOCKED_MOD_IDS = new HashSet<>();
    static {
        BLOCKED_MOD_IDS.add("data_energistics");
    }

    /** 运行模式: 'crash'(默认) → 检测到该作者 mod 游戏闪退；'warn' → 只警告不闪退。 */
    private final String blockedMode = readBlockedMode();

    public AE2VMAddon() {
        checkBlockedMods(); // crash（或 warn）if a blocked author mod is loaded
    }

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        checkBlockedMods(); // re-check once the mod list is fully populated
        com.ae2vm.addon.config.AE2VMConfig.tryRegister(); // no-op on 1.10.2 (no Cloth Config)
    }

    /**
     * Scans all installed mods; if any belongs to the blocked author, deliberately
     * crashes the game (闪退). Runs at mod load and again during pre-init.
     */
    private void checkBlockedMods() {
        String foundId = null;
        String foundName = null;
        try {
            for (String id : BLOCKED_MOD_IDS) {
                if (Loader.instance().isModLoaded(id)) {
                    foundId = id;
                    for (ModContainer mc : Loader.instance().getActiveModList()) {
                        if (id.equals(mc.getModId())) {
                            foundName = mc.getName();
                            break;
                        }
                    }
                    break;
                }
            }
        } catch (Throwable t) {
            LOGGER.warn("[AE2-VM] Could not scan mod list: {}", t.toString());
        }
        if (foundId != null) {
            String name = foundName != null ? foundName : foundId;
            if ("warn".equalsIgnoreCase(blockedMode)) {
                // 无针对检测版：只警告，不闪退
                LOGGER.warn("[AE2-VM] 检测到该作者的 mod '{}' ({}) —— 无针对检测版：仅警告，不闪退", foundId, name);
                LOGGER.warn("[AE2-VM] Detected blocked author mod '{}' ({}) — warn mode: NOT crashing.", foundId, name);
                return;
            }
            LOGGER.error("[AE2-VM] 检测到该作者的 mod '{}' ({}) —— 游戏闪退", foundId, name);
            LOGGER.error("[AE2-VM] Detected blocked author mod '{}' ({}) — crashing on purpose.", foundId, name);
            throw new RuntimeException(
                    "AE2VMAddon refuses to run with mod '" + foundId + "' (" + name
                            + ") — mods by this author (fish1145/fish_dan) are incompatible. Remove the mod and restart.");
        }
    }

    /**
     * 从打包进 jar 的 /ae2vm/blockedmode.txt 读取运行模式（crash/warn），
     * 由构建时 -PblockedMode=... 决定，默认 crash。故意闪退的是游戏运行时，不是编译器。
     */
    private static String readBlockedMode() {
        InputStream in = AE2VMAddon.class.getResourceAsStream("/ae2vm/blockedmode.txt");
        if (in != null) {
            try {
                java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
                byte[] chunk = new byte[4096];
                int read;
                while ((read = in.read(chunk)) != -1) {
                    buffer.write(chunk, 0, read);
                }
                String mode = new String(buffer.toByteArray(), StandardCharsets.UTF_8).trim();
                if ("crash".equalsIgnoreCase(mode) || "warn".equalsIgnoreCase(mode)) {
                    return mode.toLowerCase();
                }
            } catch (Exception e) {
                LOGGER.warn("[AE2-VM] Could not read blockedmode.txt, defaulting to crash: {}", e.toString());
            } finally {
                try {
                    in.close();
                } catch (Exception ignored) {
                }
            }
        }
        return "crash";
    }
}
