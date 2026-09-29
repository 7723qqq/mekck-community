package cn.ism.mekck.network;

import cn.ism.mekck.blockentity.GrillFactoryBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** 切换烧烤工厂某个调味料槽的自动调味启用状态（默认工作模式）。 */
public class GrillSeasoningTogglePacket {
    private final BlockPos pos;
    private final int index;

    public GrillSeasoningTogglePacket(BlockPos pos, int index) {
        this.pos = pos;
        this.index = index;
    }

    public static void encode(GrillSeasoningTogglePacket packet, FriendlyByteBuf buf) {
        buf.writeBlockPos(packet.pos);
        buf.writeInt(packet.index);
    }

    public static GrillSeasoningTogglePacket decode(FriendlyByteBuf buf) {
        return new GrillSeasoningTogglePacket(buf.readBlockPos(), buf.readInt());
    }

    public static void handle(GrillSeasoningTogglePacket packet, Supplier<NetworkEvent.Context> context) {
        context.get().enqueueWork(() -> {
            ServerPlayer player = context.get().getSender();
            if (PacketGuard.target(player, packet.pos) instanceof GrillFactoryBlockEntity machine) {
                machine.toggleSeasoningEnabled(packet.index);
            }
        });
        context.get().setPacketHandled(true);
    }
}
