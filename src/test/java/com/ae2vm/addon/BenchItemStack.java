package com.ae2vm.addon;

import appeng.api.config.FuzzyMode;
import appeng.api.storage.StorageChannel;
import appeng.api.storage.data.IAEItemStack;
import appeng.api.storage.data.IAEStack;
import io.netty.buffer.ByteBuf;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

/**
 * {@link TestAeStacks} 用的<b>具体</b>假栈（原来是一份 {@code java.lang.reflect.Proxy}）。
 *
 * <p>换成手写类只为消掉夹具税：Proxy 的每个方法调用都要走一遍
 * {@code InvocationHandler.invoke} 里那串 {@code "getStackSize".equals(n)} 线性字符串比较，
 * 而生产 VM 的热路径是按操作数成百上千次地 {@code getStackSize/incStackSize/copy}。
 * 实测同一批 {@code PerformanceBenchmark} 用例：baseline(AE2 v15 原生栈) 温热中位数 100–200 ns，
 * Proxy 版 1200–2000 ns —— 差一个量级，全在反射派发上，不是 VM 算法慢。
 *
 * <p>语义与 Proxy 版逐条对齐（也即与 uel 对齐）：identity = <b>物品 + damage</b>，
 * 不含数量；{@link FuzzyMode#IGNORE_ALL} = 同物品任意 damage。
 * 未被生产/测试路径调用的序列化方法直接抛，避免"假装能用"。
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
        return this.identity;
    }

    // ---------------------------------------------------------------- 数量

    @Override
    public long getStackSize() {
        return this.size;
    }

    @Override
    public IAEItemStack setStackSize(long stackSize) {
        this.size = stackSize;
        return this;
    }

    @Override
    public void incStackSize(long amount) {
        this.size += amount;
    }

    @Override
    public void decStackSize(long amount) {
        this.size -= amount;
    }

    @Override
    public void add(IAEItemStack stack) {
        if (stack != null) {
            this.size += stack.getStackSize();
        }
    }

    @Override
    public long getCountRequestable() {
        return this.requestable;
    }

    @Override
    public IAEItemStack setCountRequestable(long countRequestable) {
        this.requestable = countRequestable;
        return this;
    }

    @Override
    public void incCountRequestable(long amount) {
        this.requestable += amount;
    }

    @Override
    public void decCountRequestable(long amount) {
        this.requestable -= amount;
    }

    @Override
    public boolean isCraftable() {
        return this.craftable;
    }

    @Override
    public IAEItemStack setCraftable(boolean isCraftable) {
        this.craftable = isCraftable;
        return this;
    }

    @Override
    public IAEItemStack reset() {
        this.size = 0L;
        this.requestable = 0L;
        this.craftable = false;
        return this;
    }

    @Override
    public boolean isMeaningful() {
        return this.size > 0L || this.requestable > 0L || this.craftable;
    }

    @Override
    public IAEItemStack copy() {
        BenchItemStack out = new BenchItemStack(this.identity, this.item, this.damage, this.size);
        out.requestable = this.requestable;
        out.craftable = this.craftable;
        return out;
    }

    @Override
    public IAEItemStack empty() {
        return new BenchItemStack(this.identity, this.item, this.damage, 0L);
    }

    // ---------------------------------------------------------------- 物品面

    @Override
    public Item getItem() {
        return this.item;
    }

    @Override
    public int getItemDamage() {
        return this.damage;
    }

    @Override
    public ItemStack getItemStack() {
        // rv4 的接口方法是 getItemStack()（v8/uel 那代叫 createItemStack()）。
        return TestAeStacks.mcStack(itemName(), this.damage, this.size);
    }

    /** bench 自己的便捷方法，rv4 的接口上没有（原先是 @Override）。 */
    public ItemStack getDefinition() {
        if (this.definition == null) {
            this.definition = TestAeStacks.mcStack(itemName(), this.damage, this.size);
        }
        return this.definition;
    }

    /** bench 自己的便捷方法，rv4 的接口上没有。 */
    public ItemStack createItemStack() {
        return TestAeStacks.mcStack(itemName(), this.damage, this.size);
    }

    /** bench 自己的便捷方法，rv4 的接口上没有。 */
    public ItemStack asItemStackRepresentation() {
        return createItemStack();
    }

    /** bench 自己的便捷方法，rv4 的接口上没有。 */
    public ItemStack getCachedItemStack(long stackSize) {
        return null;
    }

    /** bench 自己的便捷方法，rv4 的接口上没有。 */
    public void setCachedItemStack(ItemStack stack) {
        // 测试替身没有缓存，忽略。
    }

    private String itemName() {
        int colon = this.identity.lastIndexOf(':');
        return colon < 0 ? this.identity : this.identity.substring(0, colon);
    }

    // ---------------------------------------------------------------- 身份比较

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof IAEItemStack)) {
            return false;
        }
        IAEItemStack other = (IAEItemStack) obj;
        return this.item == other.getItem() && this.damage == other.getItemDamage();
    }

    @Override
    public int hashCode() {
        return (this.item == null ? 0 : System.identityHashCode(this.item)) * 31 + this.damage;
    }

    @Override
    public boolean isSameType(IAEItemStack other) {
        return other != null && this.item == other.getItem() && this.damage == other.getItemDamage();
    }

    @Override
    public boolean isSameType(ItemStack other) {
        return other != null && this.item == other.getItem()
                && this.damage == other.getItemDamage();
    }

    /** bench 自己的便捷方法，rv4 的接口上没有（v8 上有 equals(ItemStack)）。 */
    public boolean equals(ItemStack other) {
        return isSameType(other);
    }

    @Override
    public boolean sameOre(IAEItemStack other) {
        return isSameType(other);
    }

    @Override
    public boolean hasTagCompound() {
        return false;
    }

    @Override
    public appeng.api.storage.data.IAETagCompound getTagCompound() {
        // rv4 的 IAEStack 有 getTagCompound()（返回它自家的 IAETagCompound，v8 那代是
        // net.minecraft NBT 标签对象）。bench 的假栈不带 NBT —— 与 hasTagCompound() 恒 false 同口径。
        return null;
    }

    /**
     * 与 uel 的 {@code AEItemStack.fuzzyComparison} 同口径：{@code IGNORE_ALL}（以及
     * 无 damage 语义的 {@code DEFAULT}/{@code JABBA} 之外的档）= 同物品即命中。
     * bench 只需要这一条：1.12.2 的"任一木板"就是同 Item 的 6 个 damage。
     */
    @Override
    public boolean fuzzyComparison(Object other, FuzzyMode mode) {
        // rv4 的形参是 Object（v8 那代才是 T），所以要自己判类型。
        return other instanceof IAEItemStack && this.item == ((IAEItemStack) other).getItem();
    }

    @Override
    public boolean isItem() {
        return true;
    }

    @Override
    public boolean isFluid() {
        return false;
    }

    @Override
    public StorageChannel getChannel() {
        // rv4 的通道是枚举 StorageChannel（不是 v8 的 IStorageChannel）。
        // 与 Proxy 版一致：原来就没有 handler 分支，返回 null，且没有任何测试路径读它。
        return null;
    }

    // ---------------------------------------------------------------- 序列化（测试里不会走）

    @Override
    public void writeToNBT(NBTTagCompound data) {
        throw new UnsupportedOperationException("bench stack is not serializable");
    }

    @Override
    public void writeToPacket(ByteBuf data) throws java.io.IOException {
        throw new UnsupportedOperationException("bench stack is not serializable");
    }

    @Override
    public String toString() {
        return this.size + "x" + this.identity;
    }
}
