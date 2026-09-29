package cn.ism.mekck.network;

import cn.ism.mekck.util.ClientPacketBridge;
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
        NetworkEvent.Context ctx = context.get();
        // ⚠️ 这里**不能**直接写 Minecraft.getInstance() 或引用 cn.ism.mekck.client.*。
        // 本包在 FMLCommonSetupEvent（双端都触发）里统一注册，所以本类在专用服务端
        // 也要被链接，而服务端 classpath 上没有 net.minecraft.client.*。
        // 客户端逻辑全部关在 ClientPacketBridgeImpl（@OnlyIn(CLIENT)，由门面反射加载）。
        // 背景与同类事故见 util/ClientPacketBridge 的类注释。
        ctx.enqueueWork(() -> ClientPacketBridge.applyRecipeList(
                packet.pos, packet.recipeIds, packet.maxCraftable));
        ctx.setPacketHandled(true);
    }
}
