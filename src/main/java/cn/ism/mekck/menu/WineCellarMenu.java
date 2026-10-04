package cn.ism.mekck.menu;

import cn.ism.mekck.blockentity.WineCellarBlockEntity;
import cn.ism.mekck.menu.slot.MekCkSlots;
import cn.ism.mekck.registry.MekCkStandaloneMachines;
import cn.ism.mekck.util.PowerSlotUtil;
import mekanism.common.inventory.container.tile.MekanismTileContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

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

    /**
     * 玩家背包首行的 y —— <b>屏幕与菜单共用同一份</b>。
     *
     * <p>迁移前菜单自己把背包放在 103（旧 {@code WineCellarMenu.INV_TOP}）；迁到 Mek 容器后
     * 背包位置改由 {@code getInventoryYOffset()} 决定，而 {@code MekanismContainer} 默认
     * {@code BASE_Y_OFFSET = 84} —— 屏幕侧的进度条（88..98）与「Inventory」标签（94..103）
     * 于是压住背包首行。这里覆写回 103，屏幕侧引用同一个常量。</p>
     */
    public static final int INV_TOP = 103;

    /**
     * 升级槽个数 —— {@code MekanismTileContainer.addSlots()} 先挂升级输入/输出两槽
     * （javap 实测：{@code supportsUpgrades()} 为真时 {@code addSlot(upgradeSlot)} +
     * {@code addSlot(upgradeOutputSlot)}），之后才是 tile 的库存槽。
     * 本机方块已声明 {@code withSupportedUpgrades}，故恒为 2。
     */
    public static final int UPGRADE_SLOT_COUNT = 2;
    /** 存储格 0 在 {@code menu.slots} 里的下标（升级两槽之后）。 */
    public static final int STORAGE_SLOT_BASE = UPGRADE_SLOT_COUNT;
    /** 电源槽在 {@code menu.slots} 里的下标（9 个存储格之后）。 */
    public static final int POWER_SLOT_INDEX = UPGRADE_SLOT_COUNT + WineCellarBlockEntity.SLOT_POWER;
    /** 机器槽总数（升级 2 + 存储 9 + 电源 1）—— {@code quickMoveStack} 的分区边界。 */
    public static final int MACHINE_SLOT_COUNT = UPGRADE_SLOT_COUNT + WineCellarBlockEntity.TOTAL_SLOTS;

    public WineCellarMenu(int containerId, Inventory inventory, WineCellarBlockEntity tile) {
        super(resolveContainer(), containerId, inventory, tile);
    }

    /**
     * 玩家背包首行 —— 覆写回迁移前的 {@value #INV_TOP}。
     *
     * <p>不覆写时用 Mek 的默认 84，屏幕的进度条与标签会压住背包首行（见 {@link #INV_TOP}）。</p>
     */
    @Override
    protected int getInventoryYOffset() {
        return INV_TOP;
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
     * <p>槽位顺序由 Mek 的 {@code MekanismTileContainer.addSlots()} 决定：
     * <b>升级 2 → 存储 9 → 电源 1 → 背包</b>（javap 实测），所以电源槽下标是
     * {@code 2 + SLOT_POWER}，不是 {@code SLOT_POWER}（那是 tile 库存里的下标）。</p>
     */
    public int getPowerSlotIndex() {
        return POWER_SLOT_INDEX;
    }

    /**
     * 存储格在 {@code menu.slots} 里的下标（0..8），坐标见 {@link MekCkSlots.WineCellar}。
     *
     * <p>菜单不再自己算坐标——那是 tile 建槽时写死的，这里是<b>唯一</b>的坐标出处。
     * 下标要加上升级两槽的偏移：{@code menu.slots} 的前两格是升级输入/输出槽。</p>
     */
    public int getStorageSlotIndex(int storageIndex) {
        return STORAGE_SLOT_BASE + storageIndex;
    }

    /**
     * shift-click 路由 —— <b>恢复迁移前的「能量物品优先进电源槽」</b>。
     *
     * <p>迁移后走 Mek 默认的 {@code quickMoveStack}：它按 {@code inventoryContainerSlots}
     * 的顺序插入，而存储格排在电源槽之前 ⇒ 红石/能量立方会落进存储 0，永远到不了电源槽
     * （旧菜单的 {@code quickMoveStack} 是「能量物品 → 电源槽，其余 → 存储格」）。</p>
     *
     * <p>只拦「玩家背包 → 机器」的能量物品这一条，其余方向与其余物品全部交回
     * {@code super}（Mek 默认路由）—— 不复制它的槽序逻辑，升级卡等物品的落点与迁移后一致。</p>
     *
     * <p>能量物品判据用 {@link PowerSlotUtil#isValidEnergyItem}：<b>红石，或带 Forge 能量
     * capability 且能放/能充的能量容器物品</b>（javap 实测其实现：{@code isRedstone} 或
     * {@code stack.getCapability(ForgeCapabilities.ENERGY)} 的 {@code canExtract()/canReceive()}）。
     * 它与电源槽的准入谓词<b>同源</b>（{@code WineCellarBlockEntity.getInitialInventory} 的
     * {@code inputFiltered} 用的就是它），所以「shift-click 能进去的」与「槽允许放的」永远一致。</p>
     */
    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot slot = slots.get(index);
        if (slot != null && slot.hasItem() && index >= MACHINE_SLOT_COUNT
                && PowerSlotUtil.isValidEnergyItem(slot.getItem())) {
            ItemStack stack = slot.getItem();
            ItemStack copy = stack.copy();
            // 电源槽满时保持旧行为：不回落存储格，物品留在原处。
            if (!moveItemStackTo(stack, POWER_SLOT_INDEX, POWER_SLOT_INDEX + 1, false)) {
                return ItemStack.EMPTY;
            }
            if (stack.isEmpty()) {
                slot.set(ItemStack.EMPTY);
            } else {
                slot.setChanged();
            }
            return copy;
        }
        return super.quickMoveStack(player, index);
    }
}
