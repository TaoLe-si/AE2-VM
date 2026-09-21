package com.ae2vm.addon.mixin;

import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import appeng.api.networking.crafting.ICraftingPatternDetails;
import appeng.helpers.DualityInterface;

import com.ae2vm.addon.compiler.PatternCompiler;
import com.ae2vm.addon.config.AE2VMConfig;
import com.ae2vm.addon.v8.V8PatternDetails;

/**
 * AE2 v8 (1.16.5) pattern-refresh hook.
 * <p>
 * v10+ has {@code PatternProviderLogic} and v9 has {@code DualityPatternProvider}; on v8 the
 * pattern provider logic lives in {@code DualityInterface} (the ME interface itself holds the
 * patterns), refreshed by {@code updateCraftingList()}. Same purpose as the newer forks: bump
 * the global pattern version so reused {@code CraftingVM} instances drop their stale JIT
 * bundles, and pre-compile the new patterns to bytecode.
 */
@Mixin(value = DualityInterface.class, remap = false)
public abstract class DualityInterfaceMixin {

    /**
     * ⚠ 类型必须与目标字段**同时**按名字+描述符匹配，只按名字核是查不出来的。
     * rv4(1.10.2) 的 {@code DualityInterface.craftingList} 是
     * {@code private List<ICraftingPatternDetails>}；基座 nova(1.12.2 uel) 那里才是
     * {@code Set}，AE2 v8(1.16) 又是 List —— 每个版本都要 javap 一次，别照抄兄弟版。
     * 写错的实测崩溃：InvalidMixinException: @Shadow field craftingList was not located in
     * the target class appeng.helpers.DualityInterface（随后级联成 NoClassDefFoundError，
     * 表面看像是 AE2 自己崩了）。且 ae2vm.mixins.json 里 required=true + defaultRequire=1
     * → 这是**加载期 fatal**，不是运行期降级。
     */
    @Shadow
    private List<ICraftingPatternDetails> craftingList;

    @Inject(method = "updateCraftingList", at = @At("TAIL"))
    private void vmUpdateCraftingList(CallbackInfo ci) {
        PatternCompiler.bumpPatternVersion();

        if (!AE2VMConfig.isProxyEnabled()) {
            return;
        }
        if (this.craftingList == null || this.craftingList.isEmpty()) {
            return;
        }

        int compiledCount = 0;
        for (ICraftingPatternDetails p : this.craftingList) {
            if (p == null) {
                continue;
            }
            V8PatternDetails wrapped = new V8PatternDetails(p);
            if (PatternCompiler.getCompiled(wrapped) == null) {
                PatternCompiler.compileIfAbsent(wrapped);
                compiledCount++;
            }
        }
        if (compiledCount > 0 && com.ae2vm.addon.config.AE2VMConfig.isDebugLogging()) {
            com.ae2vm.addon.AE2VMAddon.LOGGER.info("[AE2-VM] compiled " + compiledCount + " pattern(s) on v8 interface refresh");
        }
    }
}
