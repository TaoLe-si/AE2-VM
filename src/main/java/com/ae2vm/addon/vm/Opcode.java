package com.ae2vm.addon.vm;

/**
 * Stack-based Virtual Machine Opcodes for AE2 Crafting Calculation
 * 
 * Stack convention: All values are long integers representing item counts.
 * Item references are stored in a constant pool and referenced by index.
 * 
 * Design principle: Compile patterns once, execute many times.
 * All recursion is eliminated at compile time - bytecode is completely flat.
 */
public enum Opcode {
    /**
     * PUSH_ITEM <constantPoolIndex:short> <count:long>
     * Stack: (...) -> (..., count)
     * Push item requirement onto stack. The item is identified by constant pool index.
     */
    PUSH_ITEM(0x00),
    
    /**
     * PUSH_LONG <value:long>
     * Stack: (...) -> (..., value)
     * Push a literal long value onto stack.
     */
    PUSH_LONG(0x01),
    
    /**
     * ADD
     * Stack: (..., a, b) -> (..., a+b)
     * Add top two stack values.
     */
    ADD(0x02),
    
    /**
     * SUB
     * Stack: (..., a, b) -> (..., a-b)
     * Subtract: a - b (b is top)
     */
    SUB(0x03),
    
    /**
     * MUL
     * Stack: (..., a, b) -> (..., a*b)
     * Multiply top two values.
     */
    MUL(0x04),
    
    /**
     * DIV_ROUNDUP
     * Stack: (..., required, perCraft) -> (..., craftTimes)
     * Calculate how many crafts needed: ceil(required / perCraft)
     * Critical for crafting calculation!
     */
    DIV_ROUNDUP(0x05),
    
    /**
     * EXTRACT_INGREDIENT <constantPoolIndex:short>
     * Stack: (..., needed) -> (..., remainingToCraft)
     * Try to extract needed items from simulation inventory.
     * Extracted items are added to usedItems.
     * Remaining items that couldn't be extracted are pushed back for crafting.
     * This is the CORRECT logic: extract first, craft only what's missing.
     */
    EXTRACT_INGREDIENT(0x06),
    
    /**
     * RECORD_OUTPUT <constantPoolIndex:short>
     * Stack: (..., count) -> (...)
     * Record final output item and count (pops count from stack).
     * This is the final result of the calculation.
     */
    RECORD_OUTPUT(0x07),
    
    /**
     * RECORD_INGREDIENT <constantPoolIndex:short>
     * Stack: (..., count) -> (...)
     * Record required ingredient for the plan (pops count from stack).
     */
    RECORD_INGREDIENT(0x08),
    
    /**
     * RECORD_MISSING <constantPoolIndex:short>
     * Stack: (..., count) -> (...)
     * Record missing item (pops count from stack).
     */
    RECORD_MISSING(0x09),
    
    /**
     * DUP
     * Stack: (..., a) -> (..., a, a)
     * Duplicate top stack value.
     */
    DUP(0x0A),
    
    /**
     * POP
     * Stack: (..., a) -> (...)
     * Discard top stack value.
     */
    POP(0x0B),
    
    /**
     * SWAP
     * Stack: (..., a, b) -> (..., b, a)
     * Swap top two stack values.
     */
    SWAP(0x0C),
    
    /**
     * RECORD_PATTERN <patternPoolIndex:short>
     * Stack: (..., craftTimes) -> (...)
     * Record that a pattern needs to be crafted N times.
     * This calls simulation.addCrafting() so AE2 knows to submit jobs.
     * Critical for correct item dispatch to containers!
     */
    RECORD_PATTERN(0x0D),
    
    /**
     * CALL <patternPoolIndex:short>
     * Stack: (..., craftTimes) -> (...)
     * Call a pre-compiled pattern from the pattern pool at the given index.
     * Used by request wrappers: PUSH_LONG craftTimes → CALL patternIdx → HALT.
     * The pattern bytecode is compiled at encode time, just referenced here.
     */
    CALL(0x0E),
    
