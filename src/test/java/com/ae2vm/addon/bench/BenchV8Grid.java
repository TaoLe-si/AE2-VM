package com.ae2vm.addon.bench;

import java.util.Collections;
import java.util.Iterator;

/**
 * AE2 v8 (1.16.5) 版 {@code IGrid} 桩。
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
