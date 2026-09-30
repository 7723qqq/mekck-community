package cn.ism.mekck.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** 中央厨房下单结果（预览或下单反馈）。 */
public final class KitchenOrderResultPacket {
    private final byte mode;
    private final String message;

    public KitchenOrderResultPacket(byte mode, String message) {
        this.mode = mode;
        this.message = message == null ? "" : message;
    }

    public KitchenOrderResultPacket(FriendlyByteBuf buffer) {
        this.mode = buffer.readByte();
        this.message = buffer.readUtf(32767);
    }

    public static KitchenOrderResultPacket decode(FriendlyByteBuf buffer) {
        return new KitchenOrderResultPacket(buffer);
    }

    public void encode(FriendlyByteBuf buffer) {
        buffer.writeByte(mode);
        buffer.writeUtf(message, 32767);
    }

    /** 最近一次预览结果（客户端界面读取）。 */
    public static volatile String lastPreview = "";
    /** 最近一次下单反馈。 */
    public static volatile String lastResult = "";

    public void handle(Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        if (!PacketGuard.fromServer("KitchenOrderResultPacket", context)) {
            context.setPacketHandled(true);
            return;
        }
        context.enqueueWork(() -> {
            if (mode == 0) lastPreview = message;
            else lastResult = message;
        });
        context.setPacketHandled(true);
    }
}
