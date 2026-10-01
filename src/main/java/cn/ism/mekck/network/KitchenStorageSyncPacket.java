package cn.ism.mekck.network;

import cn.ism.mekck.menu.CentralKitchenMenu;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * 中央厨房<b>存储浏览器</b>的服务端 → 客户端同步（修 I-N4）。
 *
 * <h3>这个包为什么必须存在</h3>
 * 存储浏览器的搜索 / 排序 / 滚动<b>一直是在服务端算的</b>：
 * {@code KitchenViewPacket}（C2S）落地后调的是<b>服务端</b> menu 的
 * {@code setSearchText} / {@code setSortMode} / {@code scroll}，
 * 服务端那份 {@code filtered} 与 {@code displayOrder} 算得完全正确。
 *
 * <p>但客户端 menu 里的 {@code CentralKitchenBlockEntity} 是一个<b>内容全空</b>的桩
 * （存储区的 300 格物品从不进 {@code ContainerSynchronizer}），
 * 而 {@code StorageSlot.getItem()} 读的是 {@code machine.items.getStackInSlot(machineIndex)}，
 * 客户端 {@code machineIndex} 恒为 {@code -1}（{@code applyScroll} 遍历的是空的
 * {@code filtered}）⇒ <b>54 个格子永远画成空的，整个浏览器是死的</b>。
 *
 * <p>修法是<b>只同步「当前可见的一页」</b>（54 格）而不是全部 300 格：
 * 300 格 × 每次改动的全量推送在 AutoIO 每 tick 拉料的机器上是带宽灾难，
 * 而玩家能看到的永远只有一页。
 *
 * <h3>为什么连 scrollRow / sortMode / filteredCount 一起发</h3>
 * 屏幕上的页码指示读的是 {@code (scrollRow + 1) / maxScrollRow()}，
 * 「共 N 条」读的是 {@code getFilteredCount()}，排序按钮的高亮读的是
 * {@code getSortMode()}——这三样在客户端都算不出来（{@code filtered} 是空的），
 * 所以必须随快照一起下行，否则页面会显示「1/0 页」「共 0 条」。
 *
 * <h3>安全</h3>
 * 走 {@link PacketGuard#fromServer}（拒绝非服务端来源）。
 * 解码侧用 {@link PacketGuard#clampCount} 限制数量，
 * 避免恶意/损坏的长度字段让客户端按超大数组预分配。
 * 只允许作用在<b>玩家自己正开着</b>的那个中央厨房界面上（按 pos 比对），
 * 不往静态表里塞全局缓存——本包的数据是<b>每个玩家界面私有</b>的。
 */
public final class KitchenStorageSyncPacket {

    private final BlockPos pos;
    private final int scrollRow;
    private final int sortModeOrdinal;
    private final int filteredCount;
    private final List<ItemStack> visible;

    public KitchenStorageSyncPacket(BlockPos pos, int scrollRow, int sortModeOrdinal,
                                    int filteredCount, List<ItemStack> visible) {
        this.pos = pos;
        this.scrollRow = Math.max(0, scrollRow);
        this.sortModeOrdinal = sortModeOrdinal;
        this.filteredCount = Math.max(0, filteredCount);
        this.visible = visible == null ? List.of() : new ArrayList<>(visible);
    }

    public KitchenStorageSyncPacket(FriendlyByteBuf buffer) {
        this.pos = buffer.readBlockPos();
        this.scrollRow = Math.max(0, buffer.readVarInt());
        this.sortModeOrdinal = buffer.readVarInt();
        this.filteredCount = Math.max(0, buffer.readVarInt());
        int n = buffer.readVarInt();
        List<ItemStack> list = new ArrayList<>(PacketGuard.clampCount(n));
        for (int i = 0; i < n; i++) {
            list.add(buffer.readItem());
        }
        this.visible = list;
    }

    public static KitchenStorageSyncPacket decode(FriendlyByteBuf buffer) {
        return new KitchenStorageSyncPacket(buffer);
    }

    public void encode(FriendlyByteBuf buffer) {
        buffer.writeBlockPos(pos);
        buffer.writeVarInt(scrollRow);
        buffer.writeVarInt(sortModeOrdinal);
        buffer.writeVarInt(filteredCount);
        buffer.writeVarInt(visible.size());
        for (ItemStack stack : visible) {
            buffer.writeItem(stack);
        }
    }

    public void handle(Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        if (!PacketGuard.fromServer("KitchenStorageSyncPacket", context)) {
            context.setPacketHandled(true);
            return;
        }
        context.enqueueWork(() -> {
            var player = context.getSender();
            if (player == null) {
                return;
            }
            // 只作用于「玩家当前正开着的那个中央厨房界面」——
            // 界面已关掉时 containerMenu 已经不是它，直接丢弃。
            if (player.containerMenu instanceof CentralKitchenMenu menu
                    && menu.getMachine() != null
                    && menu.getMachine().getBlockPos().equals(pos)) {
                menu.applyStorageSnapshot(scrollRow, sortModeOrdinal, filteredCount, visible);
            }
        });
        context.setPacketHandled(true);
    }
}
