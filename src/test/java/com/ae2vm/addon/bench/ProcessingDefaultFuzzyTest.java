package com.ae2vm.addon.bench;

import com.ae2vm.shim.api.crafting.IPatternDetails;
import com.ae2vm.shim.api.networking.crafting.ICraftingPlan;
import com.ae2vm.shim.api.stacks.AEKey;
import com.ae2vm.shim.api.stacks.KeyCounter;
import com.ae2vm.addon.compiler.PatternCompiler;
import com.ae2vm.addon.vm.CraftingBytecode;
import com.ae2vm.addon.vm.CraftingVM;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * v1.10.x regression: processing recipes (处理配方) DEFAULT to fuzzy matching. A
 * processing pattern input is a single exact variant (AE2's AEProcessingPattern has no
 * substitution flag), but the real ME network may hold the SAME item under a DIFFERENT
 * NBT variant — the GTL greenhouse fake-craft block / Mystical Agriculture essence
 * ("材料缺失但不知道哪里缺失", "有方块却报缺失"). The VM must count the item's full
 * fuzzy family (same primary key, any NBT) as satisfying the slot, mirroring AE2
 * native's {@code getValidItemTemplates} → {@code findFuzzyTemplates}.
 *
 * <p>This test drives the VM through a real {@link VariantKey} (shared primary key,
 * NBT discriminator) so the {@code KeyCounter.findFuzzy(...IGNORE_ALL)} grouping works
 * exactly like real {@code AEItemKey} NBT variants, with a {@code FakeGrid} feeding
 * {@code realStockOf} / {@code fuzzyFamilyOf} live network stock.
 */
public class ProcessingDefaultFuzzyTest {

    /** Minimal IGrid whose storage serves a fixed {@code Map<VariantKey, Long>}. */
    private static final class FakeGrid extends BenchV8Grid {
        private final Map<VariantKey, Long> stock;

        FakeGrid(Map<VariantKey, Long> stock) {
            this.stock = stock;
        }

        /** (v8) 网格能力走 {@code IGridCache}；bench 只用它的形状（§5 编译保留）。 */
        @SuppressWarnings("unchecked")
        @Override
        public <C extends appeng.api.networking.IGridCache> C getCache(
                Class<? extends appeng.api.networking.IGridCache> iface) {
            if (iface.getName().equals(com.ae2vm.shim.api.networking.storage.IStorageService.class.getName())) {
                return (C) (Object) new StorageImpl();
            }
            return null;
        }

        private final class StorageImpl implements com.ae2vm.shim.api.networking.storage.IStorageService {
            @Override
            public <T extends appeng.api.storage.data.IAEStack<T>> appeng.api.storage.IMEMonitor<T> getInventory(
                    appeng.api.storage.IStorageChannel<T> channel) {
                return null;
            }

            @Override
            public <T extends appeng.api.storage.data.IAEStack<T>> void postAlterationOfStoredItems(
                    appeng.api.storage.IStorageChannel<T> channel, Iterable<T> change,
                    appeng.api.networking.security.IActionSource src) {
            }

            @Override
            public void registerAdditionalCellProvider(appeng.api.storage.cells.ICellProvider provider) {
            }

            @Override
            public void unregisterAdditionalCellProvider(appeng.api.storage.cells.ICellProvider provider) {
            }
        }
    }

