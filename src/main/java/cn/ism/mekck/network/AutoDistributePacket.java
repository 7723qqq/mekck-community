package cn.ism.mekck.network;

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
            // 种植切配工厂分支在阶段 3 Task 2 删除：新的 PlantingCuttingFactoryTile 是
            // Mek 原生 tile，自动补料开关由 Mek 侧配/弹出取代；
            // 而 ME 自动处理走 IMekCkPorted 端口声明（在 MekckAe2 的 autoWindow /
            // portedFamily 里），不经这个包。详见上面切菜工厂的同款注释。
            // 烧烤工厂分支在阶段 3 Task 3 删除，理由与上面两条逐字相同：
            // 新的 GrillFactoryTile 同样是 Mek 原生 tile。
            // 七个工厂家族至此全部迁完，本包再无任何有效目标；
            // 留着的唯一作用是让旧客户端发的包被安静丢弃而不是报错。
        });
        context.setPacketHandled(true);
    }
}