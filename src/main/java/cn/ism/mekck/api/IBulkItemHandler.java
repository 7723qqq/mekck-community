package cn.ism.mekck.api;

import net.minecraft.world.item.ItemStack;

/**
 * 大宗物品搬运接口（long 级数量）。
 *
 * <p><b>为什么需要它</b>：Forge 的 {@code IItemHandler} 只有 {@code int} 级数量，
 * 而"每 tick 搬运 210 亿件"在 <b>int 表示上根本不存在</b>（单槽上限 {@code Integer.MAX_VALUE-1}）。
 * 更关键的是：如果按"每槽一次调用"去搬，成本随槽数线性增长；而大宗搬运可以在
 * <b>一次调用里完成整批</b>——内部仍按槽填充（O(槽数)），但跨机器只发生 2 次调用
 * （源抽 + 目标插），把"每槽一次"降到"每批一次"。</p>
 *
 * <p><b>成本模型（关键不变量）</b>：这些方法的耗时与 {@code amount} <b>无关</b>，
 * 只与区间内的槽数有关；实现里绝不允许按数量循环。因此 210 亿与 21 件的开销相同。</p>
 *
 * <p><b>兼容</b>：外部模组的容器不实现本接口，{@link cn.ism.mekck.util.FastTransfer}
 * 会自动回退到逐槽的 {@code IItemHandler} 路径（并把超过 int 的请求分片成 ≤ 21 亿的调用）。</p>
 */
public interface IBulkItemHandler {

    /** 区间 {@code [start, start+count)} 还能容纳多少件 {@code proto}（long）。 */
    long bulkSpace(ItemStack proto, int start, int count);

    /** 区间 {@code [start, start+count)} 内与 {@code proto} 同类且可抽取的总量（long）。 */
    long bulkAvailable(ItemStack proto, int start, int count);

    /** 向区间插入最多 {@code amount} 件（按槽顺序填充），返回实际插入量。 */
    long bulkInsert(ItemStack proto, long amount, int start, int count, boolean simulate);

    /** 从区间抽取最多 {@code amount} 件 {@code proto}（按槽顺序），返回实际抽取量。 */
    long bulkExtract(ItemStack proto, long amount, int start, int count, boolean simulate);
}
