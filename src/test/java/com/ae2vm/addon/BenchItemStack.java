package com.ae2vm.addon;

import appeng.api.config.FuzzyMode;
import appeng.api.storage.IStorageChannel;
import appeng.api.storage.data.IAEItemStack;
import appeng.api.storage.data.IAEStack;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * {@link TestAeStacks} 用的具体假栈：实现 AE2 v9 (1.17.1) 的<b>真</b> {@link IAEItemStack}。
 *
 * <p>为什么必须是真接口的替身：bench 键要做成产品里的真 {@code AEItemKey}（否则
 * {@code CraftingVM}/{@code PatternCompiler} 里那 17 处 {@code (AEItemKey) key} 强转必然
 * ClassCastException），而 {@code AEItemKey.toStack(long)} 交出去的就是 {@code IAEItemStack} ——
 * 它会一路走进 AE2 v9 的真 {@code MixedStackList} / {@code CraftingSimulationState}。
 * 有了这层，bench 与游戏跑的是同一条生产代码路径，产品侧不用为测试开后门。
 *
 * <p>身份口径与 AE2 一致：{@code 物品 + damage}，<b>不含数量</b>（{@code AEItemStack.isSameType}
 * 只比 item/itemDamage/NBT）；{@link FuzzyMode#IGNORE_ALL} = 同物品任意 damage，
 * bench 用它表达"模糊族"（同 base 的多个变体）。
 *
 * <p>序列化（{@code writeToNBT}/{@code writeToPacket}）直接抛：bench 不走网络/存档，
 * 抛出来比返回假数据更早暴露误用。
 */
final class BenchItemStack implements IAEItemStack {

    /** 断言与 identity 记账用的字符串形状：{@code 名字:damage}。 */
    private final String identity;
    private final Item item;
    private final int damage;
    private long size;
    private long requestable;
    private boolean craftable;
    private ItemStack definition;

    BenchItemStack(String identity, Item item, int damage, long size) {
        this.identity = identity;
        this.item = item;
        this.damage = damage;
        this.size = size;
    }

    String identity() {
        return identity;
    }

    private String itemName() {
        int colon = identity.lastIndexOf(':');
        return colon < 0 ? identity : identity.substring(0, colon);
    }

    // ---------------------------------------------------------------- 数量

    @Override
    public long getStackSize() {
        return size;
    }

    @Override
    public void setStackSize(long stackSize) {
        size = stackSize;
    }

    @Override
    public long getCountRequestable() {
        return requestable;
    }

    @Override
    public void setCountRequestable(long countRequestable) {
        requestable = countRequestable;
    }

    @Override
    public boolean isCraftable() {
        return craftable;
    }

    @Override
    public void setCraftable(boolean isCraftable) {
        craftable = isCraftable;
    }

    @Override
    public void incCountRequestable(long amount) {
        requestable += amount;
    }

    @Override
    public void decCountRequestable(long amount) {
        requestable -= amount;
    }

    @Override
    public void reset() {
        size = 0L;
        requestable = 0L;
        craftable = false;
    }

    @Override
    public boolean isMeaningful() {
        return size != 0L || requestable != 0L || craftable;
    }

    @Override
    public IAEItemStack copy() {
        BenchItemStack out = new BenchItemStack(identity, item, damage, size);
        out.requestable = requestable;
        out.craftable = craftable;
        return out;
    }

    // ---------------------------------------------------------------- 物品面

    @Override
    public Item getItem() {
        return item;
    }

    @Override
    public int getItemDamage() {
        return damage;
    }

    @Override
    public ItemStack getDefinition() {
        if (definition == null) {
            definition = TestAeStacks.mcStack(itemName(), damage, size);
        }
        return definition;
    }

    @Override
    public ItemStack createItemStack() {
        return TestAeStacks.mcStack(itemName(), damage, size);
    }

    @Override
    public ItemStack asItemStackRepresentation() {
        return createItemStack();
    }

    @Override
    public boolean hasTagCompound() {
        return false;
    }

    // ---------------------------------------------------------------- 身份比较

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof IAEItemStack other)) {
            return false;
        }
        return item == other.getItem() && damage == other.getItemDamage();
    }

    @Override
    public int hashCode() {
        return (item == null ? 0 : System.identityHashCode(item)) * 31 + damage;
    }

    @Override
    public boolean isSameType(IAEItemStack other) {
        return other != null && item == other.getItem() && damage == other.getItemDamage();
    }

    @Override
    public boolean isSameType(ItemStack other) {
        return other != null && item == other.getItem() && damage == other.getDamageValue();
    }

    @Override
    public boolean equals(ItemStack other) {
        return isSameType(other);
    }

    /** 与 AE2 的 {@code AEItemStack.fuzzyEquals} 同口径：{@code IGNORE_ALL} = 同物品即命中。 */
    @Override
    public boolean fuzzyEquals(IAEStack other, FuzzyMode mode) {
        return other instanceof IAEItemStack it && item == it.getItem();
    }

    @Override
    public IStorageChannel<?> getChannel() {
        return TestAeStacks.channel();
    }

    // ---------------------------------------------------------------- 序列化（bench 不走）

    @Override
    public void writeToNBT(CompoundTag data) {
        throw new UnsupportedOperationException("bench stack is not serializable");
    }

    @Override
    public void writeToPacket(FriendlyByteBuf data) {
        throw new UnsupportedOperationException("bench stack is not serializable");
    }

    @Override
    public String toString() {
        return size + "x" + identity;
    }
}
