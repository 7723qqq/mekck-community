package cn.ism.mekck.network;

import cn.ism.mekck.blockentity.CentralKitchenBlockEntity;
import cn.ism.mekck.kitchen.KitchenFamily;
import cn.ism.mekck.kitchen.KitchenFilter;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 中央厨房系列过滤器设置。
 *
 * <p>mode 0 = 切换过滤模式；1 = 添加一种过滤材料；2 = 移除第 index 项；3 = 清空。</p>
 */
public final class KitchenFilterPacket {

    private final BlockPos pos;
    private final int familyOrdinal;
    private final byte action;
    private final int index;
    private final ItemStack stack;

    public KitchenFilterPacket(BlockPos pos, int familyOrdinal, byte action, int index, ItemStack stack) {
        this.pos = pos;
        this.familyOrdinal = familyOrdinal;
        this.action = action;
        this.index = index;
        this.stack = stack == null ? ItemStack.EMPTY : stack;
    }

    public KitchenFilterPacket(FriendlyByteBuf buffer) {
        this.pos = buffer.readBlockPos();
        this.familyOrdinal = buffer.readVarInt();
        this.action = buffer.readByte();
        this.index = buffer.readVarInt();
        this.stack = buffer.readItem();
    }

    public static KitchenFilterPacket decode(FriendlyByteBuf buffer) {
        return new KitchenFilterPacket(buffer);
    }

    public void encode(FriendlyByteBuf buffer) {
        buffer.writeBlockPos(pos);
        buffer.writeVarInt(familyOrdinal);
        buffer.writeByte(action);
        buffer.writeVarInt(index);
        buffer.writeItem(stack);
    }

    public void handle(Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (!(PacketGuard.target(player, pos) instanceof CentralKitchenBlockEntity kitchen)) return;
            KitchenFamily[] families = KitchenFamily.values();
            if (familyOrdinal < 0 || familyOrdinal >= families.length) return;
            KitchenFilter filter = kitchen.filterOf(families[familyOrdinal]);
            switch (action) {
                case 0 -> filter.cycleMode();
                case 1 -> filter.add(stack);
                case 2 -> filter.remove(index);
                case 3 -> filter.clear();
                case 4 -> {
                    // 仅请求同步，不做修改
                }
                default -> {
                }
            }
            kitchen.setChanged();
            kitchen.sendFilterSync(player);
        });
        context.setPacketHandled(true);
    }
}
