package com.ae2vm.addon.mixin;

import appeng.api.stacks.AEKey;
import org.gtlcore.gtlcore.integration.ae2.AEUtils;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * (v1.12.47 GTL VIRTUAL CIRCUIT) Extend GTL's {@code AEUtils.isIntegratedCircuit}
 * so the single virtual-programming-circuit family is recognised as a recipe-config
 * slot (extracted once as CATALYST_SEED, returned after craft), matching
 * {@link com.ae2vm.addon.compiler.PatternCompiler#isGtlCircuitInput}:
 * <ul>
 *   <li>{@code gtceu:programmed_circuit} — GTCEu's configurable programming circuit
 *       (the ONLY virtual circuit; never actually consumed)</li>
 * </ul>
 *
 * <p><b>NOT</b> matched (real consumed materials):
 * {@code gtceu:xxx_integrated_circuit} (lv/hv/basic/good/advanced — actual machine
 * materials), {@code kubejs:circuit_resonatic_<tier>}, {@code kubejs:<tier>_universal_circuit},
 * {@code kubejs:basic_control_circuit}, {@code gtceu:circuit_compound_dust} —
 * the previous rule falsely treated these as virtual circuits, making the VM plan
 * return them instead of consuming them (jobs stalled on missing inputs).</p>
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
        // Precise match: only gtceu:programmed_circuit is a virtual circuit.
        // All *_integrated_circuit / circuit_resonatic_* / *_universal_circuit are
        // REAL consumed materials and must NOT be treated as CATALYST_SEED.
        if (lower.contains("programmed_circuit")) { cir.setReturnValue(Boolean.TRUE); return; }
    }
}