package cn.ism.mekck.network;

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
            // 阶段 2 Task 4 起切菜工厂的界面走 Mek 的 GuiConfigurableTile，ME 自动处理面板
            // （依赖旧 tile 上的 autoSelectedItems）连同旧 CuttingMachineFactoryScreen 一起退场，
            // 切菜分支因此不可达，在这里一并删掉，而不是留一个永远不匹配的空 if。
            // 阶段 3 Task 3 起最后一个接收方（GrillFactoryScreen）也换成 Mek 体系界面，
            // 同样不建这个面板——本包至此没有任何界面会消费它。
            // 仍保留注册：旧客户端发的包被安静丢弃，比抛 IllegalArgumentException 友好。
            // 真要恢复这个面板，先恢复界面的「开面板 → 发 AutoProcessListRequestPacket」
            // 那一对，再把这里的接收条件按新的 screen 类型加回来。
        });
        context.get().setPacketHandled(true);
    }
}
