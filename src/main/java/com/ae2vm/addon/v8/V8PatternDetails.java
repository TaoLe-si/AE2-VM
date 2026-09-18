package com.ae2vm.addon.v8;

import java.lang.reflect.Method;
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

        List<IAEItemStack> rawInputs = delegate.getInputs();
        this.inputs = new IInput[rawInputs.size()];
        for (int i = 0; i < rawInputs.size(); i++) {
            this.inputs[i] = new V8Input(this.delegate, rawInputs.get(i), i);
        }

        List<IAEItemStack> rawOutputs = delegate.getOutputs();
        this.outputs = new IAEStack[rawOutputs.size()];
        for (int i = 0; i < rawOutputs.size(); i++) {
            this.outputs[i] = rawOutputs.get(i);
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
            if (this.delegate.canSubstitute()) {
                // AE2 v8.0.0 (1.16.1) doesn't have ICraftingPatternDetails#getSubstituteInputs(int).
                // AE2 v8.4.7 (1.16.5) added it. Use reflection so the same shim compiles against both.
                List<IAEItemStack> subs = invokeGetSubstituteInputs(this.delegate, this.slot);
                if (subs != null) {
                    for (IAEItemStack sub : subs) {
                        if (sub != null && !possible.contains(sub)) {
                            possible.add(sub);
                        }
                    }
                }
            }
            return possible.toArray(new IAEStack[0]);
        }

        /**
         * Reflectively calls {@code ICraftingPatternDetails#getSubstituteInputs(int)} when the
         * underlying AE2 version exposes it (AE2 v8.4.7+). Returns null when the method is
         * absent (AE2 v8.0.0 / v8.1.x / v8.2.x / v8.3.x — added in v8.4.0) so the caller can
         * fall back to the primary input list.
         */
        @SuppressWarnings("unchecked")
        private static List<IAEItemStack> invokeGetSubstituteInputs(ICraftingPatternDetails delegate, int slot) {
            try {
                Method m = delegate.getClass().getMethod("getSubstituteInputs", int.class);
                Object result = m.invoke(delegate, slot);
                if (result instanceof List<?>) {
                    return (List<IAEItemStack>) result;
                }
            } catch (NoSuchMethodException expected) {
                // AE2 < 8.4.0: method does not exist → fuzzy substitution unavailable
            } catch (Throwable t) {
                // best-effort: any failure here means the VM falls back to the primary inputs
            }
            return null;
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
            ItemStack container = stack.getContainerItem();
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
