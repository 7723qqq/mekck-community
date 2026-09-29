package cn.ism.mekck.network;

import cn.ism.mekck.ae2.INetworkPullable;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** ME 终端下单总开关（GUI 内切换）：关闭后该机器的配方不再出现在 ME 终端。 */
public final class MeOrderTogglePacket {
    private final BlockPos pos;
    private final boolean enabled;

    public MeOrderTogglePacket(BlockPos pos, boolean enabled) {
        this.pos = pos;
        this.enabled = enabled;
    }

    public MeOrderTogglePacket(FriendlyByteBuf buffer) {
        this.pos = buffer.readBlockPos();
        this.enabled = buffer.readBoolean();
    }

    public static MeOrderTogglePacket decode(FriendlyByteBuf buffer) {
        return new MeOrderTogglePacket(buffer);
    }

    public void encode(FriendlyByteBuf buffer) {
        buffer.writeBlockPos(pos);
        buffer.writeBoolean(enabled);
    }

    public void handle(Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            BlockEntity be = PacketGuard.target(player, pos);
            if (be == null) return;
            if (be instanceof INetworkPullable pullable) {
                pullable.setMeOrderEnabled(enabled);
            }
        });
        context.setPacketHandled(true);
    }
}
