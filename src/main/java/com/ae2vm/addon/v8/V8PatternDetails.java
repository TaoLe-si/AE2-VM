package com.ae2vm.addon.v8;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.item.ItemStack;
import net.minecraft.world.World;

import com.ae2vm.shim.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingPatternDetails;
import appeng.api.storage.data.IAEItemStack;
import appeng.api.storage.data.IAEStack;

/**
 * Adapts an AE2 v8 (1.16.5) {@link ICraftingPatternDetails} to the v9
 * {@link IPatternDetails} surface the VM core compiles against.
 * <p>
 * v8 pattern semantics vs v9:
 * <ul>
 *   <li>v8 {@code getInputs()/getOutputs()} return aggregated {@code List<IAEItemStack>}
 *       (quantity inside the stack) — v9 returns {@code IInput[]} / {@code IAEStack[]}.</li>
 *   <li>The per-craft consumption is the input stack size, which maps directly onto
 *       v9's {@code IInput#getMultiplier()}.</li>
 *   <li>v8 has no container-item API on the pattern; the container item is derived from
 *       the input item itself ({@code ItemStack#getContainerItem()}), matching what AE2's
 *       own {@code CraftingTreeProcess} does with {@code hasContainerItem}.</li>
 * </ul>
 */
public final class V8PatternDetails implements IPatternDetails {

    private final ICraftingPatternDetails delegate;
    private final IInput[] inputs;
    private final IAEStack[] outputs;

    public V8PatternDetails(ICraftingPatternDetails delegate) {
        this.delegate = delegate;

        // uel(1.12.2) 的 ICraftingPatternDetails 是数组版：getInputs()/getOutputs() 是**逐槽**的，
        // getCondensedInputs()/getCondensedOutputs() 才是按物品合并、数量求和的表 ——
        // 与 v8(1.16) 那个 List getInputs() 同语义。§22 的量纲契约要求这里必须取 condensed。
        IAEItemStack[] rawInputs = delegate.getCondensedInputs();
        // getSubstituteInputs(int) 的下标是**逐槽**的（反编译 uel PatternHelper：
        // 先 inputs[index] 判空，再读 substituteInputs[index]，最后 getRecipeIngredient(index)），
        // 而上面取的是 condensed 表 —— 槽位被合并过就多错位（工作台 4 个木板槽 condense 成 1 条，
        // 但 4 槽同 Ingredient，正好撞对；混合样板就会拿到别的槽的候选）。
        // 所以按 identity 反查该 condensed 项在 sparse 数组里的首个下标。
        final IAEItemStack[] sparseInputs = delegate.getInputs();
        this.inputs = new IInput[rawInputs.length];
        for (int i = 0; i < rawInputs.length; i++) {
            this.inputs[i] = new V8Input(this.delegate, rawInputs[i], slotOf(sparseInputs, rawInputs[i]));
        }

        IAEItemStack[] rawOutputs = delegate.getCondensedOutputs();
        this.outputs = new IAEStack[rawOutputs.length];
        for (int i = 0; i < rawOutputs.length; i++) {
            this.outputs[i] = rawOutputs[i];
        }
    }

    /** The wrapped v8 pattern — needed when handing craft counts to the v8 crafting CPU. */
    public ICraftingPatternDetails getDelegate() {
        return this.delegate;
    }

    /** Unwraps the v8 delegate of {@code p}, or {@code null} if {@code p} is not v8-backed. */
    public static ICraftingPatternDetails unwrap(IPatternDetails p) {
        return p instanceof V8PatternDetails ? ((V8PatternDetails) p).delegate : null;
    }

    /**
     * {@code condensed} 在逐槽数组 {@code sparse} 里的下标；找不到（AE2 内部合并顺序不同、
     * 或该实现没给 sparse）返回 -1，调用方据此跳过候选枚举。
     * AE2 的 {@code AEItemStack.equals} 只看 item+damage+NBT、不看数量，所以 condensed
     * 聚合栈能和它合并前的任一 sparse 槽对上。
     */
    private static int slotOf(IAEItemStack[] sparse, IAEItemStack condensed) {
        if (sparse == null || condensed == null) {
            return -1;
        }
        for (int i = 0; i < sparse.length; i++) {
            if (sparse[i] != null && condensed.equals(sparse[i])) {
                return i;
            }
        }
        return -1;
    }

