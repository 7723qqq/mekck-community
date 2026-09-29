package cn.ism.mekck.network;

import cn.ism.mekck.blockentity.GrillFactoryBlockEntity;
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
            // 切菜工厂在阶段 2 Task 4.6 起按端口声明判定（IMekCkPorted）；
            // 烧烤工厂仍是旧 BE。勾选清单由 MekckAe2 落到各自的存储上
            // （端口声明型机器走网格宿主，烧烤工厂仍走 BE 自己的字段）。
            if (be instanceof IMekCkPorted || be instanceof GrillFactoryBlockEntity) {
                AE2Compat.toggleAutoItem(be, packet.itemId);
                List<String> available = AE2Compat.getAutoProcessableItems(be);
                List<String> selected = AE2Compat.getSelectedAutoItems(be);
                ModMessages.sendToPlayer(new AutoProcessListPacket(packet.pos, available, selected), player);
            }
        });
        context.get().setPacketHandled(true);
    }
}
