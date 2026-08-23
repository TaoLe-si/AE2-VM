package com.ae2vm.addon.api;

import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.crafting.ICraftingSubmitResult;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.api.storage.MEStorage;
import com.ae2vm.addon.AE2VMAddon;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Supplier;

/**
 * (v1.15.x GTL INVENTORY LOCK) Weak-dependency bridge to GTL's
 * {@code ManualCraftingInventoryLock}.
 *
 * <p>After the VM calculates a plan, we try to reserve {@code plan.usedItems()}
 * from the network inventory so that no other concurrent crafting job can grab
 * those materials before the calling {@code submitJob} executes. If the
 * reservation fails (materials already taken by another pending job), the VM
 * recalculates the plan with the updated inventory.</p>
 *
 * <p>All calls are via reflection — GTL is not required. When GTL is absent
 * every method silently no-ops and returns null.</p>
 */
public class GtlInventoryReservation {

    private static final Map<ICraftingPlan, Object> RESERVATIONS = new WeakHashMap<>();
    private static Class<?> LOCK_CLASS;
    private static Method TRY_ACQUIRE;
    private static Method SUBMIT;
    private static Method CLOSE;
    private static boolean RESOLVED = false;

    static {
        try {
            LOCK_CLASS = Class.forName("org.gtlcore.gtlcore.integration.ae2.crafting.ManualCraftingInventoryLock");
            TRY_ACQUIRE = LOCK_CLASS.getMethod("tryAcquire", MEStorage.class, KeyCounter.class, IActionSource.class);
            // Reservation.submit(Supplier) and close()
            Class<?> reservationClass = Class.forName(
                    "org.gtlcore.gtlcore.integration.ae2.crafting.ManualCraftingInventoryLock$Reservation");
            SUBMIT = reservationClass.getMethod("submit", Supplier.class);
            CLOSE = reservationClass.getMethod("close");
            RESOLVED = true;
            AE2VMAddon.LOGGER.info("[AE2-VM] GTL inventory lock available — reservations enabled");
        } catch (Throwable t) {
            AE2VMAddon.LOGGER.info("[AE2-VM] GTL inventory lock not available: {}", t.toString());
            // weak dependency: silently no-op
        }
    }

    /** @return the reservation object, or null if unavailable / conflict */
    public static Object tryReserve(ICraftingPlan plan, MEStorage storage, IActionSource source) {
        if (!RESOLVED) return null;
        synchronized (RESERVATIONS) {
            if (RESERVATIONS.containsKey(plan)) return RESERVATIONS.get(plan); // already reserved
        }
        try {
            KeyCounter used = plan.usedItems();
            if (used == null || used.isEmpty()) return null; // nothing to reserve
            Object reservation = TRY_ACQUIRE.invoke(null, storage, used, source);
            if (reservation != null) {
                synchronized (RESERVATIONS) {
                    RESERVATIONS.put(plan, reservation);
                }
                // AE2VMAddon.LOGGER.debug("[AE2-VM] Inventory reserved for {} ({} items)", plan, used.size());
            }
            return reservation;
        } catch (Throwable t) {
            AE2VMAddon.LOGGER.warn("[AE2-VM] Inventory reservation failed: {}", t.toString());
            return null;
        }
    }

    /** @return the reservation for a plan, or null */
    public static Object getReservation(ICraftingPlan plan) {
        synchronized (RESERVATIONS) {
            return RESERVATIONS.get(plan);
        }
    }

    /**
     * Submit a job, wrapping the action with the reservation if present.
     * Returns the submit result.
     */
    public static ICraftingSubmitResult submitWithReservation(
            ICraftingPlan plan, Supplier<ICraftingSubmitResult> action) {
        Object reservation = getReservation(plan);
        if (reservation == null || !RESOLVED) {
            return action.get();
        }
        try {
            @SuppressWarnings("unchecked")
            ICraftingSubmitResult result = (ICraftingSubmitResult) SUBMIT.invoke(reservation, new Supplier<Object>() {
                @Override
                public Object get() {
                    return action.get();
                }
            });
            return result;
        } catch (Throwable t) {
            AE2VMAddon.LOGGER.warn("[AE2-VM] Reservation submit failed: {}", t.toString());
            return action.get(); // fallback: submit without reservation
        }
    }

    /** Release a reservation (called on plan cancellation / error). */
    public static void releaseReservation(ICraftingPlan plan) {
        Object reservation;
        synchronized (RESERVATIONS) {
            reservation = RESERVATIONS.remove(plan);
        }
        if (reservation != null && RESOLVED) {
            try {
                CLOSE.invoke(reservation);
                // AE2VMAddon.LOGGER.debug("[AE2-VM] Reservation released for {}", plan);
            } catch (Throwable t) {
                AE2VMAddon.LOGGER.warn("[AE2-VM] Reservation release failed: {}", t.toString());
            }
        }
    }
}