    @Override
    public ItemStack copyDefinition() {
        ItemStack pattern = this.delegate.getPattern();
        return pattern == null ? ItemStack.EMPTY : pattern.copy();
    }

    @Override
    public IInput[] getInputs() {
        return this.inputs;
    }

    @Override
    public IAEStack[] getOutputs() {
        return this.outputs;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof V8PatternDetails)) {
            return false;
        }
        return this.delegate.equals(((V8PatternDetails) obj).delegate);
    }

    @Override
    public int hashCode() {
        return this.delegate.hashCode();
    }

    @Override
    public String toString() {
        return "V8PatternDetails[" + this.delegate + "]";
    }

    private static final class V8Input implements IInput {

        private final ICraftingPatternDetails delegate;
        private final IAEItemStack template;
        private final int slot;

        V8Input(ICraftingPatternDetails delegate, IAEItemStack template, int slot) {
            this.delegate = delegate;
            this.template = template;
            this.slot = slot;
        }

        @Override
        public IAEStack[] getPossibleInputs() {
            List<IAEItemStack> possible = new ArrayList<>();
            possible.add(this.template);
            // uel(1.12.2) 的 ICraftingPatternDetails 上有 default getSubstituteInputs(int)
            // （PatternHelper 覆盖它：sparse 槽自身 + 该槽 Ingredient 的全部匹配项），
            // 1.12.2 的字典样板（plankWood/logWood…）全靠这条路把"任一木板"列成候选 ——
            // 与 1.16.4/1.16.5 那两个已实机验收的 fork 同一份逻辑。
            // 少了它，替代样板只剩一个精确 meta 候选，内核 getFuzzyGroup 退化成单键，
            // 网络里别的 meta 变体一律算成缺料。
            if (this.slot >= 0 && this.delegate.canSubstitute()) {
                try {
                    for (IAEItemStack sub : this.delegate.getSubstituteInputs(this.slot)) {
                        if (sub != null && !possible.contains(sub)) {
                            possible.add(sub);
                        }
                    }
                } catch (Throwable ignored) {
                    // substitution is best-effort
                }
            }
            IAEStack[] out = possible.toArray(new IAEStack[0]);
            for (int i = 0; i < out.length; i++) {
                IAEItemStack variant = ((IAEItemStack) out[i]).copy();
                variant.setStackSize(1L);
                out[i] = variant;
            }
            return out;
        }

        @Override
        public long getMultiplier() {
            return this.template.getStackSize();
        }

        @Override
        public boolean isValid(IAEStack input, World level) {
            if (!(input instanceof IAEItemStack)) {
                return false;
            }
            IAEItemStack is = (IAEItemStack) input;
            if (this.template.equals(is)) {
                return true;
            }
            if (this.delegate.canSubstitute()) {
                for (IAEStack candidate : getPossibleInputs()) {
                    if (candidate != null && candidate.equals(input)) {
                        return true;
                    }
                }
            }
            return false;
        }

        @Override
        public IAEStack getContainerItem(IAEStack template) {
            if (!(template instanceof IAEItemStack)) {
                return null;
            }
            ItemStack stack = ((IAEItemStack) template).createItemStack();
            if (stack.isEmpty()) {
                return null;
            }
            // 1.12.2 的 ItemStack 没有 getContainerItem()，容器在 Item 上（MCP 名 getContainerItem/hasContainerItem）
            ItemStack container = stack.getItem().hasContainerItem(stack)
                    ? stack.getItem().getContainerItem(stack) : ItemStack.EMPTY;
            if (container.isEmpty()) {
                return null;
            }
            IAEItemStack key = appeng.util.item.AEItemStack.fromItemStack(container);
            if (key == null) {
                return null;
            }
            key.setStackSize(1);
            return key;
        }
    }
}
