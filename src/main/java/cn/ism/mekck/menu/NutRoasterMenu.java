package cn.ism.mekck.menu;

import cn.ism.mekck.machine.roasting.NutRoasterTile;
import cn.ism.mekck.registry.MekCkStandaloneMachines;
import mekanism.common.inventory.container.tile.MekanismTileContainer;
import mekanism.common.registration.impl.ContainerTypeRegistryObject;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Inventory;

/**
 * 坚果爆炒机菜单 —— Mek 体系版。
 *
 * <h3>本类为什么只剩这么点</h3>
 * 迁移前它有 385 行：<b>四个</b>手写的 {@code SlotItemHandler implements IVirtualSlot}
 * 私有类（Input / Output / Power / Machine / Upgrade），每个都要把 {@code IVirtualSlot}
 * 的 8 个方法重写一遍（因为 Mek 的 {@code GuiMekanism.addSlots()} 只认
 * {@code InventoryContainerSlot}），外加 {@code ContainerData} 的 13 条下标常量、
 * 两个 16 位拆位槽（{@code WideDataSlot}）与一套自研升级槽的隐藏坐标技巧。
 *
 * <p>换 {@link MekanismTileContainer} 后：</p>
 * <ul>
 *   <li><b>槽位</b>由 {@code addSlots()} 遍历 {@code tile.getInventorySlots(null)} 自动装配，
 *       坐标取 tile 建槽时写进去的那一份（{@link cn.ism.mekck.menu.slot.MekCkSlots.NutRoaster}）；</li>
 *   <li><b>升级槽与升级输出槽</b>由 Mek 的 {@code getUpgradeSlot()/getUpgradeOutputSlot()}
 *       承担，不需要 {@code IUpgradeMenu}；</li>
 *   <li><b>读数</b>走 {@code addContainerTrackers} 的同步通道（不再有 16 位截断，
 *       {@code WideDataSlot} 那套随之作废）；</li>
 *   <li><b>shift-click 路由</b>交给 {@code MekanismContainer} 的默认实现
 *       （旧的 quickMoveStack 连同 6 个槽位分支一并删除）。</li>
 * </ul>
 *
 * <h3>背包几何：刻意不为 0 覆写的例外</h3>
 * 本机比 Mek 基础机器多一行自定义控件（索敌目标 / 半径，见 {@code NutRoasterScreen}），
 * 因此玩家背包整体下移 {@link #EXTRA_ROW_HEIGHT}，标签仍留在背包首行之上
 * {@code LABEL_ABOVE_INVENTORY} px —— 与 {@code MekCkFactoryLayout.INVENTORY_LABEL_Y}
 * 的间距口径一致。
 */
public final class NutRoasterMenu extends MekanismTileContainer<NutRoasterTile> {

    /** 自定义控件多占的高度（一行 16px + 8px 间距）。 */
    private static final int EXTRA_ROW_HEIGHT = 24;

    /** 玩家背包首行 y —— Mek 默认 84，本机多一行控件后下移 24。 */
    public static final int INV_TOP = 84 + EXTRA_ROW_HEIGHT;

    /** 标签到背包首行的间距 —— 与 {@code MekCkFactoryLayout.LABEL_ABOVE_INVENTORY} 同值。 */
    public static final int LABEL_ABOVE_INVENTORY = 10;

    /** 面板宽度 —— 与 Mek 基础电力机器一致（{@code GuiElectricMachine} 不覆写默认值）。 */
    public static final int IMAGE_WIDTH = 176;

    /** 面板高度 = 背包首行 + 3 行背包 + 1 行快捷栏 + 底部留白（Mek 的 6px）。 */
    public static final int IMAGE_HEIGHT = INV_TOP + 58 + 18 + 6;

    /** 索敌控件行相对面板顶的 y（屏幕侧同一常量）。 */
    public static final int ATTACK_ROW_Y = 74;

    /** 索敌控件行高度。 */
    public static final int ATTACK_ROW_HEIGHT = 16;

    public NutRoasterMenu(int containerId, Inventory inventory, NutRoasterTile tile) {
        super(resolveContainer(tile), containerId, inventory, tile);
    }

    /**
     * 容器类型从注册表取回。
     *
     * <p>父类要求非空（{@code MekanismContainer} 用它做 {@code IContainerTracker} 的身份标识）。
     * 拿不到就抛出<b>指明根因</b>的异常，而不是把 null 传给父类。</p>
     */
    private static ContainerTypeRegistryObject<NutRoasterMenu> resolveContainer(NutRoasterTile tile) {
        if (tile == null) {
            // 走到这里说明方块的 BlockType 描述没绑对 tile（Mek 的容器工厂会先在客户端
            // 按坐标取 biome 实体，取不到时抛「Missing tile」）。
            throw new IllegalStateException(
                    "坚果爆炒机容器拿不到 tile：BlockTypeTile 的 tile Supplier 被过早求值，"
                            + "或方块与 tile 类型不匹配。");
        }
        ContainerTypeRegistryObject<NutRoasterMenu> container = MekCkStandaloneMachines.NUT_ROASTER_CONTAINER;
        if (container == null) {
            throw new IllegalStateException("坚果爆炒机容器尚未注册（NUT_ROASTER_CONTAINER == null）");
        }
        return container;
    }

    @Override
    protected int getInventoryYOffset() {
        return INV_TOP;
    }

    // ================== 读数：全部问 tile（两侧同一入口） ==================

    /** 进度百分比（0..100），屏幕画进度条用。 */
    public int getProgressPercent() {
        int total = getTileEntity().getProcessTime();
        return total == 0 ? 0 : getTileEntity().getProgress() * 100 / total;
    }

    /** 机身温度（单位 0.01 ℃）—— 屏幕侧除以 100.0。 */
    public int getTemperature() {
        return getTileEntity().getTemperatureDeciCelsius();
    }

    public int getTargetType() {
        return getTileEntity().getTargetType();
    }

    public int getRadius() {
        return getTileEntity().getRadius();
    }

    /** 方块坐标 —— 屏幕发网络包时要用。 */
    public BlockPos getBlockPos() {
        return getTileEntity().getBlockPos();
    }

    /** 机器实例（屏幕的 ME 下单数据源取用）。 */
    public NutRoasterTile getMachine() {
        return getTileEntity();
    }
}
