package cn.ism.mekck.network;

import cn.ism.mekck.blockentity.IceFactoryBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 急冻制冰机 / 制冰工厂的攻击配置（GUI 内调整）：
 * mode 0 = 设置目标类型（value 为 ordinal：0 敌对 / 1 全部 / 2 动物）；
 * mode 1 = 调整索敌半径（value 为增量，+1 / -1）；
 * mode 2 = 直接设置索敌半径（value 为绝对值，服务端钳制到 ≥4，上限不限）；
 * mode 3 = 设置目标温度（value 为 0.01 ℃ 绝对值，急冻制冰机只降温、服务端钳制到 [-27315, 0]）；
 * mode 4 = 调整目标温度（增量，向后兼容保留，急冻制冰机 GUI 已改用输入框不再发送）；
 * mode 5 = 控温功能开关（value != 0 为开）。
 */
public final class IceAttackConfigPacket {
    private final BlockPos pos;
    private final byte mode;
    private final int value;

    public IceAttackConfigPacket(BlockPos pos, byte mode, int value) {
        this.pos = pos;
        this.mode = mode;
        this.value = value;
    }

    public IceAttackConfigPacket(FriendlyByteBuf buffer) {
        this.pos = buffer.readBlockPos();
        this.mode = buffer.readByte();
        this.value = buffer.readInt();
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
            BlockEntity be = PacketGuard.target(player, pos);
            if (be == null) return;
            // 急冻制冰机（2026-10-06 迁到 Mek 原生 tile）：分支改指 IceMakerTile，**不删** ——
            // 索敌（目标类型 / 半径）与控温（设定温度 / 开关）都是活功能，本包是它们唯一的写入路径，
            // 删掉就是「GUI 上的控制全部静默失效」（口径 §12.4.1 同型）。
            if (be instanceof cn.ism.mekck.machine.icemaker.IceMakerTile machine) {
                switch (mode) {
                    case 0 -> machine.setTargetType(value);
                    case 2 -> machine.setRadius(value);
                    case 3 -> machine.setTargetTemperature(value);   // 设定温度（绝对值，单位 0.01 ℃）
                    case 4 -> machine.adjustTargetTemperature(value); // 调整设定温度（增量）
                    case 5 -> machine.setTemperatureControlEnabled(value != 0); // 设定温度功能开关
                    default -> machine.adjustRadius(value);
                }
            } else if (be instanceof IceFactoryBlockEntity factory) {
                switch (mode) {
                    case 0 -> factory.setTargetType(value);
                    case 2 -> factory.setRadius(value);
                    default -> factory.adjustRadius(value);
                }
            } else if (be instanceof cn.ism.mekck.blockentity.ChocolateCannonBlockEntity cannon) {
                switch (mode) {
                    case 0 -> cannon.setTargetType(value);
                    case 2 -> cannon.setRadius(value);
                    default -> cannon.adjustRadius(value);
                }
            } else if (be instanceof cn.ism.mekck.machine.roasting.NutRoasterTile roaster) {
                switch (mode) {
                    case 0 -> roaster.setTargetType(value);
                    case 2 -> roaster.setRadius(value);
                    default -> roaster.adjustRadius(value);
                }
            }
        });
        context.setPacketHandled(true);
    }
}
