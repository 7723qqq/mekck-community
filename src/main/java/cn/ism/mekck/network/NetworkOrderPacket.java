package cn.ism.mekck.network;

import cn.ism.mekck.util.AE2Compat;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 本模组下单面板的“ME 网络下单”：客户端 → 服务端。
 * 服务端先从 ME 网络抽取 quantity 份材料放入机器，再按配方下单。
 */
public class NetworkOrderPacket {
    private final BlockPos pos;
    private final String recipeId;
    private final int quantity;
    /** 调味料 id：仅烧烤工厂这类三参 setOrder 的机器使用，空串 = 不调味。 */
    private final String seasoningId;

    public NetworkOrderPacket(BlockPos pos, String recipeId, int quantity) {
        this(pos, recipeId, quantity, null);
    }

    public NetworkOrderPacket(BlockPos pos, String recipeId, int quantity, String seasoningId) {
        this.pos = pos;
        this.recipeId = recipeId;
        this.quantity = quantity;
        this.seasoningId = seasoningId == null ? "" : seasoningId;
    }

    public static void encode(NetworkOrderPacket packet, FriendlyByteBuf buf) {
        buf.writeBlockPos(packet.pos);
        buf.writeUtf(packet.recipeId == null ? "" : packet.recipeId);
        buf.writeInt(packet.quantity);
        buf.writeUtf(packet.seasoningId);
    }

    public static NetworkOrderPacket decode(FriendlyByteBuf buf) {
        BlockPos pos = buf.readBlockPos();
        String id = buf.readUtf();
        int quantity = buf.readInt();
        String seasoning = buf.readUtf();
        return new NetworkOrderPacket(pos, id, quantity, seasoning);
    }

    public static void handle(NetworkOrderPacket packet, Supplier<NetworkEvent.Context> context) {
        context.get().enqueueWork(() -> {
            ServerPlayer player = context.get().getSender();
            if (packet.quantity <= 0 || packet.recipeId.isEmpty()) return;
            net.minecraft.world.level.block.entity.BlockEntity machine = PacketGuard.target(player, packet.pos);
            if (machine == null) return;
            // 抽料 → 插机器 → 建 AE 任务 → 下单，全部按机器类型在 MekckAe2 内部**通用分派**：
            // 烹饪工厂 / 穿串工厂走各自专用分支，其余机器（烧烤工厂 / 智能厨锅 / 智能穿串机 /
            // 中央厨房 / 联动机器…）走通用分支（终端样板同源）。
            AE2Compat.pullNetworkIngredients(machine, packet.recipeId, packet.quantity,
                    packet.seasoningId.isEmpty() ? null : packet.seasoningId);
        });
        context.get().setPacketHandled(true);
    }
}
