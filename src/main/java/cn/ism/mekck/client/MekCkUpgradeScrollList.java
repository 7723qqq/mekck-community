package cn.ism.mekck.client;

import java.util.List;
import java.util.function.ObjIntConsumer;
import mekanism.client.gui.IGuiWrapper;
import mekanism.client.gui.element.GuiElementHolder;
import mekanism.client.gui.element.scroll.GuiScrollList;
import mekanism.client.render.MekanismRenderer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.NotNull;

/**
 * Mekanism 风格升级类型列表：可滚动、可点击选择，行背景用 mekanism 的 upgrade_selection 贴图并按类型着色。
 * 数据来自 {@link MekCkUpgradeType} 列表与 {@link GuiUpgradeWindow}（读取数量/槽位）。
 */
public class MekCkUpgradeScrollList extends GuiScrollList {

    // 注意：Mekanism 1.20.1 的 GUI 纹理位于 assets/mekanism/gui/（不是 textures/gui/），
    // 原路径会解析为缺失纹理（紫黑格），此处改用 MekanismUtils.getResource（与其它屏幕一致）。
    private static final ResourceLocation UPGRADE_SELECTION = mekanism.common.util.MekanismUtils
            .getResource(mekanism.common.util.MekanismUtils.ResourceType.GUI, "upgrade_selection.png");
    private static final int TEXTURE_WIDTH = 58;
    private static final int TEXTURE_HEIGHT = 36;

    private final List<MekCkUpgradeType> types;
    private final GuiUpgradeWindow owner;
    private int selectedIndex = -1;

    public MekCkUpgradeScrollList(IGuiWrapper gui, int x, int y, int width, int height,
                                  List<MekCkUpgradeType> types, GuiUpgradeWindow owner) {
        super(gui, x, y, width, height, TEXTURE_HEIGHT / 3, GuiElementHolder.HOLDER, GuiElementHolder.HOLDER_SIZE);
        this.types = types;
        this.owner = owner;
        if (!types.isEmpty()) {
            selectedIndex = 0;
        }
    }

    @Override
    protected int getMaxElements() {
        return types.size();
    }

    @Override
    public boolean hasSelection() {
        return selectedIndex >= 0 && selectedIndex < types.size();
    }

    @javax.annotation.Nullable
    public MekCkUpgradeType getSelection() {
        return hasSelection() ? types.get(selectedIndex) : null;
    }

    @Override
    protected void setSelected(int index) {
        if (index >= 0 && index < types.size() && selectedIndex != index) {
            selectedIndex = index;
            owner.onSelectionChanged();
        }
    }

    @Override
    public void clearSelection() {
        if (selectedIndex != -1) {
            selectedIndex = -1;
            owner.onSelectionChanged();
        }
    }

    @Override
    protected void renderElements(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTicks) {
        // 行背景：选中=第2帧，悬停=第0帧，普通=第1帧；整体按升级类型颜色着色
        forEachType((type, multiplied) -> {
            int shiftedY = getY() + 1 + multiplied;
            int frame = 1;
            if (type == getSelection()) {
                frame = 2;
            } else if (mouseX >= getX() + 1 && mouseX < getX() + barXShift - 1
                    && mouseY >= shiftedY && mouseY < shiftedY + elementHeight) {
                frame = 0;
            }
            MekanismRenderer.color(guiGraphics, type.getColor());
            guiGraphics.blit(UPGRADE_SELECTION, relativeX + 1, relativeY + 1 + multiplied,
                    0, elementHeight * frame, TEXTURE_WIDTH, elementHeight, TEXTURE_WIDTH, TEXTURE_HEIGHT);
            MekanismRenderer.resetColor(guiGraphics);
        });
        // 图标单独循环渲染（避免贴图绑定干扰）
        forEachType((type, multiplied) -> {
            ItemStack icon = type.getIcon();
            if (!icon.isEmpty()) {
                gui().renderItem(guiGraphics, icon, relativeX + 3, relativeY + 3 + multiplied, 0.5F);
            }
        });
    }

    @Override
    public void renderForeground(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        super.renderForeground(guiGraphics, mouseX, mouseY);
        forEachType((type, multiplied) -> drawTextWithScale(guiGraphics,
                Component.translatable("gui.mekck.upgrade_type." + type.id),
                relativeX + 13, relativeY + 3 + multiplied, 0xFF000000 | type.getColor().getColor().getValue(), 0.7F));
    }

    @Override
    public void renderToolTip(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY) {
        super.renderToolTip(guiGraphics, mouseX, mouseY);
        if (mouseX >= getX() + 1 && mouseX < getX() + barXShift - 1) {
            forEachType((type, multiplied) -> {
                if (mouseY >= getY() + 1 + multiplied && mouseY < getY() + 1 + multiplied + elementHeight) {
                    displayTooltips(guiGraphics, mouseX, mouseY,
                            Component.translatable("gui.mekck.upgrade_type." + type.id),
                            Component.translatable("gui.mekck.upgrade_info." + type.id,
                                    type.getCount(owner.getMenu()), type.getMax(owner.getMenu())));
                }
            });
        }
    }

    private void forEachType(ObjIntConsumer<MekCkUpgradeType> consumer) {
        int start = getCurrentSelection();
        for (int i = 0; i < getFocusedElements(); i++) {
            int index = start + i;
            if (index >= types.size()) {
                break;
            }
            consumer.accept(types.get(index), elementHeight * i);
        }
    }
}
