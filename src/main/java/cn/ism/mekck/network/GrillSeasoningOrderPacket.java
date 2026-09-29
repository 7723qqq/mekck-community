package cn.ism.mekck.network;

import cn.ism.mekck.blockentity.GrillFactoryBlockEntity;
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
            if (PacketGuard.target(player, packet.pos) instanceof GrillFactoryBlockEntity machine) {
                machine.setOrder(packet.recipeId, packet.quantity,
                        packet.seasoningId.isEmpty() ? null : packet.seasoningId);
            }
        });
        context.get().setPacketHandled(true);
    }
}
