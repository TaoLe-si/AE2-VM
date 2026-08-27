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

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();

        PROXY_ENABLED = builder
                .comment(
                        "是否启用 AE2-VM 代理（拦截 AE2 合成计算，用 VM 引擎替代递归计算）。默认 true。",
                        "Whether to enable the AE2-VM proxy (route AE2 crafting calculation through the VM engine). Default true.",
                        "修改后需要重启游戏生效（Requires Restart）。")
                .define("proxyEnabled", true);

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
}

