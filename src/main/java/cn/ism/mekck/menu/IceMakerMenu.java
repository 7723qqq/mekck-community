package cn.ism.mekck.menu;

import cn.ism.mekck.machine.icemaker.IceMakerTile;
import cn.ism.mekck.registry.MekCkStandaloneMachines;
import mekanism.common.inventory.container.tile.MekanismTileContainer;
import mekanism.common.registration.impl.ContainerTypeRegistryObject;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Inventory;

/**
 * 急冻制冰机菜单 —— Mek 体系版。
 *
 * <h3>本类为什么只剩这么点</h3>
 * 迁移前它有 444 行：<b>五个</b>手写的 {@code SlotItemHandler implements IVirtualSlot}
 * 私有类（Input / Output / Power / ColdBrew / Upgrade），每个都要把 {@code IVirtualSlot}
 * 的 8 个方法重写一遍（因为 Mek 的 {@code GuiMekanism.addSlots()} 只认
 * {@code InventoryContainerSlot}），外加 {@code ContainerData} 的 23 条下标常量、
 * 三个 16 位拆位槽（{@code WideDataSlot}）与一套自研升级槽的隐藏坐标技巧。
 *
 * <p>换 {@link MekanismTileContainer} 后：</p>
 * <ul>
 *   <li><b>槽位</b>由 {@code addSlots()} 遍历 {@code tile.getInventorySlots(null)} 自动装配，
 *       坐标取 tile 建槽时写进去的那一份（{@link cn.ism.mekck.menu.slot.MekCkSlots.IceMaker}）；</li>
 *   <li><b>升级槽与升级输出槽</b>由 Mek 的 {@code getUpgradeSlot()/getUpgradeOutputSlot()}
 *       承担，不需要 {@code IUpgradeMenu}；</li>
 *   <li><b>读数</b>走 {@code addContainerTrackers} 的同步通道（不再有 16 位截断，
 *       {@code WideDataSlot} 那套随之作废）—— 能量、流体量、机身温度都由 Mek 的
 *       {@code SyncableFloatingLong} / {@code SyncableFluidStack} / {@code SyncableDouble}
 *       自动同步，不需要在这里再拆高低位；</li>
 *   <li><b>shift-click 路由</b>交给 {@code MekanismContainer} 的默认实现
 *       （旧的 quickMoveStack 连同 6 个槽位分支一并删除）。</li>
 * </ul>
 *
 * <h3>背包几何：两行自定义控件</h3>
 * 本机比 Mek 基础机器多两行自定义控件（索敌目标 / 半径，控温开关 / 目标温度，
 * 见 {@code IceMakerScreen}），因此玩家背包整体下移 {@code 2 × EXTRA_ROW_HEIGHT}，
 * 标签仍留在背包首行之上 {@code LABEL_ABOVE_INVENTORY} px —— 与
 * {@code MekCkFactoryLayout.INVENTORY_LABEL_Y} 的间距口径一致。
 */
public final class IceMakerMenu extends MekanismTileContainer<IceMakerTile> {

    /** 自定义控件每行多占的高度（一行 16px + 8px 间距）。 */
    private static final int EXTRA_ROW_HEIGHT = 24;

    /** 自定义控件行数：索敌行 + 控温行。 */
    private static final int EXTRA_ROWS = 2;

    /** 玩家背包首行 y —— Mek 默认 84，本机多两行控件后下移 48。 */
    public static final int INV_TOP = 84 + EXTRA_ROWS * EXTRA_ROW_HEIGHT;

    /** 标签到背包首行的间距 —— 与 {@code MekCkFactoryLayout.LABEL_ABOVE_INVENTORY} 同值。 */
    public static final int LABEL_ABOVE_INVENTORY = 10;

    /** 面板宽度 —— 与 Mek 基础电力机器一致（{@code GuiElectricMachine} 不覆写默认值）。 */
    public static final int IMAGE_WIDTH = 176;

    /** 面板高度 = 背包首行 + 3 行背包 + 1 行快捷栏 + 底部留白（Mek 的 6px）。 */
    public static final int IMAGE_HEIGHT = INV_TOP + 58 + 18 + 6;

    /** 索敌控件行相对面板顶的 y（屏幕侧同一常量）。 */
    public static final int ATTACK_ROW_Y = 92;

    /** 控温控件行相对面板顶的 y（索敌行下方 20px）。 */
    public static final int TEMP_ROW_Y = ATTACK_ROW_Y + 20;

    /** 控件行高度。 */
    public static final int CONTROL_ROW_HEIGHT = 16;

    public IceMakerMenu(int containerId, Inventory inventory, IceMakerTile tile) {
        super(resolveContainer(tile), containerId, inventory, tile);
    }

    /**
     * 容器类型从注册表取回。
     *
     * <p>父类要求非空（{@code MekanismContainer} 用它做 {@code IContainerTracker} 的身份标识）。
     * 拿不到就抛出<b>指明根因</b>的异常，而不是把 null 传给父类。</p>
     */
    private static ContainerTypeRegistryObject<IceMakerMenu> resolveContainer(IceMakerTile tile) {
        if (tile == null) {
            // 走到这里说明方块的 BlockType 描述没绑对 tile（Mek 的容器工厂会先在客户端
            // 按坐标取 biome 实体，取不到时抛「Missing tile」）。
            throw new IllegalStateException(
                    "急冻制冰机容器拿不到 tile：BlockTypeTile 的 tile Supplier 被过早求值，"
                            + "或方块与 tile 类型不匹配。");
        }
        ContainerTypeRegistryObject<IceMakerMenu> container = MekCkStandaloneMachines.ICE_MAKER_CONTAINER;
        if (container == null) {
            throw new IllegalStateException("急冻制冰机容器尚未注册（ICE_MAKER_CONTAINER == null）");
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
    public int getCurrentTemperature() {
        return getTileEntity().getTemperatureDeciCelsius();
    }

    /** 设定温度（单位 0.01 ℃）。 */
    public int getTargetTemperature() {
        return getTileEntity().getTargetTemperature();
    }

    /** 设定温度功能是否开启。 */
    public boolean isTemperatureControlEnabled() {
        return getTileEntity().isTemperatureControlEnabled();
    }

    public int getTargetType() {
        return getTileEntity().getTargetType();
    }

    public int getRadius() {
        return getTileEntity().getRadius();
    }

    /**
     * 第 index 格冷萃已安装的等级序号 + 1（0 = 未安装）。
     *
     * <p>屏幕据此在冷萃槽上画「已安装」徽标 —— 那是冷萃<b>卸载</b>的唯一入口
     * （冷萃不是 Mek 的 {@code Upgrade}，Mek 的升级界面碰不到它）。</p>
     */
    public int getInstalledColdBrewCode(int index) {
        return getTileEntity().getInstalledColdBrewCode(index);
    }

    /** 方块坐标 —— 屏幕发网络包时要用。 */
    public BlockPos getBlockPos() {
        return getTileEntity().getBlockPos();
    }
}
