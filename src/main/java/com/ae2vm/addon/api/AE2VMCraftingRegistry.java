package com.ae2vm.addon.api;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Opt-in registry for third-party crafting mods (ECO, ExtendedAE, ...).
 * <p>
 * AE2 VM is an <b>optional</b> accelerator. A third-party mod decides whether
 * to let the VM take over its crafting requests by calling {@link #register(String)}
 * at startup (e.g. from its {@code @Mod} constructor). Requests initiated by a
 * mod that has <b>not</b> registered (detected via the {@code simRequester} class
 * in {@code beginCraftingJob}) are left entirely to that mod's own crafting logic —
 * AE2 VM never intercepts them.
 *
 * <pre>{@code
 * // in the third-party mod's constructor
 * AE2VMCraftingRegistry.register("neoecoae");
 * }</pre>
 */
public final class AE2VMCraftingRegistry {

    /** Registered markers — substrings matched against requester/provider class names and item namespaces. */
    private static final Set<String> REGISTERED = ConcurrentHashMap.newKeySet();

    private AE2VMCraftingRegistry() {
    }

    /**
     * Opt a third-party crafting mod in to AE2 VM.
     *
     * @param marker a substring present in the class names owned by the mod
     *               (e.g. {@code "neoecoae"} or {@code "extendedae"})
     */
    public static void register(String marker) {
        if (marker == null || marker.trim().isEmpty()) {
            return;
        }
        REGISTERED.add(marker);
    }

    /**
     * Whether the given requester/provider class belongs to a registered third-party mod.
     *
     * @param className fully-qualified requester or provider class name
     * @return {@code true} if the class is owned by a registered mod
     */
    public static boolean isRegistered(String className) {
        if (className == null) {
            return false;
        }
        for (String marker : REGISTERED) {
            if (className.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether a requester class belongs to a third-party mod that has NOT opted in.
     * <p>
     * AE2's own classes ({@code appeng.*}) are never considered third-party, so
     * requests they initiate are always handled by the VM. Anything outside AE2
     * is a third-party requester: it is only handled by the VM once its owning
     * mod calls {@link #register(String)}; otherwise it is left to that mod's
     * own crafting logic.
     *
     * @param className fully-qualified requester class name
     * @return {@code true} if the requester is third-party and NOT registered
     */
    public static boolean isUnregisteredThirdParty(String className) {
        if (className == null) {
            return false;
        }
        if (className.startsWith("appeng.")) {
            return false; // AE2's own → VM handles
        }
        return !isRegistered(className);
    }

    /**
     * Whether any third-party mod has opted in to AE2 VM.
     */
    public static boolean hasRegistrations() {
        return !REGISTERED.isEmpty();
    }
}
