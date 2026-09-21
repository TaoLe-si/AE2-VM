package com.ae2vm.addon.bench;

import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.storage.data.IAEItemStack;
import appeng.api.storage.data.IAEStack;
import appeng.crafting.inv.CraftingSimulationState;
import com.ae2vm.addon.mixin.CraftingSimulationStateAccessor;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * In-memory {@link CraftingSimulationState} so the VM runs offline (no IGrid,
 * no Minecraft world). Implements {@link CraftingSimulationStateAccessor} so the
 * VM's {@code buildPlan} can read {@code bytes} exactly like the mixin accessor
 * does in-game.
 *
 * <p>语义与生产的 {@code RealtimeNetworkCraftingSimulationState} 对齐：
 * {@code simulateExtractParent} 精确匹配（物品+damage，不含数量）且**不扣减**快照，
 * {@code findFuzzyParent} 按"同一 Item 的任意变体"（{@code FuzzyMode.IGNORE_ALL} 口径）。
 * 不扣减是对的：v9 的基类 {@code extractItems} 自己会 {@code cacheFuzzy} 把父库存拉进
 * {@code modifiableCache}，MODULATE 时对缓存里的活对象 {@code decStackSize}（javap 实证），
 * 沙箱再扣一次就重复计账了。
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
        return take <= 0L ? null : IAEStack.copy(input, take);
    }

    @Override
    protected Collection<IAEStack> findFuzzyParent(IAEStack input) {
        AEKey k = asKey(input);
        List<IAEStack> out = new ArrayList<>();
        if (k == null) {
            return out;
        }
        for (Map.Entry<AEKey, Long> e : stock.entrySet()) {
            Long amount = e.getValue();
            if (amount == null || amount.longValue() <= 0L) {
                continue;
            }
            if (e.getKey() != null && e.getKey().getItem() == k.getItem()) {
                out.add(e.getKey().toStack(amount.longValue()));
            }
        }
        return out;
    }

    @Override
    public double getBytes() {
        // Same semantics as the @Accessor mixin: read the private parent `bytes` field.
        try {
            Field f = CraftingSimulationState.class.getDeclaredField("bytes");
            f.setAccessible(true);
            return f.getDouble(this);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("Cannot read CraftingSimulationState.bytes", e);
        }
    }

    /** {@code IAEStack} → 可作为 bench 库存键的 {@code AEItemKey}（identity 只看物品+damage）。 */
    static AEKey asKey(IAEStack stack) {
        return stack instanceof IAEItemStack is ? AEItemKey.wrap(is) : null;
    }
}
