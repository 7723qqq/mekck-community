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
            // 节流：previewOrder / placeOrder 每次都走 solve → buildReverseIndex，
            // 对已安装系列的**全部** recipeTypes 逐条取 getResultItem 并新建 HashMap
            // （RecipeCache 只缓存了配方列表，getResultItem 每次都真调）。
            // 而 mode==0 的预览不消耗任何材料 ⇒ 客户端可以纯刷打满主线程。
            // 三态闸门：冷却期内的同指纹重复回上次结果（不重算），不同请求静默忽略。
            // 指纹必须含机器坐标：节流槽按玩家存，不含坐标会把两台机器的同参数请求
            // 误判为重复（回错结果 / 吞掉真实下单）。详见 PacketGuard#expensiveRequestState。
            long fingerprint = PacketGuard.fingerprint(pos.asLong(), mode, recipeId.hashCode(),
                    Math.max(1, count));
            PacketGuard.ExpensiveRequest gate = PacketGuard.expensiveRequestState(player, fingerprint);
            if (gate == PacketGuard.ExpensiveRequest.DENY) {
                return;
            }
            if (gate == PacketGuard.ExpensiveRequest.ALLOW_CACHED) {
                Object cached = PacketGuard.cachedResult(player, fingerprint);
                if (cached instanceof String text) {
                    cn.ism.mekck.network.ModMessages.sendToPlayer(
                            new KitchenOrderResultPacket(mode, text), player);
                    return;
                }
                // 缓存缺失只可能出现在「上次计算抛异常」的路径：退回真实计算，保证请求必有响应。
            }
            String result;
            if (mode == 0) {
                result = kitchen.previewOrder(level, net.minecraft.resources.ResourceLocation.tryParse(recipeId),
                        Math.max(1, count));
            } else {
                String err = kitchen.placeOrder(level, net.minecraft.resources.ResourceLocation.tryParse(recipeId),
                        Math.max(1, count));
                result = err == null ? "§a下单成功：" + recipeId + " ×" + Math.max(1, count) : "§c" + err;
            }
            PacketGuard.rememberResult(player, fingerprint, result);
            cn.ism.mekck.network.ModMessages.sendToPlayer(
                    new KitchenOrderResultPacket(mode, result), player);
        });
        context.setPacketHandled(true);
    }
}
