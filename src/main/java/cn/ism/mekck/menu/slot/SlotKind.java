package cn.ism.mekck.menu.slot;

/**
 * 槽位类别 —— 决定该槽用哪一族 Mek 槽对象、以及它在侧配里属于哪个 {@code DataType}。
 *
 * <p>与 {@link mekanism.common.inventory.container.slot.ContainerSlotType} 一一对应，
 * 但<b>刻意分开</b>：本枚举是「设计意图」（这个槽是干什么的），
 * 而 {@code ContainerSlotType} 是 Mek 的渲染/侧配契约。两者混用会让
 * 「这里到底想要输入槽还是只想让它显示成输入色」无从分辨。</p>
 */
public enum SlotKind {

    /** 输入槽：外部自动化不得抽取（{@code canExtract = notExternal}），任何来源都可放入。 */
    INPUT,

    /** 输出槽：只有机器自己可写入（{@code canInsert = internalOnly}），谁都能取。 */
    OUTPUT,

    /** 能源槽：只接受充能物品，由 Mek 的 {@code EnergyInventorySlot} 承担。 */
    POWER,

    /** 升级槽：只接受升级物品，由 Mek 的 {@code UpgradeInventorySlot} 承担。 */
    UPGRADE,

    /** 家族专属槽（营养液 / 生长土 / 调味料 / 样品 等），语义上属于输入侧但单独呈现。 */
    EXTRA,

    /** 大容量存储槽（穿串 81 / 烹饪 144），内容活在悬浮窗里。 */
    STORAGE
}
