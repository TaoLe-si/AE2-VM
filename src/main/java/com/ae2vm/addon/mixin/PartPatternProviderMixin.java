package com.ae2vm.addon.mixin;

import appeng.api.networking.crafting.ICraftingPatternDetails;
import appeng.helpers.DualityInterface;
import com.ae2vm.addon.api.Rv4PatternDetails;
import com.ae2vm.addon.compiler.PatternCompiler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * Pre-compiles every pattern held by an ME Interface (the rv4 pattern provider) to VM
 * bytecode as soon as the interface re-scans its encoded patterns.
 *
 * <p><b>rv4 note:</b> the 1.20.1 target {@code PatternProviderLogic} does not exist in
 * rv4 — pattern storage lives in {@code appeng.helpers.DualityInterface} (the shared
 * backend of both the ME Interface part {@code PartInterface} and the Interface block
 * {@code TileInterface}). The compiled patterns are held in the private field
 * {@code List<ICraftingPatternDetails> craftingList}, re-scanned by the private method
 * {@code updateCraftingList()}. The mixin class name is kept as
 * {@code PartPatternProviderMixin} to match {@code ae2vm.mixins.json}; only the
 * {@code @Mixin(value)} target differs.
 */
@Mixin(value = DualityInterface.class, remap = true)
public abstract class PartPatternProviderMixin {
    @Shadow
    private List<ICraftingPatternDetails> craftingList;

    @Inject(method = "updateCraftingList", at = @At("TAIL"))
    private void onUpdateCraftingList(CallbackInfo ci) {
        // 配置开关：proxy.enabled=false 时跳过模式预编译（VM 代理整体禁用）。
        if (!com.ae2vm.addon.config.AE2VMConfig.isProxyEnabled()) {
            return;
        }
        if (this.craftingList != null && !this.craftingList.isEmpty()) {
            for (ICraftingPatternDetails pattern : this.craftingList) {
                Rv4PatternDetails wrapped = new Rv4PatternDetails(pattern);
                if (PatternCompiler.getCompiled(wrapped) == null) {
                    PatternCompiler.compileIfAbsent(wrapped);
                }
            }
        }
    }
}
