package cn.ism.mekck.client;

import cn.ism.mekck.machine.MekCkMachineTile;
import cn.ism.mekck.menu.MekCkFactoryLayout;
import mekanism.client.gui.GuiConfigurableTile;
import mekanism.client.gui.element.bar.GuiVerticalPowerBar;
import mekanism.client.gui.element.progress.GuiProgress;
import mekanism.client.gui.element.progress.ProgressType;
import mekanism.client.jei.MekanismJEIRecipeType;
import mekanism.common.inventory.container.tile.MekanismTileContainer;
import mekanism.common.inventory.warning.WarningTracker.WarningType;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraftforge.fml.ModList;

import java.util.function.DoubleSupplier;
import java.util.function.IntToDoubleFunction;

/**
 * 六个工厂屏幕（切菜 / 研磨 / 烧烤 / 种植切配 / 烹饪 / 穿串）共用的三块：
 * 机器名与背包标签、竖直能源条、进度条。
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
 * 旧 GUI 每个工厂屏都有一条竖直能源条（{@code GuiVerticalPowerBar}），迁到 Mek 体系时
 * 整条丢了——只剩侧栏的能量 tab。六个家族的位置与数据源完全一致，所以收在基类里一份。
 * 位置照上游 {@code GuiFactory} 的 {@code (imageWidth - 12, 16)}，不是旧自研 GUI 的 22。
 *
 * <h3>进度条为什么不覆写 {@code isActive()}</h3>
 * Mek 的 {@code IProgressInfoHandler.isActive()} <b>默认返回 {@code true}</b>，
 * 而 {@code GuiProgress.drawBackground} 在它为 false 时<b>连底图都不 blit</b>
 * ——不是画个空框，是整条不执行。Mek 自己的 {@code GuiFactory} 用两参 lambda
 * {@code new GuiProgress(() -> tile.getScaledProgress(1, i), ProgressType.DOWN, this, x, y)}
 * 构造，走的就是这个默认值 ⇒ <b>底图常驻，空闲时显示一条空槽</b>。
 *
 * <p>六个工厂屏此前都覆写成 {@code menu.isBusy()}（= 任一路进度 &gt; 0），
 * 于是机器空闲时进度条整条消失——玩家看到的就是「进度条丢了」。
 * 而且 {@code MekCkMachineTile.workCycle()} 每跑完一路会把那一路的进度
 * 归零一 tick，所以即便在连续生产，进度条也会每批次闪一下。</p>
 */
