package com.ae2vm.addon.compiler;

import appeng.api.crafting.IPatternDetails;
import appeng.api.crafting.IPatternDetails.IInput;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import com.ae2vm.addon.AE2VMAddon;
import com.ae2vm.addon.vm.CraftingBytecode;
import com.ae2vm.addon.vm.Opcode;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.resources.ResourceLocation;

public class PatternCompiler {
   private static final Map<IPatternDetails, CraftingBytecode> COMPILED_PATTERNS = new ConcurrentHashMap<>();

   public static void compileIfAbsent(IPatternDetails pattern) {
      if (pattern != null) {
         COMPILED_PATTERNS.computeIfAbsent(pattern, PatternCompiler::compilePattern);
      }
   }

   public static CraftingBytecode getCompiled(IPatternDetails pattern) {
      return COMPILED_PATTERNS.get(pattern);
   }

   public static CraftingBytecode compileRequest(IPatternDetails pattern, long requestedAmount) {
      CraftingBytecode patternBytecode = COMPILED_PATTERNS.get(pattern);
      if (patternBytecode == null) {
         compileIfAbsent(pattern);
         patternBytecode = COMPILED_PATTERNS.get(pattern);
         if (patternBytecode == null) {
            throw new IllegalStateException("Failed to compile pattern: " + pattern);
         }
      }

      long outputPerCraft = patternBytecode.getOutputAmountPerCraft();
      long craftTimes = (requestedAmount + outputPerCraft - 1L) / outputPerCraft;
      CraftingBytecode.Builder builder = new CraftingBytecode.Builder();
      int outputIdx = builder.addConstant(patternBytecode.getOutput());
      builder.setOutput(outputIdx, requestedAmount);
      int patternIdx = builder.addPattern(pattern);
      builder.emitPushLong(craftTimes);
      builder.emit(Opcode.CALL);
      builder.emitShort(patternIdx);
      return builder.build();
   }

   private static CraftingBytecode compilePattern(IPatternDetails pattern) {
      CraftingBytecode.Builder builder = new CraftingBytecode.Builder();
      GenericStack primaryOutput = pattern.getPrimaryOutput();
      AEKey outputKey = primaryOutput.what();
      long outputPerCraft = primaryOutput.amount();
      int outputIdx = builder.addConstant(outputKey);
      int patternIdx = builder.addPattern(pattern);
      builder.setOutput(outputIdx, outputPerCraft);
      AE2VMAddon.LOGGER
         .info(
            "[AE2-VM] Compiling pattern: {} x {} ({} inputs, {} outputs)",
            new Object[]{outputPerCraft, outputKey, pattern.getInputs().length, pattern.getOutputs().size()}
         );
      builder.emit(Opcode.DUP);
      builder.emitRecordPattern(patternIdx);

      for (IInput inputEntry : pattern.getInputs()) {
         GenericStack[] possibleInputs = inputEntry.getPossibleInputs();
         if (possibleInputs.length != 0) {
            GenericStack inputStack = possibleInputs[0];
            AEKey inputKey = inputStack.what();
            long multiplier = inputEntry.getMultiplier();
            AE2VMAddon.LOGGER
               .info(
                  "[AE2-VM]   Input: key={}, stackAmt={}, multiplier={}, totalPerCraft={}", new Object[]{inputKey, inputStack.amount(), multiplier, multiplier}
               );
            int inputKeyIdx = builder.addConstant(inputKey);
            builder.emit(Opcode.DUP);
            builder.emitPushLong(multiplier);
            builder.emit(Opcode.MUL);
            builder.emitExtractIngredient(inputKeyIdx);
            builder.emit(Opcode.DUP);
            int inputKeyIdx2 = builder.addConstant(inputKey);
            builder.emitCallByKey(inputKeyIdx2);
            builder.emitExtractIngredient(inputKeyIdx);
            builder.emit(Opcode.POP);
         }
      }

      for (GenericStack output : pattern.getOutputs()) {
         int outIdx = builder.addConstant(output.what());
         builder.emit(Opcode.DUP);
         builder.emitPushLong(output.amount());
         builder.emit(Opcode.MUL);
         builder.emitInsertOutput(outIdx);
      }

      builder.emit(Opcode.POP);
      builder.emit(Opcode.RETURN);
      return builder.build();
   }

   public static void invalidate(IPatternDetails pattern) {
      COMPILED_PATTERNS.remove(pattern);
   }

   public static void clearCache() {
      COMPILED_PATTERNS.clear();
   }

   public static int getCompiledCount() {
      return COMPILED_PATTERNS.size();
   }

   public static IPatternDetails findCompiledByOutput(AEKey outputKey) {
      if (outputKey != null && outputKey.getId() != null) {
         String targetId = outputKey.getId().toString();

         for (Entry<IPatternDetails, CraftingBytecode> entry : COMPILED_PATTERNS.entrySet()) {
            GenericStack patternOutput = entry.getKey().getPrimaryOutput();
            if (patternOutput != null && patternOutput.what() != null) {
               ResourceLocation patternId = patternOutput.what().getId();
               if (patternId != null && targetId.equals(patternId.toString())) {
                  return entry.getKey();
               }
            }

            for (GenericStack out : entry.getKey().getOutputs()) {
               if (out != null && out.what() != null && out.what().getId() != null && targetId.equals(out.what().getId().toString())) {
                  return entry.getKey();
               }
            }
         }

         return null;
      } else {
         return null;
      }
   }

   // --- Network-key overloads (1.0.0 logic uses a single global cache, so the network key is ignored). ---
   public static void compileIfAbsent(Object network, IPatternDetails pattern) {
      compileIfAbsent(pattern);
   }

   public static CraftingBytecode getCompiled(Object network, IPatternDetails pattern) {
      return getCompiled(pattern);
   }

   public static CraftingBytecode compileRequest(Object network, IPatternDetails pattern, long requestedAmount) {
      return compileRequest(pattern, requestedAmount);
   }

   public static IPatternDetails findCompiledByOutput(Object network, AEKey outputKey) {
      return findCompiledByOutput(outputKey);
   }
}