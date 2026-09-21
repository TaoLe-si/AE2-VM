package com.ae2vm.addon.vm;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 跨 mixin 共享的可变状态（VM 原生回退标志 + 请求计数）。
 * <p>
 * <b>⚠️ 这些字段必须放在一个「非 mixin」类里</b>，不能声明在 mixin 类内部。
 * 原因：Mixin 会把 mixin 类里的成员（包括匿名内部类）合并进目标类，匿名内部类会被
 * 重命名成 {@code <Target>$Anonymous$<hash>}。AE2 的发布 jar 是<b>签名过</b>的，
 * 往 {@code appeng.*} 这种已签名包注入一个"新类"会触发
 * <pre>
 * SecurityException: class "appeng.me.cache.CraftingGridCache$Anonymous$..."'s signer
 *                    information does not match signer information of other classes
 *                    in the same package
 * </pre>
 * 进而表现为 {@code ClassNotFoundException: appeng.me.cache.CraftingGridCache$Anonymous$...}
 * —— 而且崩的是 **AE2 自己的 FMLCommonSetupEvent 派发**，堆栈完全看不出是我们的锅。
 * <p>
 * 放在普通类里，匿名内部类就留在 {@code com.ae2vm.addon.vm} 包下，不会被合并，也就没有签名冲突。
 */
public final class VmMixinState {

    private static final ThreadLocal<Boolean> VM_FALLBACK = new ThreadLocal<Boolean>() {
        @Override
        protected Boolean initialValue() {
            return Boolean.FALSE;
        }
    };

    private static final AtomicLong REQUEST_COUNTER = new AtomicLong();

    private VmMixinState() {
    }

    /** True while a failed VM request is being retried through AE2's native crafting path. */
    public static boolean isVmFallback() {
        return VM_FALLBACK.get();
    }

    public static void setVmFallback(boolean value) {
        VM_FALLBACK.set(value);
    }

    public static void clearVmFallback() {
        VM_FALLBACK.remove();
    }

    /** Monotonic id for log correlation. */
    public static long nextRequestId() {
        return REQUEST_COUNTER.incrementAndGet();
    }
}
