package cn.ism.mekck.client;

import cn.ism.mekck.menu.IUpgradeMenu;
import mekanism.client.gui.IGuiWrapper;
import mekanism.client.gui.element.GuiInnerScreen;
import mekanism.client.gui.element.progress.GuiProgress;
import mekanism.client.gui.element.progress.ProgressType;
import mekanism.client.gui.element.slot.GuiVirtualSlot;
import mekanism.client.gui.element.slot.SlotType;
import mekanism.client.gui.element.window.GuiWindow;
import mekanism.common.inventory.container.SelectedWindowData.WindowType;
import mekanism.common.inventory.container.slot.IVirtualSlot;
import mekanism.common.util.MekanismUtils;
import mekanism.common.util.MekanismUtils.ResourceType;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.Slot;

import java.util.ArrayList;
import java.util.List;

/**
 * Mekanism 风格升级弹窗（重写版）：
 * <ul>
 *   <li>左侧：可滚动的升级类型列表（按类型着色、带图标、可点击选择）；</li>
 *   <li>左下方：「支持的升级」类型条（不支持的淡显）；</li>
 *   <li>中间：内屏显示所选升级的名称 / 数量 / 效果；</li>
 *   <li>右侧：所选升级的实际槽位 + 安装进度条（数量/上限）与卸载进度条。</li>
 * </ul>
 * 视觉与交互参考 mekanism.client.gui.element.window.GuiUpgradeWindow。
 */
public class GuiUpgradeWindow extends GuiWindow {

    private final IUpgradeMenu menu;
    private final List<MekCkUpgradeType> types;
    private final MekCkUpgradeScrollList scrollList;
    private final GuiVirtualSlot selectedSlot;
    /** 当前选中升级类型对应的物品槽索引（-1 = 未选中）。 */
    private int selectedSlotIndex = -1;

    public GuiUpgradeWindow(IGuiWrapper gui, IUpgradeMenu menu) {
        super(gui, gui.getWidth() / 2 - 78, 15, 156, 88, WindowType.UPGRADE);
        this.menu = menu;
        interactionStrategy = InteractionStrategy.ALL;

        // 该机器支持的升级类型（按机器能力过滤：如急冻制冰机不支持速度升级）
        this.types = new ArrayList<>();
        if (menu.supportsSpeedUpgrade()) {
            types.add(MekCkUpgradeType.SPEED);
        }
        if (menu.supportsEnergyUpgrade()) {
            types.add(MekCkUpgradeType.ENERGY);
        }
        if (menu.hasStackUpgrade()) {
            types.add(MekCkUpgradeType.STACK);
        }
        if (menu.hasCreativeUpgrade()) {
            types.add(MekCkUpgradeType.CREATIVE);
        }
        if (menu.getGasUpgradeSlot() != null) {
            types.add(MekCkUpgradeType.GAS);
        }

        // 左侧：升级类型列表（66×50）
        scrollList = addChild(new MekCkUpgradeScrollList(gui, relativeX + 6, relativeY + 18, 66, 50, types, this));
        // 左下方：支持的升级类型条
        addChild(new MekCkUpgradeTypesStrip(gui, relativeX + 6, relativeY + 68, types));
        // 中间：信息内屏（59×50）
        addChild(new GuiInnerScreen(gui, relativeX + 72, relativeY + 18, 59, 50));
        // 右侧：安装/卸载进度条
        addChild(new GuiProgress(this::getInstallProgress, ProgressType.INSTALLING, gui, relativeX + 134, relativeY + 37));
        addChild(new GuiProgress(() -> 0, ProgressType.UNINSTALLING, gui, relativeX + 134, relativeY + 59));
        // 右侧：卸载按钮（点击卸载 1 个；按住 Shift 点击卸载全部）
        // arrow_down 位于 mekanism:gui/ 而非 gui/button/，不能走 getButtonLocation（否则缺纹理）
        if (menu.supportsUpgradeUninstall()) {
            addChild(new mekanism.client.gui.element.button.MekanismImageButton(gui,
                    relativeX + 133, relativeY + 42, 12,
                    MekanismUtils.getResource(ResourceType.GUI, "arrow_down.png"),
                    () -> uninstallSelected(),
                    getOnHover(() -> net.minecraft.network.chat.Component.translatable("tooltip.mekck.uninstall_upgrade"))));
        }
        // 右侧：所选升级类型的实际槽位（随选择切换）
        selectedSlot = addChild(new GuiVirtualSlot(SlotType.NORMAL, gui, relativeX + 133, relativeY + 18));
        onSelectionChanged();

        // 打开窗口时激活升级槽以便交互/渲染，关闭时由 close() 恢复
        menu.setUpgradePageActive(true);
    }

