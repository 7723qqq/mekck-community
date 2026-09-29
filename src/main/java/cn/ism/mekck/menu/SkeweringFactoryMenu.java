package cn.ism.mekck.menu;

import cn.ism.mekck.UniversalCuttingMachine;
import cn.ism.mekck.machine.skewering.SkeweringFactoryTile;
import mekanism.common.inventory.container.tile.MekanismTileContainer;
import mekanism.common.registration.impl.ContainerTypeRegistryObject;
import net.minecraft.world.entity.player.Inventory;


/**
 * 穿串工厂容器（Mek 体系版）—— 阶段 3 Task 5。
 *
 * <h3>整个类只剩一个构造器</h3>
 * 与前四个家族同款：{@code MekanismTileContainer.addSlots()} 已把全部槽位装配做完
 * （玩家背包 / 快捷栏 / 副手 → 升级槽与升级输出槽 → 遍历
 * {@code tile.getInventorySlots(null)} 逐个 {@code createContainerSlot()}）。
 * 槽位数量与坐标全由 tile 侧的 {@code getInitialInventory}（3 输入 + 2 输出 + 能量）
 * 与 {@code appendExtraSlots}（81 格存储）决定，<b>所以本类一行槽位代码都不该有</b>。
 *
 * <h3>被删掉的那一大截下标逻辑</h3>
 * 旧实现是这套里最绕的一个：{@code storageSlotStart}、{@code creativeUpgradeSlot}、
 * 以及一个写着
 * {@code powerSlot = INPUT_SLOTS + storageSlots + 2 + (hasStackUpgrade ? 4 : 3)}
 * 的公式——<b>而 {@code totalSlots} 含一个没有菜单槽的创造升级槽</b>，
 * 于是玩家背包第 0 格被误判成机器槽，shift-click 会走错分支
 * （旧 {@code SkeweringFactoryMenu} 第 183/185 行的已知 bug）。
 * 换成 {@code IInventorySlot} 对象引用后下标本身消失了，这个 bug 随之消失，
 * 不是「小心绕开」而是<b>不再存在</b>。
 */
public final class SkeweringFactoryMenu extends MekanismTileContainer<SkeweringFactoryTile> {

    /**
     * 81 格存储的虚拟容器槽 —— 由 {@code super.addSlots()} 自动建成后捞出来交给悬浮窗。
     *
     * <p>构造器里 {@code addSlotsAndOpen()} 会调用 {@link #addSlots()}，所以本字段在
     * 构造完成后即已填好；窗口构造时读取它是安全的。</p>
     */
    /**
     * 悬浮窗槽位（按类别分三组）—— 字段初始化器在 {@code super(...)} 之后执行，
     * 那时 {@code addSlots()} 已把虚拟槽建好，所以这里能捞到。
     */
    private final MekCkWindowSlotHolder windowSlots = new MekCkWindowSlotHolder(this);

    public SkeweringFactoryMenu(int containerId, Inventory inventory, SkeweringFactoryTile tile) {
        super(resolveContainer(tile), containerId, inventory, tile);
    }

    /** 悬浮窗槽位（输入 / 输出 / 存储三组）。 */
    public MekCkWindowSlotHolder windowSlots() {
        return windowSlots;
    }

    private static ContainerTypeRegistryObject<SkeweringFactoryMenu> resolveContainer(SkeweringFactoryTile tile) {
        if (tile == null) {
            throw new IllegalStateException(
                    "穿串工厂容器拿不到 tile：BlockTypeTile 的 tile Supplier 被过早求值，"
                            + "或方块与 tile 类型不匹配。");
        }
        ContainerTypeRegistryObject<SkeweringFactoryMenu> container = UniversalCuttingMachine.SKEWERING_FACTORY_CONTAINER;
        if (container == null) {
            throw new IllegalStateException("穿串工厂容器尚未注册（SKEWERING_FACTORY_CONTAINER == null）");
        }
        return container;
    }

    // ── 给 GUI 读的转发 ──────────────────────────────────────────────────

    /**
     * 进度条：0.0~1.0。
     *
     * <p>分母走 {@code tile.getTicksPerWorkCycle()} 而不是写死 200：装速度卡后批次变短，
     * 进度条必须跟着变短才有正确的「越快越满」手感。</p>
     */
    public double getProgressRatio() {
        SkeweringFactoryTile tile = getTileEntity();
        if (tile == null) {
            return 0;
        }
        return tile.getWorkProgress() / (double) tile.getTicksPerWorkCycle();
    }

    public boolean isBusy() {
        SkeweringFactoryTile tile = getTileEntity();
        return tile != null && tile.isBusy();
    }

    /** 存量能量（FE）。 */
    public double getEnergy() {
        SkeweringFactoryTile tile = getTileEntity();
        return tile == null ? 0 : tile.getEnergyContainer().getEnergy().doubleValue();
    }

    /** 容量上限（FE）。 */
    public double getMaxEnergy() {
        SkeweringFactoryTile tile = getTileEntity();
        return tile == null ? 0 : tile.getEnergyContainer().getMaxEnergy().doubleValue();
    }

    public boolean hasOrder() {
        SkeweringFactoryTile tile = getTileEntity();
        return tile != null && tile.hasOrder();
    }

    public int getOrderQuantity() {
        SkeweringFactoryTile tile = getTileEntity();
        return tile == null ? 0 : tile.getOrderQuantity();
    }

    public int getOrderCompleted() {
        SkeweringFactoryTile tile = getTileEntity();
        return tile == null ? 0 : tile.getOrderCompleted();
    }

    /**
     * 玩家背包首行的 y —— <b>必须与屏幕侧的面板高度同源</b>。
     *
     * <p>Mek 的 {@code MekanismContainer.getInventoryYOffset()} 默认返回
     * {@code BASE_Y_OFFSET = 84}；Mek 自己的 {@code FactoryContainer} 会按机器形态覆写成
     * 85 / 95 / 105（见其真源码）。MekCK 此前<b>零覆写</b>，于是面板随并行数长高、背包却钉死在 84，
     * 从 ELITE 档起机器槽就压进玩家背包（SINGULARITY 重叠 119px）。</p>
     *
     * <p>这里按 Mek 的规则算：格数与档位无关 ⇒ 面板高度是常量。
     * 公式与依据见 {@link MekCkFactoryLayout} 的类注释。</p>
     */
    @Override
    protected int getInventoryYOffset() {
        return MekCkFactoryLayout.inventoryYOffset(MekCkFactoryLayout.skeweringImageHeight());
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
        return MekCkFactoryLayout.inventoryXOffset(MekCkFactoryLayout.SKEWERING_PANEL_WIDTH);
    }
}
