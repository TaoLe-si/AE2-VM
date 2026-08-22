package com.ae2vm.addon.api;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEKey;
import appeng.me.service.CraftingService;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * (v1.15.x GTL CATALYST SLOTS) Resolves, per pattern, the set of inputs that a
 * GTL pattern-buffer machine satisfies from its OWN catalyst slots
 * ({@code CatalystItemStackHandler} / {@code CatalystFluidStackHandler}).
 *
 * <p>A GT recipe input with {@code content.chance <= 0} is a catalyst: the
 * machine keeps it in a dedicated slot and NEVER takes it from the AE network
 * (the GTL recipe handler removes it from the demand via {@code left.removeIf}).
 * The VM must therefore NOT count such an input as consumed demand:
 * <ul>
 *   <li>if it counted it, the plan reports a giant missing (observed:
 *       helium_plasma = 391.5B missing for antimatter → every binary-search
 *       candidate fails → the requester never crafts) — false NEGATIVE;</li>
 *   <li>if the input's variants were transiently empty and it got dropped, the
 *       plan was "feasible" while the machine could not run — false POSITIVE.</li>
 * </ul>
 * Correct behavior: skip the catalyst input in the compiled plan (the machine
 * provides it), and the GTL extract layer skips it at CPU execution.
 *
 * <p><b>v1.12.42 BUG FIX — pattern-identity cache key (NOT output key)</b><br>
 * Earlier keyed cache by {@code outKey = primary.what().toString()} with an
 * early-return guard {@code if (CATALYSTS_BY_OUTPUT.containsKey(outKey)) return}.
 * When two distinct GTL pattern buffers produced the SAME output key (e.g. one
 * buffer makes {@code antimatter_fuel_rod} via recipe chain A, another via
 * chain B, each with DIFFERENT catalyst slot items) the first buffer's catalysts
 * were cached and the second buffer's were silently discarded. Result:
 * <ul>
 *   <li>The second buffer's true catalyst inputs were NOT skipped during
 *       compilation → VM demanded them from the AE network → CPU extracted
 *       them and pushed into the second buffer's main inventory.</li>
 *   <li>AE2's CraftingService, scheduling parallel craft jobs across the two
 *       buffers, then allocated the first buffer's now-depleted catalyst slot
 *       from its main inventory → A's recipe chain read the same stock items
 *       B was supposed to own → "A产物用的原料被B产物用掉" cross-pollution.</li>
 * </ul>
 * Fix: cache catalysts by the {@link IPatternDetails} instance identity (an
 * {@code IPatternDetails} wrapper never represents two recipes; AE2 creates a
 * fresh wrapper per encoded pattern). Each pattern owns its own catalyst set.
 *
 * <p>GTCEu/gtlcore classes are accessed reflectively so this mod keeps its
 * weak dependency (no compile-time reference, no-op when the mods are absent).</p>
 */
public final class GtlCatalystRegistry {

    /**
     * (v1.12.42) Per-pattern catalyst cache. Keyed by {@link IPatternDetails}
     * via an identity wrapper ({@link IdentityKey}); two distinct pattern
     * instances never collide, even when they target the same output AEKey.
     */
    private static final Map<IdentityKey, Set<AEKey>> CATALYSTS_BY_PATTERN = new ConcurrentHashMap<>();

    /**
     * (v1.12.42) Track, by pattern OUTPUT key, whether ANY registered pattern
     * for that output comes from a GTL pattern-buffer machine. Kept for the
     * {@link #isGtlPattern(AEKey)} API — resolve() callers still ask "is this
     * key's pattern from a GTL buffer?" by output key (they have only the
     * AEKey at hand, not the pattern object).
     */
    private static final Set<String> GTL_OUTPUTS = ConcurrentHashMap.newKeySet();

    private GtlCatalystRegistry() {}

    /** True if the pattern producing {@code output} comes from a GTL pattern-buffer machine. */
    public static boolean isGtlPattern(AEKey output) {
        return output != null && GTL_OUTPUTS.contains(output.toString());
    }

    /**
     * (v1.12.42) True if {@code input} is a known catalyst for {@code pattern}.
     * Use this from {@link com.ae2vm.addon.compiler.PatternCompiler} — it always
     * has the pattern instance available.
     */
    public static boolean isCatalyst(IPatternDetails pattern, AEKey input) {
        if (pattern == null || input == null) return false;
        Set<AEKey> catalysts = CATALYSTS_BY_PATTERN.get(new IdentityKey(pattern));
        return catalysts != null && catalysts.contains(input);
    }

    /**
     * (v1.12.42) Resolves and caches the catalyst-input set for a pattern.
     * Per-pattern instance; two patterns sharing an output AEKey both get
     * independent entries.
     */
    public static void register(CraftingService service, IPatternDetails pattern) {
        try {
            if (pattern == null) return;
            var primary = pattern.getPrimaryOutput();
            if (primary == null || primary.what() == null) return;
            String outKey = primary.what().toString();
            IdentityKey key = new IdentityKey(pattern);

            // Already registered (same instance) — just refresh the GTL-output marker.
            if (CATALYSTS_BY_PATTERN.containsKey(key)) {
                GTL_OUTPUTS.add(outKey);
                return;
            }

            Set<AEKey> catalysts = new HashSet<>();
            boolean gtl = false;
            for (appeng.api.networking.crafting.ICraftingProvider provider : service.getProviders(pattern)) {
                if (collectFromProvider(provider, catalysts)) gtl = true;
            }
            if (gtl) {
                GTL_OUTPUTS.add(outKey);
            }
            if (!catalysts.isEmpty()) {
                CATALYSTS_BY_PATTERN.put(key, catalysts);
            }
        } catch (Throwable ignored) {
            // weak dependency: gtlcore/GTCEu absent → no catalysts known, VM keeps
            // treating all inputs as consumed (vanilla parity).
        }
    }

