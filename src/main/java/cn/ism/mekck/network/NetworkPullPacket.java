package cn.ism.mekck.network;

import cn.ism.mekck.compat.AE2Compat;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 通用"网络拉料"：客户端 → 服务端。
 * <ul>
 *   <li>action=0：从 ME 网络拉取机器当前可处理配方的一份输入进输入槽；</li>
 *   <li>action=1：勾选/取消勾选某物品的持续自动补料（仅支持简单配方机器）。</li>
 * </ul>
 */
public class NetworkPullPacket {
    public static final int ACTION_PULL = 0;
    public static final int ACTION_TOGGLE_AUTO = 1;

    private final BlockPos pos;
    private final int action;
    private final String itemId;

    public NetworkPullPacket(BlockPos pos, int action, String itemId) {
        this.pos = pos;
        this.action = action;
        this.itemId = itemId == null ? "" : itemId;
    }

    public static void encode(NetworkPullPacket packet, FriendlyByteBuf buf) {
        buf.writeBlockPos(packet.pos);
        buf.writeInt(packet.action);
        buf.writeUtf(packet.itemId);
    }

    public static NetworkPullPacket decode(FriendlyByteBuf buf) {
        return new NetworkPullPacket(buf.readBlockPos(), buf.readInt(), buf.readUtf());
    }

    public static void handle(NetworkPullPacket packet, Supplier<NetworkEvent.Context> context) {
        context.get().enqueueWork(() -> {
            ServerPlayer player = context.get().getSender();
            BlockEntity be = PacketGuard.target(player, packet.pos);
            if (be == null) return;
            if (!(be instanceof cn.ism.mekck.ae2.INetworkPullable)) return;
            if (packet.action == ACTION_PULL) {
                AE2Compat.pullNetworkInputs(be);
            } else if (packet.action == ACTION_TOGGLE_AUTO) {
                if (packet.itemId.isEmpty()) {
                    // 返回一句说明，用动作栏提示玩家（开启/关闭/不支持/无可补料物品）
                    Object msg = AE2Compat.toggleAutoItemAutoMessage(be);
                    if (msg instanceof String s && !s.isEmpty()) {
                        player.displayClientMessage(net.minecraft.network.chat.Component.literal(s), true);
                    }
                } else {
                    AE2Compat.toggleAutoItemGeneric(be, packet.itemId);
                }
            }
        });
        context.get().setPacketHandled(true);
    }
}
