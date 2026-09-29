package cn.ism.mekck.network;

import cn.ism.mekck.client.CookingFactoryScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * ME 网络可下单配方列表（服务端 → 客户端），供烹饪工厂下单面板使用。
 */
public class NetworkRecipeListPacket {
    private final BlockPos pos;
    private final List<String> recipeIds;
    private final Map<String, Integer> maxCraftable;

    public NetworkRecipeListPacket(BlockPos pos, List<String> recipeIds, Map<String, Integer> maxCraftable) {
        this.pos = pos;
        this.recipeIds = new ArrayList<>(recipeIds);
        this.maxCraftable = new LinkedHashMap<>(maxCraftable);
    }

    public static void encode(NetworkRecipeListPacket packet, FriendlyByteBuf buf) {
        buf.writeBlockPos(packet.pos);
        buf.writeVarInt(packet.recipeIds.size());
        for (String id : packet.recipeIds) {
            buf.writeUtf(id);
        }
        buf.writeVarInt(packet.maxCraftable.size());
        for (Map.Entry<String, Integer> e : packet.maxCraftable.entrySet()) {
            buf.writeUtf(e.getKey());
            buf.writeInt(e.getValue());
        }
    }

    public static NetworkRecipeListPacket decode(FriendlyByteBuf buf) {
        BlockPos pos = buf.readBlockPos();
        int n = buf.readVarInt();
        List<String> ids = new ArrayList<>(PacketGuard.clampCount(n));
        for (int i = 0; i < n; i++) {
            ids.add(buf.readUtf());
        }
        int m = buf.readVarInt();
        Map<String, Integer> max = new LinkedHashMap<>();
        for (int i = 0; i < m; i++) {
            max.put(buf.readUtf(), buf.readInt());
        }
        return new NetworkRecipeListPacket(pos, ids, max);
    }

    public static void handle(NetworkRecipeListPacket packet, Supplier<NetworkEvent.Context> context) {
        context.get().enqueueWork(() -> {
            Minecraft mc = Minecraft.getInstance();
            // 烹饪 / 穿串工厂：沿用各自屏幕内已验收的 ME 段
            if (mc.screen instanceof CookingFactoryScreen screen
                    && screen.getMenu().getBlockPos().equals(packet.pos)) {
                screen.setNetworkOrderData(packet.recipeIds, packet.maxCraftable);
            } else if (mc.screen instanceof cn.ism.mekck.client.SkeweringFactoryScreen screen
                    && screen.getMenu().getBlockPos().equals(packet.pos)) {
                screen.setNetworkOrderData(packet.recipeIds, packet.maxCraftable);
            } else if (mc.screen instanceof cn.ism.mekck.client.NetworkOrderHost host) {
                // 其余屏幕（烧烤工厂 / 智能穿串机 / 智能厨锅 / 中央厨房下单窗…）：共用面板
                cn.ism.mekck.client.NetworkOrderPanel panel = host.networkOrderPanel();
                if (panel != null) panel.setData(packet.pos, packet.recipeIds, packet.maxCraftable);
            }
        });
        context.get().setPacketHandled(true);
    }
}
