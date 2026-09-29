package cn.ism.mekck.menu;

import cn.ism.mekck.UniversalCuttingMachine;
import cn.ism.mekck.machine.cooking.CookingFactoryTile;
import mekanism.common.inventory.container.tile.MekanismTileContainer;
import mekanism.common.registration.impl.ContainerTypeRegistryObject;
import net.minecraft.world.entity.player.Inventory;

/**
 * 烹饪工厂容器（Mek 体系版）—— 阶段 3 Task 7。
 *
 * <h3>整个类只剩一个构造器</h3>
 * 与前五个家族同款：{@code MekanismTileContainer.addSlots()} 已把全部槽位装配做完
 * （玩家背包 / 快捷栏 / 副手 → 升级槽与升级输出槽 → 遍历
 * {@code tile.getInventorySlots(null)} 逐个 {@code createContainerSlot()}）。
 * 槽位数量与坐标全由 tile 侧的 {@code getInitialInventory}（6 输入 + 9 产物 +
 * 3 返还 + 能量）与 {@code appendExtraSlots}（144 格存储）决定，
 * <b>所以本类一行槽位代码都不该有</b>。
 *
 * <h3>被删掉的那一大截</h3>
 * 旧实现是本项目里最复杂的一个菜单：487 行、20 个 {@code ContainerData} 索引常量
 * （含 3 组流体量/流体 id）、144 个存储槽的手写下标、以及一整套
 * {@code *Slot} 内部类。这些随旧 BE 一起删——下标本身在换成
 * {@code IInventorySlot} 对象引用之后就不存在了。
 */
public final class CookingFactoryMenu extends MekanismTileContainer<CookingFactoryTile> {

    public CookingFactoryMenu(int containerId, Inventory inventory, CookingFactoryTile tile) {
        super(resolveContainer(tile), containerId, inventory, tile);
    }

    private static ContainerTypeRegistryObject<CookingFactoryMenu> resolveContainer(CookingFactoryTile tile) {
        if (tile == null) {
            throw new IllegalStateException(
                    "烹饪工厂容器拿不到 tile：BlockTypeTile 的 tile Supplier 被过早求值，"
                            + "或方块与 tile 类型不匹配。");
        }
        ContainerTypeRegistryObject<CookingFactoryMenu> container = UniversalCuttingMachine.COOKING_FACTORY_CONTAINER;
        if (container == null) {
            throw new IllegalStateException("烹饪工厂容器尚未注册（COOKING_FACTORY_CONTAINER == null）");
        }
        return container;
    }

    // ── 给 GUI 读的转发 ──────────────────────────────────────────────────

    /**
     * 进度条：0.0~1.0。
     *
     * <p>旧实现是 {@code progress * 24 / max}，Mek 的 {@code ProgressType.SMALL_RIGHT}
     * 内部按 0~1 的比例画，所以这里直接给比例。分母走
     * {@code tile.getTicksPerWorkCycle()} 而不是写死 200：装速度卡后批次变短，
     * 进度条必须跟着变短才有正确的「越快越满」手感。</p>
     */
    public double getProgressRatio() {
        CookingFactoryTile tile = getTileEntity();
        if (tile == null) {
            return 0;
        }
        return tile.getWorkProgress() / (double) tile.getTicksPerWorkCycle();
    }

    public boolean isBusy() {
        CookingFactoryTile tile = getTileEntity();
        return tile != null && tile.isBusy();
    }

    /** 存量能量（FE）。 */
    public double getEnergy() {
        CookingFactoryTile tile = getTileEntity();
        return tile == null ? 0 : tile.getEnergyContainer().getEnergy().doubleValue();
    }

    /** 容量上限（FE）。 */
    public double getMaxEnergy() {
        CookingFactoryTile tile = getTileEntity();
        return tile == null ? 0 : tile.getEnergyContainer().getMaxEnergy().doubleValue();
    }

    public boolean hasOrder() {
        CookingFactoryTile tile = getTileEntity();
        return tile != null && tile.hasOrder();
    }

    public int getOrderQuantity() {
        CookingFactoryTile tile = getTileEntity();
        return tile == null ? 0 : tile.getOrderQuantity();
    }

    public int getOrderCompleted() {
        CookingFactoryTile tile = getTileEntity();
        return tile == null ? 0 : tile.getOrderCompleted();
    }
}
