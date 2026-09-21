package com.ae2vm.shim.api.networking.crafting;

/**
 * AE2 v8 (1.16.5) shim of the v9 {@code ICraftingSimulationRequester}.
 * <p>
 * v8 (1.16.5) has no such type — crafting requests carry a
 * {@code ICraftingRequester} / {@code ICraftingCallback} instead. The VM core only uses
 * this as an opaque requester identity (third-party opt-in registry), so an empty marker
 * interface is enough; v8 call sites pass {@code null} or a v8 adapter.
 */
public interface ICraftingSimulationRequester {
}
