package cn.ism.mekck.client;

import cn.ism.mekck.CuttingMachineFactoryTier;
import cn.ism.mekck.machine.plantingcutting.PlantingCuttingFactoryTile;
import cn.ism.mekck.menu.PlantingCuttingFactoryMenu;
import mekanism.api.math.FloatingLong;
import mekanism.client.gui.GuiConfigurableTile;
import mekanism.client.gui.element.progress.GuiProgress;
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
        extends MekCkFactoryScreenBase<PlantingCuttingFactoryTile, PlantingCuttingFactoryMenu> {

    /**
     * 槽位悬浮窗标签页 —— 只有 &gt;17 并行的高档工厂才有窗口槽，
     * 所以本字段可能恒为 null（见 {@code menu.windowSlots().isEmpty()}）。
     * 关闭窗口后需要用同一实例重新激活，因此必须留引用。
     */
    private MekCkSlotWindowTab slotWindowTab;

    /** 输入方阵与输出方阵的水平间隔，与 tile 侧 {@code GRID_GAP} 同值。 */
    private static final int GAP_BETWEEN = 30;
    /** 额外槽（营养液 / 生长土）个数 —— 与 tile 侧 {@code appendExtraSlots} 同源。 */
    private static final int EXTRA_SLOTS = 2;

    public PlantingCuttingFactoryScreen(PlantingCuttingFactoryMenu menu, Inventory inventory,
                                       Component title) {
        super(menu, inventory, title);
        // 面板尺寸由 MekCkFactoryLayout 统一给出（屏幕与菜单共用同一份公式），见该类注释。
        // 营养液/生长土两格是「额外槽」，方阵分支要多留一行（gridExtraHeight = 18）。
        imageWidth = cn.ism.mekck.menu.MekCkFactoryLayout.gridFamilyPanelWidth(tile);
        imageHeight = cn.ism.mekck.menu.MekCkFactoryLayout.gridFamilyPanelHeight(tile, EXTRA_SLOTS, 0, 18);
        inventoryLabelY = cn.ism.mekck.menu.MekCkFactoryLayout.inventoryLabelY(imageHeight);
        dynamicSlots = true;
    }

    @Override
    protected void addGuiElements() {
        // 侧配 / 传输配置 / 升级 / 红石 / 安全 + 全部槽位 widget —— 一句 super 全排好。
        super.addGuiElements();

        CuttingMachineFactoryTier tier = tile.getTier();
        addRenderableWidget(new GuiEnergyTab(this, tile.getEnergyContainer(),
                () -> FloatingLong.create(tier == null ? 0 : tier.energyPerTick)));

        // 竖直能源条（旧 GUI 有、迁移时丢的那条），位置与数据源见基类。
        addEnergyBar();

        // 槽位悬浮窗标签页：只有高档工厂（>17 并行）或烹饪/穿串才有窗口槽，
        // 三组皆空时不加标签页（menu.windowSlots().isEmpty()）。
        if (!menu.windowSlots().isEmpty()) {
            slotWindowTab = addRenderableWidget(new MekCkSlotWindowTab(this, tile,
                    menu.windowSlots(), () -> slotWindowTab));
        }

        int progressX;
        int progressY;
        int processes = tier == null ? 1 : tier.processes;
        if (cn.ism.mekck.menu.MekCkFactoryLayout.usesSlotWindow(tile)) {
            progressX = (imageWidth - 28) / 2;
            progressY = 41;
        } else if (cn.ism.mekck.menu.MekCkFactoryLayout.useOneRow(processes)) {
            int rowWidth = (processes - 1) * cn.ism.mekck.menu.MekCkFactoryLayout.oneRowStep(processes) + 18;
            progressX = cn.ism.mekck.menu.MekCkFactoryLayout.oneRowBaseX(processes) + (rowWidth - 28) / 2;
            progressY = 33;
        } else {
            int columns = (int) Math.ceil(Math.sqrt(processes));
            int rows = (int) Math.ceil((double) processes / columns);
            progressX = 38 + columns * 18 + (GAP_BETWEEN - 28) / 2;
            progressY = 41 + rows * 18 / 2 - 4;
        }
        // isActive() 不覆写（Mek 默认 true，底图常驻）——理由见 MekCkFactoryScreenBase 类注释。
        addRenderableWidget(new GuiProgress(() -> menu.getProgressRatio(), ProgressType.SMALL_RIGHT, this, progressX, progressY));
    }
}
