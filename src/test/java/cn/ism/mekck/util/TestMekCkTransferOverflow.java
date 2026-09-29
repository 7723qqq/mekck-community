package cn.ism.mekck.util;

import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.items.ItemStackHandler;
import net.minecraftforge.items.SlotItemHandler;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * {@link MekCkTransfer#moveItemStackTo} 在 21 亿级堆叠下的**溢出**回归测试（审查项 I10）。
 *
 * <h3>它钉住的故障形态</h3>
 * <p>合并分支原先写的是 {@code int total = existing.getCount() + source.getCount();}。
 * 本模组的输入/存储槽上限就是 {@code Integer.MAX_VALUE}（见各机器 {@code getSlotLimit}），
 * 两个 15 亿 + 10 亿级的堆叠一相加就<b>溢出为负</b>，而接下来判断的是
 * {@code total <= capacity}——负数当然成立，于是执行「源堆清零 + 目标堆写 total」：
 * 源堆凭空消失、目标堆被写成一个<b>负数计数</b>。负数在
 * {@link BigStackItemHandler#readStack}（{@code count <= 0 → ItemStack.EMPTY}）下
 * 于下次存档往返直接变成空气，两个堆叠一起蒸发。</p>
 *
 * <h3>断言口径</h3>
 * <p>不假设实现细节，只钉守恒量：合并后两处计数之和必须等于合并前，
 * 且两处都必须落在 {@code [0, 槽位上限]} 内（不得为负、不得越界）。
 * 修复前的实现会在这三条上全部失败。</p>
 */
public class TestMekCkTransferOverflow {

    /** 与真实机器一致：槽位上限 = Integer.MAX_VALUE。 */
    private static final int SLOT_LIMIT = Integer.MAX_VALUE;

    @BeforeClass
    public static void boot() {
        net.minecraft.SharedConstants.tryDetectVersion();
        try {
            net.minecraft.server.Bootstrap.bootStrap();
        } catch (Throwable ignored) {
            // Forge 的网络钩子在未变换的 classpath 上必然失败；此时注册表已就绪。
        }
    }

    private static ItemStackHandler hugeSlots(int size) {
        return new ItemStackHandler(size) {
            @Override
            public int getSlotLimit(int slot) {
                return SLOT_LIMIT;
            }
        };
    }

    private static ItemStack stone(int count) {
        ItemStack stack = new ItemStack(Items.STONE);
        stack.setCount(count);
        return stack;
    }

    @Test
    public void twoHugeStacksDoNotOverflowIntoNegative() {
        ItemStackHandler handler = hugeSlots(1);
        handler.setStackInSlot(0, stone(1_500_000_000));
        assertEquals("前置条件：槽位应能容纳 15 亿",
                1_500_000_000, handler.getStackInSlot(0).getCount());

        List<Slot> slots = List.of(new SlotItemHandler(handler, 0, 0, 0));
        ItemStack source = stone(1_000_000_000);

        boolean moved = MekCkTransfer.moveItemStackTo(source, slots, 0, 1, false);
        assertTrue("源堆与槽内同物，应当发生合并", moved);

        int inSlot = handler.getStackInSlot(0).getCount();
        int left = source.getCount();
        assertTrue("槽内计数被写成负数（int 相加溢出）：" + inSlot, inSlot > 0);
        assertTrue("源堆计数非法：" + left, left >= 0);
        assertEquals("槽内应被填到上限", SLOT_LIMIT, inSlot);
        assertEquals("源堆只应被扣掉「上限 − 已有量」",
                1_000_000_000 - (SLOT_LIMIT - 1_500_000_000), left);
        assertEquals("总量必须守恒（溢出实现下这里是 0 或负数）",
                2_500_000_000L, (long) inSlot + (long) left);
    }

    @Test
    public void mergingBelowCapacityStillMergesCompletely() {
        ItemStackHandler handler = hugeSlots(1);
        handler.setStackInSlot(0, stone(1_000_000_000));
        List<Slot> slots = List.of(new SlotItemHandler(handler, 0, 0, 0));
        ItemStack source = stone(500_000_000);

        assertTrue(MekCkTransfer.moveItemStackTo(source, slots, 0, 1, false));
        assertEquals(1_500_000_000, handler.getStackInSlot(0).getCount());
        assertEquals(0, source.getCount());
    }

    @Test
    public void differentItemsAreNotMerged() {
        ItemStackHandler handler = hugeSlots(1);
        handler.setStackInSlot(0, stone(1_000_000_000));
        List<Slot> slots = List.of(new SlotItemHandler(handler, 0, 0, 0));
        ItemStack source = new ItemStack(Items.DIRT);
        source.setCount(1_000_000_000);

        boolean moved = MekCkTransfer.moveItemStackTo(source, slots, 0, 1, false);
        assertFalse("不同物品不得合并（槽内也放不下）", moved);
        assertEquals(1_000_000_000, handler.getStackInSlot(0).getCount());
        assertEquals(1_000_000_000, source.getCount());
    }
}
