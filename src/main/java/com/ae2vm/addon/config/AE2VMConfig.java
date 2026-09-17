package com.ae2vm.addon.config;

import net.minecraftforge.common.ForgeConfigSpec;

/**
 * AE2VM 配置（Forge {@link ForgeConfigSpec} → {@code config/ae2vm-common.toml}）。
 *
 * <p>配置迁移说明：旧版使用 Cloth Config API（AutoConfig + Gson）读写
 * {@code config/ae2vm.json}；现改为 Forge 原生 TOML 配置：
 * <ul>
 *   <li>配置文件：{@code config/ae2vm-common.toml}（COMMON 分类，客户端/服务端共用）；</li>
 *   <li>Configured（Forge 版）会自动扫描本 mod 注册的 {@code ModConfig}，在游戏内
 *       「Mod Configuration」界面直接编辑本配置，无需任何额外适配代码；</li>
 *   <li>未安装 Configured 时配置仍然生效（首次启动自动生成 TOML，可手动编辑）。</li>
 * </ul>
 */
public final class AE2VMConfig {

    /** 已构建的 COMMON 配置规格，注册于 mod 构造函数（{@code ModLoadingContext.registerConfig}）。 */
    public static final ForgeConfigSpec COMMON_SPEC;

    /** 是否启用 AE2-VM 代理（拦截 AE2 合成计算，用 VM 引擎替代递归计算）。默认 true。 */
    public static final ForgeConfigSpec.BooleanValue PROXY_ENABLED;

    /** 是否输出调试日志（被 if (isDebugLogging()) 包裹的额外 LOGGER.info 调用）。默认 false。 */
    public static final ForgeConfigSpec.BooleanValue DEBUG_LOGGING;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();

        PROXY_ENABLED = builder
                .comment(
                        "是否启用 AE2-VM 代理（拦截 AE2 合成计算，用 VM 引擎替代递归计算）。默认 true。",
                        "Whether to enable the AE2-VM proxy (route AE2 crafting calculation through the VM engine). Default true.",
                        "修改后需要重启游戏生效（Requires Restart）。")
                .define("proxyEnabled", true);

        DEBUG_LOGGING = builder
                .comment(
                        "是否输出调试日志（被 if (isDebugLogging()) 包裹的额外 LOGGER.info 调用）。默认 false。",
                        "Output debug-level log lines (every LOGGER.info call wrapped in if (isDebugLogging())). Default false.",
                        "修改后需要重启游戏生效（Requires Restart）。",
                        "实际上只是输出额外的调试信息，不影响性能（未启用时跳过日志调用，几乎零开销）。")
                .define("debugLogging", false);

        COMMON_SPEC = builder.build();
    }

    private AE2VMConfig() {
    }

    /** 是否启用 AE2-VM 代理。任何异常都回退到默认启用，保证不影响合成。 */
    public static boolean isProxyEnabled() {
        try {
            return PROXY_ENABLED.get();
        } catch (Throwable t) {
            return true; // 任何异常都回退到默认启用，保证不影响合成
        }
    }

    /**
     * 是否输出调试日志。默认 false。调用方可以用 {@code if (isDebugLogging())} 包裹 LOGGER.info 调用，
     * 当且仅当 toml 设 debugLogging=true 时才会真正输出。
     * <p>任何异常都回退到默认 false，保证默认不输出调试日志。
     */
    public static boolean isDebugLogging() {
        try {
            return DEBUG_LOGGING.get();
        } catch (Throwable t) {
            return false; // 默认不输出调试
        }
    }
}

