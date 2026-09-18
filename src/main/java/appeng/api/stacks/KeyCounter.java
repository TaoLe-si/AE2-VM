package appeng.api.stacks;

import appeng.api.config.FuzzyMode;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;

/**
 * AE2 v9 (1.17.1) compatibility shim for the v10+ {@code appeng.api.stacks.KeyCounter}
 * API: a mutable multiset of ({@code AEKey} → {@code long} amount).
 * <p>
 * Backed by a fastutil {@code Object2LongOpenHashMap} so iteration yields
 * {@code Object2LongMap.Entry<AEKey>} exactly like v10+ (the VM core reads
 * {@code entry.getKey()} / {@code entry.getLongValue()}). Zero-valued entries are kept
 * after merge (v10+ {@code mergeLong} semantics); {@link #get} returns 0 for missing keys.
 */
public final class KeyCounter implements Iterable<Object2LongMap.Entry<AEKey>> {

    private final Object2LongOpenHashMap<AEKey> counter = new Object2LongOpenHashMap<>();

    public KeyCounter() {
    }

    /** Adds {@code amount} to the key's tally (v10+ {@code mergeLong} semantics). */
    public void add(AEKey key, long amount) {
        if (key == null) {
            return;
        }
        counter.mergeLong(key, amount, Long::sum);
    }

    /** Subtracts {@code amount} from the key's tally (v10+ {@code remove(k, amount)}). */
    public void remove(AEKey key, long amount) {
        add(key, -amount);
    }

    /** Replaces the key's tally outright. */
    public void set(AEKey key, long amount) {
        if (key == null) {
            return;
        }
        counter.put(key, amount);
    }

    /** The key's tally, 0 when absent. */
    public long get(AEKey key) {
        return counter.getOrDefault(key, 0L);
    }

    /** True when the counter has no entries at all. */
    public boolean isEmpty() {
        return counter.isEmpty();
    }

    /** Number of distinct keys tallied (zero-valued entries included, like v10+). */
    public int size() {
        return counter.size();
    }

    /** All tallied keys. */
    public Set<AEKey> keySet() {
        return counter.keySet();
    }

    /** All tallies. */
    public Collection<Long> values() {
        return counter.values();
    }

    /** Key → amount entries. */
    public Set<Object2LongMap.Entry<AEKey>> entrySet() {
        return counter.object2LongEntrySet();
    }

    /** True when {@code key} is tallied. */
    public boolean containsKey(AEKey key) {
        return counter.containsKey(key);
    }

    /** Removes every entry. */
    public void clear() {
        counter.clear();
    }

    /** Synonym for {@link #clear()} (v10+ API). */
    public void reset() {
        clear();
    }

    /** Iterates key → amount entries (fastutil entries: getKey/getLongValue). */
    @Override
    public ObjectIteratorAdapter iterator() {
        return new ObjectIteratorAdapter(counter.object2LongEntrySet().fastIterator());
    }

    /** Iterates key → amount entries. */
    public void forEach(BiConsumer<? super AEKey, ? super Long> action) {
        counter.object2LongEntrySet().forEach(e -> action.accept(e.getKey(), e.getLongValue()));
    }

    /**
     * Entries that fuzzy-match {@code key} (v10+ surface used by the realtime stock
     * lookup). Shim semantics for {@code FuzzyMode.IGNORE_ALL}: same item, any NBT /
     * damage — the same fuzzy contract the VM relies on for processing-recipe inputs.
     */
    public Collection<Object2LongMap.Entry<AEKey>> findFuzzy(AEKey key, FuzzyMode fuzzy) {
        Objects.requireNonNull(key, "key");
        java.util.HashSet<it.unimi.dsi.fastutil.objects.Object2LongMap.Entry<appeng.api.stacks.AEKey>> result = new HashSet<Object2LongMap.Entry<AEKey>>();
        for (Object2LongMap.Entry<AEKey> e : counter.object2LongEntrySet()) {
            if (e.getKey().getItem() == key.getItem()) {
                result.add(e);
            }
        }
        return result;
    }

    /** Single key (an arbitrary first entry) or {@code null} when empty (v10+ API). */
    public AEKey getFirstKey() {
        it.unimi.dsi.fastutil.objects.ObjectIterator<it.unimi.dsi.fastutil.objects.Object2LongMap.Entry<appeng.api.stacks.AEKey>> it = counter.object2LongEntrySet().iterator();
        return it.hasNext() ? it.next().getKey() : null;
    }

    /**
     * Simple Iterator wrapper over fastutil's reusable fast iterator so the VM's
     * for-each loops get a fresh, safe iterator per call.
     */
    private static final class ObjectIteratorAdapter
            implements java.util.Iterator<Object2LongMap.Entry<AEKey>> {
        private final it.unimi.dsi.fastutil.objects.ObjectIterator<Object2LongMap.Entry<AEKey>> it;

        ObjectIteratorAdapter(it.unimi.dsi.fastutil.objects.ObjectIterator<Object2LongMap.Entry<AEKey>> it) {
            this.it = it;
        }

        @Override
        public boolean hasNext() {
            return it.hasNext();
        }

        @Override
        public Object2LongMap.Entry<AEKey> next() {
            return it.next();
        }
    }
}
