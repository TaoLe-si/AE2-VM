package com.ae2vm.addon.vm;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;

import java.util.Arrays;
import java.util.List;

/**
 * Compiled bytecode for a crafting tree.
 * This is the result of pattern compilation - all recursion is eliminated,
 * bytecode is completely flat and ready for linear execution.
 * 
 * Compilation happens ONCE when patterns are encoded/loaded, not per craft request!
 */
public class CraftingBytecode {
    /**
     * Constant pool: maps index -> AEKey (item/fluid type)
     * Indices are 0-based, referenced by PUSH_ITEM/CHECK_AVAILABLE etc.
     */
    private final AEKey[] constantPool;
    
    /**
     * Pattern pool: maps index -> IPatternDetails (for crafting job recording)
     * Indices are 0-based, referenced by RECORD_PATTERN.
     */
    private final IPatternDetails[] patternPool;
    
    /**
     * Actual bytecode instructions.
     * Each opcode is 1 byte, followed by operands as defined in Opcode.
     */
    private final byte[] code;
    
    /**
     * Cached hash for quick lookup
     */
    private final int hash;
    
    /**
     * Output item index in constant pool (for quick access)
     */
    private final int outputIndex;
    
    /**
     * Output amount per craft
     */
    private final long outputAmountPerCraft;
    
    public CraftingBytecode(AEKey[] constantPool, IPatternDetails[] patternPool, byte[] code, 
                           int outputIndex, long outputAmountPerCraft) {
        this.constantPool = constantPool;
        this.patternPool = patternPool;
        this.code = code;
        this.outputIndex = outputIndex;
        this.outputAmountPerCraft = outputAmountPerCraft;
        this.hash = Arrays.hashCode(code) * 31 + Arrays.hashCode(constantPool);
    }
    
    public AEKey[] getConstantPool() {
        return constantPool;
    }
    
    public IPatternDetails[] getPatternPool() {
        return patternPool;
    }
    
    public byte[] getCode() {
        return code;
    }
    
    public int getOutputIndex() {
        return outputIndex;
    }
    
    public long getOutputAmountPerCraft() {
        return outputAmountPerCraft;
    }
    
    public AEKey getOutput() {
        return constantPool[outputIndex];
    }
    
    public GenericStack getOutputStack() {
        return new GenericStack(getOutput(), outputAmountPerCraft);
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        CraftingBytecode that = (CraftingBytecode) o;
        return hash == that.hash && 
               Arrays.equals(constantPool, that.constantPool) && 
               Arrays.equals(patternPool, that.patternPool) &&
               Arrays.equals(code, that.code);
    }
    
    @Override
    public int hashCode() {
        return hash;
    }
    
    /**
     * Get total number of unique ingredients in this crafting tree
     */
    public int getIngredientCount() {
        return constantPool.length;
    }
    
    /**
     * Get bytecode length in bytes
     */
    public int getCodeLength() {
        return code.length;
    }
    
    /**
     * Builder for constructing bytecode during compilation
     */
    public static class Builder {
        private final List<AEKey> constantPool = new java.util.ArrayList<>();
        private final List<IPatternDetails> patternPool = new java.util.ArrayList<>();
        private final java.io.ByteArrayOutputStream codeStream = new java.io.ByteArrayOutputStream();
        private int outputIndex = -1;
        private long outputAmountPerCraft = 0;
        
        public int addConstant(AEKey key) {
            int existing = constantPool.indexOf(key);
            if (existing >= 0) {
                return existing;
            }
            int index = constantPool.size();
            constantPool.add(key);
            return index;
        }
        
        public int addPattern(IPatternDetails pattern) {
            int existing = patternPool.indexOf(pattern);
            if (existing >= 0) {
                return existing;
            }
            int index = patternPool.size();
            patternPool.add(pattern);
            return index;
        }
        
        public void setOutput(int outputIndex, long amountPerCraft) {
            this.outputIndex = outputIndex;
            this.outputAmountPerCraft = amountPerCraft;
        }
        
        public void emit(Opcode op) {
            codeStream.write(op.code);
        }
        
        public void emitShort(int value) {
            codeStream.write((value >> 8) & 0xFF);
            codeStream.write(value & 0xFF);
        }
        
        public void emitLong(long value) {
            for (int i = 56; i >= 0; i -= 8) {
                codeStream.write((int)((value >> i) & 0xFF));
            }
        }
        
        public void emitPushItem(int constantIndex, long count) {
            emit(Opcode.PUSH_ITEM);
            emitShort(constantIndex);
            emitLong(count);
        }
        
        public void emitPushLong(long value) {
            emit(Opcode.PUSH_LONG);
            emitLong(value);
        }
        
        public void emitExtractIngredient(int constantIndex) {
            emit(Opcode.EXTRACT_INGREDIENT);
            emitShort(constantIndex);
        }
        
        public void emitRecordIngredient(int constantIndex) {
            emit(Opcode.RECORD_INGREDIENT);
            emitShort(constantIndex);
        }
        
        public void emitRecordMissing(int constantIndex) {
            emit(Opcode.RECORD_MISSING);
            emitShort(constantIndex);
        }
        
        public void emitRecordOutput(int constantIndex) {
            emit(Opcode.RECORD_OUTPUT);
            emitShort(constantIndex);
        }
        
        public void emitRecordPattern(int patternIndex) {
            emit(Opcode.RECORD_PATTERN);
            emitShort(patternIndex);
        }
        
        public void emitCallByKey(int constantIndex) {
            emit(Opcode.CALL_BY_KEY);
            emitShort(constantIndex);
        }
        
        /** Marks the next CALL_BY_KEY as coming from a replacement-enabled (fuzzy) input slot. */
        public void emitFuzzySlot() {
            emit(Opcode.FUZZY_SLOT);
        }
        
        public void emitInsertOutput(int constantIndex) {
            emit(Opcode.INSERT_OUTPUT);
            emitShort(constantIndex);
        }

        /** Records a one-time catalyst/container seed demand (pops the seed amount). */
        public void emitCatalystSeed(int constantIndex) {
            emit(Opcode.CATALYST_SEED);
            emitShort(constantIndex);
        }

        /**
         * Records a finite-use (durability) tool demand. Pops {@code uses} then {@code amount}
         * (i.e. stack: ..., amount, uses), and stores the (key → [amount, uses]) rate in the
         * bundle so the aggregation can demand {@code amount × ceil(times/uses)} tools.
         */
        public void emitDurabilityTool(int constantIndex) {
            emit(Opcode.DURABILITY_TOOL);
            emitShort(constantIndex);
        }
        
        public void emitHalt() {
            emit(Opcode.HALT);
        }
        
        /** Get current bytecode length (before build) */
        public int getCodeLength() {
            return codeStream.size();
        }
        
        public CraftingBytecode build() {
            if (outputIndex < 0) {
                throw new IllegalStateException("Output not set");
            }
            emitHalt();
            return new CraftingBytecode(
                constantPool.toArray(new AEKey[0]),
                patternPool.toArray(new IPatternDetails[0]),
                codeStream.toByteArray(),
                outputIndex,
                outputAmountPerCraft
            );
        }
    }
}
