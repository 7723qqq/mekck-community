package cn.ism.mekck.client;

import cn.ism.mekck.network.ModMessages;
import cn.ism.mekck.network.NetworkPullPacket;
import cn.ism.mekck.util.AE2Compat;
import mekanism.client.gui.GuiUtils;
import mekanism.client.gui.element.GuiElement;
import mekanism.client.gui.IGuiWrapper;
import mekanism.client.render.MekanismRenderer;
import mekanism.common.util.MekanismUtils;
import mekanism.common.util.MekanismUtils.ResourceType;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

/** AE2 通用"网络拉料"按钮元素（供无自定义 renderBg 的屏幕使用）。 */
public class NetworkPullButtonElement extends GuiElement {
    /** §F14 #4：与 {@link NetworkPullButton} 同步改为 24×24，并画在面板外左侧 tab 列。 */
    private static final int W = NetworkPullButton.W;
    private static final int H = NetworkPullButton.H;
    private static final ResourceLocation BUTTON_TEXTURE = MekanismUtils.getResource(ResourceType.GUI, "button.png");
    private final BlockPos pos;

    public NetworkPullButtonElement(IGuiWrapper gui, int x, int y, BlockPos pos) {
        super(gui, x, y, W, H);
        this.pos = pos;
    }

    @Override
    public void drawBackground(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTicks) {
        super.drawBackground(guiGraphics, mouseX, mouseY, partialTicks);
        if (!AE2Compat.isLoaded()) return;
        boolean hover = mouseX >= getX() && mouseX < getX() + W && mouseY >= getY() && mouseY < getY() + H;
        MekanismRenderer.resetColor(guiGraphics);
        GuiUtils.blitNineSlicedSized(guiGraphics, BUTTON_TEXTURE, getX(), getY(), W, H,
                20, 4, 200, 20, 0, (hover ? 2 : 1) * 20, 200, 60);
        guiGraphics.drawString(gui().getFont(), "ME拉",
                getX() + (W - gui().getFont().width("ME拉")) / 2,
                getY() + (H - gui().getFont().lineHeight) / 2, 0xFFFFFFFF);
    }

    @Override
    public void onClick(double mouseX, double mouseY, int button) {
        if (button == 0 && AE2Compat.isLoaded()) {
            ModMessages.sendToServer(new NetworkPullPacket(pos, NetworkPullPacket.ACTION_PULL, ""));
        }
    }
}
