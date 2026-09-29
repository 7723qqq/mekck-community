package cn.ism.mekck.client;

import java.util.List;
import net.minecraft.world.inventory.Slot;

/**
 * 「大堆叠数量压制」渲染辅助。
 * <p>
 * 机器 GUI 的堆叠数量可能远超 1000，原版数字会画成超长字符串；各屏幕的既有做法是渲染前把这类堆叠
 * 临时改成 1、渲染后还原。原先**每帧**都要新建两个 ArrayList（还伴随 Integer 装箱），17 个屏幕累计
 * 是不小的客户端 GC 压力；这里改为复用数组缓冲，**每帧零分配**，行为与原先完全一致。
 * </p>
 */
public final class BigStackHud {

    private Slot[] slots = new Slot[16];
    private int[] counts = new int[16];
    private int size;

    /** 渲染前调用：把数量 ≥ 1000 的堆叠临时压成 1，并记录原数量。 */
    public void shrink(List<Slot> menuSlots) {
        size = 0;
        if (menuSlots == null) return;
        for (int i = 0; i < menuSlots.size(); i++) {
            Slot slot = menuSlots.get(i);
            if (slot == null || !slot.isActive() || !slot.hasItem()) continue;
            int count = slot.getItem().getCount();
            if (count < 1000) continue;
            if (size == slots.length) grow();
            slots[size] = slot;
            counts[size] = count;
            size++;
            slot.getItem().setCount(1);
        }
    }

    /** 渲染后调用：还原被压制的数量。 */
    public void restore() {
        for (int i = 0; i < size; i++) {
            Slot slot = slots[i];
            if (slot != null && slot.hasItem()) {
                slot.getItem().setCount(counts[i]);
            }
            slots[i] = null;
        }
        size = 0;
    }

    private void grow() {
        int n = slots.length * 2;
        Slot[] newSlots = new Slot[n];
        int[] newCounts = new int[n];
        System.arraycopy(slots, 0, newSlots, 0, slots.length);
        System.arraycopy(counts, 0, newCounts, 0, counts.length);
        slots = newSlots;
        counts = newCounts;
    }
}
