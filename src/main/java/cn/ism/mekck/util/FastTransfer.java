package cn.ism.mekck.util;

import cn.ism.mekck.api.IBulkItemHandler;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.items.IItemHandler;

/**
 * 高性能批量物品传输调度。
 *
 * <h3>成本模型（核心不变量）</h3>
 * 耗时只与「<b>类型数 × (源槽数 + 目标槽数)</b>」有关，<b>与数量无关</b>：
 * 搬 210 亿与搬 21 件的调用次数相同。真正的天花板是<b>表示能力</b>
 * （单个 {@code ItemStack} 最多 {@code Integer.MAX_VALUE-1} 件），不是性能。
 *
 * <h3>多材料（例如每 tick 10 种材料、每种 21 亿）</h3>
 * <ul>
 *   <li><b>本模组机器之间</b>：双方物品容器原生实现 {@link IBulkItemHandler}
 *       ⇒ 每种材料只需 <b>3 次调用</b>（问空间 / 模拟抽 / 插入+真实抽），10 种 = 30 次；</li>
 *   <li><b>对外部容器 / 过滤包装器</b>：经 {@link IntHandlerBulkView} 适配
 *       ⇒ 每种材料 O(源槽 + 目标槽) 次调用（先扫容量、再按槽投递，每次投递用满 21.47 亿）；
 *       相比旧实现"每个源槽遍历全部目标槽"的 O(类型 × 源槽 × 目标槽) 明显更低；</li>
 *   <li><b>公平额度</b>：每方向预算按"源区间内非空槽数"均分给各类型，
 *       避免降档时限流被前面的类型吃光、后面类型被饿死。</li>
 * </ul>
 *
 * <p>其余优化：内层零分配（复用模拟副本）、满槽跳过、单次模拟、同类记忆、绝不混合不同物品。</p>
 */
public final class FastTransfer {

    private FastTransfer() {
    }

    // ==================== 对外入口 ====================

    /** 从 {@code from} 抽入 {@code into} 的 [insertStart, insertStart+insertCount) 区间。 */
    public static boolean pull(IItemHandler from, IItemHandler into, int insertStart, int insertCount) {
        if (from == null || into == null || from == into) return false;
        return bulkTransfer(IntHandlerBulkView.of(from), IntHandlerBulkView.of(into), from, into,
                0, from.getSlots(), insertStart, insertCount);
    }

    /** 把 {@code mine} 的 [outputStart, outputStart+outputSlotCount) 推到 {@code to}。 */
    public static boolean push(IItemHandler mine, int outputStart, int outputSlotCount, IItemHandler to) {
        if (mine == null || to == null || mine == to) return false;
        return bulkTransfer(IntHandlerBulkView.of(mine), IntHandlerBulkView.of(to), mine, to,
                outputStart, outputSlotCount, 0, to.getSlots());
    }

    // ==================== 统一批量传输 ====================

    /**
     * @param src         源视图
     * @param dst         目标视图
     * @param srcView     源 {@code IItemHandler}（用于枚举槽与读取物品类型）
     * @param dstView     目标 {@code IItemHandler}
     * @param srcStart    源区间起点
     * @param srcCount    源区间长度
     * @param dstStart    目标区间起点
     * @param dstCount    目标区间长度
     */
    private static boolean bulkTransfer(IBulkItemHandler src, IBulkItemHandler dst,
                                        IItemHandler srcView, IItemHandler dstView,
                                        int srcStart, int srcCount, int dstStart, int dstCount) {
        // 公平额度：按源区间非空槽数均分本方向的预算（210 亿级下预算为 long 不限量）
        long budget = LagMonitor.getMaxItemsPerDirectionLong();
        int busy = 0;
        int srcEnd = Math.min(srcStart + srcCount, srcView.getSlots());
        for (int i = Math.max(0, srcStart); i < srcEnd; i++) {
            if (!srcView.getStackInSlot(i).isEmpty()) busy++;
        }
        if (busy == 0) return false;
        long perType = Math.max(1L, budget / busy);

        boolean any = false;
        ItemStack lastHandled = null;
        for (int i = Math.max(0, srcStart); i < srcEnd && budget > 0L; i++) {
            ItemStack proto = srcView.getStackInSlot(i);
            if (proto.isEmpty()) continue;
            if (lastHandled != null && ItemStack.isSameItemSameTags(lastHandled, proto)) {
                continue; // 同一种物品已处理过：要么已抽空，要么目标已满
            }

            long space = dst.bulkSpace(proto, dstStart, dstCount);
            if (space <= 0L) continue;
            long avail = src.bulkAvailable(proto, i, srcEnd - i);
            if (avail <= 0L) continue;
            long move = Math.min(Math.min(avail, space), Math.min(perType, budget));
            if (move <= 0L) continue;

            long dryRun = src.bulkExtract(proto, move, i, srcEnd - i, true);
            if (dryRun <= 0L) continue;
            long inserted = dst.bulkInsert(proto, dryRun, dstStart, dstCount, false);
            if (inserted <= 0L) continue;
            src.bulkExtract(proto, inserted, i, srcEnd - i, false);
            budget -= inserted;
            any = true;
            lastHandled = proto;
        }
        return any;
    }
}