    /**
     * (v1.12.42) Identity-based key wrapper. {@link ConcurrentHashMap} delegates
     * equality to the wrapper, which compares by reference — two
     * {@link IPatternDetails} instances representing different recipes (even
     * when the wrapper class implements value-equality) get distinct entries.
     */
    private static final class IdentityKey {
        private final IPatternDetails pattern;
        private final int hash;
        IdentityKey(IPatternDetails pattern) {
            this.pattern = pattern;
            this.hash = System.identityHashCode(pattern);
        }
        @Override public boolean equals(Object o) {
            return o instanceof IdentityKey ik && ik.pattern == this.pattern;
        }
        @Override public int hashCode() { return hash; }
    }

    private static boolean collectFromProvider(Object provider, Set<AEKey> out) throws Exception {
        // provider is an IGridNode; getMachine() → the GT MetaMachine.
        var nodeGet = provider.getClass().getMethod("getMachine");
        Object machine = nodeGet.invoke(provider);
        if (machine == null) return false;
        // instanceof IMEPatternPartMachine (gtlcore)
        Class<?> iface = Class.forName("org.gtlcore.gtlcore.api.machine.trait.MEPart.IMEPatternPartMachine");
        if (!iface.isInstance(machine)) return false;
        var traitGet = iface.getMethod("getMETrait");
        Object trait = traitGet.invoke(machine);
        if (trait == null) return true; // gtlcore machine, trait not ready yet
        // getCachedGTRecipe() → ObjectSet<GTRecipe>
        var recipesGet = trait.getClass().getMethod("getCachedGTRecipe");
        Object recipes = recipesGet.invoke(trait);
        if (recipes instanceof Iterable<?> iterable) {
            for (Object recipe : iterable) {
                collectCatalystsFromRecipe(recipe, out);
            }
        }
        return true;
    }

    private static void collectCatalystsFromRecipe(Object gtRecipe, Set<AEKey> out) {
        try {
            // GTRecipe.getInputContents(ItemRecipeCapability.CAP) / (FluidRecipeCapability.CAP)
            // → List<Content>; Content.chance <= 0 marks a catalyst.
            collectCap(gtRecipe, "com.gregtechceu.gtceu.api.capability.recipe.ItemRecipeCapability", out);
            collectCap(gtRecipe, "com.gregtechceu.gtceu.api.capability.recipe.FluidRecipeCapability", out);
        } catch (Throwable ignored) {}
    }

    private static void collectCap(Object gtRecipe, String capClassName, Set<AEKey> out) throws Exception {
        Class<?> capClass = Class.forName(capClassName);
        Object cap = capClass.getField("CAP").get(null);
        var getInputs = gtRecipe.getClass().getMethod("getInputContents", Class.class);
        Object contents = getInputs.invoke(gtRecipe, cap);
        if (!(contents instanceof Iterable<?> iterable)) return;
        for (Object content : iterable) {
            try {
                double chance = ((Number) content.getClass().getField("chance").get(content)).doubleValue();
                if (chance > 0) continue; // not a catalyst
                Object ingredient = content.getClass().getField("content").get(content);
                if (ingredient == null) continue;
                if (capClassName.contains("ItemRecipeCapability")) {
                    // Ingredient.getItems() → ItemStack[] → AEItemKey
                    var itemsGet = ingredient.getClass().getMethod("getItems");
                    Object items = itemsGet.invoke(ingredient);
                    if (items instanceof Object[] arr) {
                        for (Object it : arr) {
                            var itemStack = (net.minecraft.world.item.ItemStack) it;
                            if (!itemStack.isEmpty()) {
                                var key = appeng.api.stacks.AEItemKey.of(itemStack);
                                if (key != null) out.add(key);
                            }
                        }
                    }
                } else {
                    // FluidIngredient.getStacks() → FluidStack[] → AEFluidKey
                    // (LDLib's FluidStack is not on this mod's compile classpath —
                    // access via reflection to keep the weak dependency).
                    var stacksGet = ingredient.getClass().getMethod("getStacks");
                    Object stacks = stacksGet.invoke(ingredient);
                    if (stacks instanceof Object[] arr) {
                        for (Object st : arr) {
                            if (st == null) continue;
                            try {
                                boolean empty = (Boolean) st.getClass().getMethod("isEmpty").invoke(st);
                                if (empty) continue;
                                Object fluid = st.getClass().getMethod("getFluid").invoke(st);
                                if (fluid == null) continue;
                                Object tag = null;
                                try { tag = st.getClass().getMethod("getTag").invoke(st); } catch (Throwable ignored) {}
                                AEKey key = tag != null
                                        ? appeng.api.stacks.AEFluidKey.of((net.minecraft.world.level.material.Fluid) fluid, (net.minecraft.nbt.CompoundTag) tag)
                                        : appeng.api.stacks.AEFluidKey.of((net.minecraft.world.level.material.Fluid) fluid);
                                if (key != null) out.add(key);
                            } catch (Throwable ignored) {}
                        }
                    }
                }
            } catch (Throwable ignored) {}
        }
    }
}