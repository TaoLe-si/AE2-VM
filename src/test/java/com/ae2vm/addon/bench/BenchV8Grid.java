package com.ae2vm.addon.bench;

import com.ae2vm.addon.TestAeStacks;
import com.ae2vm.shim.api.stacks.AEKey;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.Iterator;
import java.util.Map;

/**
 * AE2 v8 (1.16.1) 版 {@code IGrid} 桩。
 * <p>
 * 1.17.1（v9）的 bench 网格实现的是 v9 的 {@code IGrid}：{@code getService(Class<? extends IGridService>)}
 * 与 {@code GridEvent}。AE2 v8 还没有这两组抽象 —— 网格能力是 {@code IGridCache}
 * （{@code IGrid#getCache}），事件是 {@code MENetworkEvent}。bench 只用到网格的
 * "形状"（MIGRATION-PATTERNS §5，编译保留、运行 exclude），所以这里给出一个
 * 全空实现供 {@link FakeBenchGrid} / {@code ProcessingDefaultFuzzyTest.FakeGrid} 复用。
 */
public abstract class BenchV8Grid implements appeng.api.networking.IGrid {

    @Override
    public appeng.api.networking.events.MENetworkEvent postEvent(
            appeng.api.networking.events.MENetworkEvent ev) {
        return ev;
    }

    @Override
    public appeng.api.networking.events.MENetworkEvent postEventTo(
            appeng.api.networking.IGridNode node, appeng.api.networking.events.MENetworkEvent ev) {
        return ev;
    }

    @Override
    public appeng.api.util.IReadOnlyCollection<Class<? extends appeng.api.networking.IGridHost>> getMachinesClasses() {
        return new EmptyROCollection<Class<? extends appeng.api.networking.IGridHost>>();
    }

    @Override
    public appeng.api.networking.IMachineSet getMachines(Class<? extends appeng.api.networking.IGridHost> c) {
        return null;
    }

    @Override
    public appeng.api.util.IReadOnlyCollection<appeng.api.networking.IGridNode> getNodes() {
        return new EmptyROCollection<appeng.api.networking.IGridNode>();
    }

    @Override
    public boolean isEmpty() {
        return true;
    }

    @Override
    public appeng.api.networking.IGridNode getPivot() {
        return null;
    }

    /**
     * v8 的网格能力是 {@code IGridCache}（{@code IGrid#getCache}），而
     * {@code CraftingVM.ensureRealStockSnapshot()} 走的正是
     * {@code getCache(IStorageGrid) → getInventory(channel).getStorageList()}。
     * 不接上这条，{@code realStockOf()} 恒为 0、{@code fuzzyFamilyOf()} 恒为编译期组，
     * 库存感知的那几个分支（primaryStock/substituteStock/模糊族）就与生产行为不同。
     */
    @SuppressWarnings("unchecked")
    @Override
    public <C extends appeng.api.networking.IGridCache> C getCache(
            Class<? extends appeng.api.networking.IGridCache> iface) {
        if (iface == appeng.api.networking.storage.IStorageGrid.class) {
            return (C) benchStorageGrid(benchStock());
        }
        return null;
    }

    /** 本桩对外呈现的网络库存（bench 用例的种子库存），每次 {@code getCache} 现取。 */
    protected abstract Map<AEKey, Long> benchStock();

    /**
     * {@code IStorageGrid} 桩：只有 {@code getInventory(channel).getStorageList()} 被 VM
     * 用到（每次 execute 取一次快照），其余成员用默认值应答。
     */
    public static appeng.api.networking.storage.IStorageGrid benchStorageGrid(final Map<AEKey, Long> stock) {
        final Object monitor = Proxy.newProxyInstance(
                appeng.api.storage.IMEMonitor.class.getClassLoader(),
                new Class<?>[]{appeng.api.storage.IMEMonitor.class},
                new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method m, Object[] args) {
                        if ("getStorageList".equals(m.getName())) {
                            appeng.api.storage.data.IItemList<appeng.api.storage.data.IAEItemStack> snap =
                                    TestAeStacks.newItemList();
                            for (Map.Entry<AEKey, Long> e : stock.entrySet()) {
                                Long amount = e.getValue();
                                if (e.getKey() != null && amount != null && amount.longValue() > 0L) {
                                    snap.addStorage(e.getKey().toStack(amount.longValue()));
                                }
                            }
                            return snap;
                        }
                        return fallback(proxy, m, args);
                    }
                });
        return (appeng.api.networking.storage.IStorageGrid) Proxy.newProxyInstance(
                appeng.api.networking.storage.IStorageGrid.class.getClassLoader(),
                new Class<?>[]{appeng.api.networking.storage.IStorageGrid.class},
                new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method m, Object[] args) {
                        if ("getInventory".equals(m.getName())) {
                            return monitor;
                        }
                        return fallback(proxy, m, args);
                    }
                });
    }

    /** {@code Object} 三件套按身份语义应答，其余按返回类型给零值。 */
    private static Object fallback(Object proxy, Method m, Object[] args) {
        String name = m.getName();
        if ("equals".equals(name)) {
            return Boolean.valueOf(proxy == (args == null ? null : args[0]));
        }
        if ("hashCode".equals(name)) {
            return Integer.valueOf(System.identityHashCode(proxy));
        }
        if ("toString".equals(name)) {
            return "BenchStorageGrid";
        }
        Class<?> rt = m.getReturnType();
        if (rt == boolean.class) {
            return Boolean.FALSE;
        }
        if (rt == int.class) {
            return Integer.valueOf(0);
        }
        if (rt == long.class) {
            return Long.valueOf(0L);
        }
        return null;
    }

    /** v8 {@code IReadOnlyCollection} 不是函数式接口（有 size/isEmpty/contains），必须显式实现。 */
    public static final class EmptyROCollection<T> implements appeng.api.util.IReadOnlyCollection<T> {
        @Override
        public Iterator<T> iterator() {
            return Collections.<T>emptyList().iterator();
        }

        @Override
        public int size() {
            return 0;
        }

        @Override
        public boolean isEmpty() {
            return true;
        }

        @Override
        public boolean contains(Object o) {
            return false;
        }
    }
}
