package cn.ism.mekck.network;

import cn.ism.mekck.blockentity.CuttingMachineFactoryBlockEntity;
import cn.ism.mekck.blockentity.GrillFactoryBlockEntity;
import cn.ism.mekck.util.AE2Compat;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.network.NetworkEvent;

import java.util.List;
import java.util.function.Supplier;

/** 勾选/取消勾选 ME 自动处理材料（客户端 → 服务端）。 */
public class AutoProcessTogglePacket {
    private final BlockPos pos;
    private final String itemId;

    public AutoProcessTogglePacket(BlockPos pos, String itemId) {
        this.pos = pos;
        this.itemId = itemId;
    }

    public static void encode(AutoProcessTogglePacket packet, FriendlyByteBuf buf) {
        buf.writeBlockPos(packet.pos);
        buf.writeUtf(packet.itemId == null ? "" : packet.itemId);
    }

    public static AutoProcessTogglePacket decode(FriendlyByteBuf buf) {
        return new AutoProcessTogglePacket(buf.readBlockPos(), buf.readUtf());
    }

    public static void handle(AutoProcessTogglePacket packet, Supplier<NetworkEvent.Context> context) {
        context.get().enqueueWork(() -> {
            ServerPlayer player = context.get().getSender();
            if (packet.itemId.isEmpty()) return;
            BlockEntity be = PacketGuard.target(player, packet.pos);
            if (be instanceof CuttingMachineFactoryBlockEntity || be instanceof GrillFactoryBlockEntity) {
                AE2Compat.toggleAutoItem(be, packet.itemId);
                List<String> available = AE2Compat.getAutoProcessableItems(be);
                List<String> selected = AE2Compat.getSelectedAutoItems(be);
                ModMessages.sendToPlayer(new AutoProcessListPacket(packet.pos, available, selected), player);
            }
        });
        context.get().setPacketHandled(true);
    }
}
