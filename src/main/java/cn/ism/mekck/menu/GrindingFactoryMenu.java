package cn.ism.mekck.menu;

import cn.ism.mekck.UniversalCuttingMachine;
import cn.ism.mekck.machine.grinding.GrindingFactoryTile;
import mekanism.common.inventory.container.tile.MekanismTileContainer;
import mekanism.common.registration.impl.ContainerTypeRegistryObject;
import net.minecraft.world.entity.player.Inventory;

/**
 * 研磨工厂容器（Mek 体系版）—— 阶段 3 Task 1。
 *
 * <h3>为什么整个类只剩一个构造器</h3>
 * {@code MekanismTileContainer.addSlots()} 已经把全部槽位装配做完：
 * 玩家背包 / 快捷栏 / 副手 → 升级槽与升级输出槽 → 遍历
 * {@code tile.getInventorySlots(null)} 逐个 {@code createContainerSlot()}。
 * 槽位数量与坐标全由 tile 侧的 {@code getInitialInventory} 决定，
 * 所以本类一行槽位代码都不该有。
 *
 * <h3>被删掉的那一大截下标逻辑</h3>
 * 旧实现靠 {@code speedUpgradeSlot = 2 * inputSlots} 这类<b>手写下标</b>拼槽位，
 * 并在 {@link #quickMoveStack} 里重复同样的算术（还与真正的处理器槽数各算一遍）。
 * 换成 {@code IInventorySlot} 对象引用之后，下标本身消失了：
 * 想遍历输入槽就 {@code tile.getInputSlots()}，不会因为排布不连续而漏算。
 *
 * <h3>三个构造器变一个</h3>
 * 旧类有 {@code (int, Inventory, FriendlyByteBuf)} / {@code (int, Inventory, BE)} /
 * {@code (int, Inventory, BE, ContainerData)} 三个入口，以及一整页
 * {@code ContainerData} 索引常量（11 项）。现在没有 {@code FriendlyByteBuf} 入口——
 * Mek 的容器工厂是 {@code (int, Inventory, TILE)}；
 * 也没有 {@code ContainerData}——进度/能量/红石/升级数不再走同步整数，
 * 客户端直接读 tile 的网络同步状态。
 */
public final class GrindingFactoryMenu extends MekanismTileContainer<GrindingFactoryTile> {

    /**
     * 构造器签名必须与 Mek 的容器工厂一致：{@code (int, Inventory, TILE)}。
     *
     * <p>父类<b>要求</b>容器类型非空（{@code MekanismContainer} 用它做
     * {@code IContainerTracker} 的身份标识），所以这里从注册表取回。
     * 12 个等级共用一个容器类型 {@code mekck:grinding_factory}——与旧实现一致，
     * 也让 {@link #resolveContainer} 不必按等级分叉。</p>
     */
    /**
     * 悬浮窗槽位（按类别分三组）—— 字段初始化器在 {@code super(...)} 之后执行，
     * 那时 {@code addSlots()} 已把虚拟槽建好，所以这里能捞到。
     *
     * <p>只有 &gt;17 并行的高档工厂才有窗口槽（输入/输出各 {@code processes} 格）；
     * 其余档位三组皆空，屏幕因此不会加标签页。</p>
     */
    private final MekCkWindowSlotHolder windowSlots = new MekCkWindowSlotHolder(this);

    public GrindingFactoryMenu(int containerId, Inventory inventory, GrindingFactoryTile tile) {
        super(resolveContainer(tile), containerId, inventory, tile);
    }

    /** 悬浮窗槽位（输入 / 输出 / 存储三组）。 */
    public MekCkWindowSlotHolder windowSlots() {
        return windowSlots;
    }

