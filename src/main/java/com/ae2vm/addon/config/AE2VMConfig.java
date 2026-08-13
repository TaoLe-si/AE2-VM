package com.ae2vm.addon.config;

/**
 * AE2VM 配置门面（MC 1.10.2 / AE2 rv4 端口）。
 *
 * <p>1.10.2 没有 Cloth Config API，因此本端口不再提供游戏内/JSON 配置面板。
 * 代理开关固定为启用（{@code proxy.enabled = true}）；如需禁用 VM 代理，可修改
 * {@link #PROXY_ENABLED} 后重新编译。保留与原 1.20.1 版相同的门面方法签名，使
 * mixin / 主类无需改动调用方式。
 */
public final class AE2VMConfig {

    /** 是否启用 AE2-VM 代理（拦截 AE2 合成计算）。默认 true。 */
    private static final boolean PROXY_ENABLED = true;

    private AE2VMConfig() {
    }

    /** 是否启用 AE2-VM 代理（拦截 AE2 合成计算，用 VM 引擎替代递归计算）。 */
    public static boolean isProxyEnabled() {
        return PROXY_ENABLED;
    }

    /** 占位：1.10.2 无 Cloth Config，不注册任何配置。 */
    public static void tryRegister() {
        // no-op on 1.10.2
    }
}
