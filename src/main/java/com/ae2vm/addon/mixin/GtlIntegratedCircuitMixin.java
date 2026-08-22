package com.ae2vm.addon.mixin;

import appeng.api.stacks.AEKey;
import org.gtlcore.gtlcore.integration.ae2.AEUtils;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * (v1.12.41 GTL CIRCUIT) Extend GTL's {@code AEUtils.isIntegratedCircuit} so
 * every GTL circuit family ({@code circuit_resonatic_*}, {@code *_universal_circuit})
 * is recognised the same way as the GTCEu integrated_circuit — they are
 * recipe-config slots consumed by the machine's own circuit slot, never by
 * the AE network.
 *
 * <p>Replaces the earlier {@code GtlCatalystExtractMixin} (v1.12.40) which
 * used {@code @Overwrite} on {@code extractForProcessingPattern} — that broke
 * GTL's amount-scaling logic for non-circuit inputs and made CPU extraction
 * submit incomplete input sets, so the machine stalled.</p>
 *
 * <p>This mixin uses vanilla {@code @Inject} on the {@code RETURN} so we only
 * override the boolean verdict GTL already computed, preserving all of its
 * own amount logic.</p>
 */
@Mixin(value = AEUtils.class, remap = false)
public abstract class GtlIntegratedCircuitMixin {

    @Inject(method = "isIntegratedCircuit", at = @At("RETURN"), cancellable = true)
    private static void ae2vm$expandIntegratedCircuit(AEKey what, CallbackInfoReturnable<Boolean> cir) {
        // If GTL already recognised the key, keep its verdict.
        if (Boolean.TRUE.equals(cir.getReturnValue())) return;
        if (what == null) return;
        var id = what.getId();
        if (id == null) return;
        String s = id.toString();
        if (s == null) return;
        String lower = s.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("circuit") && !lower.contains("circuit_board") && !lower.contains("circuit_compound")) {
            cir.setReturnValue(Boolean.TRUE);
        }
    }
}