package cn.ism.mekck.client;

import cn.ism.mekck.CuttingMachineFactoryTier;
import cn.ism.mekck.machine.skewering.SkeweringFactoryTile;
import cn.ism.mekck.menu.SkeweringFactoryMenu;
import mekanism.api.math.FloatingLong;
import mekanism.client.gui.GuiConfigurableTile;
import mekanism.client.gui.element.progress.GuiProgress;
import mekanism.client.gui.element.progress.IProgressInfoHandler;
import mekanism.client.gui.element.progress.ProgressType;
import mekanism.client.gui.element.tab.GuiEnergyTab;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

/**
 * 穿串工厂 GUI（Mek 体系版）—— 阶段 3 Task 5。
 *
 * <h3>从 1359 行缩到一百来行</h3>
 * 与前四个家族同款：继承 {@link GuiConfigurableTile} 后 {@code super.addGuiElements()}
 * 一句就把 侧配 / 传输配置 / 升级 / 红石 / 安全 排好，坐标由 Mek 的常量决定；
 * 槽位 widget 由 {@code dynamicSlots = true} 从容器槽自动建，取的是 tile 侧
 * {@code getInitialInventory} / {@code appendExtraSlots} 里写进去的 x/y。
 * 旧界面手摆那 86 个 {@code GuiVirtualSlot} / {@code GuiSlot} 的原因也在这里：
 * 旧菜单的槽是原版 {@code Slot}，不是 {@code InventoryContainerSlot}，自动通路认不出来。</p>
 *
 * <h3>81 格存储的怪布局被原样保留</h3>
 * 存储分两块挂在面板<b>左右外侧</b>（左块 42 格 x = -130…-22，右块 39 格 x = 180…288），
 * 面板本体 176 宽。这是旧 GUI 在 480×270 下「81 格挂不下面板内」那次故障后的布局。
 * 沿用它的代价是<b>屏幕分辨率大 / GUI 缩放大时两块存储会被挤出可视区</b>——
 * 旧版就有这个问题，本轮不重新设计（重新设计要动槽位坐标，而槽位坐标是存档与
 * 界面共用的同一份数据）。真要修，见 {@code SkeweringFactoryTile} 的存储布局常量。
 *
 * <h3>本界面没有的面板（与前四个家族同款取舍）</h3>
 * <ul>
 *   <li><b>下单面板（本机 + ME 两套）</b>、<b>订单搜索框</b>、<b>订单进度条与取消按钮</b>：
 *       都是围绕旧 tile 的订单字段手绘的。订单<b>引擎</b>没丢——
 *       {@code SkeweringFactoryExecutor} 仍持有订单状态、仍按订单门禁配方匹配、
 *       仍推进完成计数；丢的只是这几块手绘 GUI，理由与切菜/研磨/烧烤一致：
 *       GUI 侧的订单面板等 {@code GuiConfigurableTile} 的 tab 布局定稿后统一接。</li>
 *   <li><b>「自选搭配」</b>：旧实现里 {@code customSelected} 只有读、没有增删，
 *       恒为空 ⇒ 那条分支本来就不可达（旧实现的已知死功能）。</li>
 *   <li><b>ME 拉料按钮</b>（{@code NetworkPullButton}）：旧 BE 实现的
 *       {@code INetworkPullable} 一并退役，AE2 侧改走 {@code IMekCkPorted} 端口声明。</li>
 * </ul>
 */
public final class SkeweringFactoryScreen extends GuiConfigurableTile<SkeweringFactoryTile, SkeweringFactoryMenu> {

    /** 面板宽：Mek 标准宽度，81 格存储挂在它左右外侧。 */
    private static final int PANEL_WIDTH = 176;
    /** 输入三格与输出两格之间的水平间隔，与 tile 侧 {@code GRID_GAP} 同值。 */
    private static final int GAP_BETWEEN = 30;
    /** 存储列起点，与 tile 侧存储布局常量同源。 */
    private static final int STORAGE_COLS = 7;
    private static final int STORAGE_LEFT_COUNT = 42;
    private static final int STORAGE_X_OFFSET = -(STORAGE_COLS * 18) - 4;
    private static final int STORAGE_Y = 62;
    private static final int STORAGE_RIGHT_X = PANEL_WIDTH + 4;

    public SkeweringFactoryScreen(SkeweringFactoryMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        CuttingMachineFactoryTier tier = tile.getTier();
        // 耗电 tooltip 用的每 tick 能耗来自等级，档位未知时给 0 而不是抛。
        imageWidth = PANEL_WIDTH;
        imageHeight = 184;
        inventoryLabelY = 89;
        dynamicSlots = true;
    }

    @Override
    protected void addGuiElements() {
        // 侧配 / 传输配置 / 升级 / 红石 / 安全 + 全部槽位 widget —— 一句 super 全排好。
        super.addGuiElements();

        CuttingMachineFactoryTier tier = tile.getTier();
        addRenderableWidget(new GuiEnergyTab(this, tile.getEnergyContainer(),
                () -> FloatingLong.create(tier == null ? 0 : tier.energyPerTick)));

        // 进度条：SMALL_RIGHT 箭头，横在输入三格与输出两格之间。
        int progressX = 38 + SkeweringFactoryTile.INPUT_SLOTS * 18 + (GAP_BETWEEN - 28) / 2;
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
    }

    @Override
    protected void renderBg(GuiGraphics guiGraphics, float partialTick, int mouseX, int mouseY) {
        super.renderBg(guiGraphics, partialTick, mouseX, mouseY);

        // 存储区只有槽位 widget（由 dynamicSlots 建在面板外侧），没有底板也没有标签——
        // 底板画在 panel 之外会被 renderBg 的 base.png 盖掉，所以标签跟着面板走。
        // 两块存储的标题贴在各自的左上角，让玩家知道屏外那两片是什么。
        int leftLabelX = leftPos + STORAGE_X_OFFSET;
        int rightLabelX = leftPos + STORAGE_RIGHT_X;
        int labelY = topPos + STORAGE_Y - 10;
        guiGraphics.drawString(font, "存储", leftLabelX, labelY, 0xFFAAAAAA);
        guiGraphics.drawString(font, "存储", rightLabelX, labelY, 0xFFAAAAAA);
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        super.render(guiGraphics, mouseX, mouseY, partialTick);

        // 当前订单进度：旧界面上是手绘的一行字 + 取消按钮。
        // 这里保留只读的那一行，取消按钮留空——本界面没有订单面板可取消，
        // 而一个点了没反应的按钮比没有按钮更糟。
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
