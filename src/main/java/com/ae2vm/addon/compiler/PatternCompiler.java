package com.ae2vm.addon.compiler;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import com.ae2vm.addon.AE2VMAddon;
import com.ae2vm.addon.vm.CraftingBytecode;
import com.ae2vm.addon.vm.Opcode;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Compiles individual AE2 patterns into standalone bytecode.
 * 
 * ARCHITECTURE:
 * 1. Patterns are compiled ONCE when they enter an AE network, into that
 *    network's cache via {@link #compileIfAbsent(Object, IPatternDetails)}
 * 2. Sub-patterns use CALL_BY_KEY — resolved lazily at VM runtime, not at compile time
 * 3. Each pattern compiles to independent bytecode: expects craftTimes on stack, returns clean
 * 4. The compiled cache is PER-NETWORK (keyed by IGrid / CraftingService) — a crafting
 *    task only ever reads patterns compiled under its OWN AE network, so an ingredient
 *    never appears craftable because a different network compiled a pattern for it
 * 5. Craft times are compiled into a separate "request wrapper" bytecode per crafting request
 * 
 * Compilation model:
 *   Pattern bytecode (compiled ONCE at encode time):
 *     DUP craftTimes → RECORD_PATTERN → for each input: compute needed →
 *     EXTRACT_INGREDIENT → CALL_BY_KEY(raw) or RECORD_MISSING(raw) →
 *     POP craftTimes → RETURN
 *   
 *   Request wrapper bytecode (compiled per crafting request):
 *     PUSH_LONG craftTimes → CALL pattenIdx → HALT
 */
public class PatternCompiler {
    
    /**
     * Per-network cache: network key (IGrid / CraftingService) → pattern → bytecode.
     * A crafting task must only ever read patterns compiled under the SAME AE network,
     * otherwise an ingredient could appear "craftable" because another network compiled it.
     */
    private static final Map<Object, Map<IPatternDetails, CraftingBytecode>> NETWORK_CACHES =
        new ConcurrentHashMap<>();
    
    /**
     * Compile a pattern to bytecode if not already compiled for the given network.
     * Safe to call multiple times — subsequent calls are no-ops.
     *
     * @param network the network (IGrid / CraftingService) this pattern belongs to
     * @param pattern the pattern to compile
     */
    public static void compileIfAbsent(Object network, IPatternDetails pattern) {
        if (pattern == null) return;
        networkCache(network).computeIfAbsent(pattern, PatternCompiler::compilePattern);
    }
    
    /**
     * Get compiled bytecode for a pattern in the given network. Returns null if not yet compiled.
     *
     * @param network the network (IGrid / CraftingService) the crafting task is running in
     * @param pattern the pattern to look up
     */
    public static CraftingBytecode getCompiled(Object network, IPatternDetails pattern) {
        if (pattern == null) return null;
        return networkCache(network).get(pattern);
    }
    
    /** The per-network compile cache for the given network key. */
    private static Map<IPatternDetails, CraftingBytecode> networkCache(Object network) {
        return NETWORK_CACHES.computeIfAbsent(network, k -> new ConcurrentHashMap<>());
    }
    
    /**
     * Build a request wrapper bytecode that pushes craftTimes then calls the pattern.
     * This is the "运行次数也作为字节码" — craft times compiled into bytecode.
     * 
     * @param network         the network (IGrid / CraftingService) the request runs in
     * @param pattern         The target pattern (must already be compiled)
     * @param requestedAmount How many of the output item are requested
     * @return bytecode: PUSH_LONG craftTimes → CALL patternIdx → HALT
     */
    public static CraftingBytecode compileRequest(Object network, IPatternDetails pattern, long requestedAmount) {
        CraftingBytecode patternBytecode = getCompiled(network, pattern);
        if (patternBytecode == null) {
            // Compile on-demand if somehow not yet compiled
            compileIfAbsent(network, pattern);
            patternBytecode = getCompiled(network, pattern);
            if (patternBytecode == null) {
                throw new IllegalStateException("Failed to compile pattern: " + pattern);
            }
        }
        
        long outputPerCraft = patternBytecode.getOutputAmountPerCraft();
        long craftTimes = (requestedAmount + outputPerCraft - 1) / outputPerCraft;
        
        CraftingBytecode.Builder builder = new CraftingBytecode.Builder();
        
        // Add output key to request's OWN constant pool (not the pattern's pool)
        int outputIdx = builder.addConstant(patternBytecode.getOutput());
        // Store the ACTUAL requested amount, not per-craft amount
        // This is what appears in the final crafting plan output
        builder.setOutput(outputIdx, requestedAmount);
        
        // Add the pattern to this request's pattern pool (index 0)
        int patternIdx = builder.addPattern(pattern);
        
        // Compile craftTimes as bytecode: PUSH_LONG craftTimes
        builder.emitPushLong(craftTimes);
        
        // CALL the pattern
        builder.emit(Opcode.CALL);
        builder.emitShort(patternIdx);
        
        return builder.build();
    }
    
