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

    public SkeweringFactoryMenu(int containerId, Inventory inventory, SkeweringFactoryTile tile) {
        super(resolveContainer(tile), containerId, inventory, tile);
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
}
