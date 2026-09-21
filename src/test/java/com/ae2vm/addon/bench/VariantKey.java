package com.ae2vm.addon.bench;

import com.ae2vm.addon.TestAeStacks;
import java.util.LinkedHashMap;
import java.util.Map;

import com.ae2vm.shim.api.stacks.AEItemKey;
import com.ae2vm.shim.api.stacks.AEKey;

/**
 * bench 用例的"同物品多变体"键工厂 —— 与 {@link BenchAEKey} 一样**返回真实的产品键类型**，
 * 变体用 damage 表达（1.12.2 还没有 flattening，同一 Item 的多个 damage 就是 AE2 眼里的
 * "同物品变体"，所以 {@code FuzzyMode.IGNORE_ALL} 的模糊族语义天然成立）。
 *
 * <p>旧实现是自己继承 {@code AEKey}、用 base/variant 两个字段记账，
 * 那样过不了 {@code CraftingVM}/{@code PatternCompiler} 里的 {@code (AEItemKey)} 强转。
 */
public final class VariantKey {

    /** "base|variant" → damage（damage 0 保留给"无变体主项"）。 */
    private static final Map<String, Integer> DAMAGE = new LinkedHashMap<String, Integer>();

    /** "base|damage" → variant 名，供断言还原。 */
    private static final Map<String, String> VARIANT_NAME = new LinkedHashMap<String, String>();

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
            dmg = Integer.valueOf(DAMAGE.size() + 1);
            DAMAGE.put(slot, dmg);
            VARIANT_NAME.put(base + "|" + dmg.intValue(), variant);
        }
        return AEItemKey.wrap(TestAeStacks.stack(base, dmg.intValue(), 1L));
    }

    /** 键的物品名。 */
    public static String base(AEKey key) {
        return BenchAEKey.id(key);
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

    /** 同物品的全部变体键（含主项）——用于模糊族断言。 */
    public static synchronized AEKey[] family(String base, int count) {
        AEKey[] out = new AEKey[count];
        out[0] = of(base, "");
        for (int i = 1; i < count; i++) {
            out[i] = of(base, "v" + i);
        }
        return out;
    }
}
