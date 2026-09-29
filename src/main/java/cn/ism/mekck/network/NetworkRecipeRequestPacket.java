package cn.ism.mekck.network;

import cn.ism.mekck.util.AE2Compat;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 请求某台机器在 ME 网络里的可下单配方（客户端 → 服务端）。
 * 服务端回复 {@link NetworkRecipeListPacket}。
 */
public class NetworkRecipeRequestPacket {
    private final BlockPos pos;

    public NetworkRecipeRequestPacket(BlockPos pos) {
        this.pos = pos;
    }

    public static void encode(NetworkRecipeRequestPacket packet, FriendlyByteBuf buf) {
        buf.writeBlockPos(packet.pos);
    }

    public static NetworkRecipeRequestPacket decode(FriendlyByteBuf buf) {
        return new NetworkRecipeRequestPacket(buf.readBlockPos());
    }

    public static void handle(NetworkRecipeRequestPacket packet, Supplier<NetworkEvent.Context> context) {
        context.get().enqueueWork(() -> {
            ServerPlayer player = context.get().getSender();
            net.minecraft.world.level.block.entity.BlockEntity be = PacketGuard.target(player, packet.pos);
            // 任何挂了 ME 网络节点的机器都能回列表（服务端通用分派：终端样板同源），
            // 未联网 / 未装 AE2 时 AE2Compat 短路为空表，客户端显示"ME 网络中无可用食材"。
            Map<String, Integer> max = new LinkedHashMap<>(AE2Compat.getNetworkCraftableMap(be));
            List<String> ids = new ArrayList<>(max.keySet());
            ModMessages.sendToPlayer(new NetworkRecipeListPacket(packet.pos, ids, max), player);
        });
        context.get().setPacketHandled(true);
    }
}
