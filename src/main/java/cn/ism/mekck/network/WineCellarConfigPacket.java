package cn.ism.mekck.network;

import cn.ism.mekck.blockentity.WineCellarBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 陈化窖（F20）倍速设定：GUI 里玩家敲数字回车后，把所选倍速 S 发到服务端写入 BE。
 * value = 期望倍速；服务端 {@link WineCellarBlockEntity#setSpeed} 会再钳制到 [1,50]。
 */
public final class WineCellarConfigPacket {
    private final BlockPos pos;
    private final int speed;

    public WineCellarConfigPacket(BlockPos pos, int speed) {
        this.pos = pos;
        this.speed = speed;
    }

    public WineCellarConfigPacket(FriendlyByteBuf buffer) {
        this.pos = buffer.readBlockPos();
        this.speed = buffer.readInt();
    }

    public void encode(FriendlyByteBuf buffer) {
        buffer.writeBlockPos(pos);
        buffer.writeInt(speed);
    }

    public void handle(Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            BlockEntity be = PacketGuard.target(player, pos);
            if (be == null) return;
            if (be instanceof WineCellarBlockEntity cellar) {
                cellar.setSpeed(speed);
            }
        });
        context.setPacketHandled(true);
    }
}
