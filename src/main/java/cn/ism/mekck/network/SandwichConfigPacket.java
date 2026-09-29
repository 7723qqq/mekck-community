package cn.ism.mekck.network;

import cn.ism.mekck.blockentity.SandwichAssemblerBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 三明治组装机设置：
 * mode 0 = 切换模式；mode 1 = 直接设置目标数量；mode 2 = 调整目标数量（增量）。
 */
public final class SandwichConfigPacket {

    private final BlockPos pos;
    private final byte mode;
    private final int value;

    public SandwichConfigPacket(BlockPos pos, byte mode, int value) {
        this.pos = pos;
        this.mode = mode;
        this.value = value;
    }

    public SandwichConfigPacket(FriendlyByteBuf buffer) {
        this.pos = buffer.readBlockPos();
        this.mode = buffer.readByte();
        this.value = buffer.readInt();
    }

    public static SandwichConfigPacket decode(FriendlyByteBuf buffer) {
        return new SandwichConfigPacket(buffer);
    }

    public void encode(FriendlyByteBuf buffer) {
        buffer.writeBlockPos(pos);
        buffer.writeByte(mode);
        buffer.writeInt(value);
    }

    public void handle(Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (PacketGuard.target(player, pos) instanceof SandwichAssemblerBlockEntity machine) {
                switch (mode) {
                    case 0 -> machine.setMode(value);
                    case 1 -> machine.setTargetCount(value);
                    default -> machine.adjustTargetCount(value);
                }
            }
        });
        context.setPacketHandled(true);
    }
}
