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
      // LOG disabled (v1.8.20/GTL): keep only total calc time.
      // AE2VMAddon.LOGGER.info("[AE2-VM] bumpPatternVersion: {} -> {}", patternVersion - 1, patternVersion);
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
      IPatternDetails.IInput[] patternInputs = pattern.getInputs();
      if (patternInputs == null) {
         return; // (v1.12.x GTL DEFENSIVE) exotic pattern without an input list
      }
      for (IInput inputEntry : patternInputs) {
         GenericStack[] possibleInputs = inputEntry.getPossibleInputs();
         if (possibleInputs == null) {
            continue;
         }
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
      // (v1.12.x GTL DEFENSIVE) Patterns with NO usable output (empty getOutputs() or
      // null primary output — possible with buggy/partial recipes in modpacks) cannot be
      // crafted: skip them instead of letting compilePattern NPE and dragging the whole
      // request into a native fallback (stall).
      if (effective != null && hasUsableOutput(effective) && effective.getInputs() != null) {
         COMPILED_PATTERNS.computeIfAbsent(effective, PatternCompiler::compilePattern);
      }
   }

   /** True if the pattern exposes at least one output with a non-null key. */
   private static boolean hasUsableOutput(IPatternDetails pattern) {
      try {
         GenericStack[] outputs = pattern.getOutputs();
         if (outputs == null || outputs.length == 0) {
            return false;
         }
         GenericStack primary = pattern.getPrimaryOutput();
         return primary != null && primary.what() != null;
      } catch (RuntimeException e) {
         return false; // defensive: an exotic pattern that throws on inspection is unusable
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
      // (v1.12.x GTL BIG-ORDER FIX) Saturating ceil-div — (a + b - 1) overflows to a
      // negative craft count for requestedAmount near Long.MAX_VALUE (10^18+ orders):
      // e.g. MAX + 2 - 1 wraps to Long.MIN_VALUE, / 2 → negative → the plan silently
      // crafts nothing ("大数量订单假阴/卡死"). a/b + (a%b!=0) never overflows.
      long craftTimes = ceilDiv(requestedAmount, outputPerCraft);
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

   /**
    * (v1.15.x GTL CIRCUIT SLOT) True when the input's primary variant is a GT
    * integrated circuit (registry id contains "integrated_circuit"). The circuit
    * selects the machine recipe; it is never consumed and must not appear as a
    * pattern demand. GTL's PatternCircuitHandler filters it at pattern creation,
    * but FOA/third-party rewrites may reintroduce it — skip defensively.
    */
   private static boolean isGtlCircuitInput(GenericStack[] possible) {
      if (possible == null || possible.length == 0) return false;
      AEKey k = possible[0].what();
      if (k instanceof appeng.api.stacks.AEItemKey ikey) {
         var id = ikey.getId();
         // (v1.15.x GTL 1.20.1) id.getPath() was added in 1.21 — 1.20.1 only exposes the
         // String-typed ResourceLocation via toString(). Match the integrated_circuit
         // segment on the canonical "namespace:path" form to keep behaviour identical
         // across MC versions. Forge Gradle 1.20.1 must NOT call id.getPath() — that
         // throws NoSuchMethodError at runtime and crashes the grid tick chain (saw it
         // land in Ticking GridNode → MC server tick → instant crash).
         if (id != null) {
            String s = id.toString();
            if (s != null && s.toLowerCase(java.util.Locale.ROOT).contains("integrated_circuit")) {
               return true;
            }
         }
      }
      return false;
   }


   private static CraftingBytecode compilePattern(IPatternDetails pattern) {
      // (v1.12.x GTL DEFENSIVE) Never compile a pattern without a usable primary output
      // (compileIfAbsent already filters; this guards direct computeIfAbsent callers).
      if (!hasUsableOutput(pattern)) {
         return null;
      }
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

      // (v1.12.x GTL DEFENSIVE) Null inputs = not compilable (compileIfAbsent already
      // filters; this guards direct computeIfAbsent callers from the same pattern).
      if (pattern.getInputs() == null) {
         return null;
      }
      IPatternDetails.IInput[] patternInputs = pattern.getInputs();
      // (v1.15.x GTL PARTIAL-INPUT GUARD) Count skipped (null/empty) input slots
      // BEFORE emitting: a pattern with SOME normal inputs and SOME empty ones is a
      // BROKEN pattern state — the game log shows antiproton's helium_plasma input
      // vanishing mid-session (GTL provider refresh); the old code silently dropped
      // it and the binary search returned a 309-rod "feasible" plan whose CPU could
      // never extract liq_h2 / helium_plasma (avail=0) — the whole chain stalled.
      // Failing the compile makes the caller report the input missing / fall back to
      // the native path (which crafts the real feasible amount). A pattern whose
      // inputs are ALL empty is a legitimate no-input (free-output) pattern and
      // still compiles without inputs.
      // (v1.15.x GTL SINGLE-PASS) Snapshot EVERY input's variant list ONCE into an
      // identity map, then (a) count skipped slots and (b) emit from the SNAPSHOT.
      // Calling getPossibleInputs() twice (guard pass + emit pass) raced with the
      // GTL pattern-buffer refresh: the first call returned the variants, the second
      // returned empty → the guard did NOT fire (input looked fine) but the emit
      // pass silently dropped the input (observed: antiproton's helium_plasma input
      // vanished from the compiled bytecode → a "feasible" plan whose CPU could
      // never extract helium_plasma → every task stalled).
      java.util.Map<IInput, GenericStack[]> variantSnapshot = new java.util.IdentityHashMap<>();
      int skippedInputs = 0;
      for (IInput inputEntry : patternInputs) {
         GenericStack[] pp = inputEntry.getPossibleInputs();
         // (v1.15.x GTL LAZY CACHE) GTL pattern-buffer inputs read EMPTY on the
         // FIRST access (the machine's GT recipe cache builds lazily) and return
         // the real variants afterwards — observed: iron_ingot's iron_dust input
         // empty at compile time while vanilla, 1ms later, saw it and expanded
         // the smelt; the VM reported the smelt missing even though the pattern
         // existed. Retry the read a few times to let the lazy cache settle;
         // still-empty inputs fall through to the partial-empty guard below.
         for (int retry = 0; (pp == null || pp.length == 0) && retry < 4; retry++) {
            pp = inputEntry.getPossibleInputs();
         }
         variantSnapshot.put(inputEntry, pp);
         // (v1.15.x GTL CIRCUIT SLOT) GTL pattern buffers filter the integrated
         // circuit out of the pattern, but some paths (FOA rewrite, third-party
         // encoders) may keep it. A circuit is machine configuration, NOT a
         // consumed input — never count it as a broken/empty slot.
         if (isGtlCircuitInput(pp)) continue;
         if (pp == null || pp.length == 0) skippedInputs++;
      }
      // (v1.15.x GTL COMPILE-LOG) 编译每个样板时全量打印：输出 + 每个输入槽位
      // 实际读到的内容（含 EMPTY 空槽）。用于逐个排查 iron_ingot 缺料——确认是
      // 哪个样板的哪个输入在编译时读空。
      try {
         StringBuilder sb = new StringBuilder("[AE2-VM COMPILE] out=").append(outputKey)
               .append(" inputs=").append(patternInputs.length).append(" skipped=").append(skippedInputs).append(" =>");
         for (var ie : patternInputs) {
            var ps = variantSnapshot.get(ie);
            if (ps != null && ps.length > 0) {
               for (int k = 0; k < ps.length; k++) {
                  if (ps[k] != null && ps[k].what() != null) {
                     sb.append(" [").append(ps[k].what()).append("x").append(ps[k].amount()).append("]");
                  } else {
                     sb.append(" [NULL]");
                  }
               }
            } else {
               sb.append(" [EMPTY]");
            }
         }
         AE2VMAddon.LOGGER.warn(sb.toString());
      } catch (Throwable ignored) {}
      // A pattern with SOME normal inputs and SOME empty ones is a BROKEN pattern
      // state — fail the compile so the caller reports missing / falls back to the
      // native path. All-empty inputs = legitimate no-input (free-output) pattern.
      if (skippedInputs > 0 && skippedInputs < patternInputs.length) {
         AE2VMAddon.LOGGER.warn("[AE2-VM COMPILE] out={} PARTIAL-EMPTY FAIL skipped={} total={}", outputKey, skippedInputs, patternInputs.length);
         return null;
      }
      for (IInput inputEntry : patternInputs) {
         GenericStack[] possibleInputs = variantSnapshot.get(inputEntry);
         if (isGtlCircuitInput(possibleInputs)) {
            continue; // circuit slot: machine config, not a consumed input
         }
         if (possibleInputs == null || possibleInputs.length == 0) {
            continue;
         }
            GenericStack inputStack = possibleInputs[0];
            AEKey inputKey = inputStack.what();
         // (v1.15.x GTL CATALYST SLOT) Inputs the machine satisfies from its
         // catalyst slots are NOT consumed demand — skip them (the GTL extract
         // overwrite also skips them at CPU execution).
         if (com.ae2vm.addon.api.GtlCatalystRegistry.isCatalyst(outputKey, inputKey)) {
            continue;
         }
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

   /**
    * (v1.12.x GTL BIG-ORDER FIX) Saturated ceil-division. The naive
    * {@code (a + b - 1) / b} overflows when {@code a} is near {@link Long#MAX_VALUE}
    * (10^18+ orders), producing a NEGATIVE craft count — the VM then silently crafts
    * nothing and the plan reports false missing (or the job stalls). The remainder form
    * never overflows and equals ceil(a/b) for positive longs.
    */
   public static long ceilDiv(long a, long b) {
      if (a <= 0L) return 0L;
      if (b <= 0L) return 0L;
      return a / b + (a % b == 0L ? 0L : 1L);
   }
}