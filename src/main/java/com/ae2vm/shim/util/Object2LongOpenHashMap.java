package com.ae2vm.shim.util;

import java.util.Collection;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Set;

/**
 * {@link Object2LongMap} 的 1.10.2 实现：开放寻址 + <b>原始 long 值数组</b>，
 * 也就是 fastutil {@code Object2LongOpenHashMap} 的结构。
 *
 * <p>为什么不用 {@code HashMap<K,Long>}：本表是 VM 热路径（{@code CraftingVM} 的
 * used/missing/emitted 记账每轮都过它），装箱会让每次读写都分配一个 {@code Long}。
 * 实测用 {@code LinkedHashMap<K,Long>} 时 {@code PerformanceBenchmark} 的 6 条温热用例
 * 稳定落在 3600–5500 ns（阈值 1000 ns，同一份算式在 nova 上是亚微秒），是 4–5 倍的整体
 * 变慢而不是抖动 —— 所以必须把装箱从热路径上去掉。
 *
 * <p>本表<b>没有按键删除</b>（对齐 v10+：合并后留下的 0 值条目仍在册，真删由调用方自己
 * 处理），所以不需要 tombstone，探测只在空洞处停。
 */
public final class Object2LongOpenHashMap<K> implements Object2LongMap<K> {

    private static final float LOAD_FACTOR = 0.75f;
    private static final int INIT_CAPACITY = 16;

    private Object[] keys;
    private long[] values;
    private boolean[] used;
    private int mask;
    private int filler;

    public Object2LongOpenHashMap() {
        allocate(INIT_CAPACITY);
    }

    private void allocate(int capacity) {
        capacity = Integer.highestOneBit(Math.max(capacity, INIT_CAPACITY) - 1) << 1;
        this.keys = new Object[capacity];
        this.values = new long[capacity];
        this.used = new boolean[capacity];
        this.mask = capacity - 1;
        this.filler = 0;
    }

    /** fastutil 同款的散列搅动：让低位也带上高位的信息，线性探测才不会成片撞槽。 */
    private int probe(Object key) {
        int h = key.hashCode();
        h ^= (h >>> 20) ^ (h >>> 12);
        return (h ^ (h >>> 7) ^ (h << 4)) & this.mask;
    }

    /** 返回 key 所在槽；key 不在表里时返回第一个空槽（此时该槽 {@code used} 为 false）。 */
    private int findSlot(Object key) {
        int i = probe(key);
        while (this.used[i]) {
            if (key.equals(this.keys[i])) {
                return i;
            }
            i = (i + 1) & this.mask;
        }
        return i;
    }

    private void putSlot(K key, long value) {
        if (this.filler >= this.used.length * LOAD_FACTOR) {
            rehash(this.used.length << 1);
        }
        int i = findSlot(key);
        if (!this.used[i]) {
            this.keys[i] = key;
            this.used[i] = true;
            this.filler++;
        }
        this.values[i] = value;
    }

    private void rehash(int capacity) {
        Object[] oldKeys = this.keys;
        long[] oldValues = this.values;
        boolean[] oldUsed = this.used;
        allocate(capacity);
        for (int i = 0; i < oldUsed.length; i++) {
            if (oldUsed[i]) {
                @SuppressWarnings("unchecked")
                K key = (K) oldKeys[i];
                putSlot(key, oldValues[i]);
            }
        }
    }

    /** 累加并返回新值；键不在表里时按 0 起算（fastutil {@code addTo} 语义，零分配）。 */
    public long addTo(K key, long delta) {
        if (this.filler >= this.used.length * LOAD_FACTOR) {
            rehash(this.used.length << 1);
        }
        int i = findSlot(key);
        if (!this.used[i]) {
            this.keys[i] = key;
            this.used[i] = true;
            this.filler++;
            this.values[i] = delta;
            return delta;
        }
        long next = this.values[i] + delta;
        this.values[i] = next;
        return next;
    }

    public void put(K key, long value) {
        putSlot(key, value);
    }

    /** 键的量，缺省 0（不分配）。 */
    public long get(K key) {
        int i = findSlot(key);
        return this.used[i] ? this.values[i] : 0L;
    }

    public long getOrDefault(K key, long def) {
        int i = findSlot(key);
        return this.used[i] ? this.values[i] : def;
    }

    public boolean containsKey(K key) {
        int i = findSlot(key);
        return this.used[i];
    }

    public boolean isEmpty() {
        return this.filler == 0;
    }

    public int size() {
        return this.filler;
    }

    public void clear() {
        allocate(INIT_CAPACITY);
    }

    @SuppressWarnings("unchecked")
    public Set<K> keySet() {
        Set<K> out = new java.util.LinkedHashSet<>();
        for (int i = 0; i < this.used.length; i++) {
            if (this.used[i]) {
                out.add((K) this.keys[i]);
            }
        }
        return out;
    }

