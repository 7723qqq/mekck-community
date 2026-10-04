package cn.ism.mekck.client;

import cn.ism.mekck.menu.MekCkFactoryLayout;
import mekanism.api.energy.IEnergyContainer;
import mekanism.client.gui.GuiConfigurableTile;
import mekanism.client.gui.element.bar.GuiVerticalPowerBar;
import mekanism.common.inventory.container.tile.MekanismTileContainer;
import mekanism.common.inventory.warning.WarningTracker.WarningType;
import mekanism.common.tile.base.TileEntityMekanism;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraftforge.fml.ModList;

import java.util.function.BooleanSupplier;

/**
 * <b>Mek 容器屏的公共基类</b> —— 工厂屏与单机屏共用的那几件事。
 *
 * <h3>为什么要有它（本类把「手绘」的根因消掉）</h3>
 * 本仓此前只有一个基类 {@link MekCkFactoryScreenBase}，它的类型参数
 * <b>绑死在 {@code MekCkMachineTile}</b>（带档位的工厂基类）上：
 *
 * <pre>
 *   public abstract class MekCkFactoryScreenBase&lt;TILE extends MekCkMachineTile, ...&gt;
 * </pre>
 *
 * 而 13 台单机是无档位的（tile 直接继承 {@code TileEntityConfigurableMachine}），
 * <b>继承不了这个基类</b> ⇒ 于是每个单机屏都只好把下面这几件事各自重写一遍：
 * <ul>
 *   <li>{@code drawForegroundText}（机器名 + 「Inventory」标签）—— <b>15 个屏各写了一份</b>；</li>
 *   <li>竖直能源条 —— 各写各的坐标与数据源；</li>
 *   <li>进度条的 {@code isActive()} 语义 —— 反复踩同一个坑。</li>
 * </ul>
 * 这就是「21 个屏里有 12 个在手绘」的结构性来源：<b>不是没人愿意复用，是复用的入口被类型参数挡住了。</b>
 *
 * <p>本类把「与档位无关」的那几件事收在这里，类型参数<b>只要求 Mek 的根 tile</b>，
 * 于是工厂（{@code MekCkMachineTile} 是它的子类）与单机（{@code TileEntityConfigurableMachine}
 * 也是它的子类）都能继承。</p>
 *
 * <h3>为什么机器名与背包标签必须自己画</h3>
 * Mek 的 {@code GuiMekanism.renderLabels} 覆写了原版
 * {@code AbstractContainerScreen.renderLabels} 且<b>不调 super</b>，所以原版那两行文字
 * 不会自动出现。Mek 自己的机器是在 {@code drawForegroundText} 里补回来的——实测
 * {@code GuiElectricMachine.drawForegroundText}（粉碎机用的那个）与
 * {@code GuiFactory.drawForegroundText} 的字节码<b>都是同两行</b>：
 * <pre>
 *   renderTitleText(GuiGraphics)
 *   drawString(playerInventoryTitle, inventoryLabelX, inventoryLabelY, titleTextColor())
 *   super.drawForegroundText(...)
 * </pre>
 * 而 {@code GuiConfigurableTile} / {@code GuiMekanismTile} <b>都没有</b>覆写它。
 * 换句话说：<b>不自己覆写，这两行字一个都不画。</b>
 *
 * <h3>背包标签的 x 必须与容器的槽位 x 同源</h3>
 * 背包槽的 x 由<b>容器</b>决定（{@code MekanismContainer.addInventorySlots} 读
 * {@code getInventoryXOffset()}），文字由<b>屏幕</b>决定。两边各算各的就会出现
 * 「文字在一个地方、槽在另一个地方」。所以这里走
 * {@link MekCkFactoryLayout#inventoryXOffset(int)} —— 纯函数，只依赖 {@code imageWidth}，
 * 与各菜单的覆写是同一个函数。
 */
public abstract class MekCkContainerScreenBase<
        TILE extends TileEntityMekanism & mekanism.common.tile.interfaces.ISideConfiguration,
        MENU extends MekanismTileContainer<TILE>> extends GuiConfigurableTile<TILE, MENU> {

    /** 能源条相对面板右边缘的偏移 —— 上游 {@code GuiFactory} 的 {@code imageWidth - 12}。 */
    private static final int ENERGY_BAR_RIGHT_INSET = 12;

    protected MekCkContainerScreenBase(MENU menu, net.minecraft.world.entity.player.Inventory inventory,
                                       net.minecraft.network.chat.Component title) {
        super(menu, inventory, title);
        // Mek 的 addSlots() 只在 dynamicSlots 为真时遍历 menu.slots 建槽 widget。
        // 不设 ⇒ 槽位一个都不显示，而背景照常画出来（本仓实测踩过）。
        dynamicSlots = true;
    }

    // ── 两行字 ──────────────────────────────────────────────────────────

    /**
     * 画机器名与「Inventory」标签 —— 与 Mek 自己的
     * {@code GuiElectricMachine} / {@code GuiFactory} 逐行同款。
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

    // ── 能源条 ──────────────────────────────────────────────────────────

    /**
     * 竖直能源条 —— 直接吃 tile 的能量容器（{@code GuiVerticalPowerBar} 有
     * {@code (IGuiWrapper, IEnergyContainer, int, int)} 构造器），存量/上限与 tooltip
     * 都由 Mek 自己组装，不必手写 Component。
     *
     * <p>位置取面板右侧 {@value #ENERGY_BAR_RIGHT_INSET}px、y=16（上游 {@code GuiFactory}
     * 的位置；旧自研 GUI 用的是 22）。</p>
     *
     * @param warning 能量告警供给器；传 {@code null} 表示不挂告警
     *                （无档位单机没有「能量不足」这个服务端标志位）
     */
    protected GuiVerticalPowerBar addEnergyBar(IEnergyContainer container, BooleanSupplier warning) {
        GuiVerticalPowerBar bar = addRenderableWidget(new GuiVerticalPowerBar(this, container,
                imageWidth - ENERGY_BAR_RIGHT_INSET, 16));
        if (warning != null) {
            bar.warning(WarningType.NOT_ENOUGH_ENERGY, warning);
        }
        return bar;
    }

    // ── 网络拉料 tab ────────────────────────────────────────────────────

    /**
     * 「自动补料 / 网络拉料」两枚 tab。
     *
     * <p>必须在各屏 {@code addGuiElements} 的<b>末尾</b>调用：Mek 的 {@code mouseClicked}
     * 对 {@code children()} 倒序遍历、命中即返回，越晚注册命中优先。
     * 未装 AE2 时 {@code isVisible()} 为假、什么都不挂。</p>
     */
    protected void addNetworkPullTabs(BlockPos pos) {
        if (NetworkPullButton.isVisible()) {
            for (var tab : NetworkPullButton.register(this, pos)) {
                addRenderableWidget(tab);
            }
        }
    }

    // ── JEI 守卫 ────────────────────────────────────────────────────────

    /**
     * JEI 是否已加载。
     *
     * <p><b>守卫不能省</b>：JEI 在 {@code mods.toml} 里不是依赖，未安装时任何
     * {@code mezz.jei.*} 的解析都会 {@code NoClassDefFoundError}。所以凡是要用到
     * JEI 类的地方，先过这个守卫，被守卫的类单独一个 —— JVM 只在守卫通过时才解析它。</p>
     */
    protected static boolean jeiLoaded() {
        return ModList.get().isLoaded("jei");
    }
}
