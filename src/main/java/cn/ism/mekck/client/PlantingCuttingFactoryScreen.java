package cn.ism.mekck.client;

import cn.ism.mekck.CuttingMachineFactoryTier;
import cn.ism.mekck.machine.plantingcutting.PlantingCuttingFactoryTile;
import cn.ism.mekck.menu.PlantingCuttingFactoryMenu;
import mekanism.api.math.FloatingLong;
import mekanism.client.gui.GuiConfigurableTile;
import mekanism.client.gui.element.progress.GuiProgress;
import mekanism.client.gui.element.progress.IProgressInfoHandler;
import mekanism.client.gui.element.progress.ProgressType;
import mekanism.client.gui.element.tab.GuiEnergyTab;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

/**
 * 种植切配工厂 GUI（Mek 体系版）—— 阶段 3。
 *
 * <h3>从 535 行缩到几十行：省下的是什么</h3>
 * 与切菜、研磨同款：继承 {@link GuiConfigurableTile} 后
 * {@code super.addGuiElements()} 一句就把 侧配 / 传输配置 / 升级 / 红石 / 安全 排好，
 * 坐标由 Mek 的常量决定。槽位 widget 也不用手写——
 * {@code dynamicSlots = true} 让 {@code GuiMekanism.addSlots()} 遍历 {@code menu.slots}，
 * 对每个 {@code InventoryContainerSlot} 按其 {@code ContainerSlotType} 自动建 widget，
 * 坐标直接取容器槽的 x/y。
 *
 * <h3>本界面的两块额外高度</h3>
 * 营养液槽与生长土槽由 tile 的 {@code appendExtraSlots} 追加在方阵之后，
 * 本界面不额外排——它们跟着方阵的 y 一起长。
 */
public final class PlantingCuttingFactoryScreen
        extends GuiConfigurableTile<PlantingCuttingFactoryTile, PlantingCuttingFactoryMenu> {

    /** 输入方阵与输出方阵的水平间隔，与 tile 侧 {@code GRID_GAP} 同值。 */
    private static final int GAP_BETWEEN = 30;
    /** 额外槽（营养液 / 生长土）比方阵多占的高度：一行 18 px。 */
    private static final int EXTRA_SLOT_ROW = 18;

    public PlantingCuttingFactoryScreen(PlantingCuttingFactoryMenu menu, Inventory inventory,
                                       Component title) {
        super(menu, inventory, title);
        CuttingMachineFactoryTier tier = tile.getTier();
        int columns = tier == null ? 1 : (int) Math.ceil(Math.sqrt(tier.processes));
        int rows = tier == null ? 1 : (int) Math.ceil((double) tier.processes / columns);
        int extraHeight = Math.max(0, (rows - 2) * 18) + EXTRA_SLOT_ROW;
        imageWidth = 38 + columns * 18 + GAP_BETWEEN + columns * 18 + 20;
        imageHeight = 184 + extraHeight;
        inventoryLabelY = 89 + extraHeight;
        dynamicSlots = true;
    }

    @Override
    protected void addGuiElements() {
        // 侧配 / 传输配置 / 升级 / 红石 / 安全 + 全部槽位 widget —— 一句 super 全排好。
        super.addGuiElements();

        CuttingMachineFactoryTier tier = tile.getTier();
        addRenderableWidget(new GuiEnergyTab(this, tile.getEnergyContainer(),
                () -> FloatingLong.create(tier == null ? 0 : tier.energyPerTick)));

        int columns = tier == null ? 1 : (int) Math.ceil(Math.sqrt(tier.processes));
        int rows = tier == null ? 1 : (int) Math.ceil((double) tier.processes / columns);
        int progressX = 38 + columns * 18 + (GAP_BETWEEN - 28) / 2;
        int progressY = 41 + rows * 18 / 2 - 4;
        addRenderableWidget(new GuiProgress(new IProgressInfoHandler() {
            @Override
            public double getProgress() {
                return menu.getProgressRatio();
            }

            @Override
            public boolean isActive() {
                return menu.isBusy();
            }
        }, ProgressType.SMALL_RIGHT, this, progressX, progressY));
    }
}