    public IUpgradeMenu getMenu() {
        return menu;
    }

    /** 列表选择变化时重绑右侧槽位。 */
    public void onSelectionChanged() {
        MekCkUpgradeType type = scrollList.getSelection();
        Slot slot = null;
        if (type != null) {
            slot = switch (type) {
                case SPEED -> menu.getSpeedUpgradeSlot();
                case ENERGY -> menu.getEnergyUpgradeSlot();
                case STACK -> menu.getStackUpgradeSlot();
                case CREATIVE -> menu.getCreativeUpgradeSlot();
                case GAS -> menu.getGasUpgradeSlot();
            };
        }
        selectedSlotIndex = slot == null ? -1 : slot.getSlotIndex();
        if (slot instanceof IVirtualSlot ivs) {
            selectedSlot.updateVirtualSlot(this, ivs);
        }
    }

    /** 卸载当前选中类型的升级（Shift 卸载全部）。 */
    private void uninstallSelected() {
        if (selectedSlotIndex < 0 || !menu.supportsUpgradeUninstall()) return;
        // 创造升级（0/1）走 mode 2 带槽号卸下，命中服务端 trackerForSlot/upgradeItemForSlot 的 SLOT_CREATIVE_UPGRADE 分支；
        // 速度/能量维持原 mode 0/1 行为不变。
        if (scrollList.getSelection() == MekCkUpgradeType.CREATIVE) {
            menu.uninstallUpgrade((byte) 2, selectedSlotIndex);
            return;
        }
        boolean all = net.minecraft.client.gui.screens.Screen.hasShiftDown();
        menu.uninstallUpgrade((byte) (all ? 1 : 0), selectedSlotIndex);
    }

    /** 安装读条进度（0~1）：优先显示真实安装进度，未实现读条时回退为「数量/上限」。 */
    private double getInstallProgress() {
        double real = menu.getUpgradeInstallProgress();
        if (real > 0.0) {
            return Math.min(1.0, real);
        }
        MekCkUpgradeType type = scrollList.getSelection();
        if (type == null) {
            return 0;
        }
        int max = type.getMax(menu);
        return max <= 0 ? 0 : Math.min(1.0, (double) type.getCount(menu) / max);
    }

    @Override
    public void renderForeground(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        super.renderForeground(guiGraphics, mouseX, mouseY);
        drawTitleText(guiGraphics, Component.translatable("gui.mekck.upgrades"), 5);

        MekCkUpgradeType type = scrollList.getSelection();
        if (type == null) {
            drawTextWithScale(guiGraphics, Component.translatable("gui.mekck.upgrade_no_selection"),
                    relativeX + 74, relativeY + 20, screenTextColor(), 0.7F);
            return;
        }
        drawTextWithScale(guiGraphics, Component.translatable("gui.mekck.upgrade_type." + type.id),
                relativeX + 74, relativeY + 20, 0xFF000000 | type.getColor().getColor().getValue(), 0.8F);
        drawTextWithScale(guiGraphics, Component.translatable("gui.mekck.upgrade_count",
                        type.getCount(menu), type.getMax(menu)),
                relativeX + 74, relativeY + 30, screenTextColor(), 0.6F);
        drawTextWithScale(guiGraphics, Component.translatable("gui.mekck.upgrade_info." + type.id),
                relativeX + 74, relativeY + 40, screenTextColor(), 0.6F);
    }

    @Override
    public void close() {
        super.close();
        menu.setUpgradePageActive(false);
    }
}
