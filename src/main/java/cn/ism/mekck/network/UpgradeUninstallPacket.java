package cn.ism.mekck.network;

import cn.ism.mekck.blockentity.ChocolateCannonBlockEntity;
import cn.ism.mekck.blockentity.IceMakerBlockEntity;
import cn.ism.mekck.blockentity.SimpleMachineBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 升级卸载（升级窗口的「卸载」按钮）：
 * mode 0 = 卸载 1 个；mode 1 = 卸载全部；mode 2 = 卸载指定槽位的升级（用于冷萃 / 费列罗链式槽）。
 * slot 参数仅 mode 2 使用（升级槽的物品槽索引）。
 */
public final class UpgradeUninstallPacket {

    private final BlockPos pos;
    private final byte mode;
    private final int slot;

    public UpgradeUninstallPacket(BlockPos pos, byte mode, int slot) {
        this.pos = pos;
        this.mode = mode;
        this.slot = slot;
    }

    public UpgradeUninstallPacket(FriendlyByteBuf buffer) {
        this.pos = buffer.readBlockPos();
        this.mode = buffer.readByte();
        this.slot = buffer.readInt();
    }

    public static UpgradeUninstallPacket decode(FriendlyByteBuf buffer) {
        return new UpgradeUninstallPacket(buffer);
    }

    public void encode(FriendlyByteBuf buffer) {
        buffer.writeBlockPos(pos);
        buffer.writeByte(mode);
        buffer.writeInt(slot);
    }

    public void handle(Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            BlockEntity be = PacketGuard.target(player, pos);
            if (be == null) return;
            if (be instanceof SimpleMachineBlockEntity machine) {
                machine.uninstallUpgrade(mode, slot);
            } else if (be instanceof IceMakerBlockEntity machine) {
                machine.uninstallUpgrade(mode, slot);
            } else if (be instanceof ChocolateCannonBlockEntity machine) {
                machine.uninstallUpgrade(mode, slot);
            } else if (be instanceof cn.ism.mekck.blockentity.CookingFactoryBlockEntity machine) {
                machine.uninstallUpgrade(mode, slot);
            } else if (be instanceof cn.ism.mekck.blockentity.GrillFactoryBlockEntity machine) {
                machine.uninstallUpgrade(mode, slot);
            } else if (be instanceof cn.ism.mekck.blockentity.CuttingMachineFactoryBlockEntity machine) {
                machine.uninstallUpgrade(mode, slot);
            } else if (be instanceof cn.ism.mekck.blockentity.SkeweringFactoryBlockEntity machine) {
                machine.uninstallUpgrade(mode, slot);
            } else if (be instanceof cn.ism.mekck.blockentity.GrindingFactoryBlockEntity machine) {
                machine.uninstallUpgrade(mode, slot);
            } else if (be instanceof cn.ism.mekck.blockentity.PlantingCuttingFactoryBlockEntity machine) {
                machine.uninstallUpgrade(mode, slot);
            } else if (be instanceof cn.ism.mekck.blockentity.IceFactoryBlockEntity machine) {
                machine.uninstallUpgrade(mode, slot);
            }
        });
        context.setPacketHandled(true);
    }
}
