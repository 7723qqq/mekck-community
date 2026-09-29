package cn.ism.mekck.client;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import mekanism.client.gui.IGuiWrapper;
import mekanism.client.gui.element.GuiElement;
import mekanism.client.gui.element.GuiElementHolder;
import mekanism.common.lib.Color;
import mekanism.client.render.MekanismRenderType;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/**
 * Mekanism 风格「支持的升级」条：展示全部升级类型图标，本机不支持的淡显。
 */
public class MekCkUpgradeTypesStrip extends GuiElement {

    private static final int ELEMENT_SIZE = 12;

    private final Set<MekCkUpgradeType> supported;

    public MekCkUpgradeTypesStrip(IGuiWrapper gui, int x, int y, List<MekCkUpgradeType> supportedTypes) {
        super(gui, x, y, 125, ELEMENT_SIZE + 2);
        this.supported = new HashSet<>(supportedTypes);
    }

    @Override
    public void drawBackground(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTicks) {
        super.drawBackground(guiGraphics, mouseX, mouseY, partialTicks);
        renderBackgroundTexture(guiGraphics, GuiElementHolder.HOLDER, GuiElementHolder.HOLDER_SIZE, GuiElementHolder.HOLDER_SIZE);
        int backgroundColor = Color.argb(GuiElementHolder.getBackgroundColor()).alpha(0.5).argb();
        MekCkUpgradeType[] all = MekCkUpgradeType.values();
        for (int i = 0; i < all.length; i++) {
            MekCkUpgradeType type = all[i];
            int xPos = relativeX + 1 + 55 + i * ELEMENT_SIZE;
            int yPos = relativeY + 1;
            ItemStack icon = type.getIcon();
            if (!icon.isEmpty()) {
                gui().renderItem(guiGraphics, icon, xPos, yPos, 0.75F);
            }
            if (!supported.contains(type)) {
                guiGraphics.fill(MekanismRenderType.MEK_GUI_FADE, xPos, yPos, xPos + ELEMENT_SIZE, yPos + ELEMENT_SIZE, backgroundColor);
            }
        }
    }

    @Override
    public void renderForeground(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        super.renderForeground(guiGraphics, mouseX, mouseY);
        drawTextWithScale(guiGraphics, Component.translatable("gui.mekck.upgrades_supported"),
                relativeX + 2, relativeY + 3, titleTextColor(), 0.6F);
    }

    @Override
    public void renderToolTip(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        super.renderToolTip(guiGraphics, mouseX, mouseY);
        MekCkUpgradeType[] all = MekCkUpgradeType.values();
        for (int i = 0; i < all.length; i++) {
            MekCkUpgradeType type = all[i];
            int xPos = getX() + 1 + 55 + i * ELEMENT_SIZE;
            int yPos = getY() + 1;
            if (mouseX >= xPos && mouseX < xPos + ELEMENT_SIZE && mouseY >= yPos && mouseY < yPos + ELEMENT_SIZE) {
                Component name = Component.translatable("gui.mekck.upgrade_type." + type.id);
                if (supported.contains(type)) {
                    displayTooltips(guiGraphics, mouseX, mouseY, name,
                            Component.translatable("gui.mekck.upgrade_info." + type.id));
                } else {
                    displayTooltips(guiGraphics, mouseX, mouseY,
                            Component.translatable("gui.mekck.upgrade_not_supported", name),
                            Component.translatable("gui.mekck.upgrade_info." + type.id));
                }
                break;
            }
        }
    }
}
