package cn.ism.mekck.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * 中央厨房系列过滤器的服务端 → 客户端同步。
 * 客户端把结果缓存在静态表里供「可安装模块」窗口渲染。
 */
public final class KitchenFilterSyncPacket {

    /** 客户端缓存：系列序号 → 过滤设置。 */
    public static final Map<Integer, Cached> CLIENT_CACHE = new ConcurrentHashMap<>();

    /** 客户端侧的过滤快照。 */
    public static final class Cached {
        public final int mode;
        public final List<ItemStack> items;

        public Cached(int mode, List<ItemStack> items) {
            this.mode = mode;
            this.items = items;
        }
    }

    private final BlockPos pos;
    private final int familyOrdinal;
    private final int mode;
    private final List<ItemStack> items;

    public KitchenFilterSyncPacket(BlockPos pos, int familyOrdinal, int mode, List<ItemStack> items) {
        this.pos = pos;
        this.familyOrdinal = familyOrdinal;
        this.mode = mode;
        this.items = items == null ? List.of() : items;
    }

    public KitchenFilterSyncPacket(FriendlyByteBuf buffer) {
        this.pos = buffer.readBlockPos();
        this.familyOrdinal = buffer.readVarInt();
        this.mode = buffer.readVarInt();
        int n = buffer.readVarInt();
        List<ItemStack> list = new ArrayList<>(PacketGuard.clampCount(n));
        for (int i = 0; i < n; i++) list.add(buffer.readItem());
        this.items = list;
    }

    public static KitchenFilterSyncPacket decode(FriendlyByteBuf buffer) {
        return new KitchenFilterSyncPacket(buffer);
    }

    public void encode(FriendlyByteBuf buffer) {
        buffer.writeBlockPos(pos);
        buffer.writeVarInt(familyOrdinal);
        buffer.writeVarInt(mode);
        buffer.writeVarInt(items.size());
        for (ItemStack stack : items) buffer.writeItem(stack);
    }

    public void handle(Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> CLIENT_CACHE.put(familyOrdinal, new Cached(mode, new ArrayList<>(items))));
        context.setPacketHandled(true);
    }
}