    /**
     * CALL_BY_KEY <constantPoolIndex:short>
     * Stack: (..., required) -> (...)
     * Look up the pattern that produces constantPool[idx] at runtime,
     * compile it if not already compiled, then call it.
     * This enables patterns to be compiled at encode time WITHOUT needing
     * all sub-patterns to be available. Resolution happens lazily at runtime.
     */
    CALL_BY_KEY(0x10),
    
    /**
     * INSERT_OUTPUT <constantPoolIndex:short>
     * Stack: (..., craftTimes) -> (..., craftTimes)
     * Insert craftTimes * outputAmount of constantPool[idx] into simulation inventory.
     * Does NOT pop craftTimes — it's still needed for subsequent inputs.
     * CRITICAL for recursive crafting: sub-pattern outputs become available
     * for parent's subsequent EXTRACT_INGREDIENT calls.
     */
    INSERT_OUTPUT(0x11),

    /**
     * CATALYST_SEED <constantPoolIndex:short>
     * Stack: (..., seedAmount) -> (...)
     * Records a ONE-TIME catalyst/container seed demand for constantPool[idx].
     * Unlike EXTRACT_INGREDIENT this demand is per-BATCH, not per-craft: a
     * `returned` catalyst (e.g. a crafting template / greenhouse block) is handed
     * back unchanged after every firing, so the whole batch needs only `amount`
     * as a seed (the reference's closed form `unitsFor(times) = amount`). The seed
     * is stored in the bundle's `seeds` map, which scale() deliberately does NOT
     * multiply, so the seed is required exactly once regardless of craft count.
     */
    CATALYST_SEED(0x12),

    /**
     * DURABILITY_TOOL <constantPoolIndex:short>
     * Stack: (..., amount, uses) -> (...)
     * Records a FINITE-USE tool demand for constantPool[idx]: `amount` units are
     * consumed per firing, and one full amount-sized unit survives `uses` firings
     * (a degrading tool like {@code 1·A(n) + 1·B → 1·C + A(n-1)}). Unlike a catalyst
     * seed (one per batch) or a normal input (amount × times), a batch of `times`
     * firings needs {@code amount × ceil(times / uses)} tools — the "成环差分"
     * reduction. Stored in the bundle's `durability` map (rate, NOT scaled), applied
     * at aggregation time from stock with shortfall → missing.
     */
    DURABILITY_TOOL(0x13),
    
    /**
     * FUZZY_SLOT
     * Stack: (...) -> (...)
     * Marks the IMMEDIATELY following CALL_BY_KEY as coming from a pattern input slot
     * with item/fluid replacement enabled (getPossibleInputs().length > 1). The VM
     * remembers this so:
     *  - the leaf availability check may use the whole fuzzy group (substitutes
     *    actually satisfy this slot), and
     *  - the sub-call is recorded as a FUZZY item need, so the stock-aware aggregation
     *    only satisfies the FUZZY portion of the child's demand with substitute stock
     *    (an EXACT slot — single possible input — can only ever use its primary key,
     *    otherwise AE2's CPU execution stalls: the plan extracts a substitute the exact
     *    pattern cannot consume).
     */
    FUZZY_SLOT(0x14),
    
    /**
     * RETURN
     * Stack: (...) -> (...)
     * Return from current pattern bytecode.
     */
    RETURN(0x0F),
    
    /**
     * HALT
     * Stack: (...) -> (...)
     * End execution successfully.
     */
    HALT(0xFF);
    
    public final int code;
    
    Opcode(int code) {
        this.code = code;
    }
    
    private static final Opcode[] OPCODES = new Opcode[256];
    static {
        for (Opcode op : values()) {
            OPCODES[op.code & 0xFF] = op;
        }
    }
    
    public static Opcode fromCode(int code) {
        Opcode op = OPCODES[code & 0xFF];
        if (op == null) {
            throw new IllegalArgumentException("Unknown opcode: 0x" + Integer.toHexString(code));
        }
        return op;
    }
}
