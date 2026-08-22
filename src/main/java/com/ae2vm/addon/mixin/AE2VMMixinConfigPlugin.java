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

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (!AE2VMConfig.isOnlyVmMixins()) {
            return true; // normal mode: apply everything
        }
        return VM_ONLY_MIXIN.equals(mixinClassName) || GTL_CIRCUIT_MIXIN.equals(mixinClassName);
    }

    @Override public void onLoad(String mixinPackage) {}
    @Override public String getRefMapperConfig() { return null; }
    @Override public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}
    @Override public List<String> getMixins() { return null; }
    @Override public void preApply(String targetClassName, org.objectweb.asm.tree.ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
    @Override public void postApply(String targetClassName, org.objectweb.asm.tree.ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
}
