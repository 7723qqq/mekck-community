package cn.ism.mekck.client;

import cn.ism.mekck.machine.cutting.UniversalCuttingMachineTile;
import cn.ism.mekck.menu.UniversalCuttingMachineMenu;
import mekanism.client.gui.GuiConfigurableTile;
import mekanism.client.gui.element.GuiUpArrow;
import mekanism.client.gui.element.bar.GuiVerticalPowerBar;
import mekanism.client.gui.element.progress.GuiProgress;
import mekanism.client.gui.element.progress.IProgressInfoHandler;
import mekanism.client.gui.element.progress.ProgressType;
import mekanism.client.gui.element.tab.GuiEnergyTab;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

import java.util.List;

/**
 * 切菜机 GUI（Mek 体系版）—— 第四轮从自研 Screen 换成 {@link GuiConfigurableTile}。
 *
 * <h3>换体系省下的代码</h3>
 * 继承 {@link GuiConfigurableTile} 后 {@code super.addGuiElements()} 一句就把
 * 侧配 / 传输配置 / 升级 / 红石 / 安全排好，坐标由 Mek 的常量决定。
 *
 * <p>槽位 widget 也不用手写：{@code dynamicSlots = true} 让 {@code GuiMekanism.addSlots()}
 * 遍历 {@code menu.slots}，对每个 {@code InventoryContainerSlot} 按其
 * {@code ContainerSlotType} 自动建 {@code GuiSlot}，坐标直接取容器槽的 x/y ——
 * 而那两个值又是 tile 侧 {@code getInitialInventory} 排槽时写进去的。</p>
 *
 * <h3>随之作废的三块（换体系的必然结果）</h3>
 * <ul>
 *   <li><b>自研侧配 tab</b>（{@code MekCkTabElement}）—— 由 Mek 的
 *       {@code GuiSideConfigurationTab} + {@code TileComponentConfig} 取代；</li>
 *   <li><b>自研升级窗口</b>（{@code GuiUpgradeWindow} + {@code IUpgradeMenu}）——
 *       由 {@code GuiUpgradeWindowTab} + {@code TileComponentUpgrade} 取代，
 *       连安装读条都是 Mek 自带的；</li>
 *   <li><b>自研能量条</b>（读 {@code ContainerData}）—— 由 Mek 的
 *       {@code GuiVerticalPowerBar} 直接吃 tile 的能量容器取代。</li>
 * </ul>
 *
 * <p>槽位坐标<b>逐字沿用</b>旧屏（见 tile 的常量声明）：输入 38,41 / 输出 56,41 /
 * 升级 40,46 与 40,72 / 电源 7,13 —— 换坐标玩家会找不到位置，
 * 而这类改动<b>不产生任何报错</b>。</p>
 */
public final class UniversalCuttingMachineScreen
        extends GuiConfigurableTile<UniversalCuttingMachineTile, UniversalCuttingMachineMenu> {

    public UniversalCuttingMachineScreen(UniversalCuttingMachineMenu menu, Inventory inventory,
                                          Component title) {
        super(menu, inventory, title);
        // 面板尺寸与 Mek 基础电力熔炼炉（GuiElectricMachine）一致：176×166。
        imageWidth = 176;
        imageHeight = 166;
        // 玩家背包首行：Mek 默认 84，与旧菜单同值；inventoryLabelY 必须显式对齐，
        // 否则「Inventory」标签会浮在背包上方 12px。
        inventoryLabelY = 84;
        dynamicSlots = true;
    }

    @Override
    protected void addGuiElements() {
        // 侧配 / 传输配置 / 升级 / 红石 / 安全 + 全部槽位 widget —— 一句 super 全排好。
        super.addGuiElements();

        // 上箭头：Mek 基础电力熔炼炉 GuiUpArrow(68,38)。
        addRenderableWidget(new GuiUpArrow(this, 68, 38));

        // 能源条：Mek 基础电力熔炼炉 GuiVerticalPowerBar(164,15)。直接吃 tile 的能量容器，
        // 存量/上限与 tooltip 都由 Mek 自己组装。
        addRenderableWidget(new GuiVerticalPowerBar(this, tile.getEnergyContainer(), 164, 15));

        // 进度条：分子 0..24，与旧屏同口径。
        addRenderableWidget(new GuiProgress(new IProgressInfoHandler() {
            @Override
            public double getProgress() {
                return menu.getProgress() / 24.0;
            }

            @Override
            public boolean isActive() {
                return menu.getProgress() > 0;
            }
        }, ProgressType.BAR, this, 86, 38));

        // 能量 tab：Mek 固定在左列 (x=-26, y=137, 26, 26)，与侧配/传输配置同列不冲突。
        addRenderableWidget(new GuiEnergyTab(this, () -> List.of(
                Component.translatable("gui.mekck.energy_stored", menu.getEnergy(), menu.getMaxEnergy()),
                Component.translatable("gui.mekck.energy_per_tick",
                        UniversalCuttingMachineTile.ENERGY_PER_TICK))));
    }
}
