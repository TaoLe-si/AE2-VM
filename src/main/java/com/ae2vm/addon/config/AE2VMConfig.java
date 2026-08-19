package com.ae2vm.addon.config;

import com.ae2vm.addon.AE2VMAddon;
import net.minecraftforge.fml.ModList;

/**
 * AE2VM 配置门面。
 * <p>
 * Cloth Config API 是<b>可选</b>依赖：
 * <ul>
 *   <li>安装了 Cloth Config API → 使用 config/ae2vm.json 中的 proxy.enabled（可在游戏内/Mod Menu 修改）；</li>
 *   <li>未安装 → 回退默认值（proxy 启用），不影响正常运行。</li>
 * </ul>
 */
public final class AE2VMConfig {
    private AE2VMConfig() {
    }

    /** 是否启用 AE2-VM 代理（拦截 AE2 合成计算）。默认 true。 */
    public static boolean isProxyEnabled() {
        try {
            if (!ModList.get().isLoaded("cloth_config")) {
                return true; // Cloth Config 未安装 → 默认启用
            }
            return AE2VMConfigImpl.getProxyEnabled();
        } catch (Throwable t) {
            return true; // 任何异常都回退到默认启用，保证不影响合成
        }
    }

    /** 尝试注册 Cloth Config 配置（仅在 Cloth Config 已加载时真正注册）。 */
    public static void tryRegister() {
        try {
            if (ModList.get().isLoaded("cloth_config")) {
                AE2VMConfigImpl.register();
            }
        } catch (Throwable t) {
            AE2VMAddon.LOGGER.warn("[AE2-VM] Cloth Config registration failed: {}", t.toString());
        }
    }
}
