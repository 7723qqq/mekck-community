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
            // 判据只有 IMekCkPorted：四个已迁家族全部按端口声明判定。
            // 勾选清单由 MekckAe2 落到网格宿主上（端口声明型机器统一走这条），
            // 烧烤这一档在阶段 3 Task 3 删掉旧 BE 的 getAutoSelectedItems 之后也归到这里。
            if (be instanceof IMekCkPorted) {
                AE2Compat.toggleAutoItem(be, packet.itemId);
                List<String> available = AE2Compat.getAutoProcessableItems(be);
                List<String> selected = AE2Compat.getSelectedAutoItems(be);
                ModMessages.sendToPlayer(new AutoProcessListPacket(packet.pos, available, selected), player);
            }
        });
        context.get().setPacketHandled(true);
    }
}
