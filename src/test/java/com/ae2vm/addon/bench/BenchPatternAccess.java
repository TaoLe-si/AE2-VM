package com.ae2vm.addon.bench;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import appeng.api.storage.data.IAEItemStack;
import appeng.api.storage.data.IAEStack;
import net.minecraft.world.item.ItemStack;

/**
 * (v9, 1.17.1) Bench-side access to a pattern's outputs as shim {@link GenericStack}s.
 * <p>
 * AE2 v9's {@code IPatternDetails} speaks {@code IAEStack[]}. Every bench
 * {@code IPatternDetails} implementation therefore keeps its key-based data under
 * {@link #benchOutputs()} and inherits the v9-surface defaults declared here, so bench
 * classes stay one-liners.
 */
public interface BenchPatternAccess extends IPatternDetails {

    /** The pattern's outputs as shim GenericStacks (first = primary). */
    GenericStack[] benchOutputs();

    /**
     * 桥到产品侧的 {@code IAEStack[]}。bench 键已是真 {@code AEItemKey}（见 {@link BenchAEKey}），
     * 所以 {@code GenericStack.what().toStack(amount)} 就是产品实现，不再抛"无法桥接"
     * —— 那个 UOE 曾被 {@code PatternCompiler.hasUsableOutput} 的
     * {@code catch (RuntimeException) { return false; }} 吞掉，表现为全部用例
     * "Failed to compile pattern"。
     */
    @Override
    default IAEStack[] getOutputs() {
        GenericStack[] stacks = benchOutputs();
        if (stacks == null) {
            return new IAEStack[0];
        }
        IAEStack[] out = new IAEStack[stacks.length];
        for (int i = 0; i < stacks.length; i++) {
            if (stacks[i] != null && stacks[i].what() != null) {
                out[i] = stacks[i].what().toStack(stacks[i].amount());
            }
        }
        return out;
    }

    /** 产品侧要的"样板物品"表示：取主输出的 MC 栈（假栈自己造的 ItemStack）。 */
    @Override
    default ItemStack copyDefinition() {
        GenericStack[] stacks = benchOutputs();
        if (stacks == null || stacks.length == 0 || stacks[0] == null || stacks[0].what() == null) {
            return ItemStack.EMPTY;
        }
        return ((IAEItemStack) ((AEItemKey) stacks[0].what()).getTemplate()).createItemStack();
    }
}
