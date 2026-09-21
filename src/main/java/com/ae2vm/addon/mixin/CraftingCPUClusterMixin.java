package com.ae2vm.addon.mixin;

import java.util.Map;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.nbt.NBTTagCompound;

import appeng.api.config.Actionable;
import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.CraftingItemList;
import appeng.api.networking.crafting.ICraftingJob;
import appeng.api.networking.crafting.ICraftingLink;
import appeng.api.networking.crafting.ICraftingPatternDetails;
import appeng.api.networking.crafting.ICraftingRequester;
import appeng.api.networking.security.IActionSource;
import appeng.api.networking.storage.IStorageGrid;
import appeng.api.storage.IMEInventory;
import appeng.api.storage.data.IAEItemStack;
import appeng.api.storage.data.IItemList;
import appeng.api.AEApi;
import appeng.crafting.CraftBranchFailure;
import appeng.crafting.CraftingLink;
import appeng.crafting.MECraftingInventory;
import appeng.me.cluster.implementations.CraftingCPUCluster;

import com.ae2vm.addon.AE2VMAddon;
import com.ae2vm.addon.config.AE2VMConfig;
import com.ae2vm.addon.v8.VMCraftingJob;

/**
 * Executes the VM's plan on an AE2 v8 (1.16.5) crafting CPU.
 * <p>
 * {@code CraftingCPUCluster.submitJob} rejects anything that is not a vanilla
 * {@code appeng.crafting.CraftingJob} and then drives the job through the legacy
 * {@code CraftingTreeNode} tree. The VM's plan is flat (pattern × craft counts), so we
 * intercept the submit and reproduce the same protocol directly:
 * <ol>
 *   <li>pull {@code usedItems} out of the network through a {@code MECraftingInventory}</li>
 *   <li>{@code addStorage} / {@code addEmitable} what the VM accounted for</li>
 *   <li>{@code addCrafting(pattern, times)} for every pattern (the CPU then dispatches
 *       those to the pattern providers itself)</li>
 *   <li>commit and build the {@code CraftingLink} state machine, like vanilla does</li>
 * </ol>
 */
@Mixin(value = CraftingCPUCluster.class, remap = false)
public abstract class CraftingCPUClusterMixin {

    @Shadow
    private java.util.Map tasks;
    @Shadow
    private IItemList waitingFor;
    @Shadow
    private MECraftingInventory inventory;
    @Shadow
    private IAEItemStack finalOutput;
    @Shadow
    private boolean waiting;
    @Shadow
    private boolean isComplete;
    @Shadow
    private long availableStorage;
    @Shadow
    private ICraftingLink myLastLink;

    @Shadow
    private void markDirty() {
    }

    @Shadow
    private void updateCPU() {
    }

    @Shadow
    private String generateCraftingID() {
        return null;
    }

    @Shadow
    private NBTTagCompound generateLinkData(String craftingID, boolean standalone, boolean req) {
        return null;
    }

    @Shadow
    private void prepareElapsedTime() {
    }

    @Shadow
    private void submitLink(ICraftingLink link) {
    }

    @Shadow
    private void postChange(IAEItemStack diff, IActionSource src) {
    }

