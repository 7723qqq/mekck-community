package cn.ism.mekck.network;

import cn.ism.mekck.blockentity.IRedstoneControllable;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 红石控制模式切换包（C2S）。
 * direction > 0 表示切换到下一个模式（对应 GUI 左键），direction < 0 表示上一个模式（对应 GUI 右键）。
 */
public final class RedstoneControlPacket {
    private final BlockPos pos;
    private final int direction;

    public RedstoneControlPacket(BlockPos pos, int direction) {
        this.pos = pos;
        this.direction = direction;
    }

    public RedstoneControlPacket(FriendlyByteBuf buffer) {
        this.pos = buffer.readBlockPos();
        this.direction = buffer.readByte();
    }

    public void encode(FriendlyByteBuf buffer) {
        buffer.writeBlockPos(pos);
        buffer.writeByte(direction);
    }

    public void handle(Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            BlockEntity be = PacketGuard.target(player, pos);
            if (be == null) return;
            if (be instanceof IRedstoneControllable machine) {
                machine.cycleRedstoneControl(direction);
            }
        });
        context.setPacketHandled(true);
    }

    public BlockPos getPos() {
        return pos;
    }

    public int getDirection() {
        return direction;
    }
}