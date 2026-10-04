package cn.ism.mekck.network;

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
        NetworkEvent.Context ctx = context.get();
        // ⚠️ 同 NetworkRecipeListPacket：本类在双端都要链接，客户端符号必须隔离。
        // 背景与同类事故见 network/ClientPacketBridge 的类注释。
        if (!PacketGuard.fromServer("NetworkMissingPacket", ctx)) {
            ctx.setPacketHandled(true);
            return;
        }
        ctx.enqueueWork(() -> ClientPacketBridge.applyMissing(
                packet.pos, packet.recipeId, packet.quantity, packet.text));
        ctx.setPacketHandled(true);
    }
}
