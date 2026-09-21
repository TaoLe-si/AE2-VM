package com.ae2vm.addon.bench;

import com.ae2vm.addon.TestAeStacks;
import com.ae2vm.shim.api.stacks.AEItemKey;
import com.ae2vm.shim.api.stacks.AEKey;

/**
 * bench 用例的键工厂：**返回的就是产品里的真实键类型** {@link AEItemKey}
 * （包住 {@link TestAeStacks} 造的 {@code IAEItemStack} 替身），
 * 这样 bench 走的代码路径与游戏里一致 —— 特别是 {@code CraftingVM}/
 * {@code PatternCompiler} 里那 17 处 {@code (AEItemKey) key} 强转，
 * 旧的"字符串键继承 AEKey"写法在那里必然 ClassCastException。
 *
 * <p>物品身份沿用 uel 的语义：{@code 物品名 + damage}（见 {@link TestAeStacks}），
 * 所以 {@code BenchAEKey.of("gray")} 与 {@code BenchAEKey.of("gray")} 相等、
 * 与 {@code VariantKey.of("gray","A")} 不相等（后者用 damage 表达变体）。
 */
public final class BenchAEKey {

    private BenchAEKey() {
    }

    /** 一个不带 NBT 变体的物品键。 */
    public static AEKey of(String id) {
        return AEItemKey.wrap(TestAeStacks.stack(id, 0, 1L));
    }

    /** 同一物品的第 {@code damage} 个变体（bench 用它表达 NBT/damage 族）。 */
    public static AEKey of(String id, int damage) {
        return AEItemKey.wrap(TestAeStacks.stack(id, damage, 1L));
    }

    /** 键的物品名（日志与断言用）；非本工厂造的键返回 {@code "?"}。 */
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
