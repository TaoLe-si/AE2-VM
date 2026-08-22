package com.ae2vm.addon.mixin;

import appeng.api.networking.IGridNode;
import appeng.api.networking.crafting.ICraftingProvider;
import com.ae2vm.addon.compiler.PatternCompiler;
import org.gtlcore.gtlcore.common.machine.multiblock.part.ae.MEPatternBufferPartMachine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * (v1.15.x GTL WINDOW KILL) Eliminate the GTL pattern-buffer sync window.
 *
 * <p>Root cause (traced through AE2 1.20.1 {@code CraftingService.refreshNodeCraftingProvider},
 * GTLCore {@code MEPatternBufferPartMachineBase.update}, gtladditions
 * {@code MESuperPatternBufferPartMachine.setFOAPatternOutputMultiplier / setFOAModeEnabled}):
 * <pre>
 *   player edits FOA multiplier / buffer slot / onPatternChange / keepByProduct / ...
 *       → MEPatternBufferPartMachine.refreshAllByProduct()
 *           → slot2PatternMap.clear()
 *           → for each slot: getRealPattern() (FOA: encode/decode a NEW IPatternDetails instance)
 *           → slot2PatternMap.put(...)
 *           → reCalculatePatternSlotMap()
 *           → needPatternSync = true        ← just a flag, NOT a notify
 *   ...
 *   next server tick (50ms later):
 *       → MEPatternBufferPartMachineBase.update()
 *           → ICraftingProvider.requestUpdate(getMainNode())
 *               → AE2 CraftingService.refreshNodeCraftingProvider(node)
 *                   → craftingProviders.removeProvider(node)  // GTL pattern list now empty
 *                   → craftingProviders.addProvider(node)     // GTL pattern list repopulated
 * </pre>
 *
 * <p>The 50ms gap is the "sliding window": GTL has already cleared its
 * {@code slot2PatternMap} and rebuilt it with NEW pattern instances (FOA multiplies
 * output/input amounts, so the cached bundle for this provider has WRONG amounts),
 * but AE2's {@code craftableItems} still holds the OLD patterns until
 * {@code update()} fires {@code requestUpdate}. During this gap, the VM resolves
 * against the OLD patterns and plans with OLD amounts.
 *
 * <p><b>Fix</b>: notify AE2 IMMEDIATELY at the end of {@code refreshAllByProduct}.
 * The 50ms gap is GONE — the user-observable "几遍正常忽然坏等一会好" symptom is
 * root-caused at its origin. We also synchronously bump the global pattern version
 * so the VM's {@code bundleCache} / {@code resolverCache} drop their stale entries
 * the next {@code execute()}. {@code needPatternSync} is cleared so GTL's
 * {@code update()} does not double-notify.
 *
 * <p><b>Why mixin GTL not AE2</b>: the AE2 {@code refreshNodeCraftingProvider}
 * TAIL hook (see {@link CraftingServiceMixin}) fires AFTER the AE2 provider
 * list is rebuilt, which is correct — but it fires 50ms too late to close the
 * window. Mixing into the GTL origin ({@code refreshAllByProduct}) closes the
 * window at its source.
 *
 * <p><b>Mixin ordering</b>: gtladditions only {@code @Overwrite}s
 * {@code getRealPattern}, not {@code refreshAllByProduct}, so this {@code @Inject}
 * is not pre-empted. {@link AE2VMMixinConfigPlugin} gates the mixin via
 * {@code shouldApplyMixin} — when {@code ae2vm.onlyVmMixins} (the comparison mode)
 * is enabled, this GTL mixin is skipped so the vanilla path is observed cleanly.
 */
@Mixin(value = MEPatternBufferPartMachine.class, remap = false)
public abstract class GtlPatternBufferSyncMixin {

    /** MethodHandle (typed as Object so javac does not resolve the GTCEu return type)
     *  for {@code getMainNode()}. Resolved reflectively once at class-init; the JIT
     *  inlines the polymorphic signature call site after warmup.
     *  {@code null} if GTL renamed/removed the method (silent no-op — the worst
     *  case reverts to the pre-mixin 50ms-tick behaviour, never a crash). */
    private static final Object GET_MAIN_NODE_HANDLE;
    /** MethodHandle (typed as Object for the same javac reason) for writing
     *  {@code needPatternSync} (declared on {@code MEPatternBufferPartMachineBase};
     *  inherited by {@code this}). */
    private static final Object NEED_PATTERN_SYNC_SETTER_HANDLE;

