package cn.ism.mekck.client;

import cn.ism.mekck.machine.cutting.UniversalCuttingMachineTile;
import cn.ism.mekck.menu.MekCkFactoryLayout;
import cn.ism.mekck.menu.UniversalCuttingMachineMenu;
import mekanism.client.gui.GuiConfigurableTile;
import mekanism.client.gui.element.GuiUpArrow;
import mekanism.client.gui.element.bar.GuiVerticalPowerBar;
import mekanism.client.gui.element.progress.GuiProgress;
import mekanism.client.gui.element.progress.IProgressInfoHandler;
import mekanism.client.gui.element.progress.ProgressType;
import mekanism.client.gui.element.tab.GuiEnergyTab;
import net.minecraft.client.gui.GuiGraphics;
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
        // 「Inventory」标签必须在玩家背包首行**之上**。
        //
        // 本菜单继承 MekanismTileContainer 且**不覆写** getInventoryYOffset()，
        // 背包首行即 Mek 的 BASE_Y_OFFSET = 84（实测 MekanismContainer.addInventorySlots
        // 的字节码：槽位 y = getInventoryYOffset() + row*18，x = getInventoryXOffset() + col*18，
        // 两者的默认值分别是 84 与 8）。
        //
        // ⚠️ 此前这里写的是 84，也就是把标签画在**第一行背包槽的正上方**——
        // 标签高 9px、槽高 18px，两者直接压在一起。原注释把「标签在背包上方 12px」
        // 当成了要修的问题，于是把标签移到 84；实际那 12px 恰恰是正确的间距
        // （原版就是 imageHeight-94 配 imageHeight-84）。现已改为 84-10 = 74。
        inventoryLabelY = MekCkFactoryLayout.INVENTORY_LABEL_Y;
        dynamicSlots = true;
    }

    /**
     * 画机器名与背包标签。
     *
     * <h3>为什么必须补这一段</h3>
     * Mek 的 {@code GuiMekanism.renderLabels} 覆写了原版
     * {@code AbstractContainerScreen.renderLabels} 且<b>不调 super</b>，
     * 而 {@code GuiConfigurableTile} / {@code GuiMekanismTile} 都<b>没有</b>覆写
     * {@code drawForegroundText}（实测其方法体就是 {@code return}）。
     * 于是本屏此前<b>机器名与「Inventory」两行都不显示</b>——
     * 设置了 {@code inventoryLabelY} 也没有任何代码去读它。
     *
     * <p>六个工厂屏的同类问题已由 {@link MekCkFactoryScreenBase} 统一补上；
     * 本屏直接继承 {@code GuiConfigurableTile}，不在那条继承链上，需自带一份。</p>
     */
    @Override
    protected void drawForegroundText(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        renderTitleText(guiGraphics);
        drawString(guiGraphics, playerInventoryTitle,
                MekCkFactoryLayout.INVENTORY_X_OFFSET, inventoryLabelY, titleTextColor());
        super.drawForegroundText(guiGraphics, mouseX, mouseY);
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
