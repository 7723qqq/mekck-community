package cn.ism.mekck.util;

import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.world.Containers;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * 大堆叠掉落工具。
 *
 * <p><b>为什么不能用原版</b>：{@code Containers.dropItemStack} 会按 {@code getMaxStackSize()}（64）
 * 把堆叠拆成多个物品实体——本模组的槽位上限是 21 亿，一次掉落就会瞬间生成
 * <b>3355 万个</b>物品实体，直接把服务器打爆。</p>
 *
 * <p><b>做法</b>：数量不超过 {@link #SPLIT_THRESHOLD} 时完全走原版（行为不变）；
 * 超过阈值时**整堆只生成一个物品实体**。拾取时原版 {@code Inventory.add} 会自动按 64
 * 分配到各槽，装不下的部分留在实体里，不会丢失；大堆叠实体也不会与普通实体合并
 * （原版合并前会检查 {@code getMaxStackSize}）。</p>
 */
public final class BigStackDrops {

    /** 分堆阈值：低于等于它走原版分堆（最多 64 个实体），超过则整堆一个实体。 */
    public static final int SPLIT_THRESHOLD = 4096;

    private BigStackDrops() {
    }

    /** 在指定坐标掉落一堆物品（自动避免大堆叠炸实体）。 */
    public static void drop(Level level, double x, double y, double z, ItemStack stack) {
        if (level == null || level.isClientSide || stack == null || stack.isEmpty()) return;
        if (stack.getCount() <= SPLIT_THRESHOLD) {
            Containers.dropItemStack(level, x, y, z, stack);
            return;
        }
        ItemEntity entity = new ItemEntity(level, x, y, z, stack.copy());
        entity.setDefaultPickUpDelay();
        level.addFreshEntity(entity);
    }

    /** 在方块中心掉落一组物品。 */
    public static void dropAll(Level level, BlockPos pos, NonNullList<ItemStack> stacks) {
        if (level == null || level.isClientSide || stacks == null) return;
        double x = pos.getX() + 0.5D;
        double y = pos.getY() + 0.5D;
        double z = pos.getZ() + 0.5D;
        for (ItemStack stack : stacks) {
            drop(level, x, y, z, stack);
        }
    }

    /**
     * 按 <b>long 数量</b>掉落（为将来的 long 槽位预留）：
     * 每片最多 {@link cn.ism.mekck.util.CountMath#MAX_COUNT} 件、一片一个实体，
     * 因此 210 亿 = 10 个实体，而不是 3.2 亿个。
     */
    public static void dropBulk(Level level, double x, double y, double z, ItemStack proto, long amount) {
        if (level == null || level.isClientSide || proto == null || proto.isEmpty() || amount <= 0L) return;
        long remaining = amount;
        while (remaining > 0L) {
            int piece = (int) Math.min(remaining, (long) cn.ism.mekck.util.CountMath.MAX_COUNT);
            ItemEntity entity = new ItemEntity(level, x, y, z, proto.copyWithCount(piece));
            entity.setDefaultPickUpDelay();
            level.addFreshEntity(entity);
            remaining -= piece;
        }
    }

    /** 在方块上方掉落一堆物品（AE2 余料等场景）。 */
    public static void dropAbove(Level level, BlockPos pos, ItemStack stack) {
        if (pos == null) return;
        drop(level, pos.getX() + 0.5D, pos.getY() + 1.0D, pos.getZ() + 0.5D, stack);
    }
}
