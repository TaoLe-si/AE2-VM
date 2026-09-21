package com.ae2vm.addon.bench;

import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import com.ae2vm.addon.TestAeStacks;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * bench 用例的"同物品多变体"键工厂 —— 与 {@link BenchAEKey} 一样**返回真实的产品键类型**，
 * 变体用 damage 表达：v9 的 {@code IAEItemStack} 身份里本来就带 damage，同一 {@code Item}
 * 的多个 damage 就是 AE2 眼里的"同物品变体"，所以 {@code FuzzyMode.IGNORE_ALL} 的
 * 模糊族语义（{@code findFuzzy(base, IGNORE_ALL)} 返回全族）天然成立，正是
 * v1.10.x 处理配方默认模糊那条修复要测的东西（GTL 温室方块 / MA 精华）。
 *
 * <p>旧实现是自己继承 {@code AEKey}、用 base/variant 两个字段记账，那样既过不了
 * {@code (AEItemKey)} 强转，也进不了 v9 真接口的 {@code IAEStack} 世界。
 */
public final class VariantKey {

    /** "base|variant" → damage（damage 0 保留给"无变体主项"）。 */
    private static final Map<String, Integer> DAMAGE = new LinkedHashMap<>();

    /** "base|damage" → variant 名，供断言还原。 */
    private static final Map<String, String> VARIANT_NAME = new LinkedHashMap<>();

    private VariantKey() {
    }

    /** variant 为空串时就是无变体主项。 */
    public static synchronized AEKey of(String base, String variant) {
        if (variant == null || variant.isEmpty()) {
            return AEItemKey.wrap(TestAeStacks.stack(base, 0, 1L));
        }
        String slot = base + "|" + variant;
        Integer dmg = DAMAGE.get(slot);
        if (dmg == null) {
            dmg = DAMAGE.size() + 1;
            DAMAGE.put(slot, dmg);
            VARIANT_NAME.put(base + "|" + dmg, variant);
        }
        return AEItemKey.wrap(TestAeStacks.stack(base, dmg, 1L));
    }

    /** 键的变体名（无变体时是空串）。 */
    public static synchronized String variant(AEKey key) {
        int dmg = BenchAEKey.damage(key);
        if (dmg <= 0) {
            return "";
        }
        String v = VARIANT_NAME.get(BenchAEKey.id(key) + "|" + dmg);
        return v == null ? String.valueOf(dmg) : v;
    }
}