    /** Simulation state backed by the same VariantKey stock map (fuzzy-parent aware). */
    private static final class VariantSimState extends com.ae2vm.shim.crafting.inv.CraftingSimulationState
            implements com.ae2vm.shim.crafting.inv.CraftingSimulationStateAccessor {
        private final Map<VariantKey, Long> stock;

        VariantSimState(Map<VariantKey, Long> stock) {
            this.stock = stock;
        }

@Override
        protected appeng.api.storage.data.IAEStack simulateExtractParent(appeng.api.storage.data.IAEStack input) {
            // v9: bench 字符串键无法跨越 IAEStack 边界（编译保留，§5）
            throw new UnsupportedOperationException("bench sim-state cannot bridge into the v9 IAEStack world");
        }

@Override
        protected java.util.Collection<appeng.api.storage.data.IAEStack> findFuzzyParent(appeng.api.storage.data.IAEStack input) {
            throw new UnsupportedOperationException("bench sim-state cannot bridge into the v9 IAEStack world");
        }

        @Override
        public double getBytes() {
            try {
                java.lang.reflect.Field f = com.ae2vm.shim.crafting.inv.CraftingSimulationState.class
                        .getDeclaredField("bytes");
                f.setAccessible(true);
                return f.getDouble(this);
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException("Cannot read CraftingSimulationState.bytes", e);
            }
        }
    }

    /** Processing pattern (NOT molecular-assembler supported) with a single exact VariantKey input. */
    private static final class ProcessingPattern implements IPatternDetails, BenchPatternAccess {
        private final com.ae2vm.shim.api.crafting.IPatternDetails.IInput[] inputs;
        private final com.ae2vm.shim.api.stacks.GenericStack[] outputs;

        ProcessingPattern(VariantKey product, VariantKey input) {
            this.inputs = new com.ae2vm.shim.api.crafting.IPatternDetails.IInput[] {
                new SingleVariantInput(input)
            };
            this.outputs = new com.ae2vm.shim.api.stacks.GenericStack[] {
                new com.ae2vm.shim.api.stacks.GenericStack(product, 1)
            };
        }

        
        public com.ae2vm.shim.api.stacks.AEItemKey getDefinition() {
            return null;
        }

        @Override
        public com.ae2vm.shim.api.crafting.IPatternDetails.IInput[] getInputs() {
            return inputs;
        }

        @Override
        public com.ae2vm.shim.api.stacks.GenericStack[] benchOutputs() {
            return outputs;
        }
    }

    /** IInput with exactly one possible input (the encoded variant) — exact, no substitution. */
    private static final class SingleVariantInput implements com.ae2vm.shim.api.crafting.IPatternDetails.IInput, BenchInputAccess {
        private final com.ae2vm.shim.api.stacks.GenericStack[] possible;

        SingleVariantInput(VariantKey input) {
            this.possible = new com.ae2vm.shim.api.stacks.GenericStack[] {
                new com.ae2vm.shim.api.stacks.GenericStack(input, 1)
            };
        }

        @Override
        public com.ae2vm.shim.api.stacks.GenericStack[] benchPossibleInputs() {
            return possible;
        }

        @Override
        public long getMultiplier() {
            return 1;
        }

        public boolean isValid(com.ae2vm.shim.api.stacks.AEKey input, net.minecraft.world.World level) {
            return input.equals(possible[0].what());
        }

        @Override
        public com.ae2vm.shim.api.stacks.AEKey benchContainerItem(com.ae2vm.shim.api.stacks.AEKey template) {
            return null;
        }
    }

    private static ICraftingPlan run(long amount, VariantKey input,
            Map<VariantKey, Long> stock, VariantKey product) {
        PatternCompiler.clearCache();
        PatternCompiler.clearFuzzyGroups();
        IPatternDetails pattern = new ProcessingPattern(product, input);
        PatternCompiler.compileIfAbsent(pattern);
        CraftingBytecode req = PatternCompiler.compileRequest(pattern, amount);
        FakeGrid grid = new FakeGrid(new HashMap<>(stock));
        CraftingVM vm = new CraftingVM(grid, k -> null); // input has no sub-pattern (leaf)
        return vm.execute(req, new VariantSimState(stock));
    }

    private static Map<String, Long> used(ICraftingPlan p) {
        TreeMap<String, Long> out = new TreeMap<>();
        for (var e : BenchCompat.used(p).entrySet()) {
            out.put(e.getKey().toString(), e.getValue());
        }
        return out;
    }

    private static Map<String, Long> missing(ICraftingPlan p) {
        TreeMap<String, Long> out = new TreeMap<>();
        for (var e : BenchCompat.missing(p).entrySet()) {
            out.put(e.getKey().toString(), e.getValue());
        }
        return out;
    }

