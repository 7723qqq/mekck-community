package cn.ism.mekck.util;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.items.ItemStackHandler;

/**
 * 大堆叠物品容器：修正原版 {@code ItemStack} NBT 的 Count 上限问题。
 *
 * <p><b>为什么必须自己持久化</b>：原版 1.20.1 的 {@code ItemStack.save()} 把 Count 写成
 * <b>byte</b>（{@code i2b} + {@code putByte}），读取也是 {@code getByte}——任何超过 127 的堆叠
 * 在存档、方块掉落、任何 NBT 往返之后都会被截断（甚至变成负数而被当成空槽丢弃）。
 * 而本模组机器的输入/输出槽上限是 {@code Integer.MAX_VALUE-1}，因此必须由我们自己写出真实数量。</p>
 *
 * <p><b>兼容策略</b>：写出时保留原版字段（外部读取/旧版本仍能看到一个被截断的值），
 * 额外写 int 型 {@link #BIG_COUNT_KEY} 作为权威数量；读入时优先取权威值，没有该字段
 * （旧存档、原版写入的物品）则完全走原版 {@code ItemStack.of}，行为不变。</p>
 */
public class BigStackItemHandler extends ItemStackHandler implements cn.ism.mekck.api.IBulkItemHandler {

    /** 权威数量字段（int，可表示 21 亿）。 */
    public static final String BIG_COUNT_KEY = "McCount";

    public BigStackItemHandler(int size) {
        super(size);
    }

    @Override
    public CompoundTag serializeNBT() {
        ListTag list = new ListTag();
        for (int i = 0; i < getSlots(); i++) {
            ItemStack stack = getStackInSlot(i);
            if (stack.isEmpty()) continue;
            CompoundTag tag = new CompoundTag();
            tag.putInt("Slot", i);
            stack.save(tag); // 原版字段（Count 为 byte，仅作兼容）
            tag.putInt(BIG_COUNT_KEY, stack.getCount()); // 权威数量
            list.add(tag);
        }
        CompoundTag out = new CompoundTag();
        out.put("Items", list);
        out.putInt("Size", getSlots());
        return out;
    }

    @Override
    public void deserializeNBT(CompoundTag nbt) {
        if (nbt == null) return;
        if (nbt.contains("Size", Tag.TAG_INT)) {
            int size = nbt.getInt("Size");
            if (size >= 0 && size != getSlots()) {
                setSize(size);
            }
        }
        ListTag list = nbt.getList("Items", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag tag = list.getCompound(i);
            int slot = tag.getInt("Slot");
            if (slot < 0 || slot >= getSlots()) continue;
            ItemStack stack = readStack(tag);
            if (!stack.isEmpty()) {
                stacks.set(slot, stack);
            }
        }
        onLoad();
    }

    // ==================== 大宗搬运（long 级，成本与数量无关） ====================
    // 设计要点：单个槽位仍受 int 上限约束（ItemStack 表示），但**一次调用可以跨槽聚合**，
    // 因此 210 亿只需 1 次"问空间 + 模拟抽 + 插入 + 真实抽"，内部按槽填充（O(槽数)）。

    @Override
    public long bulkSpace(ItemStack proto, int start, int count) {
        if (proto == null || proto.isEmpty()) return 0L;
        long space = 0L;
        int end = Math.min(start + count, getSlots());
        for (int slot = Math.max(0, start); slot < end; slot++) {
            if (!isItemValid(slot, proto)) continue;
            ItemStack cur = getStackInSlot(slot);
            long limit = getSlotLimit(slot);
            if (cur.isEmpty()) {
                space += limit;
            } else if (ItemStack.isSameItemSameTags(cur, proto)) {
                space += Math.max(0L, limit - cur.getCount());
            }
            if (space < 0L) return Long.MAX_VALUE; // 防溢出（实际到不了）
        }
        return space;
    }

