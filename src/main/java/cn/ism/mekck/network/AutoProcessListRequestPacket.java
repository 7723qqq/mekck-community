package cn.ism.mekck.network;

import cn.ism.mekck.machine.ports.IMekCkPorted;
import cn.ism.mekck.util.AE2Compat;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.network.NetworkEvent;

import java.util.List;
import java.util.function.Supplier;

/** 请求 ME 自动处理列表（客户端 → 服务端）。 */
public class AutoProcessListRequestPacket {
    private final BlockPos pos;

    public AutoProcessListRequestPacket(BlockPos pos) {
        this.pos = pos;
    }

    public static void encode(AutoProcessListRequestPacket packet, FriendlyByteBuf buf) {
        buf.writeBlockPos(packet.pos);
    }

    public static AutoProcessListRequestPacket decode(FriendlyByteBuf buf) {
        return new AutoProcessListRequestPacket(buf.readBlockPos());
    }

    public static void handle(AutoProcessListRequestPacket packet, Supplier<NetworkEvent.Context> context) {
        context.get().enqueueWork(() -> {
            ServerPlayer player = context.get().getSender();
            BlockEntity be = PacketGuard.target(player, packet.pos);
            // 判据只有 IMekCkPorted：四个已迁家族（切菜 / 研磨 / 种植切配 / 烧烤）
            // 全部按端口声明判定，烧烤这一档在阶段 3 Task 3 删掉旧 BE 分支后也归到这里。
            // 判窄了界面会收不到回包而一直转圈。
            if (be instanceof IMekCkPorted) {
                List<String> available = AE2Compat.getAutoProcessableItems(be);
                List<String> selected = AE2Compat.getSelectedAutoItems(be);
                ModMessages.sendToPlayer(new AutoProcessListPacket(packet.pos, available, selected), player);
            }
        });
        context.get().setPacketHandled(true);
    }
}
