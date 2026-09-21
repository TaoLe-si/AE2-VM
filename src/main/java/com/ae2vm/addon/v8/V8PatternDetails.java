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
        this.inputs = new IInput[rawInputs.length];
        for (int i = 0; i < rawInputs.length; i++) {
            this.inputs[i] = new V8Input(this.delegate, rawInputs[i]);
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

    @Override
    public ItemStack copyDefinition() {
        ItemStack pattern = this.delegate.getPattern();
        // rv4 的 ItemStack 没有 EMPTY（1.12 才加），空栈就是 null。
        return pattern == null ? null : pattern.copy();
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

        V8Input(ICraftingPatternDetails delegate, IAEItemStack template) {
            this.delegate = delegate;
            this.template = template;
        }

        @Override
        public IAEStack[] getPossibleInputs() {
            List<IAEItemStack> possible = new ArrayList<>();
            possible.add(this.template);
            // rv4(1.10.2) **没有**多候选输入可枚举：合成接口全量签名里只有 getPattern /
            // isValidItemForSlot / isCraftable / getInputs / getCondensedInputs /
            // getCondensedOutputs / getOutputs / canSubstitute / getOutput / getPriority，
            // 整个 rv4 jar 里那个方法名的字面量命中数是 0 —— 字典与多选输入这一代根本还没有。
            // AE2 自家 CraftingTreeNode.request() 在 canSubstitute() 分支也只对 this.what 做
            // findFuzzy(what, IGNORE_ALL)，所以候选键就是 template 这一个，模糊维度交给下游
            // KeyCounter.findFuzzy —— 与 1.15.2 / 1.16.1 / 1.16.2 / 1.16.3 四个兄弟 fork 同一口径
            // （那四版的 V8BridgeQuantityTest 也是另写的一套 7 条，钉住"候选归一成 1"）。
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
            // rv4 的 IAEItemStack 没有那个拿 ItemStack 的方法，要栈本身取；
            // 且 1.10.2 的 ItemStack 既没有 EMPTY 也没有 isEmpty() —— 空栈就是 null。
            ItemStack stack = ((IAEItemStack) template).getItemStack();
            if (stack == null) {
                return null;
            }
            // 1.10.2 的容器在 Item 上，且带参的 hasContainerItem(ItemStack)/getContainerItem(ItemStack)
            // 两个重载在 stable_29 里确实存在（javap 实测），直接用。
            ItemStack container = stack.getItem().hasContainerItem(stack)
                    ? stack.getItem().getContainerItem(stack) : null;
            if (container == null) {
                return null;
            }
            IAEItemStack key = appeng.util.item.AEItemStack.create(container);
            if (key == null) {
                return null;
            }
            key.setStackSize(1);
            return key;
        }
    }
}
