package com.ae2vm.addon;

import appeng.api.config.FuzzyMode;
import appeng.api.storage.IStorageChannel;
import appeng.api.storage.data.IAEItemStack;
import appeng.api.storage.data.IAEStack;
import appeng.api.storage.data.IAEStackList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 1.17.1 单测替身工厂：在没有 MC/AE2 引导的 JVM 里，给**生产 VM** 提供它能直接吃的
 * v9 {@link IAEItemStack}（见 {@link BenchItemStack}）与配套的
 * {@link IStorageChannel}/{@link IAEStackList}，于是 AE2 v9 的真
 * {@code MixedStackList}/{@code CraftingSimulationState}/{@code CraftingPlan}
 * 都能在离线 bench 里跑通 —— bench 与游戏走的是同一条生产代码路径。
 *
 * <p>物品身份沿用 AE2 的语义：{@code 物品名 + damage}（不含数量），
 * {@code FuzzyMode.IGNORE_ALL} = 同物品任意 damage。
 */
public final class TestAeStacks {

    static {
        // ItemStack/Item 的构造要读注册表与 DFU 版本号；纯 JUnit JVM 里没有 FML 做这件事，
        // 必须先引导 Minecraft（与 1.18.1 fork 的 BenchKeyType 同一道墙，实测可过）。
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    private TestAeStacks() {
    }

    /** bench 物品通道（{@link BenchItemStack#getChannel()} 返回它，真 MixedStackList 据此建子表）。 */
    public static IStorageChannel<IAEItemStack> channel() {
        return BenchChannel.INSTANCE;
    }

    /** {@code id} 是物品名（同时决定 {@code getItem()} 返回哪个 Item），damage 是 meta。 */
    public static IAEItemStack stack(String id, int damage, long size) {
        return new BenchItemStack(id + ':' + damage, item(id), damage, size);
    }

    /** 该栈的 identity（{@code 物品:damage}）；不是物品栈返回 null。 */
    public static String identity(Object stackOrNull) {
        if (stackOrNull instanceof BenchItemStack b) {
            return b.identity();
        }
        if (stackOrNull instanceof IAEItemStack s) {
            String id = idOf(s.getItem());
            return id == null ? null : id + ':' + s.getItemDamage();
        }
        return null;
    }

    /** identity 的物品名部分。 */
    public static String idOf(IAEStack s) {
        String k = identity(s);
        return k == null ? "?" : k.substring(0, k.lastIndexOf(':'));
    }

    /** identity 的 damage 部分（无变体 = 0）。 */
    public static int damageOf(IAEStack s) {
        String k = identity(s);
        return k == null ? -1 : Integer.parseInt(k.substring(k.lastIndexOf(':') + 1));
    }

    /** {@code 物品:damage} → 假栈（与 {@link #identity} 互逆）。 */
    public static IAEItemStack fromIdentity(String identity, long size) {
        int i = identity.lastIndexOf(':');
        return stack(identity.substring(0, i), Integer.parseInt(identity.substring(i + 1)), size);
    }

    // ------------------------------------------------------------------
    // Item / ItemStack
    // ------------------------------------------------------------------

    private static final Map<String, Item> ITEMS = new LinkedHashMap<>();

    /** Item → bench 原本的名字。注册名只能小写（{@code ResourceLocation} 的 path 字符集是
     *  {@code [a-z0-9/._-]}），而 bench 的 id 带大写（{@code A}/{@code compA}），
     *  identity 又要和测试里的原文比，所以另存一张侧表。 */
    private static final Map<Item, String> IDS = new IdentityHashMap<>();

    static synchronized Item item(String id) {
        Item it = ITEMS.get(id);
        if (it == null) {
            it = new Item(new Item.Properties());
            it.setRegistryName("ae2vm:" + id.toLowerCase(Locale.ROOT));
            ITEMS.put(id, it);
            IDS.put(it, id);
        }
        return it;
    }

    static synchronized String idOf(Item item) {
        return item == null ? null : IDS.get(item);
    }

    /** 真 ItemStack：Bootstrap 之后可正常构造。数量夹到 1..64（栈上限），damage 走 tag。 */
    static ItemStack mcStack(String id, int damage, long size) {
        ItemStack out = new ItemStack(item(id), (int) Math.min(Math.max(size, 1L), 64L));
        if (damage != 0) {
            out.setDamageValue(damage);
        }
        return out;
    }

    // ------------------------------------------------------------------
    // IStorageChannel / IAEStackList
    // ------------------------------------------------------------------

    /** bench 物品通道：只被 {@code MixedStackList.getList(stack)} 用来建子表与拷贝栈。 */
    private enum BenchChannel implements IStorageChannel<IAEItemStack> {
        INSTANCE;

        @Override
        public ResourceLocation getId() {
            return new ResourceLocation("ae2vm", "bench_items");
        }

        @Override
        public IAEStackList<IAEItemStack> createList() {
            return new BenchStackList();
        }

        @Override
        public IAEItemStack createStack(ItemStack itemStack) {
            return itemStack == null ? null
                    : stack(idOf(itemStack.getItem()), itemStack.getDamageValue(), itemStack.getCount());
        }

        @Override
        public IAEItemStack readFromPacket(FriendlyByteBuf buf) {
            throw new UnsupportedOperationException("bench channel is not serializable");
        }

        @Override
        public IAEItemStack createFromNBT(CompoundTag tag) {
            throw new UnsupportedOperationException("bench channel is not serializable");
        }

        @Override
        public IAEItemStack copy(IAEItemStack stack) {
            return stack == null ? null : stack.copy();
        }
    }

    /**
     * 与 {@code appeng.util.item.ItemList} 同语义的最小实现（只支持 bench 物品通道）。
     *
     * <p>两条语义必须与 AE2 一致，否则 {@code CraftingSimulationState} 的账会错：
     * <ul>
     *   <li>{@code findPrecise} 返回<b>表内活对象</b>—— {@code extractItems} 会对它
     *       {@code decStackSize} 来扣减模拟库存；</li>
     *   <li>{@code add/addStorage} 首次插入时<b>存副本</b>——调用方随后还会改自己那份
     *       （{@code cacheFuzzy} 把同一个对象分别塞进 modifiable/unmodified 两个缓存）。</li>
     * </ul>
     */
    static final class BenchStackList implements IAEStackList<IAEItemStack> {

        private final Map<String, IAEItemStack> byIdentity = new LinkedHashMap<>();

        private static String key(IAEItemStack in) {
            String k = TestAeStacks.identity(in);
            if (k == null) {
                throw new IllegalArgumentException("栈不是 TestAeStacks 造的: " + in);
            }
            return k;
        }

        /**
         * 取表内活对象；不存在就先存一份副本。
         *
         * <p>返回值<b>总是</b>表内那份，所以调用方必须自己用 {@link #contains} 区分
         * "刚插入"与"已有"：刚插入的副本已经带着入参的数量/状态，再累加一次就翻倍
         * （实测：{@code addStorage(64)} 记成 128，模拟库存 100 记成 200）。
         */
        private IAEItemStack slot(IAEItemStack in) {
            String k = key(in);
            IAEItemStack cur = byIdentity.get(k);
            if (cur == null) {
                cur = in.copy();
                byIdentity.put(k, cur);
            }
            return cur;
        }

        private boolean contains(IAEItemStack in) {
            return byIdentity.containsKey(key(in));
        }

        @Override
        public void add(IAEItemStack stack) {
            if (stack == null) {
                return;
            }
            boolean existed = contains(stack);
            IAEItemStack cur = slot(stack);
            if (existed) {
                IAEStack.add(cur, stack);
            }
        }

        @Override
        public void addStorage(IAEItemStack stack) {
            if (stack == null) {
                return;
            }
            boolean existed = contains(stack);
            IAEItemStack cur = slot(stack);
            if (existed) {
                cur.setStackSize(cur.getStackSize() + stack.getStackSize());
            }
        }

        @Override
        public void addCrafting(IAEItemStack stack) {
            if (stack == null) {
                return;
            }
            slot(stack).setCraftable(true);
        }

        @Override
        public void addRequestable(IAEItemStack stack) {
            if (stack == null) {
                return;
            }
            boolean existed = contains(stack);
            IAEItemStack cur = slot(stack);
            if (existed) {
                cur.incCountRequestable(stack.getCountRequestable());
            }
        }

        @Override
        public IAEItemStack findPrecise(IAEItemStack stack) {
            String k = TestAeStacks.identity(stack);
            return k == null ? null : byIdentity.get(k);
        }

        @Override
        public Collection<IAEItemStack> findFuzzy(IAEItemStack input, FuzzyMode fuzzy) {
            String k = TestAeStacks.identity(input);
            List<IAEItemStack> out = new ArrayList<>();
            if (k == null) {
                return out;
            }
            String item = k.substring(0, k.lastIndexOf(':'));
            for (Map.Entry<String, IAEItemStack> e : byIdentity.entrySet()) {
                if (e.getKey().substring(0, e.getKey().lastIndexOf(':')).equals(item)) {
                    out.add(e.getValue());
                }
            }
            return out;
        }

        @Override
        public boolean isEmpty() {
            return byIdentity.isEmpty();
        }

        @Override
        public int size() {
            return byIdentity.size();
        }

        @Override
        public void resetStatus() {
            // 本替身不记 status
        }

        @Override
        public Iterator<IAEItemStack> iterator() {
            return new ArrayList<>(byIdentity.values()).iterator();
        }

        @Override
        public IAEItemStack getFirstItem() {
            Iterator<IAEItemStack> it = byIdentity.values().iterator();
            return it.hasNext() ? it.next() : null;
        }
    }
}
