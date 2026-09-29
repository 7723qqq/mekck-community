package cn.ism.mekck.network;

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
            // 切菜工厂分支在阶段 2 Task 4.6 删除：新的 CuttingFactoryTile 是 Mek 原生 tile，
            // 自动分配这个 MekCK 自研状态已随旧 BE 一起消失，Mek 自己的
            // TileComponentEjector + 弹出配置接管这件事，界面上也没有对应按钮。
            // 保留分支只会让包「到达但无事发生」，且在 Task 5 删掉旧类后直接编译不过。
            if (be instanceof GrillFactoryBlockEntity grill) {
                grill.toggleAutoDistribute();
            } else if (be instanceof PlantingCuttingFactoryBlockEntity planting) {
                planting.toggleAutoDistribute();
            }
        });
        context.setPacketHandled(true);
    }
}