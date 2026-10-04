package cn.ism.mekck.menu;

import cn.ism.mekck.blockentity.WineCellarBlockEntity;
import cn.ism.mekck.menu.slot.MekCkSlots;
import cn.ism.mekck.registry.MekCkStandaloneMachines;
import mekanism.common.inventory.container.tile.MekanismTileContainer;
import net.minecraft.world.entity.player.Inventory;

/**
 * 陈化窖（F20）菜单 —— Mek 体系版。
 *
 * <h3>本类为什么只剩这么点</h3>
 * 迁移前它有 190 行：9 个 {@code StoreSlot} + 1 个 {@code PowerSlot} 两个手写的
 * {@code SlotItemHandler implements IVirtualSlot} 私有类、逐格 {@code addSlot}、
 * 玩家背包槽、{@code quickMoveStack}、{@code stillValid}、以及 8 个直接从
 * {@code ContainerData} 读数的 getter。
 *
 * <p>其中绝大部分是<b>被自研体系逼出来的</b>，不是设计：</p>
 * <ul>
 *   <li><b>手写槽类</b>：{@code SlotItemHandler} 继承原版 {@code Slot}，Mek 的
 *       {@code GuiMekanism.addSlots()} 不认它（只对 {@code InventoryContainerSlot} 建 widget），
 *       所以每个槽都要手写一遍 {@code IVirtualSlot} 的 8 个方法，屏幕再用手画的
 *       {@code GuiVirtualSlot} 补一份渲染——<b>于是同一个槽在菜单与屏幕各有一套坐标，
 *       靠 ±1 凑合</b>。这正是本次重写的动因。</li>
 *   <li><b>逐格 addSlot</b>：Mek 的 {@code MekanismTileContainer.addSlots()} 会遍历
 *       {@code tile.getInventorySlots(null)} 自动装配，槽的坐标就是 tile 建槽时写进去的。</li>
 *   <li><b>数据 getter</b>：{@code addDataSlots(ContainerData)} 换成
 *       {@code WineCellarBlockEntity.addContainerTrackers} 的 Mek 同步通道后，
 *       菜单直接问 tile 即可——两侧同一入口，不必再经下标读 ContainerData。</li>
 * </ul>
 *
 * <p>玩家背包/快捷栏/副手也由 {@code MekanismContainer.addSlots()} 挂好，
 * 与其余 Mek 体系机器一致。</p>
 */
public final class WineCellarMenu extends MekanismTileContainer<WineCellarBlockEntity> {

    public WineCellarMenu(int containerId, Inventory inventory, WineCellarBlockEntity tile) {
        super(resolveContainer(), containerId, inventory, tile);
    }

    /**
     * 容器类型从注册表取回。
     *
     * <p>父类要求非空（{@code MekanismContainer} 用它做 {@code IContainerTracker} 的身份标识）。
     * 与 {@code CuttingMachineFactoryMenu#resolveContainer} 同款：拿不到就抛出<b>指明根因</b>的
     * 异常，而不是把 null 传给父类。</p>
     */
    private static mekanism.common.registration.impl.ContainerTypeRegistryObject<WineCellarMenu> resolveContainer() {
        mekanism.common.registration.impl.ContainerTypeRegistryObject<WineCellarMenu> container =
                MekCkStandaloneMachines.WINE_CELLAR_CONTAINER;
        if (container == null) {
            throw new IllegalStateException("陈化窖容器尚未注册（WINE_CELLAR_CONTAINER == null）");
        }
        return container;
    }

    // ================== 读数：全部问 tile（两侧同一入口） ==================

    /** 存量（展示用 int；真实值是 Mek 的 FloatingLong，见 tile）。 */
    public int getEnergy() {
        return (int) Math.min(Integer.MAX_VALUE, getTileEntity().getEnergyForDisplay());
    }

    public int getEnergyCapacity() {
        return (int) Math.min(Integer.MAX_VALUE, WineCellarBlockEntity.ENERGY_CAPACITY);
    }

    public int getSpeed() {
        return getTileEntity().getSpeed();
    }

    public int getActiveCount() {
        return getTileEntity().getActiveCount();
    }

    /** 第 slot 格进度百分比（-1 = 空格/非酒）。 */
    public int getSlotProgress(int slot) {
        return getTileEntity().progressPercent(slot);
    }

    /**
     * 方块坐标 —— 屏幕发网络包（如 {@code WineCellarConfigPacket}）时要用。
     *
     * <p>迁移前这个方法是菜单自己实现（直接问 machine）；现在问父类持有的 tile 即可，
     * 语义不变。</p>
     */
    public net.minecraft.core.BlockPos getBlockPos() {
        return getTileEntity().getBlockPos();
    }

    /**
     * 电源槽在 {@code menu.slots} 里的下标 —— 屏幕定位或诊断用。
     *
     * <p>槽位顺序由 tile 的 {@code getInitialInventory} 决定：9 个存储格在前、
     * 电源槽最后，因此下标恒为 {@link WineCellarBlockEntity#SLOT_POWER}。</p>
     */
    public int getPowerSlotIndex() {
        return WineCellarBlockEntity.SLOT_POWER;
    }

    /**
     * 存储格在 {@code menu.slots} 里的下标（0..8），坐标见 {@link MekCkSlots.WineCellar}。
     *
     * <p>菜单不再自己算坐标——那是 tile 建槽时写死的，这里是<b>唯一</b>的坐标出处。</p>
     */
    public int getStorageSlotIndex(int storageIndex) {
        return storageIndex;
    }
}