    /**
     * GTL greenhouse fake-craft: the processing pattern input is encoded as the exact
     * variant {@code greenhouse_block[A]} (no substitution flag), but the network holds
     * {@code greenhouse_block[B]} — a different NBT variant of the same item. The VM must
     * treat B as satisfying the slot: NO missing, and usedItems names the ACTUAL variant B
     * (so the CPU extracts the real key at submit time).
     */
    @Test
    void processingInputSatisfiedByDifferentNbtVariant() {
        VariantKey product = VariantKey.of("virtual_greenhouse", "");
        VariantKey encoded = VariantKey.of("greenhouse_block", "A");
        VariantKey stored = VariantKey.of("greenhouse_block", "B");
        Map<VariantKey, Long> stock = new HashMap<>();
        stock.put(stored, 5L);

        ICraftingPlan plan = run(5, encoded, stock, product);

        assertTrue(plan.missingItems().isEmpty(),
                "processing input must be satisfied by a different-NBT variant of the same item, missing=" + missing(plan));
        assertEquals(5L, BenchCompat.usedOf(plan, stored),
                "the ACTUAL variant (B) must be recorded in usedItems, used=" + used(plan));
        assertEquals(0L, BenchCompat.usedOf(plan, encoded),
                "the empty encoded variant (A) must not be recorded, used=" + used(plan));
    }

    /**
     * Sanity: when NO variant of the item is stocked, the processing input is genuinely
     * missing (the slot must not silently vanish).
     */
    @Test
    void processingInputTrulyMissingWhenNoVariantStocked() {
        VariantKey product = VariantKey.of("virtual_greenhouse", "");
        VariantKey encoded = VariantKey.of("greenhouse_block", "A");
        Map<VariantKey, Long> stock = new HashMap<>();

        ICraftingPlan plan = run(5, encoded, stock, product);

        assertEquals(5L, BenchCompat.missingOf(plan, encoded),
                "no stocked variant → the encoded processing input is missing, missing=" + missing(plan));
    }

    /**
     * UselessMod 万象样板蜜脾场景（2026-08-10 聊天确认）：Productive Bees 蜜蜂蜜脾是
     * 同一物品、不同 {@code bee_type} 组件（NBT）的变体（绿蜜脾 / 白蜜脾 / 黑蜜脾...）。
     * UselessMod 的万象样板（OmniversalPatternDetails）把蜜脾编码成具体 {@code bee_type}
     * 变体（item-id 输入槽），但网络库存里存的往往是另一个 {@code bee_type} 变体
     * （"绿蜜脾用来做铀"——蜜脾 NBT 不同导致无法识别）。VM 必须把同物品任意
     * {@code bee_type} 变体视为满足槽位（处理配方默认模糊 + findFuzzy IGNORE_ALL），
     * 并在 usedItems 记录实际消耗的变体（CPU 提交时提取真实 key）。
     */
    @Test
    void honeycombBeeTypeVariantSatisfiesOmniversalInput() {
        VariantKey product = VariantKey.of("uranium", "");
        VariantKey encoded = VariantKey.of("honeycomb", "white"); // 万象样板编码的白蜜脾
        VariantKey stored = VariantKey.of("honeycomb", "green");  // 库存里的绿蜜脾（另一 bee_type）
        Map<VariantKey, Long> stock = new HashMap<>();
        stock.put(stored, 5L);

        ICraftingPlan plan = run(5, encoded, stock, product);

        assertTrue(plan.missingItems().isEmpty(),
                "honeycomb must be satisfied by a different bee_type variant, missing=" + missing(plan));
        assertEquals(5L, BenchCompat.usedOf(plan, stored),
                "the ACTUAL bee_type variant (green) must be recorded in usedItems");
        assertEquals(0L, BenchCompat.usedOf(plan, encoded),
                "the encoded bee_type variant (white) must NOT be recorded");
    }
}
