package cn.ism.mekck.util;

import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.items.SlotItemHandler;

import java.util.List;

/**
 * 面向"无上限槽位"的物品转移工具。
 *
 * 原版 {@code AbstractContainerMenu#moveItemStackTo} 在合并时用
 * {@code min(槽位上限, 物品自身 maxStackSize)} 钳制堆叠数，导致手动放入
 * 材料最多只能堆到 64。本工具与原版语义一致，但容量改用槽位真实上限
 * （{@link SlotItemHandler} 的 {@code getSlotLimit}），从而支持把材料
 * 快速移动（Shift 点击）进 Integer.MAX_VALUE 上限的输入/存储槽。
 */
public final class MekCkTransfer {

    private MekCkTransfer() {
    }

    /**
     * 把 {@code source} 移入 {@code [startIndex, endIndex)} 范围内的槽位：
     * 先与已有相同物品合并，再尝试放入空槽。
     *
     * @return 是否有任何物品被移动。
     */
    public static boolean moveItemStackTo(ItemStack source, List<Slot> slots, int startIndex, int endIndex, boolean reverseDirection) {
        if (source.isEmpty()) return false;
        boolean moved = false;
        int i = reverseDirection ? endIndex - 1 : startIndex;
        if (source.isStackable()) {
            while (!source.isEmpty() && (reverseDirection ? i >= startIndex : i < endIndex)) {
                Slot slot = slots.get(i);
                ItemStack existing = slot.getItem();
                if (!existing.isEmpty() && existing.getItem() == source.getItem()
                        && ItemStack.isSameItemSameTags(source, existing)) {
                    int capacity = capacityOf(slot);
                    int total = existing.getCount() + source.getCount();
                    if (total <= capacity) {
                        source.setCount(0);
                        existing.setCount(total);
                        slot.setChanged();
                        moved = true;
                    } else if (existing.getCount() < capacity) {
                        source.shrink(capacity - existing.getCount());
                        existing.setCount(capacity);
                        slot.setChanged();
                        moved = true;
                    }
                }
                i += reverseDirection ? -1 : 1;
            }
        }
        if (!source.isEmpty()) {
            i = reverseDirection ? endIndex - 1 : startIndex;
            while (!source.isEmpty() && (reverseDirection ? i >= startIndex : i < endIndex)) {
                Slot slot = slots.get(i);
                if (!slot.hasItem() && slot.mayPlace(source)) {
                    int capacity = capacityOf(slot);
                    if (capacity < source.getCount()) {
                        slot.setByPlayer(source.split(capacity));
                    } else {
                        slot.setByPlayer(source.split(source.getCount()));
                    }
                    slot.setChanged();
                    moved = true;
                }
                i += reverseDirection ? -1 : 1;
            }
        }
        return moved;
    }

    /**
     * 槽位真实容量：SlotItemHandler 取 handler 的槽位上限（可超过 64），
     * 其它槽位退回原版行为。
     */
    public static int capacityOf(Slot slot) {
        if (slot instanceof SlotItemHandler handlerSlot) {
            return Math.max(handlerSlot.getItemHandler().getSlotLimit(slot.getContainerSlot()), 1);
        }
        return slot.getMaxStackSize();
    }
}
