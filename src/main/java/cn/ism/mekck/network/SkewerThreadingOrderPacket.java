package cn.ism.mekck.network;

import cn.ism.mekck.machine.skewering.SkeweringFactoryTile;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * 穿串工厂的烟火联动下单包：
 * - recipeId 非空：固定穿串配方订单
 * - customIngredients 非空：自选组合穿串订单（产物为烟火未完成烤串）
 * - 两者都空：取消订单
 */
public class SkewerThreadingOrderPacket {
    private final BlockPos pos;
    private final ResourceLocation recipeId;
    private final int quantity;
    private final List<ItemStack> customIngredients;

    public SkewerThreadingOrderPacket(BlockPos pos, ResourceLocation recipeId, int quantity, List<ItemStack> customIngredients) {
        this.pos = pos;
        this.recipeId = recipeId;
        this.quantity = quantity;
        this.customIngredients = customIngredients == null ? List.of() : customIngredients;
    }

    public static void encode(SkewerThreadingOrderPacket packet, FriendlyByteBuf buf) {
        buf.writeBlockPos(packet.pos);
        buf.writeUtf(packet.recipeId == null ? "" : packet.recipeId.toString());
        buf.writeInt(packet.quantity);
        buf.writeVarInt(packet.customIngredients.size());
        for (ItemStack stack : packet.customIngredients) {
            buf.writeItem(stack);
        }
    }

    public static SkewerThreadingOrderPacket decode(FriendlyByteBuf buf) {
        BlockPos pos = buf.readBlockPos();
        String id = buf.readUtf();
        ResourceLocation recipeId = id.isEmpty() ? null : ResourceLocation.tryParse(id);
        int quantity = buf.readInt();
        int count = buf.readVarInt();
        List<ItemStack> custom = new ArrayList<>(PacketGuard.clampCount(count));
        for (int i = 0; i < count; i++) {
            custom.add(buf.readItem());
        }
        return new SkewerThreadingOrderPacket(pos, recipeId, quantity, custom);
    }

    public static void handle(SkewerThreadingOrderPacket packet, Supplier<NetworkEvent.Context> context) {
        context.get().enqueueWork(() -> {
            ServerPlayer player = context.get().getSender();
            if (PacketGuard.target(player, packet.pos) instanceof SkeweringFactoryTile machine) {
                // 自选组合与固定配方是<b>两条互斥的路</b>（见执行器的 findRecipe）：
                // 自选组合现场拼一张虚拟配方，固定配方按 id 查。
                // 因此这里按「有没有自选材料」分流，不做「先设固定再被自选覆盖」。
                if (packet.customIngredients.isEmpty()) {
                    if (packet.recipeId == null) {
                        machine.clearOrder();
                    } else {
                        machine.setOrder(packet.recipeId, packet.quantity);
                    }
                } else {
                    List<String> ids = new ArrayList<>(packet.customIngredients.size());
                    for (ItemStack stack : packet.customIngredients) {
                        // 只存物品 id、每项恒 1 个：与旧存档的 OrderCustomIngredients 逐字同款。
                        // 数量在 orderQuantity 里，不在材料表里。
                        if (stack.isEmpty()) {
                            continue;
                        }
                        ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
                        if (id != null) {
                            ids.add(id.toString());
                        }
                    }
                    machine.setCustomOrder(ids, packet.quantity);
                }
            }
        });
        context.get().setPacketHandled(true);
    }
}
