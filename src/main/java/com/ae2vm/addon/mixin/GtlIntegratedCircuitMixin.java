package com.ae2vm.addon.mixin;

import appeng.api.stacks.AEKey;
import org.gtlcore.gtlcore.integration.ae2.AEUtils;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * (v1.12.44 GTL VIRTUAL CIRCUIT) Extend GTL's {@code AEUtils.isIntegratedCircuit}
 * so the 3 known virtual-programming-circuit families are recognised as recipe-config
 * slots (extracted once as CATALYST_SEED, returned after craft), matching
 * {@link com.ae2vm.addon.compiler.PatternCompiler#isGtlCircuitInput}:
 * <ul>
 *   <li>{@code gtceu:xxx_integrated_circuit} — GTCEu voltage-tier selectors</li>
 *   <li>{@code kubejs:circuit_resonatic_<tier>} — GTL resonatic circuits</li>
 *   <li>{@code kubejs:<tier>_universal_circuit} — GTL universal circuits</li>
 * </ul>
 *
 * <p><b>NOT</b> matched (real consumed materials):
 * {@code kubejs:basic_control_circuit}, {@code kubejs:imprinted_resonatic_circuit_board},
 * {@code gtceu:circuit_compound_dust}, {@code gtceu:epoxy_printed_circuit_board} —
 * the old {@code contains("circuit")} rule falsely matched these.</p>
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
        // Precise match: only the 3 known virtual-programming-circuit families.
        if (lower.contains("integrated_circuit")) { cir.setReturnValue(Boolean.TRUE); return; }
        if (lower.contains("circuit_resonatic")) { cir.setReturnValue(Boolean.TRUE); return; }
        if (lower.endsWith("_universal_circuit")) { cir.setReturnValue(Boolean.TRUE); return; }
    }
}