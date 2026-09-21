package com.ae2vm.addon.bench;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.storage.data.IAEItemStack;
import appeng.api.storage.data.IAEStack;
import net.minecraft.world.level.Level;

/**
 * (v9, 1.17.1) Bench-side access to a pattern input's variants as shim
 * {@link GenericStack}s.
 * <p>
 * AE2 v9's {@code IPatternDetails.IInput} speaks {@code IAEStack}. Bench input
 * implementations keep their key-based data under {@link #benchPossibleInputs()} /
 * {@link #benchContainerItem(AEKey)} and inherit the v9-surface defaults declared here.
 */
public interface BenchInputAccess extends IPatternDetails.IInput {

    /** The input's possible variants as shim GenericStacks (first = primary). */
    GenericStack[] benchPossibleInputs();

    /**
     * (v1.10.x CATALYST) A returned/catalyst input is handed back unchanged: the
     * remaining key is the input itself; null otherwise.
     */
    AEKey benchContainerItem(AEKey template);

    /** 桥到产品侧的 {@code IAEStack[]}：bench 键已是真 {@code AEItemKey}。 */
    @Override
    default IAEStack[] getPossibleInputs() {
        GenericStack[] stacks = benchPossibleInputs();
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

    /** v9 surface: bench keys never reach isValid on the AE2 runtime path. */
    @Override
    default boolean isValid(IAEStack input, Level level) {
        return false;
    }

    /**
     * v9 surface：桥到 {@link #benchContainerItem(AEKey)}。
     *
     * <p>这里曾经是一份 {@code return null} 死桩 —— 于是 {@code PatternCompiler} 的
     * {@code remainingKey == inputKey} 判定永假，{@code CATALYST_SEED}/{@code DURABILITY_TOOL}
     * 两个操作码永远发不出去，返回型输入退化成"按次消耗"（催化剂 99 次派工报缺 98 个种子、
     * 5 次派工/每把用 2 次的工具报缺 5 把）。
     */
    @Override
    default IAEStack getContainerItem(IAEStack template) {
        if (!(template instanceof IAEItemStack is)) {
            return null;
        }
        AEKey key = AEItemKey.wrap(is);
        if (key == null) {
            return null;
        }
        AEKey remaining = benchContainerItem(key);
        return remaining == null ? null : remaining.toStack(1L);
    }
}
