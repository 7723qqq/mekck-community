package cn.ism.mekck.client;

import cn.ism.mekck.blockentity.BioreactorBlockEntity;
import cn.ism.mekck.menu.BioreactorMenu;
import java.util.List;
import java.util.Locale;
import mekanism.client.gui.GuiMekanism;
import mekanism.client.gui.element.bar.GuiBar.IBarInfoHandler;
import mekanism.client.gui.element.bar.GuiVerticalPowerBar;
import mekanism.client.gui.element.slot.GuiVirtualSlot;
import mekanism.client.gui.element.slot.SlotType;
import mekanism.client.gui.element.tab.GuiEnergyTab;
import mekanism.common.inventory.container.slot.IVirtualSlot;
import mekanism.common.inventory.container.slot.SlotOverlay;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

/**
 * 生物反应堆 GUI：
 * 4×4 输入格 + 单个有机物流体条 + 流体储罐槽（流体物品）+ 能源条 + 能源信息标签 + 能源物品槽。
 */
public final class BioreactorScreen extends GuiMekanism<BioreactorMenu> {
    // 4×4 输入格（与菜单槽位坐标一致）
    private static final int INPUT_X0 = 34;
    private static final int INPUT_Y0 = 16;
    private static final int INPUT_SPACING = 18;
    // 流体条（Mekanism STANDARD 26×64）
    private static final int FLUID_GAUGE_X = 118;
    private static final int FLUID_GAUGE_Y = 16;
    // 能源条（右侧）
    private static final int ENERGY_BAR_X_OFFSET = -12;
    private static final int ENERGY_BAR_Y = 22;
        // 能源槽
        private static final int POWER_SLOT_X = 6;
        private static final int POWER_SLOT_Y = 12;
        // 流体储罐槽（流体条下方）
        private static final int TANK_SLOT_X = 122;
        private static final int TANK_SLOT_Y = 82;

    public BioreactorScreen(BioreactorMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        imageWidth = 176;
        imageHeight = 184;
        inventoryLabelY = 90;
        dynamicSlots = true;
    }

    @Override
    protected void addGuiElements() {
        super.addGuiElements();

        // 4×4 输入格背景
        for (int row = 0; row < 4; row++) {
            for (int col = 0; col < 4; col++) {
                int x = INPUT_X0 + col * INPUT_SPACING;
                int y = INPUT_Y0 + row * INPUT_SPACING;
                GuiVirtualSlot vs = new GuiVirtualSlot(SlotType.INPUT, this, x, y);
                int slotIdx = row * 4 + col;
                if (slotIdx < menu.slots.size() && menu.slots.get(slotIdx) instanceof IVirtualSlot ivs) {
                    vs.updateVirtualSlot(null, ivs);
                }
                addRenderableWidget(vs);
            }
        }

        // 能源条（右侧）
        addRenderableWidget(new GuiVerticalPowerBar(this, new IBarInfoHandler() {
            @Override
            public Component getTooltip() {
                return Component.translatable("gui.mekck.energy",
                        menu.getEnergy(), menu.getEnergyCapacity());
            }

            @Override
            public double getLevel() {
                int capacity = menu.getEnergyCapacity();
                return capacity == 0 ? 0 : (double) menu.getEnergy() / capacity;
            }
        }, imageWidth + ENERGY_BAR_X_OFFSET, ENERGY_BAR_Y));

        // 能源信息标签（存储 + 实际发电速率）
        addRenderableWidget(new GuiEnergyTab(this, () -> List.of(
                Component.translatable("gui.mekck.energy_stored",
                        menu.getEnergy(), menu.getEnergyCapacity()),
                Component.translatable("gui.mekck.generating",
                        formatGenerating(menu.getGeneratingRate()))
        )));

        // 有机物流体条
        addRenderableWidget(new GuiCkFluidGauge(this,
                FLUID_GAUGE_X, FLUID_GAUGE_Y,
                () -> menu.getFluidStack(), () -> menu.getFluidCapacity()));

        // 能源槽（能量物品）
        GuiVirtualSlot powerVs = new GuiVirtualSlot(SlotType.POWER, this, POWER_SLOT_X, POWER_SLOT_Y);
        powerVs.with(SlotOverlay.POWER);
        if (menu.slots.get(BioreactorBlockEntity.POWER_SLOT) instanceof IVirtualSlot ivs) {
            powerVs.updateVirtualSlot(null, ivs);
        }
        addRenderableWidget(powerVs);

        // 流体储罐槽（流体物品 → 流体格）
        GuiVirtualSlot tankVs = new GuiVirtualSlot(SlotType.EXTRA, this, TANK_SLOT_X, TANK_SLOT_Y);
        if (menu.slots.get(BioreactorBlockEntity.TANK_SLOT) instanceof IVirtualSlot ivs) {
            tankVs.updateVirtualSlot(null, ivs);
        }
        addRenderableWidget(tankVs);

        // AE2 自动补料 / 网络拉料标签页（生物反应堆：燃料输入）。与其余 10 屏同一入口、
        // 同一左列坐标（62 / 90）——本屏左列没有侧配/下单，上方留白但位置全模组一致。
        if (cn.ism.mekck.client.NetworkPullButton.isVisible()) {
            for (var tab : cn.ism.mekck.client.NetworkPullButton.register(this, menu.getBlockPos())) {
                addRenderableWidget(tab);
            }
        }
    }

    @Override
    protected void drawForegroundText(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        renderTitleText(guiGraphics);
        drawString(guiGraphics, playerInventoryTitle, 8, inventoryLabelY, titleTextColor());
        super.drawForegroundText(guiGraphics, mouseX, mouseY);
    }

    @Override
    public void init() {
        super.init();
        // GUI 高度超出屏幕时贴顶显示，避免整体跑出屏幕外
        if (this.topPos < 0) {
            this.topPos = 0;
        }
    }

    /** 发电量显示：高于 1000 时以千为单位（x.xkfe/t），否则原样（xxxxfe/t）。 */
    private static String formatGenerating(int rate) {
        if (rate > 1000) {
            return String.format(Locale.ROOT, "%.1fkfe/t", rate / 1000.0);
        }
        return rate + "fe/t";
    }
}
