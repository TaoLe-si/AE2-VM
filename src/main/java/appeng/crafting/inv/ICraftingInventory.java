package appeng.crafting.inv;

import java.util.Collection;

import appeng.api.config.Actionable;
import appeng.api.storage.data.IAEStack;

/**
 * AE2 v8 (1.16.5) shim of the v9 {@code appeng.crafting.inv.ICraftingInventory}.
 * <p>
 * In v9, {@code CraftingSimulationState} implements this interface, which is how
 * {@code ChildCraftingSimulationState} wraps its parent. The v8 port keeps the same shape
 * so the VM core is untouched.
 */
public interface ICraftingInventory {

    IAEStack extractItems(IAEStack input, Actionable mode);

    void injectItems(IAEStack input, Actionable mode);

    Collection<IAEStack> findFuzzyTemplates(IAEStack input);
}
