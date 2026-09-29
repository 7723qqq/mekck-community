package cn.ism.mekck.client;

import cn.ism.mekck.blockentity.SimpleMachineBlockEntity;
import cn.ism.mekck.network.ModMessages;
import cn.ism.mekck.network.WineryClearJuicePacket;
import mekanism.client.gui.IGuiWrapper;
import mekanism.client.gui.element.GuiDumpButton;

/**
 * 陈酿机「清空」按钮：复用 Mekanism Metallurgic Infuser 同款 {@link GuiDumpButton}
 * 的全部外观（{@code dump.png}、21×10、{@code UI_BUTTON_CLICK} 点击音效）。
 * <p>
 * 仅重写 {@link #onClick} 把点击行为从「父类发 Mekanism PacketGuiInteract（其服务端强绑
 * TileEntityMekanism，对我们的机器无效）」改为发 {@link WineryClearJuicePacket}；服务端现在
 * 对 winery 调 {@code dump()}——除果汁液位池外**还排空内部储罐**（用户 2026-09-24：罐被异种
 * 流体占住时果汁桶永远抽不动，而玩家没有任何排罐手段）。
 * </p>
 */
public final class MekCkWineryDumpButton extends GuiDumpButton<SimpleMachineBlockEntity> {

    public MekCkWineryDumpButton(IGuiWrapper wrapper, SimpleMachineBlockEntity tile, int x, int y) {
        super(wrapper, tile, x, y);
    }

    @Override
    public void onClick(double mouseX, double mouseY, int button) {
        ModMessages.sendToServer(new WineryClearJuicePacket(tile.getBlockPos()));
    }
}