    private static ContainerTypeRegistryObject<GrindingFactoryMenu> resolveContainer(GrindingFactoryTile tile) {
        if (tile == null) {
            // 走到这里说明方块的 BlockType 描述没绑对 tile：显式报错好过把 null 传给父类。
            throw new IllegalStateException(
                    "研磨工厂容器拿不到 tile：BlockTypeTile 的 tile Supplier 被过早求值，"
                            + "或方块与 tile 类型不匹配。");
        }
        ContainerTypeRegistryObject<GrindingFactoryMenu> container = UniversalCuttingMachine.GRINDING_FACTORY_CONTAINER;
        if (container == null) {
            throw new IllegalStateException("研磨工厂容器尚未注册（GRINDING_FACTORY_CONTAINER == null）");
        }
        return container;
    }

    // ── 给 GUI 读的转发 ──────────────────────────────────────────────────

    /**
     * 第 {@code index} 路的进度：0.0~1.0。
     *
     * <p>每路一个独立计时器（见 {@code MekCkMachineTile.workCycle}），所以进度条也是一路一条
     * —— 与 Mek 的 {@code GuiFactory} 里 {@code tile.getScaledProgress(1, i)} 逐条对应。
     * 分母走 {@code tile.getTicksPerWorkCycle()} 而不是写死 200：装速度卡后批次变短，
     * 进度条必须跟着变短才有正确的「越快越满」手感。</p>
     */
    public double getProgressRatio(int index) {
        GrindingFactoryTile tile = getTileEntity();
        return tile == null ? 0 : tile.getProgressRatio(index);
    }

    public boolean isBusy() {
        GrindingFactoryTile tile = getTileEntity();
        return tile != null && tile.isBusy();
    }

    /** 存量能量（FE）。{@code FloatingLong} 继承 {@code Number}，用 {@code doubleValue()} 取值。 */
    public double getEnergy() {
        GrindingFactoryTile tile = getTileEntity();
        return tile == null ? 0 : tile.getEnergyContainer().getEnergy().doubleValue();
    }

    /** 容量上限（FE）。 */
    public double getMaxEnergy() {
        GrindingFactoryTile tile = getTileEntity();
        return tile == null ? 0 : tile.getEnergyContainer().getMaxEnergy().doubleValue();
    }

    /**
     * 玩家背包首行的 y —— <b>必须与屏幕侧的面板高度同源</b>。
     *
     * <p>Mek 的 {@code MekanismContainer.getInventoryYOffset()} 默认返回
     * {@code BASE_Y_OFFSET = 84}；Mek 自己的 {@code FactoryContainer} 会按机器形态覆写成
     * 85 / 95 / 105（见其真源码）。MekCK 此前<b>零覆写</b>，于是面板随并行数长高、背包却钉死在 84，
     * 从 ELITE 档起机器槽就压进玩家背包（SINGULARITY 重叠 119px）。</p>
     *
     * <p>这里按 Mek 的规则算：并行方阵（与切菜同源）。
     * 公式与依据见 {@link MekCkFactoryLayout} 的类注释。</p>
     */
    @Override
    protected int getInventoryYOffset() {
        return MekCkFactoryLayout.inventoryYOffset(
                MekCkFactoryLayout.gridFamilyPanelHeight(getTileEntity(), 0, 0, 0));
    }

    /**
     * 玩家背包首列的 x —— <b>随面板宽度横向居中</b>。
     *
     * <p>Mek 的 {@code MekanismContainer} 默认返回 8，而 Mek 自己的 {@code FactoryContainer}
     * 会按面板宽度改它（ULTIMATE 用 26）。MekCK 此前<b>零覆写</b>，恒为 8 ⇒ 面板越宽、
     * 背包越贴左：SINGULARITY 面板 412 宽而背包只占最左 162px，右侧空出 250px。
     * 公式与实测依据见 {@link MekCkFactoryLayout#inventoryXOffset(int)}。</p>
     */
    @Override
    protected int getInventoryXOffset() {
        return MekCkFactoryLayout.inventoryXOffset(
                MekCkFactoryLayout.gridFamilyPanelWidth(getTileEntity()));
    }
}
