package cn.ism.mekck.network;

import cn.ism.mekck.compat.AE2Compat;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 本模组下单面板的“ME 网络下单”：客户端 → 服务端。
 * 服务端先从 ME 网络抽取 quantity 份材料放入机器，再按配方下单。
 */
public class NetworkOrderPacket {
    /**
     * 订单份数上限：与中央厨房 GUI（{@code KitchenOrderWindow} 的 +1/×2 按钮）同源的 9999。
     *
     * <p>三个下单包（本包 / {@link KitchenOrderPacket} / {@link OrderRecipePacket}）共用这一个常量：
     * 机器侧 {@code setOrder} 只夹下界，客户端可控的份数不设上限时，材料耗尽后机器仍持有巨额订单、
     * {@code ownerBusy()} 恒真；中央厨房那条还会把份数交给 {@code KitchenCraftingPlan.solve}
     * （{@code need} 随份数增长，int 乘法会溢出）。</p>
     */
    public static final int MAX_ORDER_QUANTITY = 9999;

    /** 把客户端可控的订单份数夹到 [1, {@link #MAX_ORDER_QUANTITY}]（超限按上限处理，不拒绝）。 */
    public static int clampQuantity(int quantity) {
        return Math.max(1, Math.min(MAX_ORDER_QUANTITY, quantity));
    }

    private final BlockPos pos;
    private final String recipeId;
    private final int quantity;
    /** 调味料 id：仅烧烤工厂这类三参 setOrder 的机器使用，空串 = 不调味。 */
    private final String seasoningId;

    public NetworkOrderPacket(BlockPos pos, String recipeId, int quantity) {
        this(pos, recipeId, quantity, null);
    }

    public NetworkOrderPacket(BlockPos pos, String recipeId, int quantity, String seasoningId) {
        this.pos = pos;
        this.recipeId = recipeId;
        this.quantity = quantity;
        this.seasoningId = seasoningId == null ? "" : seasoningId;
    }

    public static void encode(NetworkOrderPacket packet, FriendlyByteBuf buf) {
        buf.writeBlockPos(packet.pos);
        buf.writeUtf(packet.recipeId == null ? "" : packet.recipeId);
        buf.writeInt(packet.quantity);
        buf.writeUtf(packet.seasoningId);
    }

    public static NetworkOrderPacket decode(FriendlyByteBuf buf) {
        BlockPos pos = buf.readBlockPos();
        String id = buf.readUtf();
        int quantity = buf.readInt();
        String seasoning = buf.readUtf();
        return new NetworkOrderPacket(pos, id, quantity, seasoning);
    }

    public static void handle(NetworkOrderPacket packet, Supplier<NetworkEvent.Context> context) {
        context.get().enqueueWork(() -> {
            ServerPlayer player = context.get().getSender();
            if (packet.quantity <= 0 || packet.recipeId.isEmpty()) return;
            net.minecraft.world.level.block.entity.BlockEntity machine = PacketGuard.target(player, packet.pos);
            if (machine == null) return;
            // 客户端可控的 quantity 必须夹上限：机器侧 setOrder 只夹下界，任意大的订单会让
            // ownerBusy() 恒真、AE job 永不释放（finding 20261005）。超限按上限处理，不拒绝。
            int quantity = clampQuantity(packet.quantity);
            // 节流：pullNetworkIngredients 每包做一次全网扫描（extractAll 单遍扫全网 +
            // 逐需求项 Ingredient.test），客户端可无限刷 ⇒ 与 KitchenOrderPacket 同款三态闸。
            // 本包没有回包：DENY 与 ALLOW_CACHED 都不重算。
            long fingerprint = PacketGuard.fingerprint(packet.pos.asLong(), packet.recipeId.hashCode(),
                    quantity, packet.seasoningId.hashCode());
            if (PacketGuard.expensiveRequestState(player, fingerprint)
                    != PacketGuard.ExpensiveRequest.ALLOW_COMPUTE) {
                return;
            }
            // 抽料 → 插机器 → 建 AE 任务 → 下单，全部按机器类型在 MekckAe2 内部**通用分派**：
            // 烹饪工厂 / 穿串工厂走各自专用分支，其余机器（烧烤工厂 / 智能厨锅 / 智能穿串机 /
            // 中央厨房 / 联动机器…）走通用分支（终端样板同源）。
            AE2Compat.pullNetworkIngredients(machine, packet.recipeId, quantity,
                    packet.seasoningId.isEmpty() ? null : packet.seasoningId);
        });
        context.get().setPacketHandled(true);
    }
}