public abstract class MekCkFactoryScreenBase<TILE extends MekCkMachineTile,
        MENU extends MekanismTileContainer<TILE>> extends GuiConfigurableTile<TILE, MENU> {

    /** 能源条相对面板右边缘的偏移 —— 上游 {@code GuiFactory} 的 {@code imageWidth - 12}。 */
    private static final int ENERGY_BAR_RIGHT_INSET = 12;
    /** 能源条的 y —— 上游 {@code GuiFactory} 的 16（旧自研 GUI 用的是 22）。 */
    private static final int ENERGY_BAR_Y = 16;
    /** {@code ProgressType.DOWN} 的尺寸 —— 上游 {@code ProgressType} 的枚举参数就是 8×20。 */
    private static final int DOWN_BAR_WIDTH = 8;
    private static final int DOWN_BAR_HEIGHT = 20;
    /** 未装 JEI 时的分类数组 —— 见 {@link #jeiCategoriesOf}。 */
    private static final MekanismJEIRecipeType<?>[] NO_JEI_CATEGORIES = new MekanismJEIRecipeType<?>[0];
    /** {@code lane} 取此值表示「汇总条」：告警判据是「任一路有告警」而不是某一路。 */
    private static final int SUMMARY_LANE = -1;

    protected MekCkFactoryScreenBase(MENU menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        // 上游 GuiFactory / GuiExtraFactory 的构造器都写 titleLabelY = 4（原版默认是 6）。
        titleLabelY = 4;
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
                imageWidth - ENERGY_BAR_RIGHT_INSET, ENERGY_BAR_Y))
                // 能量告警 —— 上游 GuiFactory 同样把 NOT_ENOUGH_ENERGY 挂在这条竖条上
                // （.warning(WarningType.NOT_ENOUGH_ENERGY, tile.getWarningCheck(...))）。
                // 供给器读的是服务端算好、随容器同步下来的布尔位，客户端不做任何配方查找。
                .warning(WarningType.NOT_ENOUGH_ENERGY, tile::isNotEnoughEnergy);
    }

    /**
     * 「自动分选」标签页 —— 上游 {@code GuiFactory.addGuiElements} 的第一句就是
     * {@code addRenderableWidget(new GuiSortingTab(this, tile))}。
     *
     * <p>家族不支持分选时（见 {@link MekCkMachineTile#supportsSorting()}）不加这个标签页：
     * 一个点了没反应的按钮比没有按钮更糟。</p>
     */
    protected void addSortingTab() {
        if (tile.supportsSorting()) {
            addRenderableWidget(new MekCkSortingTab(this, tile));
        }
    }

    /**
     * 进度条 —— 一行式档位<b>每并行槽一条</b>，与上游逐条对齐。
     *
     * <p>上游 {@code GuiFactory.addGuiElements} 与 {@code GuiExtraFactory.addGuiElements}
     * 都是 {@code for (i < tier.processes) addProgress(new GuiProgress(..., ProgressType.DOWN, this,
     * 4 + baseX + i * baseXMult, 33))}：<b>N 条 8×20 的竖条</b>，各自贴在自己那一路槽位正下方。
     * 本模组此前只画一条 28×8 的 {@code SMALL_RIGHT} 箭头、水平居中于整行 —— 档位越高、
     * 槽位越多，这条居中的箭头离两端的槽就越远，与上游的差别也越明显。</p>
     *
     * <p><b>每条读自己那一路的进度</b>：{@code MekCkMachineTile.workCycle} 现在也是
     * 一路一个独立计时器（与上游 {@code TileEntityFactory.progress} 同构），
     * 所以这 N 条会各走各的，不再同涨同落。</p>
     *
     * <p>悬浮窗布局（&gt;17 并行）主面板上一个机器槽都没有，上游没有对应物；窗口里的格子是
     * 18px 密排的，塞不下 8×20 的竖条。所以那里主面板只留<b>一条汇总条</b>（取全部路的最大值），
     * 逐路进度改画在窗口里每个输入槽的格内（见 {@code MekCkSlotWindow.drawLaneBars}）。</p>
     */
    protected void addFactoryProgressBars(IntToDoubleFunction ratio) {
        if (MekCkFactoryLayout.usesSlotWindow(tile)) {
            // 悬浮窗布局：主面板上没有机器槽，逐路进度改画在窗口里每个输入槽的格内
            // （见 MekCkSlotWindow.drawLaneBars）。这里留一条汇总条，取全部路的最大值——
            // 原先取第 0 路，那是任选一路，与整机状态无关。
            addSingleProgressBar(tile::getMaxProgressRatio, SUMMARY_LANE, 0, imageWidth, 43);
            return;
        }
        int processes = MekCkFactoryLayout.processesOf(tile);
        for (int i = 0; i < processes; i++) {
            int index = i;
            addRenderableWidget(progressBar(() -> ratio.applyAsDouble(index), index,
                    MekCkFactoryLayout.oneRowProgressX(index, processes),
                    MekCkFactoryLayout.ONE_ROW_PROGRESS_Y));
        }
    }

    /**
     * 整机一次家族（烹饪 / 穿串）的进度条 —— 只有一路，画成 Mek 工厂同款的
     * {@code ProgressType.DOWN} 竖条（8×20），横向居中于
     * {@code gapLeft .. gapLeft + gapWidth} 这条空带。
     *
     * @param lane    这条进度条读哪一路的告警；{@link #SUMMARY_LANE} 表示汇总条
     * @param centerY 竖条纵向中心（调用方按自己那排槽的中线给）
     */
    protected void addSingleProgressBar(DoubleSupplier ratio, int lane, int gapLeft, int gapWidth, int centerY) {
        addRenderableWidget(progressBar(ratio, lane,
                gapLeft + (gapWidth - DOWN_BAR_WIDTH) / 2, centerY - DOWN_BAR_HEIGHT / 2));
    }

    /**
     * 造一条 {@code DOWN} 竖条并挂上 JEI 分类与逐路告警 —— 见 {@link #jeiCategoriesOf}。
     *
     * <p>分类为空时<b>不调</b> {@code jeiCategories}：那个方法会把字段设成传入的数组，
     * 空数组非 null，Mek 的 {@code GuiElementHandler} 只判 null ⇒ 会拿一个零长度的
     * 分类数组去建 JEI 点击区。不调则字段保持 null，点击区根本不建。</p>
     */
    private GuiProgress progressBar(DoubleSupplier ratio, int lane, int x, int y) {
        GuiProgress progress = new GuiProgress(ratio::getAsDouble, ProgressType.DOWN, this, x, y);
        // 逐路告警 —— 上游 GuiFactory 同样把 INPUT_DOESNT_PRODUCE_OUTPUT 挂在每条进度条上
        // （.warning(WarningType.INPUT_DOESNT_PRODUCE_OUTPUT, tile.getWarningCheck(..., cacheIndex))）。
        // 供给器读的是服务端算好、随容器同步下来的布尔位，客户端不做任何配方查找。
        progress.warning(WarningType.INPUT_DOESNT_PRODUCE_OUTPUT,
                lane == SUMMARY_LANE ? tile::hasAnyLaneWarning : () -> tile.hasLaneWarning(lane));
        MekanismJEIRecipeType<?>[] categories = jeiCategoriesOf(tile);
        if (categories.length > 0) {
            progress.jeiCategories(categories);
        }
        return progress;
    }

    /**
     * 本机进度条对应的 JEI 配方分类。
     *
     * <p><b>守卫不能省</b>：JEI 在 {@code mods.toml} 里不是依赖，未安装时
     * {@link MekCkFactoryJei} 引用的 {@code mezz.jei.*} 会 {@code NoClassDefFoundError}。
     * 守卫放在这里、被守卫的类单独一个，JVM 只在守卫通过时才解析它。</p>
     */
    private static MekanismJEIRecipeType<?>[] jeiCategoriesOf(MekCkMachineTile tile) {
        if (!ModList.get().isLoaded("jei")) {
            return NO_JEI_CATEGORIES;
        }
        return MekCkFactoryJei.categoriesOf(tile);
    }
}
