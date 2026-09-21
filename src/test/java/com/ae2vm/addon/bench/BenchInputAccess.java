package com.ae2vm.addon.bench;

import com.ae2vm.shim.api.crafting.IPatternDetails;
import com.ae2vm.shim.api.stacks.AEKey;
import com.ae2vm.shim.api.stacks.GenericStack;

/**
 * (v9, 1.17.1) Bench-side access to a pattern input's variants as shim
 * {@link GenericStack}s.
 * <p>
 * AE2 v9's {@code IPatternDetails.IInput} speaks {@code IAEStack}, which string bench
 * keys cannot produce. Bench input implementations keep their key-based data under
 * {@link #benchPossibleInputs()} / {@link #benchContainerItem(AEKey)} and inherit
 * this interface's defaults for the v9 surface. Extending {@code IInput} here lets
 * those defaults legally override the inherited abstract methods.
 */
public interface BenchInputAccess extends IPatternDetails.IInput {

    /** The input's possible variants as shim GenericStacks (first = primary). */
    GenericStack[] benchPossibleInputs();

    /**
     * (v1.10.x CATALYST) A returned/catalyst input is handed back unchanged: the
     * remaining key is the input itself; null otherwise.
     */
    AEKey benchContainerItem(AEKey template);

    /**
     * 桥到产品侧的 {@code IAEStack[]}。bench 键现在就是真 {@code AEItemKey}
     * （见 {@link AEKey}/{@link AEKey}），所以 {@code GenericStack.what().toStack(amount)}
     * 能给出产品实现，不再需要抛"无法桥接"。
     */
    @Override
    default appeng.api.storage.data.IAEStack[] getPossibleInputs() {
        GenericStack[] stacks = benchPossibleInputs();
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

    /** v9 surface: bench keys never reach isValid on the AE2 runtime path. */
    @Override
    default boolean isValid(appeng.api.storage.data.IAEStack input, net.minecraft.world.World level) {
        return false;
    }

    /**
     * v9 surface：桥到 {@link #benchContainerItem(AEKey)}。
     *
     * <p>这里曾经是一份 {@code return null} 死桩 —— 于是 {@code PatternCompiler} 的
     * {@code remainingKey == inputKey} 判定永假，{@code CATALYST_SEED}/{@code DURABILITY_TOOL}
     * 两个操作码永远发不出去，返回型输入退化成"按次消耗"（催化剂 99 次派工报缺 98 个种子、
     * 5 次派工/每把用 2 次的工具报缺 5 把）。同文件的 {@link #getPossibleInputs()} 已随
     * R2 升级成真桥接，这一条漏改了。
     */
    @Override
    default appeng.api.storage.data.IAEStack getContainerItem(appeng.api.storage.data.IAEStack template) {
        if (!(template instanceof appeng.api.storage.data.IAEItemStack)) {
            return null;
        }
        AEKey key = com.ae2vm.shim.api.stacks.AEItemKey.wrap((appeng.api.storage.data.IAEItemStack) template);
        if (key == null) {
            return null;
        }
        AEKey remaining = benchContainerItem(key);
        return remaining == null ? null : remaining.toStack(1L);
    }
}
