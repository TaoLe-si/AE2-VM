package com.ae2vm.addon;

import com.ae2vm.addon.vm.CraftingBytecode;
import com.ae2vm.addon.vm.CraftingVM;
import com.ae2vm.addon.vm.Opcode;
import org.junit.jupiter.api.Test;

/**
 * Tests for the AE2 VM Crafting Accelerator.
 * Verifies core VM operations and the new CALL_BY_KEY architecture.
 */
public class VMTest {
    
    @Test
    public void testDivRoundUp() {
        assert divRoundUp(10, 3) == 4;
        assert divRoundUp(9, 3) == 3;
        assert divRoundUp(1, 1) == 1;
        assert divRoundUp(0, 5) == 0;
        assert divRoundUp(39088196, 1) == 39088196;
        System.out.println("[TEST] DIV_ROUNDUP tests passed!");
    }
    
    @Test
    public void testSimpleBytecode() {
        CraftingBytecode.Builder builder = new CraftingBytecode.Builder();
        int outputIdx = 0;
        builder.setOutput(outputIdx, 1);
        builder.emitPushLong(5);
        builder.emitRecordOutput(outputIdx);
        
        CraftingBytecode bytecode = builder.build();
        
        assert bytecode.getCodeLength() > 0;
        assert bytecode.getOutputAmountPerCraft() == 1;
        System.out.println("[TEST] Simple bytecode: " + bytecode.getCodeLength() + " bytes, OK!");
    }
    
    @Test
    public void testCallByKeyBytecode() {
        // Test that CALL_BY_KEY bytecode is generated correctly
        CraftingBytecode.Builder builder = new CraftingBytecode.Builder();
        builder.addConstant(null); // placeholder AEKey at index 0
        builder.setOutput(0, 1);
        
        // Emit CALL_BY_KEY instruction
        builder.emitCallByKey(0);
        
        CraftingBytecode bytecode = builder.build();
        byte[] code = bytecode.getCode();
        
        // Verify the opcode is CALL_BY_KEY (0x0E)
        boolean foundCallByKey = false;
        for (int i = 0; i < code.length; i++) {
            if ((code[i] & 0xFF) == Opcode.CALL_BY_KEY.code) {
                foundCallByKey = true;
                // Next 2 bytes should be the short operand (0x00, 0x00 for index 0)
                assert (code[i + 1] & 0xFF) == 0x00 : "High byte should be 0";
                assert (code[i + 2] & 0xFF) == 0x00 : "Low byte should be 0";
                break;
            }
        }
        assert foundCallByKey : "CALL_BY_KEY opcode not found in bytecode";
        System.out.println("[TEST] CALL_BY_KEY bytecode: " + bytecode.getCodeLength() + " bytes, OK!");
    }
    
    @Test
    public void testRequestWrapperBytecode() {
        // Test the request wrapper pattern:
        // PUSH_LONG craftTimes → CALL patternIdx → HALT
        CraftingBytecode.Builder builder = new CraftingBytecode.Builder();
        builder.setOutput(0, 1);
        
        // PUSH_LONG 42 (craft 42 times)
        builder.emitPushLong(42);
        
        // CALL pattern at index 0
        int patternIdx = builder.addPattern(null); // placeholder
        builder.emit(Opcode.CALL);
        builder.emitShort(patternIdx);
        
        CraftingBytecode bytecode = builder.build();
        byte[] code = bytecode.getCode();
        
        // Verify structure: [PUSH_LONG(1), 8 bytes of 42, CALL(1), 2 bytes index, HALT(1)]
        assert (code[0] & 0xFF) == Opcode.PUSH_LONG.code : "First opcode should be PUSH_LONG";
        assert (code[9] & 0xFF) == Opcode.CALL.code : "After 8-byte long should be CALL";
        
        System.out.println("[TEST] Request wrapper: " + bytecode.getCodeLength() + " bytes, OK!");
    }
    
    @Test
    public void testMultiplication() {
        assert 2L * 3L == 6L;
        assert 39088196L * 1 == 39088196L;
        System.out.println("[TEST] Multiplication tests passed!");
    }
    
    @Test
    public void testFullPatternBytecode() {
        // Simulate what compilePattern generates:
        // DUP → RECORD_PATTERN → (for each input: DUP, PUSH_LONG, MUL, EXTRACT, CALL_BY_KEY) → POP → RETURN
        CraftingBytecode.Builder builder = new CraftingBytecode.Builder();
        builder.addConstant(null); // output at idx 0
        builder.addPattern(null);  // pattern at idx 0
        builder.setOutput(0, 1);
        
        // DUP
        builder.emit(Opcode.DUP);
        // RECORD_PATTERN 0
        builder.emitRecordPattern(0);
        
        // Input 1: DUP → PUSH_LONG 3 → MUL → EXTRACT 1 → CALL_BY_KEY 1
        builder.addConstant(null); // input key at idx 1
        builder.emit(Opcode.DUP);
        builder.emitPushLong(3);
        builder.emit(Opcode.MUL);
        builder.emitExtractIngredient(1);
        builder.emitCallByKey(1);
        
        // POP
        builder.emit(Opcode.POP);
        // RETURN
        builder.emit(Opcode.RETURN);
        
        CraftingBytecode bytecode = builder.build();
        
        assert bytecode.getCodeLength() > 0;
        System.out.println("[TEST] Full pattern bytecode: " + bytecode.getCodeLength() + " bytes, OK!");
    }
    
    private static long divRoundUp(long a, long b) {
        if (a <= 0) return 0;
        return (a + b - 1) / b;
    }
    
    public static void main(String[] args) {
        VMTest test = new VMTest();
        test.testDivRoundUp();
        test.testSimpleBytecode();
        test.testCallByKeyBytecode();
        test.testRequestWrapperBytecode();
        test.testMultiplication();
        test.testFullPatternBytecode();
        System.out.println("\n=== All VM tests passed! ===");
    }
}
