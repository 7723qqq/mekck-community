package cn.ism.mekck.client;

import cn.ism.mekck.machine.MekCkMachineTile;
import cn.ism.mekck.menu.MekCkFactoryLayout;
import mekanism.client.gui.GuiConfigurableTile;
import mekanism.client.gui.element.bar.GuiVerticalPowerBar;
import mekanism.common.inventory.container.tile.MekanismTileContainer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

/**
 * 六个工厂屏幕（切菜 / 研磨 / 烧烤 / 种植切配 / 烹饪 / 穿串）共用的两块。
 *
 * <h3>为什么需要它：机器名与背包标签此前<b>一个都没画</b></h3>
 * Mek 的 {@code GuiMekanism.renderLabels} 覆写了原版 {@code AbstractContainerScreen.renderLabels}
 * 且<b>不调 super</b>，所以原版那两行文字（机器名 + 「Inventory」）不会自动出现。
 * Mek 自己的机器 GUI 是在 {@code drawForegroundText} 里补回来的——实测
 * {@code GuiFactory.drawForegroundText} 的字节码：
 * <pre>
 *   0: aload_0 / aload_1 / invokevirtual renderTitleText(GuiGraphics)V
 *   5: aload_0 / aload_1 / getfield playerInventoryTitle / getfield inventoryLabelX
 *     / getfield inventoryLabelY / invokevirtual titleTextColor()I / invokevirtual drawString(...)
 *  27: invokespecial GuiConfigurableTile.drawForegroundText(GuiGraphics, int, int)V
 * </pre>
 * 而 {@code GuiConfigurableTile} / {@code GuiMekanismTile} <b>都没有</b>覆写它
 * （{@code GuiMekanism.drawForegroundText} 的方法体就是 {@code return}）。
 * 六个工厂屏此前谁都没覆写 ⇒ 机器名与背包标签全部缺失。
 *
 * <h3>背包标签的 x 必须与菜单的 getInventoryXOffset() 同源</h3>
 * 背包槽的 x 由<b>容器</b>决定（{@code MekanismContainer.addInventorySlots} 读
 * {@code getInventoryXOffset()}），文字由<b>屏幕</b>决定。两边各算各的就会出现
 * 「文字在一个地方、槽在另一个地方」。所以这里走
 * {@link MekCkFactoryLayout#inventoryXOffset(int)}，与六个菜单的覆写是同一个函数。
 *
 * <h3>能源条为什么也在这里</h3>
 * 旧 GUI 每个工厂屏都有一条竖直能源条（{@code GuiVerticalPowerBar}，位置
 * {@code (imageWidth - 12, 22)}），迁到 Mek 体系时整条丢了——只剩侧栏的能量 tab。
 * 六个家族的位置与数据源完全一致，所以收在基类里一份。
 *
 * <h3>进度条为什么不覆写 {@code isActive()}</h3>
 * Mek 的 {@code IProgressInfoHandler.isActive()} <b>默认返回 {@code true}</b>，
 * 而 {@code GuiProgress.drawBackground} 在它为 false 时<b>连底图都不 blit</b>
 * ——不是画个空框，是整条不执行。Mek 自己的 {@code GuiFactory} 用两参 lambda
 * {@code new GuiProgress(() -> tile.getScaledProgress(1, i), ProgressType.DOWN, this, x, y)}
 * 构造，走的就是这个默认值 ⇒ <b>底图常驻，空闲时显示一条空槽</b>。
 *
 * <p>六个工厂屏此前都覆写成 {@code menu.isBusy()}（= {@code workProgress > 0}），
 * 于是机器空闲时进度条整条消失——玩家看到的就是「进度条丢了」。
 * 而且 {@code MekCkMachineTile.workCycle()} 每跑完一个批次会把 {@code workProgress}
 * 归零一 tick，所以即便在连续生产，进度条也会每批次闪一下。</p>
 */
public abstract class MekCkFactoryScreenBase<TILE extends MekCkMachineTile,
        MENU extends MekanismTileContainer<TILE>> extends GuiConfigurableTile<TILE, MENU> {

    /** 能源条相对面板右边缘的偏移（旧 GUI 的 {@code imageWidth - 12}）。 */
    private static final int ENERGY_BAR_RIGHT_INSET = 12;
    /** 能源条的 y（旧 GUI 的 22）。 */
    private static final int ENERGY_BAR_Y = 22;

    protected MekCkFactoryScreenBase(MENU menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
    }

    /**
     * 画机器名与背包标签 —— 见类注释。
     *
     * <p>子类若还要画自己的读数（温度、订单进度等），覆写本方法并<b>先调
     * {@code super.drawForegroundText(...)}</b>，否则这两行会消失。</p>
     */
    @Override
    protected void drawForegroundText(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        renderTitleText(guiGraphics);
        drawString(guiGraphics, playerInventoryTitle,
                MekCkFactoryLayout.inventoryXOffset(imageWidth), inventoryLabelY, titleTextColor());
        super.drawForegroundText(guiGraphics, mouseX, mouseY);
    }

    /**
     * 竖直能源条。
     *
     * <p>直接吃 tile 的能量容器（{@code GuiVerticalPowerBar} 有
     * {@code (IGuiWrapper, IEnergyContainer, int, int)} 这个构造器），
     * 存量/上限与 tooltip 都由 Mek 自己组装，不必像旧界面那样手写 Component。</p>
     *
     * <p>位置取面板右侧 {@value #ENERGY_BAR_RIGHT_INSET}px、y={@value #ENERGY_BAR_Y}：
     * 一行式布局的输入行在 y=13..31、输出行在 y=57..75，而最右一个槽的右边缘
     * 距面板右边缘至少 12px（{@code oneRowPanelWidth} 的余量），所以这条竖条
     * 与任何槽位都不重叠。</p>
     */
    protected void addEnergyBar() {
        addRenderableWidget(new GuiVerticalPowerBar(this, tile.getEnergyContainer(),
                imageWidth - ENERGY_BAR_RIGHT_INSET, ENERGY_BAR_Y));
    }
}
