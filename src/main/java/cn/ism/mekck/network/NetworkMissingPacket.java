package cn.ism.mekck.network;

import cn.ism.mekck.client.NetworkOrderHost;
import cn.ism.mekck.client.NetworkOrderPanel;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 「缺料清单」回包（服务端 → 客户端）：空字符串表示材料够用。
 *
 * @see NetworkMissingRequestPacket
 */
public class NetworkMissingPacket {
    private final BlockPos pos;
    private final String recipeId;
    private final int quantity;
    private final String text;

    public NetworkMissingPacket(BlockPos pos, String recipeId, int quantity, String text) {
        this.pos = pos;
        this.recipeId = recipeId == null ? "" : recipeId;
        this.quantity = quantity;
        this.text = text == null ? "" : text;
    }

    public static void encode(NetworkMissingPacket packet, FriendlyByteBuf buf) {
        buf.writeBlockPos(packet.pos);
        buf.writeUtf(packet.recipeId);
        buf.writeVarInt(packet.quantity);
        buf.writeUtf(packet.text);
    }

    public static NetworkMissingPacket decode(FriendlyByteBuf buf) {
        return new NetworkMissingPacket(buf.readBlockPos(), buf.readUtf(), buf.readVarInt(), buf.readUtf());
    }

    public static void handle(NetworkMissingPacket packet, Supplier<NetworkEvent.Context> context) {
        context.get().enqueueWork(() -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.screen instanceof NetworkOrderHost host) {
                NetworkOrderPanel panel = host.networkOrderPanel();
                if (panel != null) {
                    // 共用面板的屏幕
                    panel.setMissing(packet.pos, packet.recipeId, packet.quantity, packet.text);
                } else {
                    // 自绘 ME 面板的屏幕（烹饪 / 穿串工厂）
                    host.setNetworkMissing(packet.pos, packet.recipeId, packet.quantity, packet.text);
                }
            }
        });
        context.get().setPacketHandled(true);
    }
}
