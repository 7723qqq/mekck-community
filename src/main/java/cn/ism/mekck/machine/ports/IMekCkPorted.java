package cn.ism.mekck.machine.ports;

import cn.ism.mekck.machine.MekCkMachineTile;
import mekanism.api.inventory.IInventorySlot;

import java.util.List;

/**
 * 机器对自动化的 AE2 端口声明。
 *
 * <h3>为什么方法名长得像别人的接口</h3>
 * 这套签名（{@code mePattern*} 前缀、{@code List<IInventorySlot>} 参数、
 * {@code meGroupParallelItemInputs}）与外部 mod Mek Energistics 公开的
 * {@code IMePatternAutomationHost} 逐条对应。对方的目标版本是
 * MC 1.21.1 / NeoForge / Mekanism 10.7.x，与本项目（1.20.1 / Forge / 10.4.x）
 * <b>无交集、无法作为编译期依赖</b>。
 *
 * <p>但形状是可以提前对齐的：将来对方若出 1.20.1 版本，接入 MekCK 只需写一层反射桥接，
 * 不必再改机器代码。<b>起这套名字的成本是零，收益是留门。</b>
 * 本期<b>不实现</b>桥接。
 *
 * <h3>为什么参数是 IInventorySlot 而不是槽位下标</h3>
 * 旧接口 {@code INetworkPullable} 用 {@code int[] getInputSlotRange()}，
 * 只能表达一段连续区间，于是不得不加一个
 * {@code getExtraInputSlots()} 去补「0..4 与 10..13 两段不连续」的情况。
 * 槽对象本身是离散的，没有这个拼接问题——旧接口的债在这里直接消失。
 */
public interface IMekCkPorted {

    /** 本机器是否支持 AE2 样板自动化。 */
    default boolean meSupportsPatternAutomation() {
        return true;
    }

    /** 样板配料槽：会被 AE2 按配方投入。 */
    default List<IInventorySlot> mePatternItemInputs() {
        return List.of();
    }

    /** 产物槽：完成后回写 ME 存储。 */
    default List<IInventorySlot> mePatternItemOutputs() {
        return List.of();
    }

    /**
     * 常驻槽：跨订单不清空（如燃料槽、催化剂槽）。
     * 默认与 {@link #mePatternItemInputs()} 同集，即全部视为常驻。
     */
    default List<IInventorySlot> mePersistentItemInputs() {
        return mePatternItemInputs();
    }

    /** 只手动、AE2 不该碰的槽（升级槽、能量槽、配置槽）。 */
    default List<IInventorySlot> meManualOnlyItemSlots() {
        return List.of();
    }

    /**
     * 是否把 N 个同物输入槽塌缩成 1 个组端口。
     *
     * <p>切菜工厂的并行槽最多到奇点创世的 81 个。若不塌缩，
     * AE2 会把 81 个槽当成 81 个独立配料口，一单编码样板就把它们全占满。
     */
    default boolean meGroupParallelItemInputs() {
        return false;
    }

    /** 本机型的 tile。 */
    default MekCkMachineTile mePortedTile() {
        return null;
    }
}
