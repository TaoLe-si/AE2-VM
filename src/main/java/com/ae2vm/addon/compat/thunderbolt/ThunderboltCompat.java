package com.ae2vm.addon.compat.thunderbolt;

import com.ae2vm.addon.AE2VMAddon;
import com.moakiee.thunderbolt.api.crafting.CraftingPlanningEngines;
import net.minecraftforge.fml.ModList;

/**
 * Thunderbolt-Core weak (optional) dependency facade - 1.20.1 Forge port.
 *
 * <p>This class is always on the AE2VMAddon classpath. All references to Thunderbolt
 * classes (CraftingPlanningEngines, AE2VMBatchCraftingPlanner) happen inside
 * isThunderboltLoaded()-guarded methods (lazy) - if Thunderbolt is not installed,
 * {@code NoClassDefFoundError} never fires, AE2VMAddon keeps its default behaviour.
 *
 * <p>Behaviour:
 * <ul>
 *   <li>Thunderbolt installed: engine selection/routing delegated to Thunderbolt. Player
 *       selects {@code ae2vm} -> AE2VMBatchCraftingPlanner serves the VM; not selected
 *       -> we don't take over, Thunderbolt runs vanilla/other engines.</li>
 *   <li>Thunderbolt not installed: AE2VMAddon CraftingServiceMixin takes over all requests
 *       directly (default).</li>
 * </ul>
 */
public final class ThunderboltCompat {

    /** Thunderbolt-Core mod id (string literal, no class loading on static init). */
    public static final String MOD_ID = "thunderbolt";

    /** Our (AE2 VM) engine id in Thunderbolt's multi-engine system. */
    public static final String ENGINE_ID = AE2VMBatchCraftingPlanner.ENGINE_ID;

    private static volatile boolean registered = false;

    private ThunderboltCompat() {
    }

    /**
     * Thunderbolt-Core is installed?
     *
     * <p>Safe to call any time: uses the ModList string-literal check, never touches
     * any Thunderbolt class.
     */
    public static boolean isThunderboltLoaded() {
        try {
            return ModList.get() != null && ModList.get().isLoaded(MOD_ID);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Is our engine currently selected (/thunderbolt engine ae2vm)?
     *
     * <p>Without Thunderbolt, always false (our mixin takes over).
     * With Thunderbolt, we check that our engine is registered in the engine chain.
     */
    public static boolean isEngineSelected() {
        if (!isThunderboltLoaded()) {
            return false;
        }
        try {
            var id = AE2VMBatchCraftingPlanner.INSTANCE.id();
            return CraftingPlanningEngines.get(id) != null;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Register our engine with Thunderbolt (idempotent, once-only).
     *
     * <p>Call from commonSetup enqueueWork (async, never block the mod-loading thread).
     *
     * @see AE2VMAddon#commonSetup
     */
    public static void registerIfPresent() {
        if (!isThunderboltLoaded() || registered) {
            return;
        }
        try {
            // Priority 900: lower than Thunderbolt V2's 1000 (so Thunderbolt V2 wins by
            // default) but higher than vanilla (min). When the player explicitly picks
            // ae2vm via /thunderbolt engine ae2vm (or the algorithm menu), it takes
            // precedence over vanilla.
            CraftingPlanningEngines.register(
                    AE2VMBatchCraftingPlanner.INSTANCE,
                    900,           // algorithmPriority
                    false          // publicAlgorithm = false (needs provider node)
            );
            registered = true;
            AE2VMAddon.LOGGER.info(
                    "[AE2-VM] Registered AE2 VM engine with Thunderbolt-Core (id={}, priority=900). "
                            + "Select it via /thunderbolt engine {}",
                    ENGINE_ID, ENGINE_ID);
        } catch (Throwable t) {
            AE2VMAddon.LOGGER.warn("[AE2-VM] Could not register with Thunderbolt-Core: {}", t.toString());
        }
    }
}