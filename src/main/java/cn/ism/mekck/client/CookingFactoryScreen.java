package cn.ism.mekck.client;

import cn.ism.mekck.CuttingMachineFactoryTier;
import cn.ism.mekck.machine.cooking.CookingFactoryTile;
import cn.ism.mekck.menu.CookingFactoryMenu;
import mekanism.api.fluid.IExtendedFluidTank;
import mekanism.api.math.FloatingLong;
import mekanism.client.gui.GuiConfigurableTile;
import mekanism.client.gui.element.progress.GuiProgress;
import mekanism.client.gui.element.progress.IProgressInfoHandler;
import mekanism.client.gui.element.progress.ProgressType;
import mekanism.client.gui.element.tab.GuiEnergyTab;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraftforge.fluids.FluidStack;

/**
 * 烹饪工厂 GUI（Mek 体系版）—— 阶段 3 Task 7。
 *
 * <h3>面板尺寸沿用旧版而不是 Mek 的 176</h3>
 * {@code CookingFactoryTile} 的槽位坐标沿用旧 GUI 逐像素同值，其中 144 格存储
 * 挂在面板<b>左右外侧</b>（左 77 格 x 为负、右 67 格 x 超过面板宽）。面板本体
 * 因此保持旧版的 204 宽。旧布局曾是「12 列 × 12 行」宽高各 216px，在 GUI 缩放 4 /
 * 480×270 下整块排到屏幕外——2026 年的实测故障记录在旧菜单第 59-77 行。
 * <b>沿用旧布局的代价</b>：GUI 缩放大 / 屏幕小的时候两块存储会被挤出可视区，
 * 旧版就有这个问题，本轮不重新设计（槽位坐标是存档与界面共用的同一份数据）。
 *
 * <h3>本界面仍然手绘的唯一一块：3 个流体液位条</h3>
 * Mek 的 {@code GuiFluidTank} 绑死 {@code IExtendedFluidTank} 且由 Mek 的
 * 流体配置驱动，本机的罐不经过那条路，所以用自研的 {@link GuiCkFluidGauge}
 * （它直接吃 {@code Supplier<FluidStack>} + 容量，构造器与调用方式见该类）。
 * 数据源是 tile 自己的罐——Mek 自己会同步罐的液位，
 * <b>不再用旧实现那 7 个 {@code ContainerData} 同步整数</b>
 * （3 个 mb + 3 个流体 id + 1 个总量）。
 *
 * <h3>本界面没有的面板（与前五个家族同款取舍）</h3>
 * <ul>
 *   <li><b>下单面板（本机 + ME 两套）</b>、搜索框、数量输入、订单进度条与取消按钮：
 *       围绕旧 tile 的订单字段手绘。订单<b>引擎</b>没丢——
 *       {@code CookingFactoryExecutor} 仍持有订单状态、仍按订单门禁配方匹配、
 *       仍推进完成计数；丢的只是这几块手绘 GUI。</li>
 *   <li><b>ME 自动处理 / 自动分配 / AE2 拉料按钮</b>：与前五族同款，
 *       自动化改走 {@code IMekCkPorted} 端口声明。</li>
 *   <li><b>温度文本</b>：旧 BE 发热但<b>温度不参与任何运行判定</b>
 *       （注释原文「运行产热，不影响运行条件」），新 tile 没接
 *       {@code ITileHeatHandler} 的热容。显示一个没有数据源、也不影响任何行为的
 *       读数只会误导玩家。</li>
 * </ul>
 */
