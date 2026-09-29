package cn.ism.mekck.util;

import net.minecraft.world.item.ItemStack;
import net.minecraftforge.items.IItemHandlerModifiable;

/**
 * 存储空间自动合并工具。
 *
 * 把同一区域内后格的相同物品合并到前面的格子（"放在 2 格的相同物品会
 * 合并至前 1 格"）。只在同一区间内操作，不跨区、不动空格，因此不会打乱
 * AutoIO 的抽取/弹出顺序。
 */
public final class StorageMerger {

    private StorageMerger() {
    }

    /**
     * 合并 {@code [start, start+count)} 内的相同物品堆。
     *
     * @return 若发生任何实际移动返回 true（调用方据此 setChanged）。
     */
    public static boolean merge(IItemHandlerModifiable handler, int start, int count) {
        if (handler == null || count <= 1) return false;
        boolean changed = false;
        for (int i = start + 1; i < start + count; i++) {
            ItemStack src = handler.getStackInSlot(i);
            if (src.isEmpty()) continue;
            for (int j = start; j < i && !src.isEmpty(); j++) {
                ItemStack dst = handler.getStackInSlot(j);
                if (dst.isEmpty() || !ItemStack.isSameItemSameTags(dst, src)) continue;
                int space = Math.min(handler.getSlotLimit(j), Integer.MAX_VALUE) - dst.getCount();
                if (space <= 0) continue;
                int moved = Math.min(space, src.getCount());
                dst.grow(moved);
                handler.setStackInSlot(j, dst);
                src.shrink(moved);
                changed = true;
                if (src.isEmpty()) {
                    handler.setStackInSlot(i, ItemStack.EMPTY);
                    break;
                }
                handler.setStackInSlot(i, src);
            }
        }
        return changed;
    }

    /**
     * 带相位错开的周期触发判断：以方块坐标哈希为相位偏移，每 {@code interval}
     * tick 执行一次，避免全体机器在同一 tick 齐发合并扫描造成尖峰。
     */
    public static boolean shouldRunMerge(long gameTime, net.minecraft.core.BlockPos pos, int interval) {
        long phase = (pos.getX() * 31L) ^ (pos.getY() * 17L) ^ (pos.getZ() * 13L);
        return Math.floorMod(gameTime + phase, interval) == 0;
    }
}
