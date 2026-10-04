package cn.ism.mekck.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 面板 ME 下单的「缺料清单」请求（客户端 → 服务端）。
 *
 * <p>AE 终端在合成状态里会列出「缺少 X × N」；面板下单没有 CPU 任务，
 * 因此改为：选中配方 / 改数量时问一次服务端，服务端按
 * 「该配方每份输入 × 数量」与 ME 网络库存算出缺口摘要，回 {@link NetworkMissingPacket}。</p>
 */
public class NetworkMissingRequestPacket {
    private final BlockPos pos;
    private final String recipeId;
    private final int quantity;

    public NetworkMissingRequestPacket(BlockPos pos, String recipeId, int quantity) {
        this.pos = pos;
        this.recipeId = recipeId == null ? "" : recipeId;
        this.quantity = quantity;
    }

    public static void encode(NetworkMissingRequestPacket packet, FriendlyByteBuf buf) {
        buf.writeBlockPos(packet.pos);
        buf.writeUtf(packet.recipeId);
        buf.writeVarInt(packet.quantity);
    }

    public static NetworkMissingRequestPacket decode(FriendlyByteBuf buf) {
        return new NetworkMissingRequestPacket(buf.readBlockPos(), buf.readUtf(), buf.readVarInt());
    }

    public static void handle(NetworkMissingRequestPacket packet, Supplier<NetworkEvent.Context> context) {
        context.get().enqueueWork(() -> {
            ServerPlayer player = context.get().getSender();
            BlockEntity be = PacketGuard.target(player, packet.pos);
            // 与 NetworkRecipeRequestPacket 同口径：任何请求都必回响应（首请求不得被丢弃），
            // 不做「丢弃式」节流；昂贵部分由 MekckAe2.describeNetworkMissing 内部的面板入口
            // （最短刷新间隔合并）承担，窗口内重复请求复用上次样例结果。
            String text = be == null ? null : cn.ism.mekck.compat.AE2Compat.describeNetworkMissing(
                    be, packet.recipeId, packet.quantity);
            ModMessages.sendToPlayer(new NetworkMissingPacket(packet.pos, packet.recipeId, packet.quantity,
                    text == null ? "" : text), player);
        });
        context.get().setPacketHandled(true);
    }
}
