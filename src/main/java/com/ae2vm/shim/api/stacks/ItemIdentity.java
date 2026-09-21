package com.ae2vm.shim.api.stacks;

import cpw.mods.fml.common.registry.GameRegistry;
import net.minecraft.item.Item;

/**
 * 1.7.10 的物品身份。
 * <p>
 * 1.7.10 的 {@code Item} <b>没有</b> {@code getRegistryName()}（那是 1.9+ 注册表重做之后的 API；
 * 实测 MCP stable_12 的 Item.java 只有 {@code getUnlocalizedName}/{@code setUnlocalizedName}），
 * FML 1.7.10 侧的对应物是 {@code GameRegistry.findUniqueIdentifierFor(Item)} →
 * {@code UniqueIdentifier{modId, name}}。AE2 rv3-GTNH 自己也是走这条路（它的
 * {@code AEItemStack} 常量池里就引用了 {@code GameRegistry$UniqueIdentifier}）。
 * <p>
 * 离线 bench 里 {@code new Item()} 没进 FML 注册表，拿不到 UniqueIdentifier，
 * 于是退到 {@code getUnlocalizedName()} —— bench 造假物品时设的就是这个字段，
 * 所以同一件物品在两条路径上得到同一个字符串，身份不会漂。
 */
public final class ItemIdentity {

    private ItemIdentity() {
    }

    /** {@code mod:name}。 */
    public static String name(Item item) {
        GameRegistry.UniqueIdentifier uid = identifier(item);
        if (uid != null) {
            return uid.modId + ":" + uid.name;
        }
        String raw = unlocalizedName(item);
        int dot = raw.lastIndexOf('.');
        return dot >= 0 ? raw.substring(dot + 1) : raw;
    }

    /** 注册方 modid；拿不到 UniqueIdentifier 时退到 unlocalized 名的前缀。 */
    public static String modId(Item item) {
        GameRegistry.UniqueIdentifier uid = identifier(item);
        if (uid != null) {
            return uid.modId;
        }
        String raw = unlocalizedName(item);
        int dot = raw.lastIndexOf('.');
        return dot >= 0 ? raw.substring(0, dot) : raw;
    }

    /**
     * {@code findUniqueIdentifierFor} 对未注册/越界的 Item 会抛（它先按数字 id 回查注册表），
     * 而"未注册"在离线 bench 里是正常状态，所以这里按边界输入处理。
     */
    private static GameRegistry.UniqueIdentifier identifier(Item item) {
        if (item == null) {
            return null;
        }
        try {
            return GameRegistry.findUniqueIdentifierFor(item);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String unlocalizedName(Item item) {
        if (item == null) {
            return "null";
        }
        String n = item.getUnlocalizedName();
        return n == null ? String.valueOf(item) : n;
    }
}
