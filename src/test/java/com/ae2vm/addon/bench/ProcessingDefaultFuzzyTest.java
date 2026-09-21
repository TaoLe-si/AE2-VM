package com.ae2vm.addon.bench;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
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
 * <p>This test drives the VM through a real {@link AEKey} (shared primary key,
 * NBT discriminator) so the {@code KeyCounter.findFuzzy(...IGNORE_ALL)} grouping works
 * exactly like real {@code AEItemKey} NBT variants, with a {@code FakeGrid} feeding
 * {@code realStockOf} / {@code fuzzyFamilyOf} live network stock.
 */
public class ProcessingDefaultFuzzyTest {

    /** Minimal IGrid whose storage serves a fixed {@code Map<AEKey, Long>}. */
    private static final class FakeGrid implements appeng.api.networking.IGrid {
        private final Map<AEKey, Long> stock;

        FakeGrid(Map<AEKey, Long> stock) {
            this.stock = stock;
        }

        @Override
        public <C extends appeng.api.networking.IGridService> C getService(Class<C> iface) {
            if (iface == appeng.api.networking.storage.IStorageService.class) {
                return iface.cast(new StorageImpl());
            }
            return null;
        }

        private final class StorageImpl implements appeng.api.networking.storage.IStorageService {
            @Override
            @SuppressWarnings("unchecked")
            public <T extends appeng.api.storage.data.IAEStack> appeng.api.storage.IMEMonitor<T> getInventory(
                    appeng.api.storage.IStorageChannel<T> channel) {
                return (appeng.api.storage.IMEMonitor<T>) FakeBenchGrid.benchMonitor(stock);
            }

            @Override
            public <T extends appeng.api.storage.data.IAEStack> void postAlterationOfStoredItems(
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



        // --- Unused IGrid surface ---
        @Override
        public <T extends appeng.api.networking.events.GridEvent> T postEvent(T ev) {
            return ev;
        }

        @Override
        public Iterable<Class<?>> getMachineClasses() {
            return java.util.Set.of();
        }

        @Override
        public Iterable<appeng.api.networking.IGridNode> getMachineNodes(Class<?> machineClass) {
            return java.util.Set.of();
        }

        @Override
        public <T> java.util.Set<T> getMachines(Class<T> machineClass) {
            return java.util.Set.of();
        }

        @Override
        public <T> java.util.Set<T> getActiveMachines(Class<T> machineClass) {
            return java.util.Set.of();
        }

        @Override
        public Iterable<appeng.api.networking.IGridNode> getNodes() {
            return java.util.Set.of();
        }

        @Override
        public boolean isEmpty() {
            return true;
        }

        @Override
        public appeng.api.networking.IGridNode getPivot() {
            return null;
        }

        @Override
        public int size() {
            return 0;
        }

        @Override
        public appeng.api.networking.ticking.ITickManager getTickManager() {
            return null;
        }

        @Override
        public appeng.api.networking.energy.IEnergyService getEnergyService() {
            return null;
        }

        @Override
        public appeng.api.networking.crafting.ICraftingService getCraftingService() {
            return null;
        }

        @Override
        public appeng.api.networking.pathing.IPathingService getPathingService() {
            return null;
        }

    }

    /** Simulation state backed by the same AEKey stock map (fuzzy-parent aware). */
    private static final class VariantSimState extends appeng.crafting.inv.CraftingSimulationState
            implements com.ae2vm.addon.mixin.CraftingSimulationStateAccessor {
        private final Map<AEKey, Long> stock;

        VariantSimState(Map<AEKey, Long> stock) {
            this.stock = stock;
        }

        @Override
        protected appeng.api.storage.data.IAEStack simulateExtractParent(appeng.api.storage.data.IAEStack input) {
            appeng.api.stacks.AEKey k = BenchSimulationState.asKey(input);
            Long have = k == null ? null : stock.get(k);
            if (have == null || have.longValue() <= 0L) {
                return null;
            }
            long take = Math.min(input.getStackSize(), have.longValue());
            return take <= 0L ? null : appeng.api.storage.data.IAEStack.copy(input, take);
        }

        @Override
        protected java.util.Collection<appeng.api.storage.data.IAEStack> findFuzzyParent(appeng.api.storage.data.IAEStack input) {
            appeng.api.stacks.AEKey k = BenchSimulationState.asKey(input);
            java.util.List<appeng.api.storage.data.IAEStack> out = new java.util.ArrayList<>();
            if (k == null) {
                return out;
            }
            for (java.util.Map.Entry<appeng.api.stacks.AEKey, Long> e : stock.entrySet()) {
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
            try {
                java.lang.reflect.Field f = appeng.crafting.inv.CraftingSimulationState.class
                        .getDeclaredField("bytes");
                f.setAccessible(true);
                return f.getDouble(this);
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException("Cannot read CraftingSimulationState.bytes", e);
            }
        }
    }

    /** Processing pattern (NOT molecular-assembler supported) with a single exact AEKey input. */
    private static final class ProcessingPattern implements IPatternDetails, BenchPatternAccess {
        private final appeng.api.crafting.IPatternDetails.IInput[] inputs;
        private final appeng.api.stacks.GenericStack[] outputs;

        ProcessingPattern(AEKey product, AEKey input) {
            this.inputs = new appeng.api.crafting.IPatternDetails.IInput[] {
                new SingleVariantInput(input)
            };
            this.outputs = new appeng.api.stacks.GenericStack[] {
                new appeng.api.stacks.GenericStack(product, 1)
            };
        }

        @Override
        public appeng.api.crafting.IPatternDetails.IInput[] getInputs() {
            return inputs;
        }

        @Override
        public appeng.api.stacks.GenericStack[] benchOutputs() {
            return outputs;
        }
    }

    /** IInput with exactly one possible input (the encoded variant) — exact, no substitution. */
    private static final class SingleVariantInput implements appeng.api.crafting.IPatternDetails.IInput, BenchInputAccess {
        private final appeng.api.stacks.GenericStack[] possible;

        SingleVariantInput(AEKey input) {
            this.possible = new appeng.api.stacks.GenericStack[] {
                new appeng.api.stacks.GenericStack(input, 1)
            };
        }

        @Override
        public appeng.api.stacks.GenericStack[] benchPossibleInputs() {
            return possible;
        }

        @Override
        public long getMultiplier() {
            return 1;
        }

        public boolean isValid(appeng.api.stacks.AEKey input, net.minecraft.world.level.Level level) {
            return input.equals(possible[0].what());
        }

        @Override
        public appeng.api.stacks.AEKey benchContainerItem(appeng.api.stacks.AEKey template) {
            return null;
        }
    }

    private static ICraftingPlan run(long amount, AEKey input,
            Map<AEKey, Long> stock, AEKey product) {
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
        AEKey product = VariantKey.of("virtual_greenhouse", "");
        AEKey encoded = VariantKey.of("greenhouse_block", "A");
        AEKey stored = VariantKey.of("greenhouse_block", "B");
        Map<AEKey, Long> stock = new HashMap<>();
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
        AEKey product = VariantKey.of("virtual_greenhouse", "");
        AEKey encoded = VariantKey.of("greenhouse_block", "A");
        Map<AEKey, Long> stock = new HashMap<>();

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
        AEKey product = VariantKey.of("uranium", "");
        AEKey encoded = VariantKey.of("honeycomb", "white"); // 万象样板编码的白蜜脾
        AEKey stored = VariantKey.of("honeycomb", "green");  // 库存里的绿蜜脾（另一 bee_type）
        Map<AEKey, Long> stock = new HashMap<>();
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
