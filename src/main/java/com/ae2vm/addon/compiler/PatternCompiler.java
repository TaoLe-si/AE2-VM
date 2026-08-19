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

   /**
    * (v1.11.x PATTERN-REFRESH) Monotonic version of the network's pattern set. Bumped
    * whenever a pattern provider's {@code updatePatterns} runs (a player ADDS / REMOVES /
    * MODIFIES a pattern in a provider). CraftingVM's JIT {@code bundleCache} persists
    * across requests on a reused VM; a bundle captured while an intermediate key had NO
    * pattern records that key as a capture-time {@code missing} leaf. If the player then
    * adds that pattern, the stale bundle would keep reporting the intermediate as missing
    * until a restart (the 1.20.1 "新样板作为中间产物识别不到" report). Each VM checks this
    * version at {@code execute()} and drops its JIT memo when it changed, so the next
    * request re-captures the affected chains and recognises the new pattern.
    */
   private static volatile long patternVersion = 0;

   /** Current pattern-set version (monotonic). */
   public static long patternVersion() {
      return patternVersion;
   }

   /** Mark the network pattern set as changed (call from pattern-update entry points). */
   public static void bumpPatternVersion() {
      patternVersion++;
      // (v1.11.x DIAG) Log when pattern version bumps — if this never fires after a
      // pattern is added, the mixin is not being applied or the target method is wrong.
      AE2VMAddon.LOGGER.info("[AE2-VM] bumpPatternVersion: {} -> {}", patternVersion - 1, patternVersion);
   }

   /**
    * Fuzzy / fluid-substitution groups (v1.9.13): pattern inputs whose
    * {@code getPossibleInputs()} returns MORE than one variant — i.e. the encoded
    * pattern has item-replacement (物品替换) or fluid-replacement (流体替换) enabled.
    * Each group maps every variant key to the full set of acceptable variants, so the
    * VM's missing-check can see that e.g. gray wool can be satisfied by white wool
    * stock. Groups are registered from {@link #registerFuzzyGroups(IPatternDetails)},
    * which {@link #compilePattern(IPatternDetails)} calls for every compiled pattern,
    * and are consumed by {@code CraftingVM}'s aggregation (fuzzy-group stock) and the
    * no-sub-pattern leaf check.
    */
   private static final Map<AEKey, java.util.Set<AEKey>> FUZZY_GROUPS = new ConcurrentHashMap<>();

   /**
    * Processing-recipe input keys (v1.10.x). Processing recipes (处理配方) default to
    * FUZZY matching: a stored variant of the input item (same item, any NBT/damage —
    * {@code FuzzyMode.IGNORE_ALL}) satisfies the slot, even though AE2's
    * {@code AEProcessingPattern} encodes each input as a single exact variant with no
    * substitution flag. This mirrors AE2 native's inventory-level lookup
    * ({@code CraftingCpuHelper.getValidItemTemplates} → {@code findFuzzyTemplates}),
    * which the VM was missing — it treated processing inputs as exact and reported
    * false "missing" (GTL greenhouse fake-craft / Mystical Agriculture essence:
    * "材料缺失但不知道哪里缺失", "有方块却报缺失"). Keys are collected from every
    * processing pattern at compile time and consumed by {@code CraftingVM}'s
    * missing-check / stock-aware aggregation / extraction.
    */
   private static final java.util.Set<AEKey> PROCESSING_INPUT_KEYS = ConcurrentHashMap.newKeySet();

   /**
    * (v1.10.x PRODUCTIVE_BEES) EXACT processing-recipe input keys. AE2's native
    * {@code AEProcessingPattern} encodes each input as a single EXACT variant and its
    * {@code IInput.isValid} is {@code input.matches(template[0])} = exact {@code equals}
    * (see CraftingCpuHelper.getValidItemTemplates, which filters every
    * {@code findFuzzyTemplates} NBT variant through {@code isValid} — only the encoded
    * exact variant passes). So for AE2-native processing patterns a different-NBT variant
    * of the same item (e.g. Productive Bees honeycombs with different {@code bee_type}
    * components) must NOT satisfy the slot — using the wrong honeycomb variant would
    * consume the wrong raw material. Only third-party patterns whose {@code isValid}
    * genuinely accepts variants (GTL greenhouse, MA essence, UselessMod omniversal) keep
    * the v1.10.x default-fuzzy behaviour via {@link #PROCESSING_INPUT_KEYS}.
    * <p>Keys are moved here (from the default-fuzzy set) by
    * {@link #registerFuzzyGroups(IPatternDetails)} for AE2-native patterns, or registered
    * directly by callers via {@link #registerExactProcessingInput(AEKey)}.
    */
   private static final java.util.Set<AEKey> EXACT_PROCESSING_KEYS = ConcurrentHashMap.newKeySet();

   /** True for patterns that are NOT molecular-assembler crafting patterns. */
   private static boolean isProcessingPattern(IPatternDetails pattern) {
      if (pattern == null) {
         return false;
      }
      // Crafting patterns (AECraftingPattern) run in the ME molecular assembler and
      // implement IMolecularAssemblerSupportedPattern; everything else (AE processing
      // patterns, custom machine patterns like GTL's fake-craft) is a processing recipe.
      return !(pattern instanceof appeng.blockentity.crafting.IMolecularAssemblerSupportedPattern);
   }

   /** True if {@code key} is an input of a processing recipe with default fuzzy matching.
    *  AE2-native exact processing inputs (e.g. bee_type honeycombs) return false. */
   public static boolean isProcessingInput(AEKey key) {
      return key != null && PROCESSING_INPUT_KEYS.contains(key)
            && !EXACT_PROCESSING_KEYS.contains(key);
   }

   /** Mark {@code key} as an EXACT processing input (no NBT-variant substitution). */
   public static void registerExactProcessingInput(AEKey key) {
      if (key == null) {
         return;
      }
      PROCESSING_INPUT_KEYS.remove(key);
      EXACT_PROCESSING_KEYS.add(key);
   }

   public static void clearProcessingInputKeys() {
      PROCESSING_INPUT_KEYS.clear();
      EXACT_PROCESSING_KEYS.clear();
   }

   /** Register every input variant group of {@code pattern} (call once per pattern at encode time). */
   public static void registerFuzzyGroups(IPatternDetails pattern) {
      if (pattern == null) {
         return;
      }
      boolean processing = isProcessingPattern(pattern);
      // (v1.10.x PRODUCTIVE_BEES) AE2's native processing pattern encodes each input as an
      // EXACT variant (its IInput.isValid is exact equals — see class doc on
      // EXACT_PROCESSING_KEYS). A different-NBT variant (e.g. a honeycomb with another
      // bee_type component) must NOT satisfy such a slot — otherwise the VM consumes the
      // WRONG honeycomb as raw material. Only third-party processing patterns (whose
      // isValid genuinely accepts variants) keep the default-fuzzy PROCESSING_INPUT set.
      boolean nativeAE2Processing = pattern instanceof appeng.crafting.pattern.AEProcessingPattern;
      for (IInput inputEntry : pattern.getInputs()) {
         GenericStack[] possibleInputs = inputEntry.getPossibleInputs();
         if (processing) {
            // Processing recipes default to fuzzy matching: remember the input's primary
            // key so the VM matches it against the item's full fuzzy family at runtime.
            if (possibleInputs != null && possibleInputs.length > 0
                  && possibleInputs[0] != null && possibleInputs[0].what() != null) {
               if (nativeAE2Processing) {
                  // Exact: move to the EXACT set so isProcessingInput() returns false.
                  EXACT_PROCESSING_KEYS.add(possibleInputs[0].what());
               } else {
                  // Third-party default-fuzzy: remember the primary key so the VM matches
                  // it against the item's full fuzzy family at runtime.
                  PROCESSING_INPUT_KEYS.add(possibleInputs[0].what());
               }
            }
         }
         if (possibleInputs == null || possibleInputs.length <= 1) {
            continue; // exact input (replacement not encoded) — no fuzzy group
         }
         java.util.Set<AEKey> group = new java.util.HashSet<>();
         for (GenericStack gs : possibleInputs) {
            if (gs != null && gs.what() != null) {
               group.add(gs.what());
            }
         }
         if (group.size() > 1) {
            for (AEKey k : group) {
               FUZZY_GROUPS.merge(k, group, (a, b) -> {
                  a.addAll(b);
                  return a;
               });
            }
         }
      }
   }

   /** The full set of acceptable variants for {@code key} (always contains {@code key} itself). */
   public static java.util.Set<AEKey> getFuzzyGroup(AEKey key) {
      java.util.Set<AEKey> group = FUZZY_GROUPS.get(key);
      return group != null ? group : java.util.Set.of(key);
   }

   public static void clearFuzzyGroups() {
      FUZZY_GROUPS.clear();
      PROCESSING_INPUT_KEYS.clear();
      EXACT_PROCESSING_KEYS.clear();
   }

   /**
    * True for UselessMod's virtual smart-doubling wrapper {@code ScaledProcessingPattern}
    * (and the benchmark stand-in {@code ScaledBenchPatternDetails}): a runtime wrapper
    * whose class name carries {@code Scaled…Pattern}. Such wrappers are NOT part of the
    * pattern-provider lists the {@code updatePatterns} pass compiles, so every compiler
    * entry point unwraps them to the ORIGINAL pattern before compiling (v1.10.8).
    */
   private static boolean isScaledPattern(IPatternDetails pattern) {
      if (pattern == null) {
         return false;
      }
      String cn = pattern.getClass().getName();
      return cn.contains("Scaled") && cn.contains("Pattern");
   }

   /**
    * Recursively unwraps a virtual smart-doubling wrapper down to its ORIGINAL pattern via
    * its {@code getOriginal()} accessor (reflective — the wrapper is an optional third-party
    * class). Returns {@code pattern} unchanged when it is not a scaled wrapper or
    * unwrapping fails. Compiling the ORIGINAL (never the virtual wrapper) keeps
    * {@code outputPerCraft} at the real per-craft amount and makes every plan's
    * {@code patternTimes} key a real AE2 pattern that the Crafting CPU / {@code getProviders}
    * / furnace {@code pushPattern} recognise — UselessMod re-applies smart-doubling at
    * submit time because the key is NOT a {@code ScaledProcessingPattern}.
    */
   private static IPatternDetails unwrapScaled(IPatternDetails pattern) {
      if (pattern == null) {
         return null;
      }
      IPatternDetails current = pattern;
      while (isScaledPattern(current)) {
         try {
            var method = current.getClass().getMethod("getOriginal");
            Object original = method.invoke(current);
            if (!(original instanceof IPatternDetails) || original == null) {
               break;
            }
            current = (IPatternDetails) original;
         } catch (Exception e) {
            break; // not a wrapper we can unwrap — keep current
         }
      }
      return current;
   }

   public static void compileIfAbsent(IPatternDetails pattern) {
      IPatternDetails effective = unwrapScaled(pattern);
      if (effective != null) {
         COMPILED_PATTERNS.computeIfAbsent(effective, PatternCompiler::compilePattern);
      }
   }

   public static CraftingBytecode getCompiled(IPatternDetails pattern) {
      return COMPILED_PATTERNS.get(unwrapScaled(pattern));
   }

   public static CraftingBytecode compileRequest(IPatternDetails pattern, long requestedAmount) {
      IPatternDetails effective = unwrapScaled(pattern);
      CraftingBytecode patternBytecode = COMPILED_PATTERNS.get(effective);
      if (patternBytecode == null) {
         compileIfAbsent(effective);
         patternBytecode = COMPILED_PATTERNS.get(effective);
         if (patternBytecode == null) {
            throw new IllegalStateException("Failed to compile pattern: " + pattern);
         }
      }

      long outputPerCraft = patternBytecode.getOutputAmountPerCraft();
      long craftTimes = (requestedAmount + outputPerCraft - 1L) / outputPerCraft;
      CraftingBytecode.Builder builder = new CraftingBytecode.Builder();
      int outputIdx = builder.addConstant(patternBytecode.getOutput());
      builder.setOutput(outputIdx, requestedAmount);
      // (v1.10.8) Use the UNWRAPPED (original) pattern as the plan's pattern key — never the
      // virtual scaled wrapper — so AE2's CPU / getProviders / furnace pushPattern all match.
      int patternIdx = builder.addPattern(effective);
      builder.emitPushLong(craftTimes);
      builder.emit(Opcode.CALL);
      builder.emitShort(patternIdx);
      return builder.build();
   }

   private static CraftingBytecode compilePattern(IPatternDetails pattern) {
      // (v1.9.13) 编码阶段：检测样板是否开启模糊匹配/流体替换（getPossibleInputs()
      // 返回多个变体，如灰色羊毛样板可接受白色羊毛）。把该样板的所有输入变体注册为
      // 模糊组——A、B 可替换时，A→C、B→C 都视为可接受输入路径，供 VM 的库存缺失
      // 判断识别"灰色羊毛可由白色羊毛满足"。此处在 compilePattern 内注册，保证任何
      // 样板来源（分子装配室/样板终端/ME 接口）编译时都生效。
      registerFuzzyGroups(pattern);
      CraftingBytecode.Builder builder = new CraftingBytecode.Builder();
      GenericStack primaryOutput = pattern.getPrimaryOutput();
      AEKey outputKey = primaryOutput.what();
      long outputPerCraft = primaryOutput.amount();
      int outputIdx = builder.addConstant(outputKey);
      int patternIdx = builder.addPattern(pattern);
      builder.setOutput(outputIdx, outputPerCraft);
      // Compile logging disabled (v1.9.1) — keep only total calc time.
      // AE2VMAddon.LOGGER
      //    .info(
      //       "[AE2-VM] Compiling pattern: {} x {} ({} inputs, {} outputs)",
      //       new Object[]{outputPerCraft, outputKey, pattern.getInputs().length, pattern.getOutputs().size()}
      //    );
      builder.emit(Opcode.DUP);
      builder.emitRecordPattern(patternIdx);

      for (IInput inputEntry : pattern.getInputs()) {
         GenericStack[] possibleInputs = inputEntry.getPossibleInputs();
         if (possibleInputs.length != 0) {
            GenericStack inputStack = possibleInputs[0];
            AEKey inputKey = inputStack.what();
            long multiplier = inputEntry.getMultiplier();
            // Fix (AE2 1.20.1 faithful): per-craft consumption is multiplier × amount,
            // not just multiplier. Fixes fluid/bucket per-craft amounts (1 bucket of
            // water = 1000 mB, not 1 mB) and any other input with amount > 1.
            long totalPerCraft = multiplier * Math.max(1, inputStack.amount());
            // (v1.10.x CATALYST) Returned/catalyst input: the input is handed back unchanged
            // after every firing (getRemainingKey returns the input itself), so the whole
            // batch needs only `amount` as a seed — NOT amount × times. AE2's native
            // container/catalyst handling extracts the container and re-emits it; the closed
            // form is `unitsFor(times) = amount` (a catalyst seed serves the whole batch).
            // This is the GTL greenhouse fake-craft / crafting-template case where a block
            // (or template) must be present but is never consumed. Emit a one-time
            // CATALYST_SEED demand instead of the per-craft CALL_BY_KEY/EXTRACT chain.
            AEKey remainingKey = inputEntry.getRemainingKey(inputKey);
            if (remainingKey != null && remainingKey.equals(inputKey)) {
               // (v1.10.x DURABILITY) A finite-use (durability) tool is a returned input that
               // degrades: one amount-sized unit survives `uses` firings, so a batch of
               // `times` firings needs amount × ceil(times/uses) tools (the "成环差分" closed
               // form) — NOT one seed (catalyst) and NOT amount × times (consumed). Distinguish
               // via the IFiniteUseInput capability (durabilityUses() == MAX_VALUE → catalyst).
               long uses = Long.MAX_VALUE;
               if (inputEntry instanceof IFiniteUseInput f) {
                  uses = f.durabilityUses();
               }
               int seedIdx = builder.addConstant(inputKey);
               if (uses == Long.MAX_VALUE) {
                  builder.emitPushLong(totalPerCraft);
                  builder.emit(Opcode.CATALYST_SEED);
               } else {
                  builder.emitPushLong(totalPerCraft);
                  builder.emitPushLong(uses);
                  builder.emit(Opcode.DURABILITY_TOOL);
               }
               builder.emitShort(seedIdx);
               continue;
            }
            // AE2VMAddon.LOGGER
            //    .info(
            //       "[AE2-VM]   Input: key={}, stackAmt={}, multiplier={}, totalPerCraft={}", new Object[]{inputKey, inputStack.amount(), multiplier, totalPerCraft}
            //    );
            int inputKeyIdx = builder.addConstant(inputKey);
            builder.emit(Opcode.DUP);
            builder.emitPushLong(totalPerCraft);
            builder.emit(Opcode.MUL);
            // FIX (false-missing): ALWAYS schedule the sub-craft with the FULL
            // per-craft need BEFORE consuming stock. The old code extracted stock
            // first and CALL_BY_KEY'd only the residual — but the 1-craft capture
            // runs against the LIVE network, so any small stock (enough for 1 craft)
            // dropped the residual to 0 → CALL_BY_KEY(req=0) → NO sub-craft was
            // scheduled. At aggregation (scaled to N crafts) that stock was
            // exhausted and the whole demand fell to missing even though a pattern
            // existed (gold_ingot 181K missing etc.). Now the sub-craft is always
            // scheduled; the EXTRACT chain after it consumes the crafted output
            // first, then any remaining stock. Pure compile-time change — the VM's
            // opcodes are unchanged.
            builder.emit(Opcode.DUP);            // (v1.10.x video fix) Mark the slot when replacement is enabled
            // (getPossibleInputs() returns more than one variant): only then may the
            // leaf availability check and the stock-aware aggregation satisfy this
            // slot with a substitute. An EXACT slot (single possible input) can only
            // ever use its primary key — applying the global fuzzy group to it made
            // the plan extract a substitute the exact pattern cannot consume, and the
            // AE2 CPU execution stalled at zero progress (the 2026-08-09 video bug).
            if (possibleInputs.length > 1) {
               builder.emitFuzzySlot();
            }            builder.emitCallByKey(inputKeyIdx);
            // Fuzzy matching / fluid substitution: consume the crafted output and
            // each possible variant's stock; each EXTRACT's shortfall feeds the next.
            for (GenericStack possible : possibleInputs) {
               if (possible == null || possible.what() == null) {
                  continue;
               }
               int pIdx = builder.addConstant(possible.what());
               builder.emitExtractIngredient(pIdx);
            }
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
      COMPILED_PATTERNS.remove(unwrapScaled(pattern));
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