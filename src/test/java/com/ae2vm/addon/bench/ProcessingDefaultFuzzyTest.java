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
 * <p>This test drives the VM through a real {@link VariantKey} (shared primary key,
 * NBT discriminator) so the {@code KeyCounter.findFuzzy(...IGNORE_ALL)} grouping works
 * exactly like real {@code AEItemKey} NBT variants, with a {@code FakeGrid} feeding
 * {@code realStockOf} / {@code fuzzyFamilyOf} live network stock.
 */
public class ProcessingDefaultFuzzyTest {

    /** Minimal IGrid whose storage serves a fixed {@code Map<VariantKey, Long>}. */
    private static final class FakeGrid implements appeng.api.networking.IGrid {
        private final Map<VariantKey, Long> stock;

        FakeGrid(Map<VariantKey, Long> stock) {
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
            public appeng.api.storage.MEStorage getInventory() {
                return new MEStorageImpl();
            }

            @Override
            public KeyCounter getCachedInventory() {
                var out = new KeyCounter();
                stock.forEach((k, v) -> {
                    if (v > 0) out.add(k, v);
                });
                return out;
            }

            @Override
            public void addGlobalStorageProvider(appeng.api.storage.IStorageProvider cc) {
            }

            @Override
            public void removeGlobalStorageProvider(appeng.api.storage.IStorageProvider cc) {
            }

            @Override
            public void refreshNodeStorageProvider(appeng.api.networking.IGridNode node) {
            }

            @Override
            public void refreshGlobalStorageProvider(appeng.api.storage.IStorageProvider provider) {
            }

            @Override
            public void invalidateCache() {
            }
        }

        private final class MEStorageImpl implements appeng.api.storage.MEStorage {
            @Override
            public net.minecraft.network.chat.Component getDescription() {
                return net.minecraft.network.chat.Component.literal("fake");
            }

            @Override
            public long extract(AEKey what, long amount, appeng.api.config.Actionable mode,
                    appeng.api.networking.security.IActionSource source) {
                if (!(what instanceof VariantKey k)) return 0;
                long avail = stock.getOrDefault(k, 0L);
                long got = Math.min(avail, amount);
                if (mode == appeng.api.config.Actionable.MODULATE) {
                    stock.put(k, avail - got);
                }
                return got;
            }

            @Override
            public void getAvailableStacks(KeyCounter out) {
                stock.forEach((k, v) -> {
                    if (v > 0) out.add(k, v);
                });
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

        @Override
        public appeng.api.networking.spatial.ISpatialService getSpatialService() {
            return null;
        }

        @Override
        public void export(com.google.gson.stream.JsonWriter jsonWriter) throws java.io.IOException {
        }
    }

    /** Simulation state backed by the same VariantKey stock map (fuzzy-parent aware). */
    private static final class VariantSimState extends appeng.crafting.inv.CraftingSimulationState
            implements com.ae2vm.addon.mixin.CraftingSimulationStateAccessor {
        private final Map<VariantKey, Long> stock;

        VariantSimState(Map<VariantKey, Long> stock) {
            this.stock = stock;
        }

        @Override
        protected long simulateExtractParent(AEKey what, long amount) {
            long available = what instanceof VariantKey k ? stock.getOrDefault(k, 0L) : 0L;
            return Math.min(available, amount);
        }

        @Override
        protected Iterable<AEKey> findFuzzyParent(AEKey input) {
            // Return every variant sharing the input's primary key (same "item").
            java.util.List<AEKey> variants = new java.util.ArrayList<>();
            for (VariantKey k : stock.keySet()) {
                if (k.base().equals(input.getPrimaryKey())) {
                    variants.add(k);
                }
            }
            return variants;
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

    /** Processing pattern (NOT molecular-assembler supported) with a single exact VariantKey input. */
    private static final class ProcessingPattern implements IPatternDetails {
        private final appeng.api.crafting.IPatternDetails.IInput[] inputs;
        private final java.util.List<appeng.api.stacks.GenericStack> outputs;

        ProcessingPattern(VariantKey product, VariantKey input) {
            this.inputs = new appeng.api.crafting.IPatternDetails.IInput[] {
                new SingleVariantInput(input)
            };
            this.outputs = List.of(new appeng.api.stacks.GenericStack(product, 1));
        }

        @Override
        public appeng.api.stacks.AEItemKey getDefinition() {
            return null;
        }

        @Override
        public appeng.api.crafting.IPatternDetails.IInput[] getInputs() {
            return inputs;
        }

        @Override
        public java.util.List<appeng.api.stacks.GenericStack> getOutputs() {
            return outputs;
        }
    }

    /** IInput with exactly one possible input (the encoded variant) — exact, no substitution. */
    private static final class SingleVariantInput implements appeng.api.crafting.IPatternDetails.IInput {
        private final appeng.api.stacks.GenericStack[] possible;

        SingleVariantInput(VariantKey input) {
            this.possible = new appeng.api.stacks.GenericStack[] {
                new appeng.api.stacks.GenericStack(input, 1)
            };
        }

        @Override
        public appeng.api.stacks.GenericStack[] getPossibleInputs() {
            return possible;
        }

        @Override
        public long getMultiplier() {
            return 1;
        }

        @Override
        public boolean isValid(appeng.api.stacks.AEKey input, net.minecraft.world.level.Level level) {
            return input.equals(possible[0].what());
        }

        @Override
        public appeng.api.stacks.AEKey getRemainingKey(appeng.api.stacks.AEKey template) {
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
        for (var e : p.usedItems()) {
            out.put(e.getKey().toString(), e.getLongValue());
        }
        return out;
    }

    private static Map<String, Long> missing(ICraftingPlan p) {
        TreeMap<String, Long> out = new TreeMap<>();
        for (var e : p.missingItems()) {
            out.put(e.getKey().toString(), e.getLongValue());
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
        assertEquals(5L, plan.usedItems().get(stored),
                "the ACTUAL variant (B) must be recorded in usedItems, used=" + used(plan));
        assertEquals(0L, plan.usedItems().get(encoded),
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

        assertEquals(5L, plan.missingItems().get(encoded),
                "no stocked variant → the encoded processing input is missing, missing=" + missing(plan));
    }
}
