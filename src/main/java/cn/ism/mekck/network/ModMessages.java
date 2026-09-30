package cn.ism.mekck.network;

import cn.ism.mekck.UniversalCuttingMachine;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

public final class ModMessages {
    /**
     * 协议版本 —— 两端 {@code PROTOCOL_VERSION::equals} 双向严格相等才允许连接。
     *
     * <h3>为什么从 "1" 升到 "2"</h3>
     * 第四轮清理删除了 8 个<b>已无发送方</b>的包（详见类尾「协议演进」一节）。
     * 这些包在 <b>1.0.0 基线</b>（提交 {@code 47f5ba8}）里是有发送方的，
     * 因此已发布的 1.0.0 客户端仍会构造它们。若只删注册不改版本号，
     * 老客户端连上来后发 id 1/4/5/9/11/12/17 会命中「未知包 id」——
     * 表现是晦涩的断线而不是清晰的版本不匹配。
     *
     * <p>升号让 Forge 在握手阶段就按标准消息拒绝，老玩家看到的是
     * 「客户端与服务端版本不一致」而不是莫名其妙的包错误。
     * <b>代价</b>：1.0.0 客户端不能连本版本服务端 —— 这本来就是事实
     * （注册表/配方已变），只是现在把它显式化了。</p>
     *
     * <p><b>改包格式时也要升它。</b>本类所有包 id 是手工编号且<b>刻意留空洞</b>
     * （删掉的 id 不回填），所以新增包直接用下一个空号即可，不要重排 ——
     * 重排会让「同一个 id 在两个版本指不同包」，比留空洞危险得多。</p>
     */
    private static final String PROTOCOL_VERSION = "2";
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
        // 空洞 1：AutoDistributePacket 已删（自动分配随旧 BE 退场，无发送方）。
        CHANNEL.registerMessage(2, OrderRecipePacket.class,
                OrderRecipePacket::encode,
                OrderRecipePacket::decode,
                OrderRecipePacket::handle);
        CHANNEL.registerMessage(3, RedstoneControlPacket.class,
                RedstoneControlPacket::encode,
                RedstoneControlPacket::new,
                RedstoneControlPacket::handle);
        // 空洞 4/5：GrillSeasoningOrderPacket 与 SkewerThreadingOrderPacket 已删
        // （调味料/穿串「订单」实际走 OrderRecipePacket，这两个从来没有稳定的发送方）。
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
        // 空洞 9/10/11：AutoProcess{ListRequest,List,Toggle}Packet 三件套已删。
        // ME 自动处理面板已随 Mek 原生化界面退场，三个包互相喂、全仓零发送方；
        // 背景见 CuttingMachineFactoryScreen 与 GrindingFactoryScreen 类注释。
        // 空洞 12：GrillWorkModePacket 已删（烧烤工作模式改走 tile 内状态，无发送方）。
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
        // 空洞 17：MeOrderTogglePacket 已删（ME 下单开关改走 KitchenOrderPacket 的 mode 字段）。
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

    public static void sendToServer(GrillSeasoningTogglePacket message) {
        CHANNEL.send(PacketDistributor.SERVER.noArg(), message);
    }

    public static void sendToServer(IceAttackConfigPacket message) {
        CHANNEL.send(PacketDistributor.SERVER.noArg(), message);
    }

    public static void sendToServer(UpgradeUninstallPacket message) {
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

    // ==================== 协议演进 ====================
    //
    // 已删除的包 id（**刻意留空洞，不要回填、不要重排**）：
    //
    // | id  | 包 | 为什么删 |
    // |-----|-----|---|
    // | 1   | AutoDistributePacket | 「自动分配」随旧 BlockEntity 退场，Mek 化界面不再建这个开关 |
    // | 4   | GrillSeasoningOrderPacket | 烧烤调味料订单实际走 OrderRecipePacket |
    // | 5   | SkewerThreadingOrderPacket | 穿串订单实际走 OrderRecipePacket（该包自述「从无发送方」） |
    // | 9   | AutoProcessListRequestPacket | ME 自动处理面板已退场 |
    // | 10  | AutoProcessListPacket | 同上，S2C 侧仅由 9/11 喂，两者皆删后无生产者 |
    // | 11  | AutoProcessTogglePacket | 同上 |
    // | 12  | GrillWorkModePacket | 烧烤工作模式改走 tile 内状态 |
    // | 17  | MeOrderTogglePacket | ME 下单开关改走 KitchenOrderPacket 的 mode 字段 |
    //
    // 核查口径：`git log -S "sendToServer(new <Packet>" --all` + 全仓 `new <Packet>(` 检索。
    // 其中 6 个在 1.0.0 基线（`47f5ba8`）里**确实有过发送方**，是 Mek 原生化迁移
    // （`9a93561`/`16748a4`/`01b5641`/`257c97a`）把界面改掉后才失去的 ——
    // 因此已发布的 1.0.0 客户端仍会构造它们，这就是 PROTOCOL_VERSION 必须升号的原因。
    //
    // 护栏：TestPacketGuardCoverage 逐包核对「注册表 ↔ 发送端」方向一致性。
}