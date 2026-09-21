package com.ae2vm.addon.bench;

import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.IGridService;
import appeng.api.networking.events.GridEvent;
import appeng.api.networking.storage.IStorageService;
import appeng.api.stacks.AEKey;
import appeng.api.storage.IMEMonitor;
import appeng.api.storage.data.IAEItemStack;
import com.ae2vm.addon.TestAeStacks;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.Set;

/**
 * Minimal {@link IGrid} whose storage snapshot comes from a fixed
 * {@code Map<AEKey, Long>}. Lets the VM's {@code realStockOf} (used by the
 * v1.8.22 stock-aware sub-craft aggregation) observe real network stock exactly
 * like in-game, so the "last craft with a fluid + partial stock" boundary can be
 * reproduced offline.
 *
 * <p>NOTE: {@code realStockOf} snapshots the inventory once per {@code execute()}, so
 * the stock map is read-only from the VM's {@code used} accounting (the sandbox sim
 * tracks its own consumption). To mirror the game — where both read the same live
 * inventory — tests pass the SAME map to the grid and the simulation.
 */
public final class FakeBenchGrid implements IGrid {

    private final Map<AEKey, Long> stock;

    public FakeBenchGrid(Map<AEKey, Long> stock) {
        this.stock = stock;
    }

    @Override
    public <C extends IGridService> C getService(Class<C> iface) {
        if (iface == IStorageService.class) {
            return iface.cast(benchStorageService(stock));
        }
        return null;
    }

    @Override
    public <T extends GridEvent> T postEvent(T ev) {
        return ev;
    }

    @Override
    public Iterable<Class<?>> getMachineClasses() {
        return Set.of();
    }

    @Override
    public Iterable<IGridNode> getMachineNodes(Class<?> machineClass) {
        return Set.of();
    }

    @Override
    public <T> Set<T> getMachines(Class<T> machineClass) {
        return Set.of();
    }

    @Override
    public <T> Set<T> getActiveMachines(Class<T> machineClass) {
        return Set.of();
    }

    @Override
    public Iterable<IGridNode> getNodes() {
        return Set.of();
    }

    @Override
    public boolean isEmpty() {
        return true;
    }

    @Override
    public IGridNode getPivot() {
        return null;
    }

    @Override
    public int size() {
        return 0;
    }

    @Override
    public appeng.api.networking.ticking.ITickManager getTickManager() {
        return null;
    }

    @Override
    public appeng.api.networking.energy.IEnergyService getEnergyService() {
        return null;
    }

    @Override
    public appeng.api.networking.crafting.ICraftingService getCraftingService() {
        return null;
    }

    @Override
    public appeng.api.networking.pathing.IPathingService getPathingService() {
        return null;
    }

    /**
     * v9 的库存快照走 {@code IGrid.getStorageService().getInventory(channel).getStorageList()}
     * （{@code CraftingVM.ensureRealStockSnapshot} 每次 execute 取一次）。不接上这条，
     * {@code realStockOf()} 恒为 0、{@code fuzzyFamilyOf()} 恒为编译期组，库存感知的那几个
     * 分支（primaryStock/substituteStock/模糊族）就与生产行为不同。
     *
     * <p>每次现取：第 2 轮补进原料后 {@code realStockOf} 必须看得到新库存。
     */
    public static IMEMonitor<IAEItemStack> benchMonitor(Map<AEKey, Long> stock) {
        InvocationHandler h = (proxy, m, args) -> {
            if ("getStorageList".equals(m.getName())) {
                var snap = TestAeStacks.channel().createList();
                for (Map.Entry<AEKey, Long> e : stock.entrySet()) {
                    Long amount = e.getValue();
                    if (e.getKey() != null && amount != null && amount.longValue() > 0L) {
                        snap.addStorage(e.getKey().toStack(amount.longValue()));
                    }
                }
                return snap;
            }
            return fallback(proxy, m, args);
        };
        @SuppressWarnings("unchecked")
        IMEMonitor<IAEItemStack> mon = (IMEMonitor<IAEItemStack>) Proxy.newProxyInstance(
                IMEMonitor.class.getClassLoader(), new Class<?>[] {IMEMonitor.class}, h);
        return mon;
    }

    /** {@code IStorageService} 桩：只有 {@code getInventory} 被 VM 用到，其余成员给零值。 */
    public static IStorageService benchStorageService(Map<AEKey, Long> stock) {
        IMEMonitor<IAEItemStack> monitor = benchMonitor(stock);
        InvocationHandler h = (proxy, m, args) -> {
            if ("getInventory".equals(m.getName())) {
                return monitor;
            }
            return fallback(proxy, m, args);
        };
        return (IStorageService) Proxy.newProxyInstance(
                IStorageService.class.getClassLoader(), new Class<?>[] {IStorageService.class}, h);
    }

    /** {@code Object} 三件套按身份语义应答，其余按返回类型给零值。 */
    private static Object fallback(Object proxy, Method m, Object[] args) {
        String name = m.getName();
        if ("equals".equals(name)) {
            return proxy == (args == null ? null : args[0]);
        }
        if ("hashCode".equals(name)) {
            return System.identityHashCode(proxy);
        }
        if ("toString".equals(name)) {
            return "BenchStorageService";
        }
        Class<?> rt = m.getReturnType();
        if (rt == boolean.class) {
            return Boolean.FALSE;
        }
        if (rt == int.class) {
            return 0;
        }
        if (rt == long.class) {
            return 0L;
        }
        return null;
    }
}
