package cn.ism.mekck.client;

import cn.ism.mekck.machine.MekCkMachineTile;
import cn.ism.mekck.menu.MekCkWindowSlotHolder;
import mekanism.client.gui.IGuiWrapper;
import mekanism.client.gui.element.tab.window.GuiWindowCreatorTab;
import mekanism.client.gui.element.window.GuiWindow;
import mekanism.client.render.MekanismRenderer;
import mekanism.common.inventory.container.SelectedWindowData;
import mekanism.common.util.MekanismUtils;
import mekanism.common.util.MekanismUtils.ResourceType;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * 「槽位」标签页 —— 点开 {@link MekCkSlotWindow}。
 *
 * <h3>照 Mek 的 {@code GuiUpgradeWindowTab} 写的</h3>
 * 同一个基类 {@link GuiWindowCreatorTab}，同样的「点击 → {@code createWindow()}
 * → {@code gui().addWindow(...)}」生命周期，所以关闭/重挂/再次激活都由 Mek 处理好。
 *
 * <h3>内容按容器实际情况分组</h3>
 * <ul>
 *   <li><b>高档工厂</b>（&gt;17 并行：烈焰炽焱 25 / 晶钛矩阵 36 / 星云塑造 49 / 奇点创世 81）：
 *       两组 —— 输入 9 列 + 输出 9 列，并排一页。<b>不翻页</b>。</li>
 *   <li><b>烹饪 / 穿串</b>：一组 —— 存储（144 / 81 格）。</li>
 *   <li>其余家族三组都空 ⇒ 屏幕不加这个标签页。</li>
 * </ul>
 */
public class MekCkSlotWindowTab extends GuiWindowCreatorTab<MekCkMachineTile, MekCkSlotWindowTab> {

    /** 标签页着色（ARGB）—— 青蓝色，与 Mek 自己的标签区分开。 */
    private static final int TAB_COLOR = 0xFF2E7D8F;

    private final List<MekCkSlotWindow.SlotGroup> groups;
    private final SelectedWindowData windowData;

    public MekCkSlotWindowTab(IGuiWrapper gui, MekCkMachineTile tile,
                              MekCkWindowSlotHolder slots,
                              Supplier<MekCkSlotWindowTab> elementSupplier) {
        // 图标复用 Mek 的 items.png（assets/mekanism/gui/items.png），不凭空造资源。
        // 右侧一列 y=99：分选 tab（62..97）之下、红石（137）之上。原先挂左列 90 ——
        // MekCkMachineTile 补实现 INetworkPullable 后，「自动补料(62)/网络拉料(90)」两枚
        // tab 进了左列（口径 §十 的左列预算表），左列已满，本 tab 按既定方案让位到右列。
        super(MekanismUtils.getResource(ResourceType.GUI, "items.png"), gui, tile,
                gui.getWidth(), 99, 26, 18, false, elementSupplier);
        this.groups = buildGroups(slots);
        // 窗口身份必须与槽里记的是同一个（槽在 tile 侧创建时就写死了）。
        // 三类槽（输入 / 输出 / 存储）共用一份身份：本标签页把非空的组放进同一扇窗，
        // 而 VirtualInventoryContainerSlot.exists(windowData) 是按身份过滤的——
        // 身份不一致就会出现「窗口里看得见、shift-click 却进不去」。
        this.windowData = MekCkMachineTile.SLOT_WINDOW;
    }

    private static List<MekCkSlotWindow.SlotGroup> buildGroups(MekCkWindowSlotHolder slots) {
        List<MekCkSlotWindow.SlotGroup> list = new ArrayList<>(2);
        if (!slots.inputs().isEmpty()) {
            list.add(new MekCkSlotWindow.SlotGroup(
                    Component.translatable("gui.mekck.slot_window.inputs"), slots.inputs()));
        }
        if (!slots.outputs().isEmpty()) {
            list.add(new MekCkSlotWindow.SlotGroup(
                    Component.translatable("gui.mekck.slot_window.outputs"), slots.outputs()));
        }
        if (!slots.storage().isEmpty()) {
            list.add(new MekCkSlotWindow.SlotGroup(
                    Component.translatable("gui.mekck.slot_window.storage"), slots.storage()));
        }
        return List.copyOf(list);
    }

    @Override
    public void renderToolTip(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        super.renderToolTip(guiGraphics, mouseX, mouseY);
        int total = groups.stream().mapToInt(g -> g.slots().size()).sum();
        displayTooltips(guiGraphics, mouseX, mouseY,
                Component.translatable("gui.mekck.slot_window.tab.tooltip", total));
    }

    @Override
    protected void colorTab(GuiGraphics guiGraphics) {
        MekanismRenderer.color(guiGraphics, TAB_COLOR);
    }

    @Override
    protected GuiWindow createWindow() {
        int windowWidth = MekCkSlotWindow.computeWidth(groups);
        int x = Math.max(0, (getGuiWidth() - windowWidth) / 2);
        return new MekCkSlotWindow(gui(), x, 15, groups, windowData);
    }
}
