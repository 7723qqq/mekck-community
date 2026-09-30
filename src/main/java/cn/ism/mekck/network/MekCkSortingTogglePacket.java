package cn.ism.mekck.network;

import cn.ism.mekck.machine.MekCkMachineTile;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 切换工厂输入槽的自动分选开关（Mek 的 {@code PacketGuiInteract(AUTO_SORT_BUTTON)} 对应物）。
 *
 * <p>开关状态是服务端的权威值：分选本身在 {@code onUpdateServer} 里跑，客户端只负责
 * 画 On/Off 并发这个包。所以这里不带目标值，只带坐标 —— 服务端翻转自己那份，
 * 结果再随容器同步回客户端。带目标值反而会引入「客户端与服务端不同步时越翻越乱」。</p>
 */
public class MekCkSortingTogglePacket {

    private final BlockPos pos;

    public MekCkSortingTogglePacket(BlockPos pos) {
        this.pos = pos;
    }

    public static void encode(MekCkSortingTogglePacket packet, FriendlyByteBuf buf) {
        buf.writeBlockPos(packet.pos);
    }

    public static MekCkSortingTogglePacket decode(FriendlyByteBuf buf) {
        return new MekCkSortingTogglePacket(buf.readBlockPos());
    }

    public static void handle(MekCkSortingTogglePacket packet, Supplier<NetworkEvent.Context> context) {
        context.get().enqueueWork(() -> {
            ServerPlayer player = context.get().getSender();
            if (PacketGuard.target(player, packet.pos) instanceof MekCkMachineTile machine) {
                machine.toggleSorting();
            }
        });
        context.get().setPacketHandled(true);
    }
}
