package cn.ism.mekck.client;

import cn.ism.mekck.network.ModMessages;
import cn.ism.mekck.network.NetworkPullPacket;
import mekanism.client.SpecialColors;
import mekanism.client.gui.IGuiWrapper;
import mekanism.client.gui.element.tab.GuiTabElementType;
import mekanism.client.gui.element.tab.TabType;
import mekanism.client.render.lib.ColorAtlas.ColorRegistryObject;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * 「自动补料 / 网络拉料」两枚标签页。
 *
 * <p><b>逐行照抄 Mek 的 {@code GuiBoilerTab} / {@code GuiMatrixTab}</b>：子类里只有构造器，
 * 一行覆写都没有；全部差异都在 {@link PullTab} 枚举里（图标 / y / 颜色 / 文案 / 动作）。
 * 点击派发（{@code tabType.onClick(dataSource)}）、holder 染色（{@code tabType.getTabColor()}）、
 * tooltip 通道、26×26/18×18 几何、左列 x=-26 全在 Mek 的 {@link GuiTabElementType} 那边。</p>
 *
 * <p>与 Mek 那两份的唯一差别是数据源：它们绑的是自己的多方块 tile，我们绑 {@link BlockEntity}
 * （{@code TabType<TILE extends BlockEntity>} 的上界），坐标由 {@code onClick} 现取。</p>
 */
public class MekCkPullTab extends GuiTabElementType<BlockEntity, MekCkPullTab.PullTab> {

    public MekCkPullTab(IGuiWrapper gui, BlockEntity tile, PullTab type) {
        super(gui, tile, type);
    }

    public enum PullTab implements TabType<BlockEntity> {
        AUTO_PULL("icon_auto_pull.png", "tooltip.mekck.auto_pull.title", NetworkPullPacket.ACTION_TOGGLE_AUTO,
                62, SpecialColors.TAB_FACTORY_SORT),
        NETWORK_PULL("icon_network_pull.png", "tooltip.mekck.network_pull.title", NetworkPullPacket.ACTION_PULL,
                90, SpecialColors.TAB_QIO_FREQUENCY);

        private final ColorRegistryObject colorRO;
        private final int action;
        private final String description;
        private final String path;
        private final int y;

        PullTab(String path, String description, int action, int y, ColorRegistryObject colorRO) {
            this.path = path;
            this.description = description;
            this.action = action;
            this.y = y;
            this.colorRO = colorRO;
        }

        @Override
        public ResourceLocation getResource() {
            return new ResourceLocation("mekck", "textures/gui/" + path);
        }

        @Override
        public int getYPos() {
            return y;
        }

        @Override
        public void onClick(BlockEntity tile) {
            ModMessages.sendToServer(new NetworkPullPacket(tile.getBlockPos(), action, ""));
        }

        @Override
        public Component getDescription() {
            return Component.translatable(description);
        }

        @Override
        public ColorRegistryObject getTabColor() {
            return colorRO;
        }
    }
}