    @Inject(method = "submitJob", at = @At("HEAD"), cancellable = true)
    private void vmSubmitJob(IGrid g, ICraftingJob job, IActionSource src, ICraftingRequester requestingMachine,
            CallbackInfoReturnable<ICraftingLink> cir) {
        final boolean dbg = AE2VMConfig.isDebugLogging();

        if (!(job instanceof VMCraftingJob)) {
            if (dbg) {
                AE2VMAddon.LOGGER.info("[AE2-VM] submitJob: not a VMCraftingJob ("
                        + (job == null ? "null" : job.getClass().getName()) + ") -> vanilla path");
            }
            return; // vanilla job — untouched
        }

        final CraftingCPUCluster self = (CraftingCPUCluster) (Object) this;
        final VMCraftingJob vmJob = (VMCraftingJob) job;

        if (dbg) {
            AE2VMAddon.LOGGER.info("[AE2-VM] submitJob: VM job output=" + vmJob.getOutput()
                    + " bytes=" + vmJob.getByteTotal()
                    + " simulation=" + vmJob.isSimulation()
                    + " patterns=" + vmJob.getPatternTimes().size()
                    + " used=" + countOf(vmJob.getUsedItems())
                    + " emitted=" + countOf(vmJob.getEmittedItems())
                    + " missing=" + countOf(vmJob.getMissingItems())
                    + " | cpu: active=" + self.isActive()
                    + " busy=" + self.isBusy()
                    + " availStorage=" + self.getAvailableStorage()
                    + " tasks=" + (this.tasks == null ? -1 : this.tasks.size())
                    + " waitingFor=" + (this.waitingFor == null ? -1 : this.waitingFor.size()));
        }

        if (!this.tasks.isEmpty() || !this.waitingFor.isEmpty()) {
            if (dbg) {
                AE2VMAddon.LOGGER.info("[AE2-VM] submitJob ABORT: CPU not empty (tasks/waitingFor non-empty)");
            }
            return;
        }
        if (self.isBusy() || !self.isActive() || this.availableStorage < job.getByteTotal()) {
            if (dbg) {
                AE2VMAddon.LOGGER.info("[AE2-VM] submitJob ABORT: busy=" + self.isBusy()
                        + " inactive=" + !self.isActive()
                        + " bytes(" + job.getByteTotal() + ") > availableStorage(" + this.availableStorage + ")");
            }
            return;
        }

        final IStorageGrid sg = g.getCache(IStorageGrid.class);
        final IMEInventory<IAEItemStack> storage =
                sg.getInventory(AEApi.instance().storage().getStorageChannel(
                        appeng.api.storage.channels.IItemStorageChannel.class));
        final MECraftingInventory ci = new MECraftingInventory(storage, true, false, false);

        try {
            this.waitingFor.resetStatus();

            // 1. initial extraction of everything the VM marked as used
            for (IAEItemStack used : vmJob.getUsedItems()) {
                final IAEItemStack ex = ci.extractItems(used, Actionable.MODULATE, src);
                if (ex == null || ex.getStackSize() != used.getStackSize()) {
                    if (dbg) {
                        AE2VMAddon.LOGGER.info("[AE2-VM] submitJob ABORT: cannot extract used item "
                                + used + " (got " + (ex == null ? "null" : String.valueOf(ex.getStackSize())) + ")");
                    }
                    throw new CraftBranchFailure(used, used.getStackSize());
                }
                self.addStorage(ex);
            }

            // 2. what the job will push back into the network
            for (IAEItemStack emitted : vmJob.getEmittedItems()) {
                self.addEmitable(emitted.copy());
            }

            // 3. hand the pattern × craft counts to the CPU
            for (Map.Entry<ICraftingPatternDetails, Long> e : vmJob.getPatternTimes().entrySet()) {
                self.addCrafting(e.getKey(), e.getValue());
            }

            if (ci.commit(src)) {
                if (dbg) {
                    AE2VMAddon.LOGGER.info("[AE2-VM] submitJob OK: committed, CPU started");
                }
                this.finalOutput = job.getOutput();
                this.waiting = false;
                this.isComplete = false;
                this.markDirty();

                this.updateCPU();
                final String craftID = this.generateCraftingID();

                this.myLastLink = new CraftingLink(this.generateLinkData(craftID, requestingMachine == null, false), self);

                this.prepareElapsedTime();

                if (requestingMachine == null) {
                    cir.setReturnValue(this.myLastLink);
                    cir.cancel();
                    return;
                }

                final ICraftingLink whatLink =
                        new CraftingLink(this.generateLinkData(craftID, false, true), requestingMachine);

                this.submitLink(this.myLastLink);
                this.submitLink(whatLink);

                final IItemList<IAEItemStack> list = AEApi.instance().storage()
                        .getStorageChannel(appeng.api.storage.channels.IItemStorageChannel.class).createList();
                self.getListOfItem(list, CraftingItemList.ALL);
                for (IAEItemStack ge : list) {
                    this.postChange(ge, src);
                }

                cir.setReturnValue(whatLink);
                cir.cancel();
            } else {
                if (dbg) {
                    AE2VMAddon.LOGGER.info("[AE2-VM] submitJob ABORT: MECraftingInventory.commit() returned false");
                }
                this.tasks.clear();
                this.inventory.getItemList().resetStatus();
            }
        } catch (final CraftBranchFailure e) {
            AE2VMAddon.LOGGER.warn("[AE2-VM] submitJob ABORT: CraftBranchFailure " + e.getMessage());
            this.tasks.clear();
            this.inventory.getItemList().resetStatus();
        } catch (final Throwable t) {
            AE2VMAddon.LOGGER.error("[AE2-VM] submitJob FAILED with unexpected throwable", t);
            this.tasks.clear();
            this.inventory.getItemList().resetStatus();
        }
    }

    @Unique
    private static int countOf(IItemList<IAEItemStack> list) {
        return list == null ? -1 : list.size();
    }
}
