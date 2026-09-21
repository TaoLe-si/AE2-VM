package appeng.core;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

import appeng.api.IAppEngApi;
import appeng.api.storage.IStorageHelper;

/**
 * ⚠ **只存在于 test 源码集的替身**（不是产品代码，也不进产物 jar）。
 *
 * <p>{@code appeng.api.AEApi} 的静态初始化会 {@code Class.forName("appeng.core.Api")}
 * 再取它的 {@code INSTANCE} 字段；真身 {@code Api.INSTANCE = MainAppEng.getInstance()}
 * 需要完整 mod 引导（AEConfig/Loader/Registry），在没有 MC 引导的单测 JVM 里必炸
 * （实测 {@code ExceptionInInitializerError}）。
 *
 * <p>而本 fork 的 {@code CraftingSimulationState}/{@code MixedStackList} 在**构造期**就要
 * {@code AEApi.instance().storage().createItemList()} 拿一个真实 {@code IItemList}，所以任何
 * "驱动生产 VM 的单元测试"都必须先让这一步可用。这个替身只做一件事：把 {@code createItemList()}
 * 接到我们自己的列表实现上（纯数据结构）。
 * ⚠ 不能用 AE2 自家的 {@code appeng.util.item.ItemList}：它字节码里调 MC 的 SRG 名方法，
 *   而测试类路径上是 stable_29 的 MCP 名 → {@code NoSuchMethodError}。
 *   故交给 {@code TestAeStacks.newItemList()}。测试类路径里 output 目录排在依赖 jar 之前，本替身生效。
 * ⚠ rv4 的 IStorageHelper 上<b>没有</b> v8 的 getStorageChannel(Class)，所以这里也不再代理
 *   那个方法（原先挂在它上面的 transferFactor/getUnitsPerByte 分支一并删掉：
 *   rv4 的字节数算式已改成常量 8，见 shim 的 CraftingSimulationState.addStackBytes）。
 */
public final class Api {

    public static final IAppEngApi INSTANCE = (IAppEngApi) Proxy.newProxyInstance(
            IAppEngApi.class.getClassLoader(), new Class<?>[]{IAppEngApi.class},
            new Handler());

    private Api() {
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
                    if ("createItemList".equals(m.getName())) {
                        return com.ae2vm.addon.TestAeStacks.newItemList();
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