    static {
        MethodHandles.Lookup lookup = MethodHandles.lookup();
        Object gmn = null;
        Object nps = null;
        // (v1.15.x GTL WINDOW KILL) getMainNode() is declared on GTCEu's
        // MultiblockPartMachine — NOT on AE2VM's compile classpath. We store the
        // MethodHandle as Object so javac does not try to resolve its declared
        // return type. The call site casts back to MethodHandle and uses the
        // polymorphic-signature invoke(Object) overload — also Object-typed, so
        // javac stays clear of the GTCEu class.
        try {
            Method m = MEPatternBufferPartMachine.class.getMethod("getMainNode");
            gmn = lookup.unreflect(m);
        } catch (Throwable ignored) {}
        try {
            Field f = Class.forName(
                    "org.gtlcore.gtlcore.common.machine.multiblock.part.ae.MEPatternBufferPartMachineBase"
            ).getDeclaredField("needPatternSync");
            f.setAccessible(true);
            nps = lookup.unreflectSetter(f);
        } catch (Throwable ignored) {}
        GET_MAIN_NODE_HANDLE = gmn;
        NEED_PATTERN_SYNC_SETTER_HANDLE = nps;
    }

    /**
     * Force-synchronise the pattern set on the same server thread the slot edit ran on.
     * Avoids the 50ms server-tick gap that GTL otherwise leaves between editing a slot
     * (FOA multiplier toggle, slot put/take, keepByProduct, etc.) and notifying AE2.
     */
    @Inject(method = "refreshAllByProduct", at = @At("TAIL"))
    private void vmImmediateAeSync(CallbackInfo ci) {
        // (v1.15.x GTL WINDOW KILL) bump BEFORE requestUpdate so any in-flight VM execute
        // whose snapshot read the OLD patterns sees the new version on its next read and
        // drops the stale JIT bundles. bumpPatternVersion is monotonic, lock-free, safe to
        // call from any thread. The mixin runs on the SERVER thread (slot edits only
        // happen on server), so no cross-thread visibility concern — the AtomicLong write
        // is the same primitive PatternCompiler uses everywhere.
        PatternCompiler.bumpPatternVersion();
        if (GET_MAIN_NODE_HANDLE == null) return; // getMainNode missing → cannot notify, revert to GTL behaviour
        try {
            // (v1.15.x GTL WINDOW KILL) Cast back to MethodHandle locally — javac
            // accepts the polymorphic-signature invoke(Object) call against the local
            // handle without resolving its declared return type (the GTCEu
            // MultiblockPartMachine / IManagedGridNode classes are not on this
            // project's compile classpath). The runtime value is still the real
            // IManagedGridNode instance; AE2's requestUpdate accepts it.
            MethodHandle gmnHandle = (MethodHandle) GET_MAIN_NODE_HANDLE;
            Object managedNode = gmnHandle.invoke((Object) (MEPatternBufferPartMachine)(Object) this);
            // (v1.15.x GTL WINDOW KILL) Synchronous AE2 notification. requestUpdate is a
            // STATIC method on ICraftingProvider that takes IManagedGridNode (AE2's own
            // interface, NOT IGridNode — these are two distinct types in the AE2 API).
            // Resolve the method reflectively to avoid placing
            // {@code appeng.api.networking.IManagedGridNode} on the compile classpath's
            // import path. The runtime dispatcher uses invokeExact on the MethodHandle,
            // and AE2's IManagedGridNode parameter type is on AE2's actual jar.
            invokeRequestUpdate(managedNode);
            // Clear the flag so GTL's next-tick update() does NOT re-notify. Calling
            // requestUpdate twice is harmless but forces an extra craftingMethods rebuild
            // — the rebuild itself is cheap but it would invalidate any VM bundleCache
            // kept by the SECOND bump's first read. One notify per edit is correct.
            if (NEED_PATTERN_SYNC_SETTER_HANDLE != null) {
                ((MethodHandle) NEED_PATTERN_SYNC_SETTER_HANDLE).invoke((Object) this, false);
            }
        } catch (Throwable t) {
            // requestUpdate can throw if the node is offline / removed. The pattern-set
            // change still took effect — the next time the node comes online, AE2 will
            // rebuild the provider set and bumpPatternVersion will already have fired
            // for the next VM execute. Never break the user's slot edit.
        }
    }

    /** Reflective invocation of {@code ICraftingProvider.requestUpdate(IManagedGridNode)}.
     *  AE2 ships {@code appeng.api.networking.IManagedGridNode} on its jar but it is NOT
     *  in AE2VM's compile classpath — the AE2 jar is pulled as a transitive of gtlcore
     *  (which does include AE2 at runtime), but AE2VM does not depend on it directly
     *  for compile. Calling the static method via reflection sidesteps javac's
     *  type-resolution, which would otherwise force a hard import of the AE2 class and
     *  an explicit cast that {@code javac} then validates against the parameter type
     *  (an interface AE2VM does not declare). */
    private static void invokeRequestUpdate(Object managedNode) {
        try {
            Class<?> icp = Class.forName("appeng.api.networking.crafting.ICraftingProvider");
            // requestUpdate(IManagedGridNode) — STATIC. Pass the (typed) managedNode
            // through Object so javac does not try to validate the cast.
            icp.getMethod("requestUpdate", Class.forName("appeng.api.networking.IManagedGridNode"))
               .invoke(null, managedNode);
        } catch (Throwable ignored) {
            // Reflective dispatch is best-effort. A failure here means the runtime
            // classpath is missing AE2 — which would mean AE2VM does not load at all,
            // so this mixin would never have been applied.
        }
    }
}