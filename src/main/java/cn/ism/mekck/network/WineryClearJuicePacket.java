package cn.ism.mekck.network;

import cn.ism.mekck.MachineKind;
import cn.ism.mekck.blockentity.SimpleMachineBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 陈酿机「清空」按钮包（C2S）。
 * <p>
 * 复用 Mekanism Metallurgic Infuser 同款清空按钮（{@code GuiDumpButton}）的外观，
 * 但点击改走本包：服务端收到后对 winery 调 {@link SimpleMachineBlockEntity#dump()}。
 * （父类 GuiDumpButton 的点击发的是 Mekanism 自家 PacketGuiInteract，其服务端强绑 TileEntityMekanism，
 * 对我们的 SimpleMachineBlockEntity 无效，故必须自带此包接管行为。）
 * </p>
 * <p>
 * <b>为何是 {@code dump()} 而不是 {@code clearJuicePool()}</b>（用户 2026-09-24：单只果汁桶也抽不动）：
 * 果汁格抽液要求内部 {@code inputTank} 为空或同种流体（{@code FluidTank} 不混装），罐被异种流体占住时
 * 桶会永远抽不动；而 vinery / 森罗酒馆都没有能把罐内流体抽出的容器，玩家必须有一个排罐入口。
 * 早先本包只调 {@code clearJuicePool()}（仅清液位池），导致 BE 里新的排罐逻辑无入口、形同死代码。
 * 潜行右键那条路径（{@code SimpleMachineBlock} / {@code UpgradeInstallHandler}）仍只清液位池，语义不变。
 * </p>
 */
public final class WineryClearJuicePacket {
    private final BlockPos pos;

    public WineryClearJuicePacket(BlockPos pos) {
        this.pos = pos;
    }

    public WineryClearJuicePacket(FriendlyByteBuf buffer) {
        this.pos = buffer.readBlockPos();
    }

    public void encode(FriendlyByteBuf buffer) {
        buffer.writeBlockPos(pos);
    }

    public void handle(Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            BlockEntity be = PacketGuard.target(player, pos);
            if (be == null) return;
            if (be instanceof SimpleMachineBlockEntity machine
                    && machine.getMachineKind() == MachineKind.WINERY) {
                // 提示文案与实际行为对齐：Tavern 批次活跃（BREWING/STALLED）时 dump 内部只清液位池、不动罐
                cn.ism.mekck.blockentity.TavernBrewBatch batch = machine.getTavernBatch();
                boolean tankTouched = batch != null && !batch.isBrewing() && !batch.isStalled()
                        && !machine.getInputTank().isEmpty();
                machine.dump();
                player.displayClientMessage(net.minecraft.network.chat.Component.translatable(
                        tankTouched ? "message.mekck.tank_drained" : "message.mekck.juice_cleared"), true);
            }
        });
        context.setPacketHandled(true);
    }

    public BlockPos getPos() {
        return pos;
    }
}
