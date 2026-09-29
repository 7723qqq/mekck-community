package cn.ism.mekck.network;

import cn.ism.mekck.blockentity.GrillFactoryBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** 切换烧烤工厂工作模式（默认/下单）。 */
public class GrillWorkModePacket {
    private final BlockPos pos;

    public GrillWorkModePacket(BlockPos pos) {
        this.pos = pos;
    }

    public static void encode(GrillWorkModePacket packet, FriendlyByteBuf buf) {
        buf.writeBlockPos(packet.pos);
    }

    public static GrillWorkModePacket decode(FriendlyByteBuf buf) {
        return new GrillWorkModePacket(buf.readBlockPos());
    }

    public static void handle(GrillWorkModePacket packet, Supplier<NetworkEvent.Context> context) {
        context.get().enqueueWork(() -> {
            ServerPlayer player = context.get().getSender();
            if (PacketGuard.target(player, packet.pos) instanceof GrillFactoryBlockEntity machine) {
                machine.toggleWorkMode();
            }
        });
        context.get().setPacketHandled(true);
    }
}
