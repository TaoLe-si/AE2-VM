package com.ae2vm.addon.config;

import net.minecraftforge.common.config.Configuration;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;

/**
 * AE2VM 配置（MC 1.12.2 版：Forge 原生 {@link Configuration} → {@code config/AE2VM.cfg}）。
 *
 * <p>1.16+ 各 fork（含本 fork 的代码基座 1.15.2）用的是 {@code ForgeConfigSpec} + TOML
 * （{@code config/ae2vm-common.toml}）+ {@code ModLoadingContext.registerConfig}，
 * 那套在 1.12.2 完全不存在（1.13+ 才有）。这里换成 1.12.2 的标准做法：在
 * {@code FMLPreInitializationEvent} 里 {@code new Configuration(event.getSuggestedConfigurationFile())}
 * 然后 load / save。
 *
 * <p>对外门面签名与 1.16+ 各 fork 保持一致（{@link #isProxyEnabled()} / {@link #isDebugLogging()}），
 * 所以 mixin 与主类的调用点不用改。1.12.2 没有 Configured / Cloth Config 这类游戏内配置界面，
 * 改配置是直接编辑 cfg 后重启（这一点与 1.10.2 fork 的"固定常量"做法相比保留了可配置能力）。
 */
public final class AE2VMConfig {

    private static final Logger LOGGER = LogManager.getLogger("AE2-VM-Config");
    private static final String CATEGORY = Configuration.CATEGORY_GENERAL;

    /** load 之前也必须是安全默认值：mixin 可能在 preInit 之前就读这两个开关。 */
    private static volatile boolean proxyEnabled = true;
    private static volatile boolean debugLogging = false;

    private AE2VMConfig() {
    }

    /**
     * 读取（并在需要时生成）配置文件。任何异常都退回默认值，绝不影响合成。
     *
     * @param file 一般传 {@code FMLPreInitializationEvent#getSuggestedConfigurationFile()}
     */
    public static void load(File file) {
        try {
            Configuration configuration = new Configuration(file);
            configuration.load();
            proxyEnabled = configuration.getBoolean("proxyEnabled", CATEGORY, true,
                    "是否启用 AE2-VM 代理（拦截 AE2 合成计算，用 VM 引擎替代递归计算）。默认 true。"
                    + " 修改后需要重启游戏生效 (Requires restart).");
            debugLogging = configuration.getBoolean("debugLogging", CATEGORY, false,
                    "是否输出调试日志（被 if (isDebugLogging()) 包裹的额外 LOGGER 调用）。默认 false。"
                    + " 只是多打调试信息，不影响性能。修改后需要重启游戏生效 (Requires restart).");
            if (configuration.hasChanged()) {
                configuration.save();
            }
        } catch (Throwable t) {
            proxyEnabled = true;
            debugLogging = false;
            LOGGER.warn("[AE2-VM] 读取 config/AE2VM.cfg 失败，回退默认（proxyEnabled=true, debugLogging=false）: {}",
                    t.toString());
        }
    }

    /** 是否启用 AE2-VM 代理。默认 true，保证读不到配置时行为不变。 */
    public static boolean isProxyEnabled() {
        return proxyEnabled;
    }

    /**
     * 是否输出调试日志。默认 false（配置文件读不出来时也是 false）。
     * 调用方用 {@code if (isDebugLogging())} 包裹额外的 LOGGER 调用。
     */
    public static boolean isDebugLogging() {
        return debugLogging;
    }
}
