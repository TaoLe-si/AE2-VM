package com.ae2vm.addon.compat.thunderbolt;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.crafting.CraftingPlan;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import com.ae2vm.addon.AE2VMAddon;
import com.ae2vm.addon.api.AE2VMCrafting;
import com.moakiee.thunderbolt.api.crafting.CraftingPlanningEngine;
import com.moakiee.thunderbolt.api.crafting.PlanningAttempt;
import com.moakiee.thunderbolt.api.crafting.PlanningAttemptContext;
import com.moakiee.thunderbolt.api.crafting.PlanningEngineSession;
import com.moakiee.thunderbolt.api.crafting.PlanningExitException;
import com.moakiee.thunderbolt.api.crafting.PlanningRequest;

/**
 * AE2 VM engine - registers with Thunderbolt-Core 1.20.1 Forge (ae2lt/Thunderbolt-Core
 * branch '1.20.1', mod_version 2.0.0-beta.1, Forge 47.1.3, AE2 15.4.10).
 *
 * <p>Mirrors the 1.21.1 NeoForge companion. The 1.20.1 Forge branch of Thunderbolt-Core
 * ships the same 7-field api.crafting surface (CraftingPlanningEngine / PlanningRequest
 * with the 7-field record / PlanningEngineSession / PlanningAttempt / PlanningAttemptContext
 * / PlanningExitException / CraftingPlanningEngines / CraftingPlanningEngineDescriptor /
 * PlanningChoice) - the planning-engine API is shared between the two MC versions.
 *
 * <p>Behaviour: when the player runs /thunderbolt engine ae2vm, Thunderbolt routes every
 * craft calculation request to this class via createSession; the session's attempt calls
 * AE2VMCrafting.calculate. If the VM cannot handle it, we return PlanningAttempt.DECLINE
 * and Thunderbolt tries the next engine (eventually falling back to native AE2).
 */
public final class AE2VMBatchCraftingPlanner implements CraftingPlanningEngine {

    /** Thunderbolt engine id (kept stable across MC versions for player config compat). */
    public static final String ENGINE_ID = "ae2vm";

    /** Singleton, registered with {@link com.moakiee.thunderbolt.api.crafting.CraftingPlanningEngines#register}. */
    public static final AE2VMBatchCraftingPlanner INSTANCE = new AE2VMBatchCraftingPlanner();

    private AE2VMBatchCraftingPlanner() {
    }

    @Override
    public ResourceLocation id() {
        return new ResourceLocation("ae2vm", ENGINE_ID);
    }

    @Override
    public Component getName() {
        return Component.translatable("algorithm.ae2vm.vm_engine");
    }

    @Override
    public boolean check(IGrid grid, PlanningRequest request) {
        return request.requester() != null
                && request.output() != null
                && request.requestedAmount() > 0
                && grid != null;
    }

    @Override
    public PlanningEngineSession createSession(
            PlanningRequest request,
            @org.jetbrains.annotations.Nullable Object capturedInput,
            PlanningAttemptContext context) {
        // 1.20.1: grid from request.requester() (also available via request.craftingService(),
        // but 1.20.1 AE2 doesn't expose CraftingService.grid cleanly without an accessor).
        IGrid grid = null;
        if (request.requester() != null && request.requester().getGridNode() != null) {
            grid = request.requester().getGridNode().getGrid();
        }
        return new Session(grid, request);
    }

    /**
     * One session per calculation. Holds request-level context; calls
     * {@link AE2VMCrafting#calculate} from attempt() and blocks until the result is ready.
     */
    private static final class Session implements PlanningEngineSession {
        private final IGrid grid;
        private final PlanningRequest request;

        Session(IGrid grid, PlanningRequest request) {
            this.grid = grid;
            this.request = request;
        }

        @Override
        public PlanningAttempt attempt(long amount, boolean simulate, PlanningAttemptContext context) {
            try {
                context.checkpoint(); // budget / cancellation check (1.20.1 session contract)
                if (grid == null) {
                    return PlanningAttempt.DECLINE;
                }
                var future = AE2VMCrafting.calculate(
                        grid,
                        request.requester(),
                        request.output(),
                        amount,
                        request.strategy());

                // Block on the VM async call (5-minute ceiling matches the 1.21.1 version
                // and Thunderbolt's watchdog).
                ICraftingPlan plan = future.get(5, TimeUnit.MINUTES);

                if (plan instanceof CraftingPlan cp) {
                    return PlanningAttempt.handled(cp);
                } else if (plan == null) {
                    return PlanningAttempt.DECLINE;
                } else {
                    return new PlanningAttempt(
                            PlanningAttempt.Status.HANDLED,
                            plan instanceof CraftingPlan ? (CraftingPlan) plan : null,
                            null);
                }
            } catch (PlanningExitException e) {
                return PlanningAttempt.DECLINE;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                AE2VMAddon.LOGGER.warn("[AE2-VM] Planning interrupted for {}x{}", request.output(), amount);
                return PlanningAttempt.DECLINE;
            } catch (ExecutionException | TimeoutException e) {
                AE2VMAddon.LOGGER.warn("[AE2-VM] Planning failed for {}x{}: {}",
                        request.output(), amount, e.toString());
                return PlanningAttempt.DECLINE;
            } catch (Throwable t) {
                AE2VMAddon.LOGGER.warn("[AE2-VM] Planning unexpected error: {}", t.toString());
                return PlanningAttempt.DECLINE;
            }
        }
    }
}