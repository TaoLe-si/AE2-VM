package com.ae2vm.addon.config;

import me.shedaniel.autoconfig.ConfigData;
import me.shedaniel.autoconfig.annotation.Config;
import me.shedaniel.autoconfig.annotation.ConfigEntry;

/**
 * AE2VM 配置数据（config/ae2vm.json），由 Cloth Config API（AutoConfig）读写。
 * 依赖 Cloth Config API 为可选：未安装该 mod 时本类不会被加载，回退到默认值。
 */
@Config(name = "ae2vm")
public class AE2VMConfigData implements ConfigData {
    /** 是否启用 AE2-VM 代理（拦截 AE2 合成计算，用 VM 引擎替代递归计算）。默认 true。 */
    @ConfigEntry.Gui.Tooltip
    @ConfigEntry.Gui.RequiresRestart
    public boolean proxyEnabled = true;
}
