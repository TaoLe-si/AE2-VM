package com.ae2vm.shim.crafting.inv;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import appeng.api.config.Actionable;
import com.ae2vm.shim.api.crafting.IPatternDetails;
import appeng.api.storage.data.IAEStack;
import com.ae2vm.shim.api.storage.data.MixedStackList;

/**
 * AE2 v8 (1.16.5) shim of the v9 {@code com.ae2vm.shim.crafting.inv.CraftingSimulationState}.
 * <p>
 * v8 has no simulation state at all — its legacy engine simulates directly on
 * {@code appeng.crafting.MECraftingInventory} inside the {@code CraftingJob} tree walk.
 * This shim keeps only what the VM core touches (bytes accounting, per-pattern craft
 * counts, emitted/ignored stacks) and delegates the actual stock lookups to the subclass
 * ({@code simulateExtractParent} / {@code findFuzzyParent}), exactly like v9.
 */
public abstract class CraftingSimulationState implements ICraftingInventory,
        com.ae2vm.shim.crafting.inv.CraftingSimulationStateAccessor {

    private double bytes = 0;
    private final Map<IPatternDetails, Long> crafts = new HashMap<>();
    private final MixedStackList emittedItems = new MixedStackList();
    private final MixedStackList ignored = new MixedStackList();

    /** Parent-side extraction — implemented by the concrete network-backed state. */
    protected abstract IAEStack simulateExtractParent(IAEStack input);


    /**
     * 带 {@code mode} 的同款钩子。{@code MODULATE} 表示"真取走了"，父级库存必须随之减少。
     * 这是 AE2 v15 {@code CraftingSimulationState.extract} 的语义（反编译核实）：它只有
     * 一份 {@code modifiableCache}，{@code insert} 加、{@code extract} 减，另有
     * {@code unmodifiedCache} 用来算"网络峰值需求"。我们的 shim 没有 requiredExtract，
     * 所以"注入要入账"和"抽取要扣减"必须同时成立，否则同一份库存会被下一个消费者再借一次。
     */
    protected IAEStack simulateExtractParent(IAEStack input, Actionable mode) {
        return this.simulateExtractParent(input);
    }
    /** Parent-side fuzzy lookup — implemented by the concrete network-backed state. */
    protected abstract Collection<IAEStack> findFuzzyParent(IAEStack input);

    public void addBytes(double bytes) {
        this.bytes += bytes;
    }

    public double getBytes() {
        return this.bytes;
    }

    public void addCrafting(IPatternDetails details, long crafts) {
        if (details == null || crafts <= 0) {
            return;
        }
        Long prev = this.crafts.get(details);
        this.crafts.put(details, prev == null ? crafts : prev + crafts);
    }

    public Map<IPatternDetails, Long> getCrafts() {
        return this.crafts;
    }

    public void emitItems(IAEStack what) {
        if (what != null && what.isMeaningful()) {
            this.emittedItems.addStorage(what);
        }
    }

    public MixedStackList getEmittedItems() {
        return this.emittedItems;
    }

    public void ignore(IAEStack stack) {
        if (stack != null && stack.isMeaningful()) {
            this.ignored.add(stack);
        }
    }

    public boolean isIgnored(IAEStack stack) {
        return stack != null && this.ignored.findPrecise(stack) != null;
    }

    /**
     * v1.14.2 FIX（1.20.1 对照单测定位）：这个沙箱必须**真的**存住注入量并扣减抽取量。
     *
     * <p>VM 的折抵逻辑是 {@code fromInternal = min(got, simInternal)} —— 它假设
     * {@code got} 里包含"我们自己产出的货"。原来的 shim 把 {@code injectItems} 写成空操作、
     * {@code extractItems} 也不扣减，于是自产中间品永远进不了沙箱：父样板只能按网络库存结余额，
     * 残差被记成缺料（实测：100 工作台 = 400 木板需求、网络 1 木板、木板样板已派工 100 次，
     * 却报 {@code missing={98xplanks}}；同时 {@code usedItems} 把那 1 个库存重复记成 2）。
     * 1.20.1/1.21.1 用 AE2 自家的 {@code appeng.crafting.inv.CraftingSimulationState}（有真库存），
     * 同一场景补料后 {@code missing={}}，所以差异只在这一层。
     */
    private final MixedStackList inventory = new MixedStackList();

    /** 诊断用：本沙箱自有库存里该键当前余量。 */
    private long ownOf(IAEStack stack) {
        IAEStack mine = this.inventory.findPrecise(stack);
        return mine == null ? 0L : mine.getStackSize();
    }

    @Override
    public IAEStack extractItems(IAEStack input, Actionable mode) {
        if (input == null || !input.isMeaningful()) {
            return null;
        }
        long want = input.getStackSize();
        if (want <= 0L) {
            return null;
        }
        long fromOwn = 0L;
        IAEStack mine = this.inventory.findPrecise(input);
        if (mine != null && mine.getStackSize() > 0L) {
            fromOwn = Math.min(want, mine.getStackSize());
            if (mode == Actionable.MODULATE) {
                mine.decStackSize(fromOwn);
            }
        }
        long rest = want - fromOwn;
        long fromParent = 0L;
        if (rest > 0L) {
            IAEStack ask = input.copy();
            ask.setStackSize(rest);
            IAEStack got = this.simulateExtractParent(ask, mode);
            if (got != null && got.getStackSize() > 0L) {
                fromParent = got.getStackSize();
            }
        }
        long total = fromOwn + fromParent;
        if (com.ae2vm.addon.config.AE2VMConfig.isDebugLogging()) {
            com.ae2vm.addon.AE2VMAddon.LOGGER.info("[AE2-VM] SANDBOX extract want=" + want
                    + " fromOwn=" + fromOwn + " fromParent=" + fromParent + " mode=" + mode
                    + " ownLeft=" + ownOf(input));
        }
        if (total <= 0L) {
            return null;
        }
        IAEStack out = input.copy();
        out.setStackSize(total);
        return out;
    }

    @Override
    public void injectItems(IAEStack input, Actionable mode) {
        if (mode == Actionable.MODULATE && input != null && input.isMeaningful()) {
            if (com.ae2vm.addon.config.AE2VMConfig.isDebugLogging()) {
                com.ae2vm.addon.AE2VMAddon.LOGGER.info("[AE2-VM] SANDBOX inject amount="
                        + input.getStackSize() + " ownBefore=" + ownOf(input));
            }
            this.inventory.addStorage(input);
        }
    }

    @Override
    public Collection<IAEStack> findFuzzyTemplates(IAEStack input) {
        if (input == null) {
            return java.util.Collections.emptyList();
        }
        return this.findFuzzyParent(input);
    }

    /**
     * v9 {@code addStackBytes(IAEStack, long)}: accounts the byte cost of moving
     * {@code stack} × multiplier. v8 has no simulation state, so the same formula is
     * applied here from the channel's transfer factor / units-per-byte.
     */
    public void addStackBytes(appeng.api.storage.data.IAEStack stack, long multiplier) {
        if (stack == null || !stack.isMeaningful() || multiplier <= 0) {
            return;
        }
        appeng.api.storage.IStorageChannel<?> channel = stack.getChannel();
        double perByte = channel == null ? 8.0 : (double) channel.transferFactor() * channel.getUnitsPerByte();
        if (perByte <= 0) {
            perByte = 8.0;
        }
        double bytes = stack.getStackSize() * (double) multiplier / perByte;
        this.bytes += Math.ceil(bytes);
    }

    /** Merge this child state's diff into its parent (v9 {@code applyDiff}). */
    public void applyDiff(CraftingSimulationState parent) {
        if (parent == null) {
            return;
        }
        parent.bytes += this.bytes;
        for (Map.Entry<IPatternDetails, Long> e : this.crafts.entrySet()) {
            Long prev = parent.crafts.get(e.getKey());
            parent.crafts.put(e.getKey(), prev == null ? e.getValue() : prev + e.getValue());
        }
    }
}
