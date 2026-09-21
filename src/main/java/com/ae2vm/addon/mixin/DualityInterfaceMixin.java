package com.ae2vm.addon.mixin;

import java.util.Set;

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
     * ⚠ 类型必须是 Set：uel(1.12.2) 的 DualityInterface 里这个字段是
     * {@code private Set<ICraftingPatternDetails> craftingList}，而 AE2 v8(1.16) 是 List。
     * mixin 的 @Shadow 要**名字+描述符同时**匹配，只按名字核是查不出来的 ——
     * 实测崩溃：InvalidMixinException: @Shadow field craftingList was not located in the
     * target class appeng.helpers.DualityInterface（随后级联成 NoClassDefFoundError，
     * 表面看像是 AE2 自己崩了）。
     */
    @Shadow
    private Set<ICraftingPatternDetails> craftingList;

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