    /**
     * Compile a single pattern into standalone bytecode.
     * Does NOT resolve sub-patterns — uses CALL_BY_KEY for lazy runtime resolution.
     */
    private static CraftingBytecode compilePattern(IPatternDetails pattern) {
        GenericStack primaryOutput = pattern.getPrimaryOutput();
        if (primaryOutput == null || primaryOutput.what() == null) {
            throw new IllegalStateException("Pattern has no primary output: " + pattern.getClass().getName());
        }
        IPatternDetails.IInput[] inputs = pattern.getInputs();
        if (inputs == null) {
            throw new IllegalStateException("Pattern has null inputs: " + pattern.getClass().getName());
        }

        CraftingBytecode.Builder builder = new CraftingBytecode.Builder();
        AEKey outputKey = primaryOutput.what();
        long outputPerCraft = primaryOutput.amount();
        
        int outputIdx = builder.addConstant(outputKey);
        int patternIdx = builder.addPattern(pattern);
        builder.setOutput(outputIdx, outputPerCraft);
        
        AE2VMAddon.LOGGER.info("[AE2-VM] Compiling pattern: {} x {} ({} inputs, {} outputs)",
            outputPerCraft, outputKey, inputs.length, pattern.getOutputs() == null ? 0 : pattern.getOutputs().size());
        
        // Stack pre: (..., craftTimes) — how many times to craft this pattern
        
        // 1. Record that we're crafting this pattern N times (for AE2 job scheduling)
        builder.emit(Opcode.DUP);
        builder.emitRecordPattern(patternIdx);
        
        // 2. Process each input
        for (var inputEntry : inputs) {
            GenericStack[] possibleInputs = inputEntry.getPossibleInputs();
            if (possibleInputs == null || possibleInputs.length == 0) continue;
            
            // Use primary input (first in list)
            GenericStack inputStack = possibleInputs[0];
            AEKey inputKey = inputStack.what();
            long multiplier = inputEntry.getMultiplier();
            // IMPORTANT: multiplier IS the per-craft amount (from the recipe)
            // possibleInputs[0].amount() is the stack size, NOT the recipe amount!
            // AE2's CraftingTreeProcess uses only getMultiplier() for amountPerCraft.
            long totalPerCraft = multiplier;
            
            AE2VMAddon.LOGGER.info("[AE2-VM]   Input: key={}, stackAmt={}, multiplier={}, totalPerCraft={}",
                inputKey, inputStack.amount(), multiplier, totalPerCraft);
            
            int inputKeyIdx = builder.addConstant(inputKey);
            
            // Stack: (..., craftTimes)
            builder.emit(Opcode.DUP);
            // Stack: (..., craftTimes, craftTimes)
            builder.emitPushLong(totalPerCraft);
            // Stack: (..., craftTimes, craftTimes, totalPerCraft)
            builder.emit(Opcode.MUL);
            // Stack: (..., craftTimes, totalInputRequired)
            
            // Try to extract from inventory first
            builder.emitExtractIngredient(inputKeyIdx);
            // Stack: (..., craftTimes, remaining)
            
            // CALL_BY_KEY: if remaining > 0, try to craft via sub-pattern.
            // But sub-patterns INSERT_OUTPUT into the shared buffer — without
            // immediate re-extraction, other patterns (including the parent)
            // can "steal" the produced items. DUP the remaining amount so we
            // can EXTRACT it right after CALL_BY_KEY returns, locking the
            // just-produced items for THIS pattern's input slot.
            builder.emit(Opcode.DUP);
            // Stack: (..., craftTimes, remaining, remaining_copy)
            int inputKeyIdx2 = builder.addConstant(inputKey);
            builder.emitCallByKey(inputKeyIdx2);
            // Stack after CALL_BY_KEY: (..., craftTimes, remaining_copy)
            // CALL_BY_KEY produced items into simulation buffer — claim them now:
            builder.emitExtractIngredient(inputKeyIdx);
            // Stack: (..., craftTimes, finalRemaining)
            // Discard finalRemaining — it should be 0 if sub-craft succeeded.
            // (CALL_BY_KEY already records truly missing items.)
            // CRITICAL: must POP so output section's DUP copies craftTimes, not finalRemaining!
            builder.emit(Opcode.POP);
            // Stack: (..., craftTimes)
        }
        
        // 3. Insert outputs into simulation inventory (CRITICAL for recursive crafting!)
        //    Sub-pattern outputs must be available for parent's subsequent EXTRACT_INGREDIENT calls.
        var outputs = pattern.getOutputs();
        if (outputs != null) {
            for (GenericStack output : outputs) {
                int outIdx = builder.addConstant(output.what());
                builder.emit(Opcode.DUP);                 // (..., craftTimes, craftTimes)
                builder.emitPushLong(output.amount());     // (..., craftTimes, craftTimes, amt)
                builder.emit(Opcode.MUL);                  // (..., craftTimes, totalOutput)
                builder.emitInsertOutput(outIdx);          // inserts totalOutput into inventory
            }
        }
        
        // 4. Pop craftTimes, done with this pattern
        builder.emit(Opcode.POP);
        
        // Return to caller (or HALT if this was called as top-level)
        builder.emit(Opcode.RETURN);
        
        return builder.build();
    }
    
