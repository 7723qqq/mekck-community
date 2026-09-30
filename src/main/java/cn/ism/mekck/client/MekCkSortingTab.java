package cn.ism.mekck.client;

import cn.ism.mekck.machine.MekCkMachineTile;
import cn.ism.mekck.network.MekCkSortingTogglePacket;
import cn.ism.mekck.network.ModMessages;
import mekanism.client.SpecialColors;
import mekanism.client.gui.IGuiWrapper;
import mekanism.client.gui.element.GuiInsetElement;
import mekanism.client.render.MekanismRenderer;
import mekanism.common.MekanismLang;
import mekanism.common.util.MekanismUtils;
import mekanism.common.util.MekanismUtils.ResourceType;
import mekanism.common.util.text.BooleanStateDisplay.OnOff;
import net.minecraft.client.gui.GuiGraphics;
import org.jetbrains.annotations.NotNull;

/**
 * 「自动分选」标签页 —— 上游 {@code GuiSortingTab} 的对应物。
 *
 * <h3>为什么不能直接用上游那个类</h3>
 * {@code GuiSortingTab extends GuiInsetElement<TileEntityFactory<?>>}，数据源写死了
 * Mek 自己的工厂基类；本模组的 tile 不是它的子类。所以照它的形状重写一个，
 * 贴图（{@code mekanism:gui/sorting.png}）、尺寸（26×35）、着色
 * （{@code SpecialColors.TAB_FACTORY_SORT}）、On/Off 文字位置全部照搬。
 *
 * <h3>为什么挂在右侧而不是上游的左侧</h3>
 * 上游把它放在 {@code (-26, 62)}，占 62..97。本模组左侧那一列已经被占满：
 * 侧配 6..32 / 传输配置 34..60 / 槽位悬浮窗 90..108 / 警告 109..135 / 能量 137..163，
 * 62..88 只剩 27px，塞不下 35px 高的标签页（On/Off 文字要占下半截）。
 * 右侧 52..137 是空的（升级 6..24、安全 34..52、红石 137..155），所以镜像到右侧、
 * y 仍取上游的 62。
 */
public class MekCkSortingTab extends GuiInsetElement<MekCkMachineTile> {

    /** 标签页的 y —— 与上游 {@code GuiSortingTab} 的 62 一致。 */
    private static final int TAB_Y = 62;
    /** 标签页高度 —— 与上游一致：上半 18px 图标，下半留给 On/Off 文字。 */
    private static final int TAB_HEIGHT = 35;
    /** 图标边长 —— 与上游一致。 */
    private static final int ICON_SIZE = 18;

    public MekCkSortingTab(IGuiWrapper gui, MekCkMachineTile tile) {
        super(MekanismUtils.getResource(ResourceType.GUI, "sorting.png"), gui, tile,
                gui.getWidth(), TAB_Y, TAB_HEIGHT, ICON_SIZE, false);
    }

    @Override
    public void drawBackground(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTicks) {
        super.drawBackground(guiGraphics, mouseX, mouseY, partialTicks);
        // On/Off 文字画在图标下方 —— 位置与上游逐像素一致。
        drawTextScaledBound(guiGraphics, OnOff.of(dataSource.isSorting()).getTextComponent(),
                relativeX + 3, relativeY + 24, titleTextColor(), 21);
    }

    @Override
    public void renderToolTip(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY) {
        super.renderToolTip(guiGraphics, mouseX, mouseY);
        displayTooltips(guiGraphics, mouseX, mouseY, MekanismLang.AUTO_SORT.translate());
    }

    @Override
    protected void colorTab(GuiGraphics guiGraphics) {
        MekanismRenderer.color(guiGraphics, SpecialColors.TAB_FACTORY_SORT);
    }

    @Override
    public void onClick(double mouseX, double mouseY, int button) {
        // 只发坐标：开关的权威值在服务端，翻转后随容器同步回来（见 MekCkSortingTogglePacket）。
        ModMessages.sendToServer(new MekCkSortingTogglePacket(dataSource.getBlockPos()));
    }
}
