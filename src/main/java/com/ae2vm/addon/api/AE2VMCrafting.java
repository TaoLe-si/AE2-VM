package com.ae2vm.addon.api;

import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.ICraftingGrid;
import appeng.api.networking.crafting.ICraftingPatternDetails;
import appeng.api.networking.storage.IStorageGrid;
import appeng.api.storage.data.IAEItemStack;
import com.ae2vm.addon.compiler.PatternCompiler;
import com.ae2vm.addon.vm.CraftingBytecode;
import com.ae2vm.addon.vm.CraftingVM;
import com.ae2vm.addon.vm.RealtimeNetworkCraftingSimulationState;
import net.minecraft.item.Item;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.common.Loader;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Public facade for the AE2 VM crafting engine (MC 1.10.2 / AE2 rv4 port).
 *
 * <p>On rv4 there is no async {@code beginCraftingCalculation} returning a
 * {@code Future<ICraftingPlan>}; the native crafting entry point is
 * {@code ICraftingGrid.beginCraftingJob(...)} (which returns a
 * {@code Future<ICraftingJob>}). This facade therefore exposes a <b>synchronous</b>
 * VM calculator over the same shim {@link ICraftingPlan} type the 1.20.1 build
 * returns, and is used by {@code CraftingGridCacheMixin} to warm the bytecode cache
 * and measure the VM plan alongside rv4's native calculation.
 *
 * <p>Third-party mods may call {@link #isLoaded()} to gate optional integration and
 * {@link #calculateSync(IGrid, AEKey, long)} to obtain a VM-computed plan.
 */
public final class AE2VMCrafting {

    private AE2VMCrafting() {
    }

    /**
     * Per-grid CraftingVM instances, reused across requests so the JIT bundleCache
     * (per-pattern 1-craft subtree effects) survives between crafting requests on the
     * same network. Keyed by the grid so different networks never share bundles.
     */
    private static final ConcurrentHashMap<IGrid, CraftingVM> VM_CACHE = new ConcurrentHashMap<>();

    /**
     * Whether the AE2 VM mod is loaded in the current game instance.
     *
     * <pre>{@code
     * if (AE2VMCrafting.isLoaded()) {
     *     AE2VMCrafting.calculateSync(grid, what, amount);
     * } else {
     *     // native AE2 (or your own) calculation path
     * }
     * }</pre>
     *
     * @return {@code true} if the {@code ae2vm} mod is present
     */
    public static boolean isLoaded() {
        return Loader.instance().isModLoaded("ae2vm");
    }

    /**
     * Compute a crafting plan for the given item using the AE2 VM engine (synchronous).
     *
     * @param grid   the grid to compute against
     * @param what   the item to craft
     * @param amount how many are requested
     * @return the computed {@link ICraftingPlan}
     * @throws IllegalStateException if no pattern exists or the grid lacks the required caches
     */
    public static ICraftingPlan calculateSync(IGrid grid, AEKey what, long amount) {
        IStorageGrid storage = grid.getCache(IStorageGrid.class);
        ICraftingGrid craftingGrid = grid.getCache(ICraftingGrid.class);
        if (storage == null || craftingGrid == null) {
            throw new IllegalStateException("No storage/crafting grid cache on network");
        }

        IAEItemStack whatStack = what.getItemStack();
        if (whatStack == null) {
            throw new IllegalStateException("No pattern for fluid " + what + " (rv4 has no fluid crafting)");
        }

        // rv4: getCraftingFor(stack, details, slot, world) — details/slot/world are only
        // used for subtype fuzzy matching; null/0/null performs the exact-output lookup.
        Collection<ICraftingPatternDetails> patterns = craftingGrid.getCraftingFor(whatStack, null, 0, null);
        if (patterns.isEmpty()) {
            throw new IllegalStateException("No pattern for " + what);
        }

        // Prefer the pattern with the SMALLEST per-craft output among the candidates
        // (avoids mega/bulk patterns blowing the plan up to millions of items).
        IPatternDetails topPattern = new Rv4PatternDetails(pickBestPattern(patterns, what));

        // Compile the top-level pattern to bytecode and build the request wrapper.
        PatternCompiler.compileIfAbsent(topPattern);
        CraftingBytecode requestBytecode = PatternCompiler.compileRequest(topPattern, amount);

        // Per-request resolver cache, swapped into a per-grid reused VM.
        Map<AEKey, IPatternDetails> resolverCache = new ConcurrentHashMap<>();
        CraftingVM vm = VM_CACHE.computeIfAbsent(grid, g -> new CraftingVM(g,
                key -> resolve((IGrid) g, new ConcurrentHashMap<>(), key)));
        vm.setPatternResolver(key -> resolve(grid, resolverCache, key));

        // Snapshot the LIVE network inventory (never the cached inventory).
        RealtimeNetworkCraftingSimulationState networkInv =
                new RealtimeNetworkCraftingSimulationState(storage);

        return vm.execute(requestBytecode, networkInv);
    }

    /** Async variant — computes synchronously and wraps the result. */
    public static CompletableFuture<ICraftingPlan> calculate(IGrid grid, AEKey what, long amount) {
        try {
            return CompletableFuture.completedFuture(calculateSync(grid, what, amount));
        } catch (Exception e) {
            CompletableFuture<ICraftingPlan> failed = new CompletableFuture<>();
            failed.completeExceptionally(e);
            return failed;
        }
    }

    /**
     * T1-T3 resolver: exact match → drop secondary → registry item.
     * Compiles resolved sub-patterns into the same network's cache as the task.
     */
    private static IPatternDetails resolve(IGrid grid, Map<AEKey, IPatternDetails> cache, AEKey key) {
        // ConcurrentHashMap forbids null keys — guard against a null constant-pool entry.
        if (key == null) {
            return null;
        }
        IPatternDetails cached = cache.get(key);
        if (cached != null) {
            return cached;
        }

        ICraftingGrid craftingGrid = grid.getCache(ICraftingGrid.class);

        // Try 1: exact match — prefer the smallest-output pattern.
        IAEItemStack what = key.getItemStack();
        if (what != null && craftingGrid != null) {
            Collection<ICraftingPatternDetails> subs = craftingGrid.getCraftingFor(what, null, 0, null);
            if (!subs.isEmpty()) {
                IPatternDetails sub = new Rv4PatternDetails(pickBestPattern(subs, key));
                PatternCompiler.compileIfAbsent(sub);
                cache.put(key, sub);
                return sub;
            }
        }

        // Try 2: drop secondary (verify the pattern actually outputs the item).
        AEKey clean = key.dropSecondary();
        if (!clean.equals(key) && craftingGrid != null) {
            IAEItemStack cleanWhat = clean.getItemStack();
            if (cleanWhat != null) {
                Collection<ICraftingPatternDetails> subs = craftingGrid.getCraftingFor(cleanWhat, null, 0, null);
                if (!subs.isEmpty()) {
                    IPatternDetails sub = new Rv4PatternDetails(pickBestPattern(subs, clean));
                    if (patternOutputs(sub, clean)) {
                        PatternCompiler.compileIfAbsent(sub);
                        cache.put(key, sub);
                        return sub;
                    }
                }
            }
        }

        // Try 3: registry item (verify the pattern actually outputs the item).
        String id = key.getId();
        if (id != null) {
            Item item = Item.REGISTRY.getObject(new ResourceLocation(id));
            if (item != null && craftingGrid != null) {
                AEKey pureKey = AEKey.ofItem(item);
                Collection<ICraftingPatternDetails> subs = craftingGrid.getCraftingFor(pureKey.getItemStack(), null, 0, null);
                if (!subs.isEmpty()) {
                    IPatternDetails sub = new Rv4PatternDetails(pickBestPattern(subs, pureKey));
                    if (patternOutputs(sub, key)) {
                        PatternCompiler.compileIfAbsent(sub);
                        cache.put(key, sub);
                        return sub;
                    }
                }
            }
        }
        return null;
    }

    /**
     * Pick the pattern that produces {@code want} with the SMALLEST per-craft output
     * amount. Falls back to the first pattern if none matches {@code want}.
     */
    private static ICraftingPatternDetails pickBestPattern(Collection<ICraftingPatternDetails> patterns, AEKey want) {
        ICraftingPatternDetails best = null;
        ICraftingPatternDetails fallback = null;
        long bestOut = Long.MAX_VALUE;
        for (ICraftingPatternDetails p : patterns) {
            if (p == null) {
                continue;
            }
            if (fallback == null) {
                fallback = p;
            }
            IAEItemStack[] outs = p.getCondensedOutputs();
            IAEItemStack out = (outs == null || outs.length == 0) ? null : outs[0];
            if (out == null) {
                continue;
            }
            AEKey outKey = AEKey.of(out);
            if (want != null && !outKey.equals(want)
                    && !(want.getId() != null && want.getId().equals(outKey.getId()))) {
                continue;
            }
            long amt = Math.max(1, out.getStackSize());
            if (amt < bestOut) {
                bestOut = amt;
                best = p;
            }
        }
        return best != null ? best : fallback;
    }

    /** True if the pattern's primary output is {@code want} (by key or by registry id). */
    private static boolean patternOutputs(IPatternDetails pattern, AEKey want) {
        GenericStack out = pattern.getPrimaryOutput();
        if (out == null) {
            return false;
        }
        AEKey outKey = out.what();
        if (outKey.equals(want)) {
            return true;
        }
        return want.getId() != null && want.getId().equals(outKey.getId());
    }
}