    /**
     * Invalidate a specific pattern in a network (when it changes/removed from the system)
     */
    public static void invalidate(Object network, IPatternDetails pattern) {
        var cache = NETWORK_CACHES.get(network);
        if (cache != null) cache.remove(pattern);
    }
    
    /**
     * Clear the compilation caches for all networks
     */
    public static void clearCache() {
        NETWORK_CACHES.clear();
    }
    
    /**
     * Get total number of compiled patterns across all networks (for stats/debug)
     */
    public static int getCompiledCount() {
        int total = 0;
        for (var cache : NETWORK_CACHES.values()) total += cache.size();
        return total;
    }

    /**
     * Find a pattern compiled for the given network whose output matches the key.
     * Matches by resource location ID, so it can find patterns that the crafting
     * service failed to index (e.g. molecular-assembler patterns) — they were
     * still compiled into this network's cache at provider registration time.
     *
     * @param network   the network (IGrid / CraftingService) to search
     * @param outputKey the item to find a pattern for
     * @return a compiled pattern for this network producing that item, or null
     */
    public static IPatternDetails findCompiledByOutput(Object network, AEKey outputKey) {
        if (outputKey == null || outputKey.getId() == null) return null;
        var cache = NETWORK_CACHES.get(network);
        if (cache == null) return null;
        String targetId = outputKey.getId().toString();
        for (var entry : cache.entrySet()) {
            var patternOutput = entry.getKey().getPrimaryOutput();
            if (patternOutput != null && patternOutput.what() != null
                    && patternOutput.what().getId() != null
                    && targetId.equals(patternOutput.what().getId().toString())) {
                return entry.getKey();
            }
            var outputs = entry.getKey().getOutputs();
            if (outputs != null) {
                for (var out : outputs) {
                    if (out != null && out.what() != null && out.what().getId() != null
                            && targetId.equals(out.what().getId().toString())) {
                        return entry.getKey();
                    }
                }
            }
        }
        return null;
    }
}
