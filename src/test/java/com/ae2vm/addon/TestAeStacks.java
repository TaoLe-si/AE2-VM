package com.ae2vm.addon;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import appeng.api.config.FuzzyMode;
import appeng.api.networking.IGrid;
import appeng.api.storage.IMEMonitor;
import appeng.api.storage.data.IAEItemStack;
import appeng.api.storage.data.IAEStack;
import appeng.api.storage.data.IItemList;

/**
 * 1.16.3 单测替身：在没有 MC/AE2 引导的 JVM 里驱动**生产 VM**所需的两样东西。
 *
 * <p>为什么必须是替身（都实测过）：
 * <ul>
 *   <li>{@code new ItemStack(item)} 需要先 {@code net.minecraft.init.Bootstrap.register()}
 *       （否则 {@code Accessed Items before Bootstrap!}）；而 uel 的
 *       {@code AEItemStack.fromItemStack} 在测试类路径上会
 *       {@code NoSuchMethodError: ItemStack.func_190926_b()} —— 它字节码里是 SRG 名，
 *       只有游戏运行期由 FML 改名后才存在。所以真栈用不了。</li>
 *   <li>{@code appeng.util.item.ItemList.addStorage} 里
 *       {@code NoSuchMethodError: Item.func_77645_m()} 同理 —— AE2 自己的列表类也用不了，
 *       故 {@link #newItemList()} 是本类自带的 {@code IItemList} 实现。</li>
 * </ul>
 *
 * <p>语义对齐 AE2 v8：栈的 identity = <b>物品 + damage</b>（{@code AEItemStack.isSameType →
 * AESharedItemStack.equals} 的字节码只比 item/itemDamage/NBT，不含数量）；
 * {@code FuzzyMode.IGNORE_ALL} = 同物品任意 damage。
 */
public final class TestAeStacks {

    /** 假栈的 identity 记账（Proxy 带不了字段）。 */
    private static final Map<Object, String> IDS =
            Collections.synchronizedMap(new java.util.IdentityHashMap<Object, String>());

    static {
        // {@code ItemStack.isEmpty()} 会读 {@code Items.AIR}，1.16.3 要求注册表先引导
        // （否则 {@code Accessed Items before Bootstrap!}）。{@code Bootstrap.bootStrap()}
        // 在无 Forge 客户端/服务端的 JVM 里跑得通（实测）。
        try {
            net.minecraft.util.registry.Bootstrap.bootStrap();
        } catch (Throwable ignored) {
        }
    }

    private TestAeStacks() {
    }

    /** {@code id} 是物品名（同时决定 {@code getItem()} 返回哪个 Item），damage 是 meta。 */
    public static IAEItemStack stack(String id, int damage, long size) {
        return new BenchItemStack(id + ':' + damage, item(id), damage, size);
    }

    /** 该栈的 identity（{@code 物品:damage}），非本类造的栈返回 null。 */
    public static String identity(Object stackOrNull) {
        if (stackOrNull instanceof BenchItemStack) {
            return ((BenchItemStack) stackOrNull).identity();
        }
        return stackOrNull == null ? null : IDS.get(stackOrNull);
    }

    public static String idOf(IAEStack s) {
        String k = identity(s);
        return k == null ? "?" : k.substring(0, k.indexOf(':'));
    }

    public static int damageOf(IAEStack s) {
        String k = identity(s);
        return k == null ? -1 : Integer.parseInt(k.substring(k.indexOf(':') + 1));
    }

    /** 与 {@code appeng.util.item.ItemList} 同语义的最小实现（只支持 item 通道）。 */
    public static IItemList<IAEItemStack> newItemList() {
        return new BenchItemList();
    }

    private static final class BenchItemList implements IItemList<IAEItemStack> {
        private final Map<String, IAEItemStack> byIdentity = new LinkedHashMap<String, IAEItemStack>();

        private void addAmount(IAEItemStack in) {
            String k = identity(in);
            if (k == null) {
                throw new IllegalArgumentException("栈不是 TestAeStacks 造的: " + in);
            }
            IAEItemStack cur = byIdentity.get(k);
            if (cur == null) {
                byIdentity.put(k, in.copy().setStackSize(in.getStackSize()));
            } else {
                cur.setStackSize(Long.valueOf(cur.getStackSize() + in.getStackSize()));
            }
        }

        @Override
        public void add(IAEItemStack stack) {
            addStorage(stack);
        }

        @Override
        public void addStorage(IAEItemStack stack) {
            if (stack != null) {
                addAmount(stack);
            }
        }

        @Override
        public void addCrafting(IAEItemStack stack) {
            if (stack != null) {
                addAmount(stack);
            }
        }

        @Override
        public void addRequestable(IAEItemStack stack) {
            if (stack != null) {
                addAmount(stack);
            }
        }

        @Override
        public IAEItemStack findPrecise(IAEItemStack stack) {
            String k = identity(stack);
            return k == null ? null : byIdentity.get(k);
        }

