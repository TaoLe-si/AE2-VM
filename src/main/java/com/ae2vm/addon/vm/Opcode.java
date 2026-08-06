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
