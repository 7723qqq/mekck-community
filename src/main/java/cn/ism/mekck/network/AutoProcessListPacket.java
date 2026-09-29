package cn.ism.mekck.network;

import cn.ism.mekck.client.CuttingMachineFactoryScreen;
import cn.ism.mekck.client.GrillFactoryScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/** ME 自动处理列表（服务端 → 客户端）。 */
public class AutoProcessListPacket {
    private final BlockPos pos;
    private final List<String> availableIds;
    private final List<String> selectedIds;

    public AutoProcessListPacket(BlockPos pos, List<String> availableIds, List<String> selectedIds) {
        this.pos = pos;
        this.availableIds = new ArrayList<>(availableIds);
        this.selectedIds = new ArrayList<>(selectedIds);
    }

    public static void encode(AutoProcessListPacket packet, FriendlyByteBuf buf) {
        buf.writeBlockPos(packet.pos);
        buf.writeVarInt(packet.availableIds.size());
        for (String id : packet.availableIds) buf.writeUtf(id);
        buf.writeVarInt(packet.selectedIds.size());
        for (String id : packet.selectedIds) buf.writeUtf(id);
    }

    public static AutoProcessListPacket decode(FriendlyByteBuf buf) {
        BlockPos pos = buf.readBlockPos();
        int n = buf.readVarInt();
        List<String> avail = new ArrayList<>(PacketGuard.clampCount(n));
        for (int i = 0; i < n; i++) avail.add(buf.readUtf());
        int m = buf.readVarInt();
        List<String> sel = new ArrayList<>(PacketGuard.clampCount(m));
        for (int i = 0; i < m; i++) sel.add(buf.readUtf());
        return new AutoProcessListPacket(pos, avail, sel);
    }

    public static void handle(AutoProcessListPacket packet, Supplier<NetworkEvent.Context> context) {
        context.get().enqueueWork(() -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.screen instanceof CuttingMachineFactoryScreen screen
                    && screen.getMenu().getBlockPos().equals(packet.pos)) {
                screen.setAutoProcessData(packet.availableIds, packet.selectedIds);
            } else if (mc.screen instanceof GrillFactoryScreen screen
                    && screen.getMenu().getBlockPos().equals(packet.pos)) {
                screen.setAutoProcessData(packet.availableIds, packet.selectedIds);
            }
        });
        context.get().setPacketHandled(true);
    }
}