        @Override
        public Collection<IAEItemStack> findFuzzy(IAEItemStack input, FuzzyMode fuzzy) {
            String k = identity(input);
            List<IAEItemStack> out = new ArrayList<IAEItemStack>();
            if (k == null) {
                return out;
            }
            String item = k.substring(0, k.indexOf(':'));
            for (Map.Entry<String, IAEItemStack> e : byIdentity.entrySet()) {
                if (e.getKey().substring(0, e.getKey().indexOf(':')).equals(item)) {
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
            return new ArrayList<IAEItemStack>(byIdentity.values()).iterator();
        }

        @Override
        public IAEItemStack getFirstItem() {
            Iterator<IAEItemStack> it = byIdentity.values().iterator();
            return it.hasNext() ? it.next() : null;
        }
    }

    // ------------------------------------------------------------------
    // Item / ItemStack
    // ------------------------------------------------------------------

    /**
     * 网络键替身：{@code CraftingVM.ensureRealStockSnapshot()} 走的是
     * {@code IGrid.getCache(IStorageGrid) → getInventory(channel).getStorageList()}。
     * 不接上这条，{@code realStockOf()} 恒为 0，"库存感知的子合成"那段
     * （{@code CraftingVM} 的 primaryStock/substituteStock 分支）就与生产行为不同 ——
     * 那等于测的是另一个程序。
     */
    public static Object benchGrid(final Map<String, Long> amounts) {
        Object monitor = Proxy.newProxyInstance(IMEMonitor.class.getClassLoader(),
                new Class<?>[]{IMEMonitor.class}, new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method m, Object[] args) {
                        if ("getStorageList".equals(m.getName())) {
                            // 每次现取：第 2 轮补进原料后，realStockOf 必须看得到新库存
                            IItemList<IAEItemStack> snap = newItemList();
                            for (Map.Entry<String, Long> e : amounts.entrySet()) {
                                if (e.getValue().longValue() > 0L) {
                                    snap.addStorage(fromIdentity(e.getKey(), e.getValue().longValue()));
                                }
                            }
                            return snap;
                        }
                        return defaultValue(m.getReturnType());
                    }
                });
        Object storageGrid = Proxy.newProxyInstance(
                appeng.api.networking.storage.IStorageGrid.class.getClassLoader(),
                new Class<?>[]{appeng.api.networking.storage.IStorageGrid.class},
                new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method m, Object[] args) {
                        if ("getInventory".equals(m.getName())) {
                            return monitor;
                        }
                        return defaultValue(m.getReturnType());
                    }
                });
        return Proxy.newProxyInstance(IGrid.class.getClassLoader(), new Class<?>[]{IGrid.class},
                new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method m, Object[] args) {
                        if ("getCache".equals(m.getName())) {
                            Class<?> c = (Class<?>) args[0];
                            return c != null && c.isInstance(storageGrid) ? storageGrid : null;
                        }
                        return defaultValue(m.getReturnType());
                    }
                });
    }

    /** {@code 物品:damage} → 假栈（与 {@link #identity} 互逆）。 */
    public static IAEItemStack fromIdentity(String identity, long size) {
        int i = identity.lastIndexOf(':');
        return stack(identity.substring(0, i), Integer.parseInt(identity.substring(i + 1)), size);
    }

    private static final Map<String, net.minecraft.item.Item> ITEMS =
            new LinkedHashMap<String, net.minecraft.item.Item>();

    static synchronized net.minecraft.item.Item item(String id) {
        net.minecraft.item.Item it = ITEMS.get(id);
        if (it == null) {
            // 1.16 的 ResourceLocation 只收 [a-z0-9/._-]，而 bench 的物品名是大写单字母
            // （"A"/"N9"）。注册名只用于 AEKey.getModId()/toString()，身份判定走 identity 串
            // （BenchAEKey.id → TestAeStacks.identity），所以这里降级成合法名不影响任何断言。
            it = new net.minecraft.item.Item(new net.minecraft.item.Item.Properties())
                    .setRegistryName("ae2vm:" + registrySafe(id));
            ITEMS.put(id, it);
        }
        return it;
    }

    /** 把任意 bench 物品名压成 1.16 合法的 ResourceLocation path。 */
    private static String registrySafe(String id) {
        StringBuilder out = new StringBuilder(id.length());
        for (int i = 0; i < id.length(); i++) {
            char c = Character.toLowerCase(id.charAt(i));
            boolean ok = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '/' || c == '.' || c == '_' || c == '-';
            out.append(ok ? c : '_');
        }
        return out.toString();
    }

    /** 真 ItemStack：{@code Bootstrap.register()} 之后可正常构造（实测）。 */
    static net.minecraft.item.ItemStack mcStack(String id, int damage, long size) {
        try {
            net.minecraft.item.ItemStack s =
                    new net.minecraft.item.ItemStack(item(id), (int) Math.min(size, 64L));
            s.setDamageValue(damage);
            return s;
        } catch (Throwable t) {
            return null;
        }
    }

    static Object defaultValue(Class<?> t) {
        if (!t.isPrimitive()) {
            return null;
        }
        if (t == boolean.class) {
            return Boolean.FALSE;
        }
        if (t == long.class) {
            return Long.valueOf(0L);
        }
        if (t == int.class) {
            return Integer.valueOf(0);
        }
        return null;
    }
}
