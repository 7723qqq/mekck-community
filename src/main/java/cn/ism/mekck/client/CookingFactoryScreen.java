package cn.ism.mekck.client;

import cn.ism.mekck.machine.cooking.CookingFactoryTile;
import cn.ism.mekck.menu.CookingFactoryMenu;
import mekanism.api.fluid.IExtendedFluidTank;
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
        extends MekCkFactoryScreenBase<CookingFactoryTile, CookingFactoryMenu> {

    /** 存储悬浮窗标签页（关闭窗口后需用同一实例重新激活，所以必须留引用）。 */
    private MekCkSlotWindowTab slotWindowTab;

    /** 输入方阵与输出方阵的水平间隔，与 tile 侧 {@code GRID_GAP} 同值。 */
    private static final int GAP_BETWEEN = 30;
    /** 输入 3 列 × 2 行。 */
    private static final int INPUT_COLS = CookingFactoryTile.INPUT_COLS;
    /** 面板宽，与旧 GUI 同值（144 格存储挂在它左右外侧）。 */
    private static final int PANEL_WIDTH = 204;
    /** 3 个流体条：横向排开，每个间隔 28px（与旧 GUI 的 {@code 40 + i * 28} 同值）。 */
    private static final int FLUID_GAUGE_X = 40;
    private static final int FLUID_GAUGE_STEP = 28;

    public CookingFactoryScreen(CookingFactoryMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        imageWidth = PANEL_WIDTH;
        // 烹饪的输入/输出格数与档位无关，面板高度是常量；与菜单侧的覆写同源。
        imageHeight = cn.ism.mekck.menu.MekCkFactoryLayout.cookingImageHeight();
        inventoryLabelY = cn.ism.mekck.menu.MekCkFactoryLayout.inventoryLabelY(imageHeight);
        dynamicSlots = true;
    }

    @Override
    protected void addGuiElements() {
        // 侧配 / 传输配置 / 升级 / 红石 / 安全 + 全部槽位 widget —— 一句 super 全排好。
        super.addGuiElements();

        // 第三个参数是「使用量」供给器 —— 上游 GuiFactory 传的是 tile::getLastUsage
        // （上一 tick 的真实扣电量），不是等级的声明值。
        addRenderableWidget(new GuiEnergyTab(this, tile.getEnergyContainer(), tile::getLastUsage));

        // 竖直能源条（旧 GUI 有、迁移时丢的那条），位置与数据源见基类。
        addEnergyBar();

        // 自动分选标签页（上游 GuiFactory 的第一句就是它）。
        addSortingTab();

        // 槽位悬浮窗标签页：只有高档工厂（>17 并行）或烹饪/穿串才有窗口槽，
        // 三组皆空时不加标签页（menu.windowSlots().isEmpty()）。
        if (!menu.windowSlots().isEmpty()) {
            slotWindowTab = addRenderableWidget(new MekCkSlotWindowTab(this, tile,
                    menu.windowSlots(), () -> slotWindowTab));
        }

        // 进度条：Mek 工厂同款的 DOWN 竖条（8×20）。烹饪是整机一次，只有一路。
        // 落在输入网格与输出网格之间的空带里，纵向对齐输入网格（2 行）的中线。
        addSingleProgressBar(() -> menu.getProgressRatio(0), 0,
                38 + INPUT_COLS * 18, GAP_BETWEEN, 41 + 2 * 18 / 2);

        // 3 个流体液位条：唯一 Mek 没有对应物、必须自己建的控件。
        // y 取自 MekCkFactoryLayout（面板高度公式按同一个常量保证条不压背包与标签）。
        for (int i = 0; i < CookingFactoryTile.FLUID_TANKS; i++) {
            int index = i;
            addRenderableWidget(new GuiCkFluidGauge(this, FLUID_GAUGE_X + index * FLUID_GAUGE_STEP,
                    cn.ism.mekck.menu.MekCkFactoryLayout.COOKING_FLUID_GAUGE_Y,
                    () -> fluidStackOf(index),
                    () -> capacityOf(index)));
        }

        // 自动补料 / 网络拉料 tab：必须最后注册（命中优先），见基类注释。
        addNetworkPullTabs();
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
        // 144 格存储已改为**悬浮窗虚拟槽**（见 CookingFactoryTile.extraSlotsForConfig 的注释），
        // 主面板上不再有它们的位置。此前这里在面板左右外侧各画一个「存储」标签，
        // 指向的是两块早已不存在的槽区——纯死 UI，已删。
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
            String text = Component.translatable("gui.mekck.ui.current_order", completed, qty).getString();
            int x = leftPos + 5;
            int y = topPos + 5;
            guiGraphics.fill(x, y, x + font.width(text) + 8, y + 14, 0xCC000000);
            guiGraphics.drawString(font, text, x + 4, y + 3, 0xFFFFFF00);
        }
    }
}