    public Collection<Long> values() {
        Collection<Long> out = new java.util.ArrayList<>(this.filler);
        for (int i = 0; i < this.used.length; i++) {
            if (this.used[i]) {
                out.add(Long.valueOf(this.values[i]));
            }
        }
        return out;
    }

    /**
     * 返回宿主表的<b>活视图</b>（不复制、不预分配）。
     * ⚠ 这里曾经写成"每次 new 一个 LinkedHashSet 把条目抄一遍"：fastutil 的
     * {@code object2LongEntrySet()} 是视图，而 {@code CraftingVM}/{@code AE2VMCrafting}
     * 的热循环每次都调 {@code entrySet()}，复制版让 6 条 PerformanceBenchmark 温热用例
     * 稳定超阈值。视图的每个条目仍是新建对象（与 fastutil 的标准迭代器一致），
     * 所以可以安全地 {@code add(e)} 进集合去重 —— 要复用语义请用 {@link #fastIterator()}。
     */
    @Override
    public Set<Object2LongMap.Entry<K>> entrySet() {
        return new EntrySetView();
    }

    /**
     * 快迭代器：<b>复用一个</b> entry 对象，语义与 fastutil 的 {@code fastIterator()} 一致，
     * {@link Object2LongOpenHashMap#entrySet()} 那条一次性快照集合不要拿它去做 {@code add(e)}
     * 去重（那样所有元素会是同一个对象）。
     */
    public Iterator<Object2LongMap.Entry<K>> fastIterator() {
        return new Cursor(true);
    }

    @SuppressWarnings("unchecked")
    private Object2LongMap.Entry<K> snapshotEntry(int slot) {
        return new EntryView((K) this.keys[slot], this.values[slot]);
    }

    /** 迭代期间的槽游标；表若在迭代中被改写，行为与 fastutil 一样不做并发检测。 */
    private final class Cursor implements Iterator<Object2LongMap.Entry<K>> {
        private int next = -1;
        private final EntryView reuse;

        Cursor(boolean reuseEntry) {
            this.reuse = reuseEntry ? new EntryView(null, 0L) : null;
            advance();
        }

        private void advance() {
            for (int i = this.next + 1; i < Object2LongOpenHashMap.this.used.length; i++) {
                if (Object2LongOpenHashMap.this.used[i]) {
                    this.next = i;
                    return;
                }
            }
            this.next = -2;
        }

        @Override
        public boolean hasNext() {
            return this.next >= 0;
        }

        @Override
        @SuppressWarnings("unchecked")
        public Object2LongMap.Entry<K> next() {
            if (this.next < 0) {
                throw new NoSuchElementException();
            }
            int slot = this.next;
            advance();
            if (this.reuse != null) {
                this.reuse.bind((K) Object2LongOpenHashMap.this.keys[slot],
                        Object2LongOpenHashMap.this.values[slot]);
                return this.reuse;
            }
            return snapshotEntry(slot);
        }
    }

    /** entrySet() 的活视图：只读（本表没有按键删除的路径，热路径也不允许复制整表）。 */
    private final class EntrySetView extends java.util.AbstractSet<Object2LongMap.Entry<K>> {

        @Override
        public Iterator<Object2LongMap.Entry<K>> iterator() {
            return new Cursor(false);
        }

        @Override
        public int size() {
            return Object2LongOpenHashMap.this.filler;
        }

        @Override
        public boolean contains(Object o) {
            if (!(o instanceof Object2LongMap.Entry)) {
                return false;
            }
            Object2LongMap.Entry<?> e = (Object2LongMap.Entry<?>) o;
            int i = findSlot(e.getKey());
            return Object2LongOpenHashMap.this.used[i]
                    && Object2LongOpenHashMap.this.values[i] == e.getLongValue();
        }

        @Override
        public void clear() {
            Object2LongOpenHashMap.this.clear();
        }
    }

    /** 条目：{@code setValue} 回写宿主表，所以它既是快照也能当视图用。 */
    private final class EntryView implements Object2LongMap.Entry<K> {

        private K key;
        private long value;

        EntryView(K key, long value) {
            this.key = key;
            this.value = value;
        }

        void bind(K key, long value) {
            this.key = key;
            this.value = value;
        }

        @Override
        public K getKey() {
            return this.key;
        }

        @Override
        public long getLongValue() {
            return this.value;
        }

        @Override
        public Long getValue() {
            return Long.valueOf(this.value);
        }

        @Override
        public Long setValue(long v) {
            long prev = this.value;
            putSlot(this.key, v);
            this.value = v;
            return Long.valueOf(prev);
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Object2LongMap.Entry)) {
                return false;
            }
            Object2LongMap.Entry<?> other = (Object2LongMap.Entry<?>) o;
            return this.key.equals(other.getKey()) && this.value == other.getLongValue();
        }

        @Override
        public int hashCode() {
            return this.key.hashCode() ^ (int) (this.value ^ (this.value >>> 32));
        }
    }
}
