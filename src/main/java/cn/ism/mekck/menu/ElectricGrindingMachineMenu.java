package cn.ism.mekck.menu;

import cn.ism.mekck.machine.grinding.GrindingMachineTile;
import cn.ism.mekck.registry.MekCkFactories;
import mekanism.common.inventory.container.tile.MekanismTileContainer;
import net.minecraft.world.entity.player.Inventory;

/**
 * 电力研磨机菜单 —— Mek 体系版。
 *
 * <h3>本类为什么只剩这么点</h3>
 * 迁移前它有 333 行：<b>五个</b>手写的 {@code SlotItemHandler implements IVirtualSlot}
 * 私有类（Input / Output / Power / 速度卡 / 能量卡），每个都要把 {@code IVirtualSlot}
 * 的 8 个方法重写一遍，因为 Mek 的 {@code GuiMekanism.addSlots()} 只认
 * {@code InventoryContainerSlot}，不认原版 {@code Slot}。
 *
 * <p>其中绝大部分是<b>被自研体系逼出来的</b>，不是设计：</p>
 * <ul>
 *   <li><b>手写槽类</b>：每个槽在菜单与屏幕各写一份坐标，靠 ±1 凑合
 *       （菜单 {@code (38,41)} vs 屏幕 {@code (37,40)}）——这正是本次重写的动因；</li>
 *   <li><b>逐格 addSlot</b>：Mek 的 {@code MekanismTileContainer.addSlots()} 会遍历
 *       {@code tile.getInventorySlots(null)} 自动装配，槽的坐标就是 tile 建槽时写进去的；</li>
 *   <li><b>数据 getter</b>：旧 {@code ContainerData} 换成
 *       {@code GrindingMachineTile.addContainerTrackers} 的 Mek 同步通道后，菜单直接问 tile 即可。</li>
 * </ul>
 *
 * <p>玩家背包/快捷栏/副手也由 {@code MekanismContainer.addSlots()} 挂好，与其余 Mek 体系机器一致。</p>
 */
public final class ElectricGrindingMachineMenu extends MekanismTileContainer<GrindingMachineTile> {

    public static final int INV_TOP = 101;

    public ElectricGrindingMachineMenu(int containerId, Inventory inventory, GrindingMachineTile tile) {
        super(resolveContainer(), containerId, inventory, tile);
    }

    /**
     * 容器类型从注册表取回。
     *
     * <p>父类要求非空（{@code MekanismContainer} 用它做 {@code IContainerTracker} 的身份标识）。
     * 拿不到就抛出<b>指明根因</b>的异常，而不是把 null 传给父类。</p>
     */
    private static mekanism.common.registration.impl.ContainerTypeRegistryObject<ElectricGrindingMachineMenu> resolveContainer() {
        mekanism.common.registration.impl.ContainerTypeRegistryObject<ElectricGrindingMachineMenu> container =
                MekCkFactories.GRINDING_MACHINE_CONTAINER;
        if (container == null) {
            throw new IllegalStateException("电力研磨机容器尚未注册（GRINDING_MACHINE_CONTAINER == null）");
        }
        return container;
    }

    /**
     * 玩家背包首行 —— 覆写回迁移前的 {@value #INV_TOP}。
     *
     * <p>不覆写时用 Mek 默认的 84，屏幕的进度条与标签会压住背包首行。</p>
     */
    @Override
    protected int getInventoryYOffset() {
        return INV_TOP;
    }

    // ================== 读数：全部问 tile（两侧同一入口） ==================

    /** 进度百分比（0..100），屏幕画进度条用。 */
    public int getProgressPercent() {
        int total = getTileEntity().getProcessTime();
        return total == 0 ? 0 : getClientProgress() * 100 / total;
    }

    private int getClientProgress() {
        return getTileEntity().getClientProgress();
    }

    /** 存量（展示用 int；真实值是 Mek 的 FloatingLong）。 */
    public int getEnergy() {
        return (int) Math.min(Integer.MAX_VALUE, getTileEntity().getEnergyForDisplay());
    }

    public int getEnergyCapacity() {
        return (int) Math.min(Integer.MAX_VALUE, GrindingMachineTile.ENERGY_CAPACITY);
    }

    /** 方块坐标 —— 屏幕发网络包时要用。 */
    public net.minecraft.core.BlockPos getBlockPos() {
        return getTileEntity().getBlockPos();
    }

    /** 机器实例（屏幕/AE2 面板取用）。 */
    public GrindingMachineTile getMachine() {
        return getTileEntity();
    }
}
