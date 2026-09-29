package cn.ism.mekck.menu;

import cn.ism.mekck.UniversalCuttingMachine;
import cn.ism.mekck.machine.plantingcutting.PlantingCuttingFactoryTile;
import mekanism.common.inventory.container.tile.MekanismTileContainer;
import mekanism.common.registration.impl.ContainerTypeRegistryObject;
import net.minecraft.world.entity.player.Inventory;

/**
 * 种植切配工厂容器（Mek 体系版）—— 阶段 3。
 *
 * <h3>为什么整个类只剩一个构造器</h3>
 * 与切菜、研磨同款：{@code MekanismTileContainer.addSlots()} 已把全部槽位装配做完
 * （玩家背包 / 快捷栏 / 副手 → 升级槽与升级输出槽 → 遍历
 * {@code tile.getInventorySlots(null)} 逐个 {@code createContainerSlot()}）。
 * 槽位数量与坐标全由 tile 侧的 {@code getInitialInventory} 决定，
 * <b>所以本类一行槽位代码都不该有</b>。
 *
 * <h3>被删掉的那一大截下标逻辑</h3>
 * 旧实现靠 {@code speedUpgradeSlot = 2 * inputSlots} 这类手写下标拼槽位，
 * 并用 {@code getInputSlotRange()} / {@code getExtraInputSlots()} 表达
 * 「0..4 与 10..13 两段不连续」这类区间。换成 {@code IInventorySlot} 对象引用之后，
 * 下标本身消失了，这些补偿逻辑一并作废。
 *
 * <h3>三个构造器变一个</h3>
 * 旧类有 {@code (int, Inventory, FriendlyByteBuf)} / {@code (int, Inventory, BE)} /
 * {@code (int, Inventory, BE, ContainerData)} 三个入口，以及一整页
 * {@code ContainerData} 索引常量（{@code DATA_PROGRESS} 等）。现在：
 * <ul>
 *   <li>没有 {@code FriendlyByteBuf} 入口——Mek 的容器工厂是 {@code (int, Inventory, TILE)}；</li>
 *   <li>没有 {@code ContainerData}——进度 / 能量 / 红石 / 升级数不再走 11 个同步整数。</li>
 * </ul>
 */
public final class PlantingCuttingFactoryMenu extends MekanismTileContainer<PlantingCuttingFactoryTile> {

    public PlantingCuttingFactoryMenu(int containerId, Inventory inventory, PlantingCuttingFactoryTile tile) {
        super(resolveContainer(tile), containerId, inventory, tile);
    }

    private static ContainerTypeRegistryObject<PlantingCuttingFactoryMenu> resolveContainer(
            PlantingCuttingFactoryTile tile) {
        if (tile == null) {
            throw new IllegalStateException(
                    "种植切配工厂容器拿不到 tile：BlockTypeTile 的 tile Supplier 被过早求值，"
                            + "或方块与 tile 类型不匹配。");
        }
        ContainerTypeRegistryObject<PlantingCuttingFactoryMenu> container =
                UniversalCuttingMachine.PLANTING_CUTTING_CONTAINER;
        if (container == null) {
            throw new IllegalStateException("种植切配工厂容器尚未注册（PLANTING_CUTTING_CONTAINER == null）");
        }
        return container;
    }

    // ── 给 GUI 读的转发 ──────────────────────────────────────────────────

    /** 进度条：0.0~1.0。分母随速度卡变化，装卡后进度条必须跟着变短。 */
    public double getProgressRatio() {
        PlantingCuttingFactoryTile tile = getTileEntity();
        if (tile == null) {
            return 0;
        }
        return tile.getWorkProgress() / (double) tile.getTicksPerWorkCycle();
    }

    public boolean isBusy() {
        PlantingCuttingFactoryTile tile = getTileEntity();
        return tile != null && tile.isBusy();
    }

    /** 存量能量（FE）。 */
    public double getEnergy() {
        PlantingCuttingFactoryTile tile = getTileEntity();
        return tile == null ? 0 : tile.getEnergyContainer().getEnergy().doubleValue();
    }

    /** 容量上限（FE）。 */
    public double getMaxEnergy() {
        PlantingCuttingFactoryTile tile = getTileEntity();
        return tile == null ? 0 : tile.getEnergyContainer().getMaxEnergy().doubleValue();
    }

    /** 营养液存量（mB，夹到 int 上界）。 */
    public int getNutrientCount() {
        PlantingCuttingFactoryTile tile = getTileEntity();
        return tile == null ? 0 : tile.getNutrientCount();
    }
}