    @Override
    public long bulkAvailable(ItemStack proto, int start, int count) {
        if (proto == null || proto.isEmpty()) return 0L;
        long total = 0L;
        int end = Math.min(start + count, getSlots());
        for (int slot = Math.max(0, start); slot < end; slot++) {
            ItemStack cur = getStackInSlot(slot);
            if (cur.isEmpty() || !ItemStack.isSameItemSameTags(cur, proto)) continue;
            total += cur.getCount();
        }
        return total;
    }

    @Override
    public long bulkInsert(ItemStack proto, long amount, int start, int count, boolean simulate) {
        if (proto == null || proto.isEmpty() || amount <= 0L) return 0L;
        long inserted = 0L;
        int end = Math.min(start + count, getSlots());
        for (int slot = Math.max(0, start); slot < end && inserted < amount; slot++) {
            if (!isItemValid(slot, proto)) continue;
            ItemStack cur = getStackInSlot(slot);
            long limit = getSlotLimit(slot);
            long space;
            if (cur.isEmpty()) {
                space = limit;
            } else if (ItemStack.isSameItemSameTags(cur, proto)) {
                space = (long) limit - cur.getCount();
            } else {
                continue;
            }
            if (space <= 0L) continue;
            long move = Math.min(space, amount - inserted);
            if (!simulate) {
                // 以该槽自己的上限为准（与 bulkSpace 的口径严格一致，避免差 1 件导致
                // "问到的空间插入不进去"）：BIG_STACK 槽为 Integer.MAX_VALUE-1，
                // 普通机器输入槽为 Integer.MAX_VALUE。
                long newCount = Math.min(Math.min(limit, Integer.MAX_VALUE),
                        (cur.isEmpty() ? 0L : cur.getCount()) + move);
                if (cur.isEmpty()) {
                    setStackInSlot(slot, proto.copyWithCount((int) newCount));
                } else {
                    cur.setCount((int) newCount);
                    setStackInSlot(slot, cur); // 触发 onContentsChanged（机器据此 setChanged）
                }
            }
            inserted += move;
        }
        return inserted;
    }

    @Override
    public long bulkExtract(ItemStack proto, long amount, int start, int count, boolean simulate) {
        if (proto == null || proto.isEmpty() || amount <= 0L) return 0L;
        long extracted = 0L;
        int end = Math.min(start + count, getSlots());
        for (int slot = Math.max(0, start); slot < end && extracted < amount; slot++) {
            ItemStack cur = getStackInSlot(slot);
            if (cur.isEmpty() || !ItemStack.isSameItemSameTags(cur, proto)) continue;
            long move = Math.min(cur.getCount(), amount - extracted);
            if (move <= 0L) continue;
            if (!simulate) {
                int left = (int) (cur.getCount() - move);
                if (left <= 0) {
                    setStackInSlot(slot, ItemStack.EMPTY);
                } else {
                    cur.setCount(left);
                    setStackInSlot(slot, cur);
                }
            }
            extracted += move;
        }
        return extracted;
    }

    /**
     * 读取单个槽位 NBT：优先使用 {@link #BIG_COUNT_KEY}（大堆叠），否则回退原版 {@code ItemStack.of}。
     * 供各机器自定义的 {@code deserializeNBT}（槽位迁移版本）复用。
     */
    public static ItemStack readStack(CompoundTag tag) {
        if (tag == null) return ItemStack.EMPTY;
        if (tag.contains(BIG_COUNT_KEY, Tag.TAG_INT)) {
            int count = tag.getInt(BIG_COUNT_KEY);
            if (count <= 0) return ItemStack.EMPTY;
            CompoundTag copy = tag.copy();
            copy.putByte("Count", (byte) 1); // 原版 of() 需要正的 byte 计数：先给 1，稍后改回真实数量
            ItemStack stack = ItemStack.of(copy);
            if (stack.isEmpty()) return ItemStack.EMPTY;
            stack.setCount(Math.min(count, Integer.MAX_VALUE - 1));
            return stack;
        }
        return ItemStack.of(tag);
    }
}
