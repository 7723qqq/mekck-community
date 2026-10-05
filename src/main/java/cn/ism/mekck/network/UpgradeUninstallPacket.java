package cn.ism.mekck.network;

import cn.ism.mekck.blockentity.ChocolateCannonBlockEntity;
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
 *
 * <p>切菜工厂不在分发链里：新的 {@code CuttingFactoryTile} 用 Mek 自己的
 * {@code TileComponentUpgrade}，卸载由 Mek 的升级界面直接操作组件，
 * 不经过本包（阶段 2 Task 4.6 起）。
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
            } else if (be instanceof cn.ism.mekck.machine.icemaker.IceMakerTile machine) {
                // 急冻制冰机分支改指新 tile，**不删**：冷萃升级不是 Mek 的 Upgrade
                // （它由本机的额外槽 + 20 tick 读条承担），Mek 的升级界面碰不到它，
                // 本包是该能力唯一的卸载路径（口径 §12.4.1 同型）。
                machine.uninstallUpgrade(mode, slot);
            } else if (be instanceof ChocolateCannonBlockEntity machine) {
                machine.uninstallUpgrade(mode, slot);
            } else if (be instanceof cn.ism.mekck.blockentity.IceFactoryBlockEntity machine) {
                machine.uninstallUpgrade(mode, slot);
            }
        });
        context.setPacketHandled(true);
    }
}
