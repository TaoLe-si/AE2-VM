package com.ae2vm.addon.mixin;

import com.ae2vm.addon.config.AE2VMConfig;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * (v1.14.x GTL COMPARE) Dynamic mixin filter for the VM-vs-vanilla comparison mode.
 *
 * <p>When {@code ae2vm.onlyVmMixins} is enabled (JVM arg {@code -Dae2vm.onlyVmMixins=true}
 * or the marker file {@code config/ae2vm.onlyvm} exists), ONLY {@link CraftingServiceMixin}
 * is applied. All other mixins (pattern-buffer sync, EXTRACT4 logging, transfinite logic,
 * provider logic) are skipped so the comparison is not polluted by their side effects:
 * the vanilla plan is computed against a stock AE2 (plus whatever the rest of the modpack
 * injects into CraftingService itself) and the VM plan is computed by the pure VM.</p>
 */
public class AE2VMMixinConfigPlugin implements IMixinConfigPlugin {

    /** The only mixin kept alive in "VM-only" comparison mode. */
    private static final String VM_ONLY_MIXIN = "com.ae2vm.addon.mixin.CraftingServiceMixin";
    /** GTL circuit-slot recognition — must always run, even in VM-only mode,
     *  otherwise AEUtils.isIntegratedCircuit returns false for circuit_resonatic_*
     *  and CPU extraction stalls. */
    private static final String GTL_CIRCUIT_MIXIN = "com.ae2vm.addon.mixin.GtlIntegratedCircuitMixin";
    /** (v1.15.x GTL WINDOW KILL) Skip in VM-only comparison mode: the synchronous
     *  requestUpdate pollutes the vanilla observation (AE2's craftingMethods rebuilds
     *  immediately on the slot-edit thread rather than on the next server tick, which
     *  is what vanilla modpacks expect — closing the window is the WHOLE POINT of the
     *  VM addon, not a property to compare against). */
    private static final String GTL_SYNC_MIXIN = "com.ae2vm.addon.mixin.GtlPatternBufferSyncMixin";

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (!AE2VMConfig.isOnlyVmMixins()) {
            return true; // normal mode: apply everything
        }
        // (v1.15.x GTL WINDOW KILL) VM-only comparison mode: keep only the VM-side
        // CraftingServiceMixin (so the VM computes its own plan) and the GTL circuit
        // recognition (always-on correctness dependency). Drop the GTL sync mixin so
        // vanilla observes the natural 50ms tick gap that other AE2 addons also see.
        if (VM_ONLY_MIXIN.equals(mixinClassName)) return true;
        if (GTL_CIRCUIT_MIXIN.equals(mixinClassName)) return true;
        // GTL_SYNC_MIXIN — disabled in comparison mode (see field doc).
        return false;
    }

    @Override public void onLoad(String mixinPackage) {}
    @Override public String getRefMapperConfig() { return null; }
    @Override public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}
    @Override public List<String> getMixins() { return null; }
    @Override public void preApply(String targetClassName, org.objectweb.asm.tree.ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
    @Override public void postApply(String targetClassName, org.objectweb.asm.tree.ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
}
