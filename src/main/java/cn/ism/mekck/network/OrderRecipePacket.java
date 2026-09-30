package cn.ism.mekck.network;

import cn.ism.mekck.blockentity.CentralKitchenBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public class OrderRecipePacket {
    private final BlockPos pos;
    private final ResourceLocation recipeId;
    private final int quantity;

    public OrderRecipePacket(BlockPos pos, ResourceLocation recipeId, int quantity) {
        this.pos = pos;
        this.recipeId = recipeId;
        this.quantity = quantity;
    }

    public static void encode(OrderRecipePacket packet, FriendlyByteBuf buf) {
        buf.writeBlockPos(packet.pos);
        buf.writeUtf(packet.recipeId == null ? "" : packet.recipeId.toString());
        buf.writeInt(packet.quantity);
    }

    public static OrderRecipePacket decode(FriendlyByteBuf buf) {
        BlockPos pos = buf.readBlockPos();
        String id = buf.readUtf();
        ResourceLocation recipeId = id.isEmpty() ? null : ResourceLocation.tryParse(id);
        return new OrderRecipePacket(pos, recipeId, buf.readInt());
    }

    /** 反射设置订单：命中任一签名即返回 true（recipeId=null / quantity=0 表示取消订单）。 */
    private static boolean setOrderReflectively(net.minecraft.world.level.block.entity.BlockEntity be,
                                                ResourceLocation recipeId, int quantity) {
        try {
            java.lang.reflect.Method two = cn.ism.mekck.util.Reflect.method(be.getClass(), "setOrder",
                    ResourceLocation.class, int.class);
            if (two != null) {
                two.invoke(be, recipeId, quantity);
                return true;
            }
            java.lang.reflect.Method three = cn.ism.mekck.util.Reflect.method(be.getClass(), "setOrder",
                    ResourceLocation.class, int.class, String.class);
            if (three != null) {
                three.invoke(be, recipeId, quantity, null);
                return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    public static void handle(OrderRecipePacket packet, Supplier<NetworkEvent.Context> context) {
        context.get().enqueueWork(() -> {
            ServerPlayer player = context.get().getSender();
            if (player != null) {
                net.minecraft.world.level.block.entity.BlockEntity be = PacketGuard.target(player, packet.pos);
                if (be == null) return;
                // 通用分派：各机器的 setOrder 签名不同（多数 (ResourceLocation,int)，
                // 烧烤工厂是 (ResourceLocation,int,String=调味)，中央厨房走 placeOrder）。
                // 原来写死 4 台的 instanceof 阶梯已改为反射 ⇒ **新机器（含 14 台联动机器）无需再改这里**。
                //
                // quantity 是客户端可控的 int，这里必须夹下界：13 个 setOrder 实现里有 4 个
                // （SmartCookingPot / SkeweringMachine / SimpleMachine / GrillBlockEntity）
                // 把 quantity 原样存进 orderQuantity。负数会让订单门禁（orderQuantity > 0）
                // 与订单推进（orderCompleted >= orderQuantity）同时失效 ⇒ orderRecipeId 永久非 null、
                // 订单永不完成；AE2 侧 orderStateOf 读到 OrderState(true, 负数) ⇒
                // processJob 永久早退、job 永不释放。
                // recipeId == null 是「取消订单」（两个 GUI 都发 (null, 0)），必须原样透传，
                // 不能被夹成「下 1 件」。
                int quantity = packet.recipeId == null ? packet.quantity : Math.max(1, packet.quantity);
                if (!setOrderReflectively(be, packet.recipeId, quantity)
                        && be instanceof CentralKitchenBlockEntity kitchen && packet.recipeId != null) {
                    // 与 KitchenOrderPacket 同一道节流：placeOrder 每次都走
                    // solve → buildReverseIndex（对已安装系列的全部 recipeTypes 逐条
                    // 取 getResultItem 并新建 HashMap）。这里虽然会真的建订单因而
                    // 受材料约束，但**恶意客户端可以把同一订单反复下单**，
                    // 每次都是一次全模组配方扫描。
                    if (!PacketGuard.expensiveRequest(player,
                            packet.recipeId.hashCode() * 31L + Math.max(1, quantity))) {
                        return;
                    }
                    kitchen.placeOrder(player.level(), packet.recipeId, Math.max(1, quantity));
                }
            }
        });
        context.get().setPacketHandled(true);
    }
}