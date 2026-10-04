package cn.ism.mekck.ae2;

import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.List;

/**
 * 可选 AE2 "网络拉料"能力标记接口。
 * <p>
 * 实现此接口的机器会：① 挂上 ME 网络节点能力（未装 AE2 时无影响）；
 * ② GUI 显示"网络拉料"入口——从 ME 网络把<b>当前可处理配方</b>所需材料拉进输入槽
 * （不写产物、不下单）；③ 实现 {@link #supportsAutoPull()} 的简单单输入机器
 * 额外支持"勾选持续自动补料"。
 * 本接口不引用任何 AE2 类，AE2 交互全部经由 {@code MekckAe2} 反射门面。
 */
public interface INetworkPullable {

    /** 机器方块实体本身（通常直接返回 this）。 */
    BlockEntity getNetworkPullable();

    /**
     * 当前机器<b>可处理</b>的配方所需输入材料（每项 = 一种 Ingredient + 数量）。
     * 手动"网络拉料"一次拉一份；自动补料时逐份拉。
     * 返回空表示当前无可拉取的配方输入（如输入槽已满/无适配配方）。
     */
    List<AE2InputSpec> getNetworkPullInputs();

    /** 简单配方（单物品 / 单流体 / 物品+流体 / 输入+额外输入）是否支持勾选持续自动补料。 */
    boolean supportsAutoPull();

    /**
     * ME 终端下单总开关（默认开启）。
     * 关闭后该机器的配方不再注册到 ME 终端（终端里看不到这台机器的样板），
     * 已下单的订单不受影响；重新开启即恢复。
     */
    default boolean isMeOrderEnabled() {
        return true;
    }

    /** 设置 ME 终端下单开关。默认空实现（不支持该开关的机器忽略）。 */
    default void setMeOrderEnabled(boolean enabled) {
    }

    /**
     * 自动补料时，限制输入槽最多堆积到多少份该输入。
     * 默认读配置 {@code auto_pull.auto_pull_stack_limit}（默认 64）；
     * 调到 2147483646 即可让单种材料补到 21 亿（"无限 ME"场景）。
     */
    default int getAutoPullStackLimit() {
        return cn.ism.mekck.config.MekckConfig.getAutoPullStackLimit();
    }

    /**
     * 机器输入槽范围 [start, end)（网络拉料把材料插入这些槽，
     * 且自动补料判断"存量不足"也以此为准）。
     */
    int[] getInputSlotRange();

    /** 机器的 ItemStackHandler 存储。 */
    net.minecraftforge.items.ItemStackHandler getNetworkPullItems();

    /**
     * **额外的**输入槽（与 {@link #getInputSlotRange()} 不连续时使用）。
     *
     * <p>背景：{@code getInputSlotRange()} 只能表达**一段连续区间**，而联动机器的有效输入槽是
     * <b>0..4 与 10..13 两段</b>（5..9 是产物 / 升级 / 电力槽）—— 直接返回 {@code {0,14}} 会把产物槽也当材料抽走，
     * 比现状更糟。所以这里用"区间 ∪ 额外槽"的方式表达：默认无额外槽，只有真正需要的机器覆写。</p>
     *
     * <p>网络拉料 / 面板 ME 下单在插入材料时会**同时**考虑区间与额外槽，装不下的才会落到地上。</p>
     */
    default int[] getExtraInputSlots() {
        return new int[0];
    }
}
