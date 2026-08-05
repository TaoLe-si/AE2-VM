package com.ae2vm.addon.config;

import me.shedaniel.autoconfig.AutoConfig;
import me.shedaniel.autoconfig.serializer.GsonConfigSerializer;

/**
 * Cloth Config（AutoConfig）实现。
 * 注意：本类引用了 Cloth Config 的类，只有在 Cloth Config API 已安装时才允许被加载/调用；
 * 调用方必须先用 ModList.isLoaded("cloth_config") 判断，否则会抛 NoClassDefFoundError。
 */
public final class AE2VMConfigImpl {
    private static boolean registered = false;

    private AE2VMConfigImpl() {
    }

    /** 注册配置（幂等）。会自动创建/读取 config/ae2vm.json。 */
    public static synchronized void register() {
        if (registered) {
            return;
        }
        registered = true;
        AutoConfig.register(AE2VMConfigData.class, GsonConfigSerializer::new);
    }

    /** 读取当前是否启用代理。 */
    public static boolean getProxyEnabled() {
        register();
        return AutoConfig.getConfigHolder(AE2VMConfigData.class).getConfig().proxyEnabled;
    }
}
