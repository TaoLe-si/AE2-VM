package com.ae2vm.addon.compiler;

import com.ae2vm.addon.api.AEKey;
import com.ae2vm.addon.api.GenericStack;
import com.ae2vm.addon.api.IPatternDetails;
import com.ae2vm.addon.api.IPatternDetails.IInput;
import com.ae2vm.addon.api.Rv4PatternDetails;
import com.ae2vm.addon.vm.CraftingBytecode;
import com.ae2vm.addon.vm.Opcode;

import java.util.Collections;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class PatternCompiler {
    private static final Map<IPatternDetails, CraftingBytecode> COMPILED_PATTERNS = new ConcurrentHashMap<>();

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
    private static final Map<AEKey, Set<AEKey>> FUZZY_GROUPS = new ConcurrentHashMap<>();

    /**
     * Processing-recipe input keys (v1.10.x). Processing recipes (处理配方) default to
     * FUZZY matching: a stored variant of the input item (same item, any NBT/damage —
     * {@code FuzzyMode.IGNORE_ALL}) satisfies the slot, even though AE2's processing
     * pattern encodes each input as a single exact variant with no substitution flag.
     * Keys are collected from every processing pattern at compile time and consumed by
     * {@code CraftingVM}'s missing-check / stock-aware aggregation / extraction.
     */
    private static final Set<AEKey> PROCESSING_INPUT_KEYS = ConcurrentHashMap.newKeySet();

    /**
     * True for patterns that are NOT molecular-assembler crafting patterns.
     *
     * <p>rv4 adaptation: AE2 rv4 has no {@code IMolecularAssemblerSupportedPattern}
     * marker. The equivalent discriminator is {@code ICraftingPatternDetails.isCraftable()}
     * — crafting patterns (molecular assembler) return {@code true}, processing patterns
     * return {@code false}. The shim's {@link Rv4PatternDetails} exposes the underlying
     * delegate, so we consult it directly. Non-rv4 shim wrappers (custom patterns) are
     * treated as crafting patterns.
     */
    private static boolean isProcessingPattern(IPatternDetails pattern) {
        if (pattern == null) {
            return false;
        }
        if (pattern instanceof Rv4PatternDetails) {
            return !((Rv4PatternDetails) pattern).delegate().isCraftable();
        }
        return false;
    }

    /** True if {@code key} is an input of a processing recipe (default fuzzy). */
    public static boolean isProcessingInput(AEKey key) {
        return key != null && PROCESSING_INPUT_KEYS.contains(key);
    }

    public static void clearProcessingInputKeys() {
        PROCESSING_INPUT_KEYS.clear();
    }

    /** Register every input variant group of {@code pattern} (call once per pattern at encode time). */
    public static void registerFuzzyGroups(IPatternDetails pattern) {
        if (pattern == null) {
            return;
        }
        boolean processing = isProcessingPattern(pattern);
        for (IInput inputEntry : pattern.getInputs()) {
            GenericStack[] possibleInputs = inputEntry.getPossibleInputs();
            if (processing) {
                // Processing recipes default to fuzzy matching: remember the input's primary
                // key so the VM matches it against the item's full fuzzy family at runtime.
                if (possibleInputs != null && possibleInputs.length > 0
                        && possibleInputs[0] != null && possibleInputs[0].what() != null) {
                    PROCESSING_INPUT_KEYS.add(possibleInputs[0].what());
                }
            }
            if (possibleInputs == null || possibleInputs.length <= 1) {
                continue; // exact input (replacement not encoded) — no fuzzy group
            }
            Set<AEKey> group = new java.util.HashSet<>();
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
    public static Set<AEKey> getFuzzyGroup(AEKey key) {
        Set<AEKey> group = FUZZY_GROUPS.get(key);
        return group != null ? group : Collections.singleton(key);
    }

    public static void clearFuzzyGroups() {
        FUZZY_GROUPS.clear();
        PROCESSING_INPUT_KEYS.clear();
    }

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
        // (v1.9.13) 编码阶段：检测样板是否开启模糊匹配/流体替换（getPossibleInputs()
        // 返回多个变体）。把该样板的所有输入变体注册为模糊组，供 VM 的库存缺失判断识别。
        registerFuzzyGroups(pattern);
        CraftingBytecode.Builder builder = new CraftingBytecode.Builder();
        GenericStack primaryOutput = pattern.getPrimaryOutput();
        AEKey outputKey = primaryOutput.what();
        long outputPerCraft = primaryOutput.amount();
        int outputIdx = builder.addConstant(outputKey);
        int patternIdx = builder.addPattern(pattern);
        builder.setOutput(outputIdx, outputPerCraft);
        builder.emit(Opcode.DUP);
        builder.emitRecordPattern(patternIdx);

        for (IInput inputEntry : pattern.getInputs()) {
            GenericStack[] possibleInputs = inputEntry.getPossibleInputs();
            if (possibleInputs.length != 0) {
                GenericStack inputStack = possibleInputs[0];
                AEKey inputKey = inputStack.what();
                long multiplier = inputEntry.getMultiplier();
                // Fix (AE2 1.20.1 faithful): per-craft consumption is multiplier × amount.
                long totalPerCraft = multiplier * Math.max(1, inputStack.amount());

                // (v1.10.x CATALYST / DURABILITY) On rv4 getRemainingKey() is always null
                // (see Rv4PatternDetails.Rv4Input), so this branch never fires on 1.10.2.
                // Kept verbatim from the 1.20.1 compiler for parity; the IFiniteUseInput
                // capability has no rv4 implementor.
                GenericStack remainingStack = inputEntry.getRemainingKey(inputKey);
                AEKey remainingKey = remainingStack == null ? null : remainingStack.what();
                if (remainingKey != null && remainingKey.equals(inputKey)) {
                    long uses = Long.MAX_VALUE;
                    if (inputEntry instanceof IFiniteUseInput) {
                        uses = ((IFiniteUseInput) inputEntry).durabilityUses();
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

                int inputKeyIdx = builder.addConstant(inputKey);
                builder.emit(Opcode.DUP);
                builder.emitPushLong(totalPerCraft);
                builder.emit(Opcode.MUL);
                // FIX (false-missing): ALWAYS schedule the sub-craft with the FULL
                // per-craft need BEFORE consuming stock.
                builder.emit(Opcode.DUP);
                // (v1.10.x video fix) Mark the slot when replacement is enabled.
                if (possibleInputs.length > 1) {
                    builder.emitFuzzySlot();
                }
                builder.emitCallByKey(inputKeyIdx);
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
            String targetId = outputKey.getId();

            for (Entry<IPatternDetails, CraftingBytecode> entry : COMPILED_PATTERNS.entrySet()) {
                GenericStack patternOutput = entry.getKey().getPrimaryOutput();
                if (patternOutput != null && patternOutput.what() != null) {
                    String patternId = patternOutput.what().getId();
                    if (patternId != null && targetId.equals(patternId)) {
                        return entry.getKey();
                    }
                }

                for (GenericStack out : entry.getKey().getOutputs()) {
                    if (out != null && out.what() != null && out.what().getId() != null
                            && targetId.equals(out.what().getId())) {
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
