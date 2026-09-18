package appeng.api.networking.crafting;

import java.util.Map;

import appeng.api.crafting.IPatternDetails;
import appeng.api.storage.data.IAEStack;
import appeng.api.storage.data.MixedStackList;

/**
 * AE2 v8 (1.16.5) shim of the v9 {@code appeng.api.networking.crafting.ICraftingPlan}.
 * <p>
 * v8's plan type is {@code appeng.api.networking.crafting.ICraftingJob}
 * (output + bytes + populatePlan), which carries far less structure than v9's plan. The VM
 * produces this v9-shaped plan; {@code com.ae2vm.addon.v8.VMCraftingJob} then adapts it for
 * the v8 crafting CPU.
 */
public interface ICraftingPlan {

    IAEStack finalOutput();

    long bytes();

    boolean simulation();

    boolean multiplePaths();

    MixedStackList usedItems();

    MixedStackList emittedItems();

    MixedStackList missingItems();

    Map<IPatternDetails, Long> patternTimes();
}
