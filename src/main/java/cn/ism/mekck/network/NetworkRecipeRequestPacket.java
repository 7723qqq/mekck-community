package cn.ism.mekck.network;

import cn.ism.mekck.compat.AE2Compat;
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
            // 任何请求都必回响应（打开面板的首个请求不得被丢弃），因此这里不做「丢弃式」节流：
            // 昂贵部分（全量样板重建 + 全网库存扫描）在 MekckAe2 的面板入口按最短刷新间隔
            // （PacketGuard.PANEL_REFRESH_MIN_TICKS）合并——窗口内重复请求复用上次缓存结果，
            // 窗口外或首请求立即重建。取舍：宁可回一份窗口内（≤0.5s）的缓存，也不让面板空白。
            Map<String, Integer> max = new LinkedHashMap<>(AE2Compat.getNetworkCraftableMap(be));
            List<String> ids = new ArrayList<>(max.keySet());
            ModMessages.sendToPlayer(new NetworkRecipeListPacket(packet.pos, ids, max), player);
        });
        context.get().setPacketHandled(true);
    }
}
