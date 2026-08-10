package com.ae2vm.addon.mixin;

import appeng.api.crafting.IPatternDetails;
import appeng.helpers.patternprovider.PatternProviderLogic;
import com.ae2vm.addon.AE2VMAddon;
import com.ae2vm.addon.compiler.IFiniteUseInput;
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
      // 配置开关：proxy.enabled=false 时跳过模式预编译（VM 代理整体禁用）
      if (!com.ae2vm.addon.config.AE2VMConfig.isProxyEnabled()) {
         return;
      }
      if (this.patterns != null && !this.patterns.isEmpty()) {
         int compiledCount = 0;

         for (IPatternDetails pattern : this.patterns) {
            // (v1.10.8) UselessMod 翻倍功能：ScaledProcessingPattern 是运行时虚拟包装器，
            // 需要解包获取原始样板进行编译。unwrap() 会递归解包嵌套的翻倍包装器。
            IPatternDetails targetPattern = pattern;
            boolean isScaled = isScaledPattern(pattern);
            
            if (isScaled) {
               targetPattern = unwrapScaledPattern(pattern);
               
               // 关键修复：必须先编译原始样板，因为 VM 执行时会查找原始输出的模式
               // 如果只编译翻倍包装器，AE2 的 service.getCraftingFor(key) 可能找到的是翻倍包装器
               // 而翻倍包装器的 getDefinition() 返回的是缩放后的定义，导致匹配失败
               if (targetPattern != null && PatternCompiler.getCompiled(targetPattern) == null) {
                  PatternCompiler.compileIfAbsent(targetPattern);
                  compiledCount++;
               }
               
               // 同时也编译翻倍包装器本身（如果尚未编译），确保 VM 执行时能找到它
               if (pattern != null && PatternCompiler.getCompiled(pattern) == null) {
                  PatternCompiler.compileIfAbsent(pattern);
                  compiledCount++;
               }
            } else {
               // 普通样板直接编译
               if (PatternCompiler.getCompiled(pattern) == null) {
                  PatternCompiler.compileIfAbsent(pattern);
                  compiledCount++;
               }
            }
         }

         if (compiledCount > 0) {
            // AE2VMAddon.LOGGER.info("[AE2-VM] Compiled {} new pattern(s) to bytecode (total: {} cached)", compiledCount, PatternCompiler.getCompiledCount());
         }
      }
   }

   /**
    * 检测是否为 UselessMod 的翻倍包装器 ScaledProcessingPattern。
    * 这些是运行时虚拟样板，包装原始样板并缩放输入/输出数量。
    */
   private boolean isScaledPattern(IPatternDetails pattern) {
      if (pattern == null) {
         return false;
      }
      // UselessMod 的翻倍包装器类名包含 "ScaledProcessingPattern"
      String className = pattern.getClass().getName();
      return className.contains("ScaledProcessingPattern");
   }

   /**
    * 递归解包翻倍包装器，获取原始样板。
    * UselessMod 的 ScaledProcessingPattern 有 getOriginal() 方法返回原始样板。
    */
   private IPatternDetails unwrapScaledPattern(IPatternDetails pattern) {
      if (pattern == null) {
         return null;
      }
      // 尝试通过反射调用 getOriginal() 方法
      try {
         var method = pattern.getClass().getMethod("getOriginal");
         IPatternDetails original = (IPatternDetails) method.invoke(pattern);
         // 递归解包嵌套的翻倍包装器
         if (original != null && isScaledPattern(original)) {
            return unwrapScaledPattern(original);
         }
         return original != null ? original : pattern;
      } catch (Exception e) {
         // 如果没有 getOriginal() 方法或调用失败，返回原样板
         return pattern;
      }
   }
}