public final class CookingFactoryScreen
        extends GuiConfigurableTile<CookingFactoryTile, CookingFactoryMenu> {

    /** 输入方阵与输出方阵的水平间隔，与 tile 侧 {@code GRID_GAP} 同值。 */
    private static final int GAP_BETWEEN = 30;
    /** 输入 3 列 × 2 行。 */
    private static final int INPUT_COLS = CookingFactoryTile.INPUT_COLS;
    /** 输出 3 列 × 4 行（产物 3 行 + 返还 1 行）。 */
    private static final int OUTPUT_COLS = 3;
    /** 面板宽，与旧 GUI 同值（144 格存储挂在它左右外侧）。 */
    private static final int PANEL_WIDTH = 204;
    /** 3 个流体条：横向排开，每个间隔 28px（与旧 GUI 的 {@code 40 + i * 28} 同值）。 */
    private static final int FLUID_GAUGE_X = 40;
    private static final int FLUID_GAUGE_Y = 88;
    private static final int FLUID_GAUGE_STEP = 28;

    public CookingFactoryScreen(CookingFactoryMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        CuttingMachineFactoryTier tier = tile.getTier();
        int inputRows = tier == null ? 1 : (int) Math.ceil((double) CookingFactoryTile.INPUT_SLOTS / INPUT_COLS);
        int outputRows = (int) Math.ceil(
                (double) (CookingFactoryTile.PRODUCT_SLOTS + CookingFactoryTile.RETURN_SLOTS) / OUTPUT_COLS);
        int extraHeight = Math.max(0, Math.max(inputRows, outputRows) - 2) * 18;
        imageWidth = PANEL_WIDTH;
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

        // 进度条：SMALL_RIGHT 箭头，横在输入网格与输出网格之间。
        int inputGridWidth = INPUT_COLS * 18;
        int progressX = 38 + inputGridWidth + (GAP_BETWEEN - 28) / 2;
        int progressY = 41 + 18 / 2 - 4;
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

        // 3 个流体液位条：唯一 Mek 没有对应物、必须自己建的控件。
        for (int i = 0; i < CookingFactoryTile.FLUID_TANKS; i++) {
            int index = i;
            addRenderableWidget(new GuiCkFluidGauge(this, FLUID_GAUGE_X + index * FLUID_GAUGE_STEP,
                    FLUID_GAUGE_Y,
                    () -> fluidStackOf(index),
                    () -> capacityOf(index)));
        }
    }

    private FluidStack fluidStackOf(int index) {
        IExtendedFluidTank tank = tile.fluidTank(index);
        return tank == null ? FluidStack.EMPTY : tank.getFluid();
    }

    private int capacityOf(int index) {
        IExtendedFluidTank tank = tile.fluidTank(index);
        return tank == null ? 0 : tank.getCapacity();
    }

    @Override
    protected void renderBg(GuiGraphics guiGraphics, float partialTick, int mouseX, int mouseY) {
        super.renderBg(guiGraphics, partialTick, mouseX, mouseY);

        // 144 格存储只有槽位 widget（由 dynamicSlots 建在面板外侧）、没有底板也没有标签。
        // 底图画在 panel 之外会被 renderBg 的 base.png 盖掉，所以标签跟着面板走。
        int labelY = topPos + 34 - 10;
        guiGraphics.drawString(font, "存储", leftPos - 130, labelY, 0xFFAAAAAA);
        guiGraphics.drawString(font, "存储", leftPos + 204 + 4, labelY, 0xFFAAAAAA);
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        super.render(guiGraphics, mouseX, mouseY, partialTick);

        // 当前订单进度：旧界面上是手绘的一行字 + 取消按钮。这里只保留只读那一行——
        // 取消按钮要发网络包，而本界面没有订单面板可取消，
        // 一个点了没反应的按钮比没有按钮更糟。
        if (menu.hasOrder()) {
            int qty = menu.getOrderQuantity();
            int completed = Math.min(menu.getOrderCompleted(), qty);
            String text = "当前订单: " + completed + "/" + qty;
            int x = leftPos + 5;
            int y = topPos + 5;
            guiGraphics.fill(x, y, x + font.width(text) + 8, y + 14, 0xCC000000);
            guiGraphics.drawString(font, text, x + 4, y + 3, 0xFFFFFF00);
        }
    }
}
