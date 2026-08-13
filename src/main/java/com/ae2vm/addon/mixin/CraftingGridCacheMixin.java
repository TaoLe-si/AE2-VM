package com.ae2vm.addon.mixin;

import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.ICraftingCallback;
import appeng.api.networking.crafting.ICraftingJob;
import appeng.api.networking.security.BaseActionSource;
import appeng.api.storage.data.IAEItemStack;
import appeng.me.cache.CraftingGridCache;
import com.ae2vm.addon.AE2VMAddon;
import com.ae2vm.addon.api.AE2VMCrafting;
import com.ae2vm.addon.api.AEKey;
import com.ae2vm.addon.api.ICraftingPlan;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.Future;

/**
 * Mixin into AE2 rv4's crafting-grid cache (the 1.20.1 {@code CraftingService}
 * equivalent).
 *
 * <p><b>rv4 note:</b> AE2 rv4 has no {@code beginCraftingCalculation} returning a
 * {@code Future<ICraftingPlan>}. The native entry point is
 * {@code ICraftingGrid.beginCraftingJob(...)}, which returns a {@code Future<ICraftingJob>}
 * and builds its plan internally. This hook therefore does <b>not</b> cancel the native
 * path; it runs the VM alongside it to (a) warm the bytecode cache, and (b) measure the
 * VM's plan. Full takeover of rv4's {@code CraftingJob} submission is documented as a
 * TODO in README.md (requires the AE2 rv4 dev jar for exact {@code ICraftingJob}
 * internals).
 */
@Mixin(value = CraftingGridCache.class, remap = true)
public abstract class CraftingGridCacheMixin {

    @Inject(method = "beginCraftingJob", at = @At("HEAD"))
    private void vmBeginCraftingJob(
            World world,
            IGrid grid,
            BaseActionSource actionSrc,
            IAEItemStack slotItem,
            ICraftingCallback cb,
            CallbackInfoReturnable<Future<ICraftingJob>> cir) {

        // 配置开关：proxy.enabled=false 时完全禁用 VM 代理。
        if (!com.ae2vm.addon.config.AE2VMConfig.isProxyEnabled()) {
            return;
        }

        try {
            long start = System.nanoTime();
            AEKey key = AEKey.of(slotItem);
            ICraftingPlan plan = AE2VMCrafting.calculateSync(grid, key, slotItem.getStackSize());
            long us = (System.nanoTime() - start) / 1_000;
            AE2VMAddon.LOGGER.info("[AE2-VM] VM OK: {} us ({} ms), used={} missing={}",
                    us, String.format("%.2f", us / 1000.0D),
                    plan.usedItems().size(), plan.missingItems().size());
        } catch (Exception e) {
            // VM could not handle the request — let the native rv4 crafting path proceed.
            AE2VMAddon.LOGGER.warn("[AE2-VM] VM calc failed ({}), native crafting proceeds", e.toString());
        }
    }
}
