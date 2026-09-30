package cn.ism.mekck.network;

import cn.ism.mekck.UniversalCuttingMachine;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

public final class ModMessages {
    private static final String PROTOCOL_VERSION = "1";
    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(UniversalCuttingMachine.MOD_ID, "side_config"),
            () -> PROTOCOL_VERSION,
            PROTOCOL_VERSION::equals,
            PROTOCOL_VERSION::equals);

    private ModMessages() {
    }

    public static void register() {
        CHANNEL.registerMessage(0, SideConfigPacket.class,
                SideConfigPacket::encode,
                SideConfigPacket::new,
                SideConfigPacket::handle);
        CHANNEL.registerMessage(1, AutoDistributePacket.class,
                AutoDistributePacket::encode,
                AutoDistributePacket::new,
                AutoDistributePacket::handle);
        CHANNEL.registerMessage(2, OrderRecipePacket.class,
                OrderRecipePacket::encode,
                OrderRecipePacket::decode,
                OrderRecipePacket::handle);
        CHANNEL.registerMessage(3, RedstoneControlPacket.class,
                RedstoneControlPacket::encode,
                RedstoneControlPacket::new,
                RedstoneControlPacket::handle);
        CHANNEL.registerMessage(4, GrillSeasoningOrderPacket.class,
                GrillSeasoningOrderPacket::encode,
                GrillSeasoningOrderPacket::decode,
                GrillSeasoningOrderPacket::handle);
        CHANNEL.registerMessage(5, SkewerThreadingOrderPacket.class,
                SkewerThreadingOrderPacket::encode,
                SkewerThreadingOrderPacket::decode,
                SkewerThreadingOrderPacket::handle);
        CHANNEL.registerMessage(6, NetworkOrderPacket.class,
                NetworkOrderPacket::encode,
                NetworkOrderPacket::decode,
                NetworkOrderPacket::handle);
        CHANNEL.registerMessage(7, NetworkRecipeRequestPacket.class,
                NetworkRecipeRequestPacket::encode,
                NetworkRecipeRequestPacket::decode,
                NetworkRecipeRequestPacket::handle);
        CHANNEL.registerMessage(8, NetworkRecipeListPacket.class,
                NetworkRecipeListPacket::encode,
                NetworkRecipeListPacket::decode,
                NetworkRecipeListPacket::handle);
        CHANNEL.registerMessage(9, AutoProcessListRequestPacket.class,
                AutoProcessListRequestPacket::encode,
                AutoProcessListRequestPacket::decode,
                AutoProcessListRequestPacket::handle);
        CHANNEL.registerMessage(10, AutoProcessListPacket.class,
                AutoProcessListPacket::encode,
                AutoProcessListPacket::decode,
                AutoProcessListPacket::handle);
        CHANNEL.registerMessage(11, AutoProcessTogglePacket.class,
                AutoProcessTogglePacket::encode,
                AutoProcessTogglePacket::decode,
                AutoProcessTogglePacket::handle);
        CHANNEL.registerMessage(12, GrillWorkModePacket.class,
                GrillWorkModePacket::encode,
                GrillWorkModePacket::decode,
                GrillWorkModePacket::handle);
        CHANNEL.registerMessage(13, GrillSeasoningTogglePacket.class,
                GrillSeasoningTogglePacket::encode,
                GrillSeasoningTogglePacket::decode,
                GrillSeasoningTogglePacket::handle);
        CHANNEL.registerMessage(14, IceAttackConfigPacket.class,
                IceAttackConfigPacket::encode,
                IceAttackConfigPacket::new,
                IceAttackConfigPacket::handle);
        CHANNEL.registerMessage(15, NetworkPullPacket.class,
                NetworkPullPacket::encode,
                NetworkPullPacket::decode,
                NetworkPullPacket::handle);
        CHANNEL.registerMessage(16, UpgradeUninstallPacket.class,
                UpgradeUninstallPacket::encode,
                UpgradeUninstallPacket::decode,
                UpgradeUninstallPacket::handle);
        CHANNEL.registerMessage(17, MeOrderTogglePacket.class,
                MeOrderTogglePacket::encode,
                MeOrderTogglePacket::decode,
                MeOrderTogglePacket::handle);
        CHANNEL.registerMessage(18, KitchenViewPacket.class,
                KitchenViewPacket::encode,
                KitchenViewPacket::decode,
                KitchenViewPacket::handle);
        CHANNEL.registerMessage(19, KitchenOrderPacket.class,
                KitchenOrderPacket::encode,
                KitchenOrderPacket::decode,
                KitchenOrderPacket::handle);
        CHANNEL.registerMessage(20, KitchenOrderResultPacket.class,
                KitchenOrderResultPacket::encode,
                KitchenOrderResultPacket::decode,
                KitchenOrderResultPacket::handle);
        CHANNEL.registerMessage(21, SandwichConfigPacket.class,
                SandwichConfigPacket::encode,
                SandwichConfigPacket::decode,
                SandwichConfigPacket::handle);
        CHANNEL.registerMessage(22, KitchenFilterPacket.class,
                KitchenFilterPacket::encode,
                KitchenFilterPacket::decode,
                KitchenFilterPacket::handle);
        CHANNEL.registerMessage(23, KitchenFilterSyncPacket.class,
                KitchenFilterSyncPacket::encode,
                KitchenFilterSyncPacket::decode,
                KitchenFilterSyncPacket::handle);
        // 面板 ME 缺料清单（AE 终端式「缺少 X × N」）
        CHANNEL.registerMessage(24, NetworkMissingRequestPacket.class,
                NetworkMissingRequestPacket::encode,
                NetworkMissingRequestPacket::decode,
                NetworkMissingRequestPacket::handle);
        CHANNEL.registerMessage(25, NetworkMissingPacket.class,
                NetworkMissingPacket::encode,
                NetworkMissingPacket::decode,
                NetworkMissingPacket::handle);
        // 陈酿机「清空果汁」按钮（复用 Mekanism GuiDumpButton 外观，点击走本包）
        CHANNEL.registerMessage(26, WineryClearJuicePacket.class,
                WineryClearJuicePacket::encode,
                WineryClearJuicePacket::new,
                WineryClearJuicePacket::handle);
        // 陈化窖（F20）倍速设定：GUI 敲数字回车后把 S∈[1,50] 发到服务端写 BE
        CHANNEL.registerMessage(27, WineCellarConfigPacket.class,
                WineCellarConfigPacket::encode,
                WineCellarConfigPacket::new,
                WineCellarConfigPacket::handle);
        // 工厂输入槽自动分选开关（Mek 的 GuiSortingTab 对应物）
        CHANNEL.registerMessage(28, MekCkSortingTogglePacket.class,
                MekCkSortingTogglePacket::encode,
                MekCkSortingTogglePacket::decode,
                MekCkSortingTogglePacket::handle);
    }

    public static void sendToServer(SideConfigPacket message) {
        CHANNEL.send(PacketDistributor.SERVER.noArg(), message);
    }

    public static void sendToServer(AutoDistributePacket message) {
        CHANNEL.send(PacketDistributor.SERVER.noArg(), message);
    }

    public static void sendToServer(OrderRecipePacket message) {
        CHANNEL.send(PacketDistributor.SERVER.noArg(), message);
    }

    public static void sendToServer(RedstoneControlPacket message) {
        CHANNEL.send(PacketDistributor.SERVER.noArg(), message);
    }

    public static void sendToServer(WineryClearJuicePacket message) {
        CHANNEL.send(PacketDistributor.SERVER.noArg(), message);
    }

    public static void sendToServer(WineCellarConfigPacket message) {
        CHANNEL.send(PacketDistributor.SERVER.noArg(), message);
    }

    public static void sendToServer(MekCkSortingTogglePacket message) {
        CHANNEL.send(PacketDistributor.SERVER.noArg(), message);
    }

    public static void sendToServer(GrillSeasoningOrderPacket message) {
        CHANNEL.send(PacketDistributor.SERVER.noArg(), message);
    }

    public static void sendToServer(SkewerThreadingOrderPacket message) {
        CHANNEL.send(PacketDistributor.SERVER.noArg(), message);
    }

    public static void sendToServer(NetworkOrderPacket message) {
        CHANNEL.send(PacketDistributor.SERVER.noArg(), message);
    }

    public static void sendToServer(NetworkPullPacket message) {
        CHANNEL.send(PacketDistributor.SERVER.noArg(), message);
    }

    public static void sendToServer(NetworkRecipeRequestPacket message) {
        CHANNEL.send(PacketDistributor.SERVER.noArg(), message);
    }

    public static void sendToServer(NetworkMissingRequestPacket message) {
        CHANNEL.send(PacketDistributor.SERVER.noArg(), message);
    }

    public static void sendToPlayer(NetworkMissingPacket message, net.minecraft.server.level.ServerPlayer player) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), message);
    }

    public static void sendToPlayer(NetworkRecipeListPacket message, net.minecraft.server.level.ServerPlayer player) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), message);
    }

    public static void sendToServer(AutoProcessListRequestPacket message) {
        CHANNEL.send(PacketDistributor.SERVER.noArg(), message);
    }

    public static void sendToServer(AutoProcessTogglePacket message) {
        CHANNEL.send(PacketDistributor.SERVER.noArg(), message);
    }

    public static void sendToServer(GrillWorkModePacket message) {
        CHANNEL.send(PacketDistributor.SERVER.noArg(), message);
    }

    public static void sendToServer(GrillSeasoningTogglePacket message) {
        CHANNEL.send(PacketDistributor.SERVER.noArg(), message);
    }

    public static void sendToServer(IceAttackConfigPacket message) {
        CHANNEL.send(PacketDistributor.SERVER.noArg(), message);
    }

    public static void sendToServer(UpgradeUninstallPacket message) {
        CHANNEL.send(PacketDistributor.SERVER.noArg(), message);
    }

    public static void sendToServer(MeOrderTogglePacket message) {
        CHANNEL.send(PacketDistributor.SERVER.noArg(), message);
    }

    public static void sendToServer(KitchenViewPacket message) {
        CHANNEL.send(PacketDistributor.SERVER.noArg(), message);
    }

    public static void sendToServer(KitchenOrderPacket message) {
        CHANNEL.send(PacketDistributor.SERVER.noArg(), message);
    }

    public static void sendToServer(SandwichConfigPacket message) {
        CHANNEL.send(PacketDistributor.SERVER.noArg(), message);
    }

    public static void sendToServer(KitchenFilterPacket message) {
        CHANNEL.send(PacketDistributor.SERVER.noArg(), message);
    }

    public static void sendToPlayer(KitchenFilterSyncPacket message,
                                    net.minecraft.server.level.ServerPlayer player) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), message);
    }

    public static void sendToPlayer(KitchenOrderResultPacket message,
                                    net.minecraft.server.level.ServerPlayer player) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), message);
    }

    public static void sendToPlayer(AutoProcessListPacket message, net.minecraft.server.level.ServerPlayer player) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), message);
    }
}