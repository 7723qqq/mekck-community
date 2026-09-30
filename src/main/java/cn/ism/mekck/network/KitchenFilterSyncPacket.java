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
 * 中央厨房系列设置的 服务端 → 客户端同步。
 * 客户端把结果缓存在静态表里供「可安装模块」窗口渲染。
 *
 * <p><b>本包除过滤设置外还携带「自动加工开关」</b>（第三轮补）。原因：
 * {@code CentralKitchenBlockEntity.setAutoMode} 此前<b>零调用方</b>，整个自动加工
 * 引擎（线程、ACTIVE 块状态、屏幕线程数）都是死代码；而它是个需要持久化的开关，
 * 客户端又必须知道当前状态才能把按钮画对。塞进这个已有的「按系列同步设置」包里
 * 比新开一个包省一条通道，也省一次 {@code sendFilterSync} 的遍历。</p>
 */
public final class KitchenFilterSyncPacket {

    /** 客户端缓存：系列序号 → 设置快照。 */
    public static final Map<Integer, Cached> CLIENT_CACHE = new ConcurrentHashMap<>();

    /** 客户端侧的设置快照。 */
    public static final class Cached {
        public final int mode;
        public final List<ItemStack> items;
        /** 该系列的自动加工开关（第三轮补）。 */
        public final boolean autoMode;

        public Cached(int mode, List<ItemStack> items, boolean autoMode) {
            this.mode = mode;
            this.items = items;
            this.autoMode = autoMode;
        }
    }

    private final BlockPos pos;
    private final int familyOrdinal;
    private final int mode;
    private final List<ItemStack> items;
    private final boolean autoMode;

    public KitchenFilterSyncPacket(BlockPos pos, int familyOrdinal, int mode, List<ItemStack> items,
                                   boolean autoMode) {
        this.pos = pos;
        this.familyOrdinal = familyOrdinal;
        this.mode = mode;
        this.items = items == null ? List.of() : items;
        this.autoMode = autoMode;
    }

    public KitchenFilterSyncPacket(FriendlyByteBuf buffer) {
        this.pos = buffer.readBlockPos();
        this.familyOrdinal = buffer.readVarInt();
        this.mode = buffer.readVarInt();
        int n = buffer.readVarInt();
        List<ItemStack> list = new ArrayList<>(PacketGuard.clampCount(n));
        for (int i = 0; i < n; i++) list.add(buffer.readItem());
        this.items = list;
        this.autoMode = buffer.readBoolean();
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
        buffer.writeBoolean(autoMode);
    }

    public void handle(Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> CLIENT_CACHE.put(
                familyOrdinal, new Cached(mode, new ArrayList<>(items), autoMode)));
        context.setPacketHandled(true);
    }
}
