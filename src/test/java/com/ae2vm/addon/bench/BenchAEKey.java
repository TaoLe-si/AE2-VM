package com.ae2vm.addon.bench;

import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import com.ae2vm.addon.TestAeStacks;

/**
 * bench 用例的键工厂：**返回的就是产品里的真实键类型** {@link AEItemKey}
 * （包住 {@link TestAeStacks} 造的 v9 {@code IAEItemStack} 替身）。
 *
 * <p>为什么不能再"自己继承 AEKey、拿字符串当身份"：AE2 v9 的真接口
 * （{@code IPatternDetails} / {@code CraftingSimulationState} / {@code MixedStackList}）
 * 只认 {@code IAEStack}，而 {@code CraftingVM}/{@code PatternCompiler} 里那 17 处
 * {@code (AEItemKey) key} 强转也只认 {@code AEItemKey}。字符串键在这两处都必然抛
 * {@code UnsupportedOperationException}/{@code ClassCastException}，
 * 于是 {@code PatternCompiler.hasUsableOutput} 把异常吞成 false、
 * {@code compileRequest} 报 "Failed to compile pattern"。改成真键之后 bench 与游戏
 * 走的是同一条生产代码路径。
 *
 * <p>物品身份沿用 AE2 的语义：{@code 物品名 + damage}（见 {@link TestAeStacks}），
 * 所以 {@code of("gray")} 与 {@code of("gray")} 相等、与 {@code VariantKey.of("gray","A")}
 * 不相等（后者用 damage 表达变体）。
 */
public final class BenchAEKey {

    private BenchAEKey() {
    }

    /** 一个不带变体的物品键。 */
    public static AEKey of(String id) {
        return AEItemKey.wrap(TestAeStacks.stack(id, 0, 1L));
    }

    /** 键的物品名（日志与断言用）。 */
    public static String id(AEKey key) {
        String identity = TestAeStacks.identity(((AEItemKey) key).getTemplate());
        if (identity == null) {
            return String.valueOf(key);
        }
        return identity.substring(0, identity.lastIndexOf(':'));
    }

    /** 键的变体编号（damage）；0 = 无变体。 */
    public static int damage(AEKey key) {
        String identity = TestAeStacks.identity(((AEItemKey) key).getTemplate());
        return identity == null ? -1 : Integer.parseInt(identity.substring(identity.lastIndexOf(':') + 1));
    }
}
