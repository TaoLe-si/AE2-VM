package appeng.api.storage.data;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;

import appeng.api.config.FuzzyMode;

/**
 * AE2 v8 (1.16.5) shim of the v9 {@code appeng.api.storage.data.MixedStackList}.
 * <p>
 * v9's MixedStackList aggregates every storage channel (items + fluids). v8 has no such
 * type — the item channel list is {@code IItemList<IAEItemStack>}, obtained from
 * {@code IStorageChannel#createList()}. The VM is item-only, so this shim keeps a single
 * item list as its backing store.
 */
public final class MixedStackList implements Iterable<IAEStack> {

    private final IItemList<IAEItemStack> items;

    public MixedStackList() {
        this.items = appeng.api.storage.StorageChannels.items().createList();
    }

    private MixedStackList(IItemList<IAEItemStack> items) {
        this.items = items;
    }

    public void add(IAEStack stack) {
        IAEItemStack is = asItem(stack);
        if (is != null) {
            this.items.add(is);
        }
    }

    public void addCrafting(IAEStack stack) {
        IAEItemStack is = asItem(stack);
        if (is != null) {
            this.items.addCrafting(is);
        }
    }

    public void addStorage(IAEStack stack) {
        IAEItemStack is = asItem(stack);
        if (is != null) {
            this.items.addStorage(is);
        }
    }

    public void addRequestable(IAEStack stack) {
        IAEItemStack is = asItem(stack);
        if (is != null) {
            this.items.addRequestable(is);
        }
    }

    public IAEStack findPrecise(IAEStack stack) {
        IAEItemStack is = asItem(stack);
        return is == null ? null : this.items.findPrecise(is);
    }

    public Collection<IAEStack> findFuzzy(IAEStack stack, FuzzyMode fuzzy) {
        IAEItemStack is = asItem(stack);
        Collection<IAEStack> out = new ArrayList<>();
        if (is == null) {
            return out;
        }
        for (IAEItemStack match : this.items.findFuzzy(is, fuzzy)) {
            out.add(match);
        }
        return out;
    }

    public boolean isEmpty() {
        return this.items.isEmpty();
    }

    public int size() {
        return this.items.size();
    }

    public void resetStatus() {
        this.items.resetStatus();
    }

    @Override
    public Iterator<IAEStack> iterator() {
        final Iterator<IAEItemStack> it = this.items.iterator();
        return new Iterator<IAEStack>() {
            @Override
            public boolean hasNext() {
                return it.hasNext();
            }

            @Override
            public IAEStack next() {
                return it.next();
            }

            @Override
            public void remove() {
                it.remove();
            }
        };
    }

    private static IAEItemStack asItem(IAEStack stack) {
        if (stack instanceof IAEItemStack) {
            return (IAEItemStack) stack;
        }
        return null;
    }
}
