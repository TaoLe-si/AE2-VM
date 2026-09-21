package com.ae2vm.shim.api.stacks;

import appeng.api.config.FuzzyMode;
import com.ae2vm.shim.util.Object2LongMap;
import com.ae2vm.shim.util.Object2LongOpenHashMap;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;

/**
 * v10+ {@code KeyCounter} 的替身：({@code AEKey} → {@code long}) 的可变多重集。
 * <p>
 * 后端是 {@link Object2LongOpenHashMap}（1.10.2 的 fastutil 是 Mojang 裁剪版，没有
 * {@code Object2LongMap}，见那个类的注释），条目类型仍是 {@code Object2LongMap.Entry<AEKey>}，
 * 所以内核读 {@code entry.getKey()} / {@code entry.getLongValue()} 的面与 v10+ 一致。
 * 合并后保留 0 值条目（v10+ {@code mergeLong} 语义）；{@link #get} 对缺省键返回 0。
 */
public final class KeyCounter implements Iterable<Object2LongMap.Entry<AEKey>> {

    private final Object2LongOpenHashMap<AEKey> counter = new Object2LongOpenHashMap<>();

    public KeyCounter() {
    }

    /** 累加 {@code amount}。键不存在时按 0 起算。 */
    public void add(AEKey key, long amount) {
        if (key == null) {
            return;
        }
        counter.addTo(key, amount);
    }

    /** 扣减 {@code amount}（v10+ {@code remove(k, amount)}）。 */
    public void remove(AEKey key, long amount) {
        add(key, -amount);
    }

    /** 直接替换该键的量。 */
    public void set(AEKey key, long amount) {
        if (key == null) {
            return;
        }
        counter.put(key, amount);
    }

    /** 该键的量，缺省 0。 */
    public long get(AEKey key) {
        return counter.getOrDefault(key, 0L);
    }

    /** 表里一条都没有。 */
    public boolean isEmpty() {
        return counter.isEmpty();
    }

    /** 在册的键数（含 0 值条目，与 v10+ 一致）。 */
    public int size() {
        return counter.size();
    }

    /** 全部键。 */
    public Set<AEKey> keySet() {
        return counter.keySet();
    }

    /** 全部量。 */
    public Collection<Long> values() {
        return counter.values();
    }

    /** key → amount 条目。 */
    public Set<Object2LongMap.Entry<AEKey>> entrySet() {
        return counter.entrySet();
    }

    /** {@code key} 是否在册。 */
    public boolean containsKey(AEKey key) {
        return counter.containsKey(key);
    }

    /** 清空。 */
    public void clear() {
        counter.clear();
    }

    /** {@link #clear()} 的同义写法（v10+ API）。 */
    public void reset() {
        clear();
    }

    /**
     * for-each 热路径：走 {@code fastIterator()}（复用一个 entry），与 nova 那份包着
     * fastutil 快迭代器的 {@code ObjectIteratorAdapter} 同形 —— 每次调用只产出一个游标对象。
     * ⚠ 不要把这里的元素存进集合：它们会是同一个对象。要快照请用 {@link #entrySet()}。
     */
    @Override
    public Iterator<Object2LongMap.Entry<AEKey>> iterator() {
        return counter.fastIterator();
    }

    /** 逐条回调 key → amount。 */
    public void forEach(BiConsumer<? super AEKey, ? super Long> action) {
        counter.entrySet().forEach(e -> action.accept(e.getKey(), e.getLongValue()));
    }

    /**
     * 与 {@code key} 模糊匹配的条目（实时库存查询用到的 v10+ 面）。
     * 替身语义下的 {@code FuzzyMode.IGNORE_ALL} = 同物品、任意 NBT/damage ——
     * 与 VM 对处理样板输入所依赖的模糊契约一致。
     */
    public Collection<Object2LongMap.Entry<AEKey>> findFuzzy(AEKey key, FuzzyMode fuzzy) {
        Objects.requireNonNull(key, "key");
        Set<Object2LongMap.Entry<AEKey>> result = new HashSet<>();
        for (Object2LongMap.Entry<AEKey> e : counter.entrySet()) {
            if (e.getKey().getItem() == key.getItem()) {
                result.add(e);
            }
        }
        return result;
    }

    /** 任意一个键（首条），空表返回 {@code null}（v10+ API）。 */
    public AEKey getFirstKey() {
        Iterator<Object2LongMap.Entry<AEKey>> it = counter.fastIterator();
        return it.hasNext() ? it.next().getKey() : null;
    }
}
