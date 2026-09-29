package cn.ism.mekck.network;

import cn.ism.mekck.blockentity.CentralKitchenBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** 中央厨房下单请求：mode 0 = 预览合成链；mode 1 = 正式下单。 */
public final class KitchenOrderPacket {
    private final BlockPos pos;
    private final byte mode;
    private final String recipeId;
    private final int count;

    public KitchenOrderPacket(BlockPos pos, byte mode, String recipeId, int count) {
        this.pos = pos;
        this.mode = mode;
        this.recipeId = recipeId == null ? "" : recipeId;
        this.count = count;
    }

    public KitchenOrderPacket(FriendlyByteBuf buffer) {
        this.pos = buffer.readBlockPos();
        this.mode = buffer.readByte();
        this.recipeId = buffer.readUtf(256);
        this.count = buffer.readInt();
    }

    public static KitchenOrderPacket decode(FriendlyByteBuf buffer) {
        return new KitchenOrderPacket(buffer);
    }

    public void encode(FriendlyByteBuf buffer) {
        buffer.writeBlockPos(pos);
        buffer.writeByte(mode);
        buffer.writeUtf(recipeId, 256);
        buffer.writeInt(count);
    }

    public void handle(Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null) return;
            var level = player.level();
            var be = PacketGuard.target(player, pos);
            if (!(be instanceof CentralKitchenBlockEntity kitchen)) return;
            String result;
            if (mode == 0) {
                result = kitchen.previewOrder(level, net.minecraft.resources.ResourceLocation.tryParse(recipeId),
                        Math.max(1, count));
            } else {
                String err = kitchen.placeOrder(level, net.minecraft.resources.ResourceLocation.tryParse(recipeId),
                        Math.max(1, count));
                result = err == null ? "§a下单成功：" + recipeId + " ×" + Math.max(1, count) : "§c" + err;
            }
            cn.ism.mekck.network.ModMessages.sendToPlayer(
                    new KitchenOrderResultPacket(mode, result), player);
        });
        context.setPacketHandled(true);
    }
}
