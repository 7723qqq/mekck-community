package cn.ism.mekck.network;

import cn.ism.mekck.machine.grill.GrillFactoryTile;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 烧烤工厂的调味下单包：同时设置订单（配方 + 数量）与调味料。
 * recipeId 为空表示取消订单；seasoningId 为空表示不调味。
 */
public class GrillSeasoningOrderPacket {
    private final BlockPos pos;
    private final ResourceLocation recipeId;
    private final int quantity;
    private final String seasoningId;

    public GrillSeasoningOrderPacket(BlockPos pos, ResourceLocation recipeId, int quantity, String seasoningId) {
        this.pos = pos;
        this.recipeId = recipeId;
        this.quantity = quantity;
        this.seasoningId = seasoningId == null ? "" : seasoningId;
    }

    public static void encode(GrillSeasoningOrderPacket packet, FriendlyByteBuf buf) {
        buf.writeBlockPos(packet.pos);
        buf.writeUtf(packet.recipeId == null ? "" : packet.recipeId.toString());
        buf.writeInt(packet.quantity);
        buf.writeUtf(packet.seasoningId);
    }

    public static GrillSeasoningOrderPacket decode(FriendlyByteBuf buf) {
        BlockPos pos = buf.readBlockPos();
        String id = buf.readUtf();
        ResourceLocation recipeId = id.isEmpty() ? null : ResourceLocation.tryParse(id);
        return new GrillSeasoningOrderPacket(pos, recipeId, buf.readInt(), buf.readUtf());
    }

    public static void handle(GrillSeasoningOrderPacket packet, Supplier<NetworkEvent.Context> context) {
        context.get().enqueueWork(() -> {
            ServerPlayer player = context.get().getSender();
            if (PacketGuard.target(player, packet.pos) instanceof GrillFactoryTile machine) {
                // quantity 是客户端可控的 int：夹下界。recipeId == null 表示取消订单，原样透传。
                // （本包今天没有客户端发送方，属纯加固；见 OrderRecipePacket.handle 的同款说明。）
                int quantity = packet.recipeId == null ? packet.quantity : Math.max(1, packet.quantity);
                machine.setOrder(packet.recipeId, quantity,
                        packet.seasoningId.isEmpty() ? null : packet.seasoningId);
            }
        });
        context.get().setPacketHandled(true);
    }
}
