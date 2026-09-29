package cn.ism.mekck.network;

import cn.ism.mekck.blockentity.CuttingMachineFactoryBlockEntity;
import cn.ism.mekck.blockentity.GrillFactoryBlockEntity;
import cn.ism.mekck.blockentity.PlantingCuttingFactoryBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public final class AutoDistributePacket {
    private final BlockPos pos;

    public AutoDistributePacket(BlockPos pos) {
        this.pos = pos;
    }

    public AutoDistributePacket(FriendlyByteBuf buffer) {
        this.pos = buffer.readBlockPos();
    }

    public void encode(FriendlyByteBuf buffer) {
        buffer.writeBlockPos(pos);
    }

    public void handle(Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            BlockEntity be = PacketGuard.target(player, pos);
            if (be == null) return;
            if (be instanceof CuttingMachineFactoryBlockEntity machine) {
                machine.toggleAutoDistribute();
            } else if (be instanceof GrillFactoryBlockEntity grill) {
                grill.toggleAutoDistribute();
            } else if (be instanceof PlantingCuttingFactoryBlockEntity planting) {
                planting.toggleAutoDistribute();
            }
        });
        context.setPacketHandled(true);
    }
}