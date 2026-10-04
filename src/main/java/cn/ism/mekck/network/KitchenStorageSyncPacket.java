package cn.ism.mekck.network;

import cn.ism.mekck.blockentity.CentralKitchenBlockEntity;
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
 * <h3>计数为什么单独走 VarInt</h3>
 * 原版 {@code FriendlyByteBuf.writeItem} 内部是 {@code writeByte(getCount())}，
 * 而存储槽上限是 {@code BIG_STACK = Integer.MAX_VALUE - 1}：300 会被截成 44、
 * 200 会被截成 -56（{@code isEmpty()} 为真 ⇒ 整格画成空）。
 * 所以每个 stack 先写 {@code writeVarInt(count)}，再写一个计数为 1 的 stack
 * （物品与 NBT 照旧），解码时用读到的计数 {@code setCount} —— 与
 * {@code BigStackItemHandler} 的 {@code McCount} 同思路。
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
            int count = buffer.readVarInt();
            ItemStack stack = buffer.readItem();
            if (stack.isEmpty() || count <= 0) {
                list.add(ItemStack.EMPTY);
            } else {
                stack.setCount(Math.min(count, CentralKitchenBlockEntity.BIG_STACK));
                list.add(stack);
            }
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
            buffer.writeVarInt(stack.getCount());
            buffer.writeItem(stack.copyWithCount(1));
        }
    }

    /** 解码后的可见页（往返护栏断言用；落地路径经 {@link #handle} 直接消费）。 */
    List<ItemStack> visible() {
        return visible;
    }

    public void handle(Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        if (!PacketGuard.fromServer("KitchenStorageSyncPacket", context)) {
            context.setPacketHandled(true);
            return;
        }
        context.enqueueWork(() -> {
            // 不能在这里向 Context 索取发送方玩家：本包是 S2C，客户端侧那个取值恒为 null
            // （packet listener 是 ClientPacketListener），旧实现因此每次都提前返回 ⇒
            // 快照从不落地、界面 54 格恒空。玩家只能由客户端实现类去取，故走门面。
            cn.ism.mekck.network.ClientPacketBridge.applyStorageSnapshot(
                    pos, scrollRow, sortModeOrdinal, filteredCount, visible);
        });
        context.setPacketHandled(true);
    }
}
