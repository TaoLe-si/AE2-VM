package com.ae2vm.addon.mixin;

import appeng.api.crafting.IPatternDetails;
import appeng.helpers.patternprovider.PatternProviderLogic;
import com.ae2vm.addon.AE2VMAddon;
import com.ae2vm.addon.compiler.PatternCompiler;
import java.util.List;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(
   value = {PatternProviderLogic.class},
   remap = false
)
public abstract class PatternProviderLogicMixin {
   @Shadow
   private List<IPatternDetails> patterns;

   @Inject(
      method = {"updatePatterns"},
      at = {@At("TAIL")}
   )
   private void onUpdatePatterns(CallbackInfo ci) {
      // (v1.11.x PATTERN-REFRESH) The provider's pattern set changed: bump the global
      // pattern version so every reused CraftingVM drops its stale JIT bundleCache on its
      // next execute() — otherwise an intermediate key captured as a missing leaf while
      // it had no pattern keeps reporting missing even after the pattern is added (the
      // "新样板作为中间产物识别不到" report; also present on 1.21.1).
      PatternCompiler.bumpPatternVersion();
      // 配置开关：proxy.enabled=false 时跳过模式预编译（VM 代理整体禁用）
      if (!com.ae2vm.addon.config.AE2VMConfig.isProxyEnabled()) {
         return;
      }
      if (this.patterns != null && !this.patterns.isEmpty()) {
         int compiledCount = 0;

         for (IPatternDetails pattern : this.patterns) {
            if (PatternCompiler.getCompiled(pattern) == null) {
               PatternCompiler.compileIfAbsent(pattern);
               compiledCount++;
            }
         }

         if (compiledCount > 0) {
            // AE2VMAddon.LOGGER.info("[AE2-VM] Compiled {} new pattern(s) to bytecode (total: {} cached)", compiledCount, PatternCompiler.getCompiledCount());
         }
      }
   }
}
