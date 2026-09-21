package com.ae2vm.addon.bench;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import appeng.api.storage.data.IAEItemStack;
import appeng.api.storage.data.IAEStack;

import com.ae2vm.shim.api.stacks.AEItemKey;
import com.ae2vm.shim.api.stacks.AEKey;
import com.ae2vm.shim.crafting.inv.CraftingSimulationState;
import com.ae2vm.shim.crafting.inv.CraftingSimulationStateAccessor;

/**
 * 内存版 {@link CraftingSimulationState}，让 VM 能离线跑（无 IGrid、无 MC world）。
 * 同时实现 {@link CraftingSimulationStateAccessor}，{@code buildPlan} 读 {@code bytes}
 * 的路径与游戏里的 mixin accessor 完全一致。
 *
 * <p>语义与生产的 {@code RealtimeNetworkCraftingSimulationState} 对齐：
 * {@code simulateExtractParent} 精确匹配（物品+damage，不含数量）且**不扣减**快照，
 * {@code findFuzzyParent} 按"同一 Item 的任意变体"（{@code FuzzyMode.IGNORE_ALL} 口径）。
 *
 * <p>旧版这里两个方法直接抛 UOE（当时的 bench 键是字符串、无法桥到 {@code IAEStack}）；
 * 现在 bench 键就是真 {@code AEItemKey}（{@link BenchAEKey}/{@link VariantKey}），所以能如实桥接。
 */
public final class BenchSimulationState extends CraftingSimulationState
        implements CraftingSimulationStateAccessor {

    private final Map<AEKey, Long> stock;

    public BenchSimulationState(Map<AEKey, Long> stock) {
        this.stock = stock;
    }

    @Override
    protected IAEStack simulateExtractParent(IAEStack input) {
        AEKey k = asKey(input);
        Long have = k == null ? null : stock.get(k);
        if (have == null || have.longValue() <= 0L) {
            return null;
        }
        long take = Math.min(input.getStackSize(), have.longValue());
        if (take <= 0L) {
            return null;
        }
        IAEStack got = input.copy();
        got.setStackSize(take);
        return got;
    }

    @Override
    protected Collection<IAEStack> findFuzzyParent(IAEStack input) {
        AEKey k = asKey(input);
        List<IAEStack> out = new ArrayList<IAEStack>();
        if (k == null) {
            return out;
        }
        for (Map.Entry<AEKey, Long> e : stock.entrySet()) {
            if (e.getValue() == null || e.getValue().longValue() <= 0L) {
                continue;
            }
            if (e.getKey() != null && e.getKey().getItem() == k.getItem()) {
                out.add(e.getKey().toStack(e.getValue().longValue()));
            }
        }
        return out;
    }

    @Override
    public double getBytes() {
        // 与 @Accessor mixin 同语义：读父类的私有 bytes 字段。
        try {
            Field f = CraftingSimulationState.class.getDeclaredField("bytes");
            f.setAccessible(true);
            return f.getDouble(this);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("Cannot read CraftingSimulationState.bytes", e);
        }
    }

    /** {@code IAEStack} → 可作为 stock 键的 {@code AEItemKey}（identity 只看物品+damage）。 */
    private static AEKey asKey(IAEStack stack) {
        if (!(stack instanceof IAEItemStack)) {
            return null;
        }
        return AEItemKey.wrap((IAEItemStack) stack);
    }
}
