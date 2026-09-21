package appeng.core;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

import appeng.api.IAppEngApi;
import appeng.api.storage.IStorageHelper;
import appeng.api.storage.channels.IItemStorageChannel;

/**
 * ⚠ **只存在于 test 源码集的替身**（不是产品代码，也不进产物 jar）。
 *
 * <p>{@code appeng.core.Api.INSTANCE = new Api()} 的静态初始化会拉起完整 mod 引导
 * （{@code MainAppEng}/AEConfig/registries），在没有 MC 引导的单测 JVM 里必炸。
 *
 * <p>而本 fork 的 {@code StorageChannels.items()} → {@code MixedStackList} 在**构造期**就要
 * {@code getStorageChannel(IItemStorageChannel).createList()} 拿一个真实 {@code IItemList}，
 * 所以任何"驱动生产 VM 的单元测试"都必须先让这一步可用。这个替身只做一件事：把
 * {@code storage().getStorageChannel(...)} 接到一个 {@code createList()} 上
 * （纯数据结构）。⚠ 不能用 AE2 自家的 {@code appeng.util.item.ItemList}：它字节码里
 *       调 MC 的混淆名方法，测试类路径上是 mapped 名 → {@code NoSuchMethodError}。
 *       故 createList() 交给 {@code TestAeStacks.newItemList()}。
 *       测试类路径里 output 目录排在依赖 jar 之前，故本替身生效。
 */
public final class Api {

    private static final IAppEngApi INSTANCE = (IAppEngApi) Proxy.newProxyInstance(
            IAppEngApi.class.getClassLoader(), new Class<?>[]{IAppEngApi.class},
            new Handler());

    private Api() {
    }

    /** AE2 v7 (1.15.2) 的真实入口是静态 {@code Api.instance()}，本 fork 的 shim 就这么调。 */
    public static IAppEngApi instance() {
        return INSTANCE;
    }

    private static final class Handler implements InvocationHandler {
        @Override
        public Object invoke(Object proxy, Method m, Object[] args) {
            if ("storage".equals(m.getName())) {
                return STORAGE;
            }
            return defaultValue(m.getReturnType());
        }
    }

    private static final IStorageHelper STORAGE = (IStorageHelper) Proxy.newProxyInstance(
            IStorageHelper.class.getClassLoader(), new Class<?>[]{IStorageHelper.class},
            new InvocationHandler() {
                @Override
                public Object invoke(Object proxy, Method m, Object[] args) {
                    if ("getStorageChannel".equals(m.getName())) {
                        return CHANNEL;
                    }
                    return defaultValue(m.getReturnType());
                }
            });

    private static final IItemStorageChannel CHANNEL = (IItemStorageChannel) Proxy.newProxyInstance(
            IItemStorageChannel.class.getClassLoader(), new Class<?>[]{IItemStorageChannel.class},
            new InvocationHandler() {
                @Override
                public Object invoke(Object proxy, Method m, Object[] args) {
                    String n = m.getName();
                    if ("createList".equals(n)) {
                        return com.ae2vm.addon.TestAeStacks.newItemList();
                    }
                    if ("transferFactor".equals(n)) {
                        return Integer.valueOf(8);
                    }
                    if ("getUnitsPerByte".equals(n)) {
                        return Integer.valueOf(8);
                    }
                    if ("equals".equals(n)) {
                        return Boolean.valueOf(proxy == args[0]);
                    }
                    if ("hashCode".equals(n)) {
                        return Integer.valueOf(System.identityHashCode(proxy));
                    }
                    if ("toString".equals(n)) {
                        return "BenchItemStorageChannel";
                    }
                    return defaultValue(m.getReturnType());
                }
            });

    private static Object defaultValue(Class<?> t) {
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
