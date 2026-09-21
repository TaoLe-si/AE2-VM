package com.ae2vm.addon.bench;

import com.ae2vm.shim.api.crafting.IPatternDetails;
import com.ae2vm.shim.api.stacks.GenericStack;

/**
 * (v9, 1.17.1) Bench-side access to a pattern's outputs as shim {@link GenericStack}s.
 * <p>
 * AE2 v9's {@code IPatternDetails} speaks {@code IAEStack[]}, which string bench keys
 * cannot produce. Every bench {@code IPatternDetails} implementation therefore keeps
 * its real, key-based data under {@link #benchOutputs()} and inherits this interface's
 * UOE defaults for the v9 surface ({@code getOutputs} / {@code copyDefinition}).
 * Extending {@code IPatternDetails} here lets those defaults legally override the
 * inherited abstract methods, so bench classes stay one-liners.
 */
public interface BenchPatternAccess extends IPatternDetails {

    /** The pattern's outputs as shim GenericStacks (first = primary). */
    GenericStack[] benchOutputs();

    /**
     * 桥到产品侧的 {@code IAEStack[]}。bench 键已是真 {@code AEItemKey}（见 {@link BenchAEKey}），
     * 所以 {@code GenericStack.what().toStack(amount)} 就是产品实现。
     */
    @Override
    default appeng.api.storage.data.IAEStack[] getOutputs() {
        GenericStack[] stacks = benchOutputs();
        if (stacks == null) {
            return new appeng.api.storage.data.IAEStack[0];
        }
        appeng.api.storage.data.IAEStack[] out = new appeng.api.storage.data.IAEStack[stacks.length];
        for (int i = 0; i < stacks.length; i++) {
            if (stacks[i] != null && stacks[i].what() != null) {
                out[i] = stacks[i].what().toStack(stacks[i].amount());
            }
        }
        return out;
    }

    /** 产品侧要的"样板物品"表示：取主输出的 MC 栈（假栈自己造的 ItemStack）。 */
    @Override
    default net.minecraft.item.ItemStack copyDefinition() {
        GenericStack[] stacks = benchOutputs();
        if (stacks == null || stacks.length == 0 || stacks[0] == null || stacks[0].what() == null) {
            // rv4/1.10.2 的 ItemStack 没有 EMPTY（1.12 才加），空栈就是 null。
            return null;
        }
        return ((appeng.api.storage.data.IAEItemStack) ((com.ae2vm.shim.api.stacks.AEItemKey)
                stacks[0].what()).getTemplate()).getItemStack();
    }
}
