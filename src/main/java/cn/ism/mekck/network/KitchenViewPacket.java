package cn.ism.mekck.network;

import cn.ism.mekck.menu.CentralKitchenMenu;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 中央厨房存储区视图操作：
 * mode 0 = 设置搜索词；mode 1 = 设置排序模式；mode 2 = 滚动（value 为行增量）。
 */
public final class KitchenViewPacket {

    private final net.minecraft.core.BlockPos pos;
    private final byte mode;
    private final String text;
    private final int value;

    public KitchenViewPacket(net.minecraft.core.BlockPos pos, byte mode, String text, int value) {
        this.pos = pos;
        this.mode = mode;
        this.text = text == null ? "" : text;
        this.value = value;
    }

    public KitchenViewPacket(FriendlyByteBuf buffer) {
        this.pos = buffer.readBlockPos();
        this.mode = buffer.readByte();
        this.text = buffer.readUtf(64);
        this.value = buffer.readInt();
    }

    public static KitchenViewPacket decode(FriendlyByteBuf buffer) {
        return new KitchenViewPacket(buffer);
    }

    public void encode(FriendlyByteBuf buffer) {
        buffer.writeBlockPos(pos);
        buffer.writeByte(mode);
        buffer.writeUtf(text, 64);
        buffer.writeInt(value);
    }

    public void handle(Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null) return;
            if (!PacketGuard.allowed(player, pos)) return;
            if (player.containerMenu instanceof CentralKitchenMenu menu
                    && menu.getMachine().getBlockPos().equals(pos)) {
                switch (mode) {
                    case 0 -> menu.setSearchText(text);
                    case 1 -> menu.setSortMode(CentralKitchenMenu.SortMode.values()[
                            Math.max(0, Math.min(CentralKitchenMenu.SortMode.values().length - 1, value))]);
                    default -> menu.scroll(value);
                }
            }
        });
        context.setPacketHandled(true);
    }
}
