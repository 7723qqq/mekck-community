package cn.ism.mekck.event;

import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import static cn.ism.mekck.UniversalCuttingMachine.MOD_ID;

/**
 * 服务端侧事件：网络厨师学徒进度的公共触发点（方块放置 / 玩家登录 / 数据包同步 / 服务端 tick）。
 *
 * <p>本类原为 {@code UniversalCuttingMachine} 的内嵌事件订阅类；注册中枢拆分时
 * 升格为顶层类（去掉 {@code static} 修饰，方法体逐字未改）。</p>
 */
@Mod.EventBusSubscriber(modid = MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class NetworkAdvancementEvents {

/**
 * FORGE 总线（服务端触发）订阅：网络厨师学徒进度的公共事件。
 * 所有处理都在不依赖 AE2 类的 {@code cn.ism.mekck.advancement.NetworkChefProgress}（可选联动安全：
 * 未安装 AE2 时本类与主模组仍可正常加载；联网触发由 MekckAe2 经 AE2Compat 门面接入）。
 */
    private NetworkAdvancementEvents() {
    }

    @SubscribeEvent
    public static void onBlockPlaced(net.minecraftforge.event.level.BlockEvent.EntityPlaceEvent event) {
        if (!(event.getLevel() instanceof net.minecraft.world.level.Level lv) || lv.isClientSide) return;
        if (!(event.getEntity() instanceof net.minecraft.server.level.ServerPlayer placer)) return;
        net.minecraft.world.level.block.entity.BlockEntity be = event.getLevel().getBlockEntity(event.getPos());
        if (be == null) return; // 防御：正常时序 BE 已创建（setBlock → attachCapabilities → setPlacedBy → 事件）
        if (!cn.ism.mekck.advancement.NetworkChefProgress.isAe2Machine(be)) return;
        cn.ism.mekck.advancement.NetworkChefProgress.recordPlacer(be, placer.getUUID());
    }

    @SubscribeEvent
    public static void onPlayerLogin(net.minecraftforge.event.entity.player.PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof net.minecraft.server.level.ServerPlayer sp) {
            cn.ism.mekck.advancement.NetworkChefProgress.grantPendingOnLogin(sp);
        }
    }

    /** 服务端数据包同步完成（玩家登录 / 资源重载）——玩家数据就绪时补授予，覆盖“在线但资源后加载”。 */
    @SubscribeEvent
    public static void onDatapackSync(net.minecraftforge.event.OnDatapackSyncEvent event) {
        if (event.getPlayer() != null) {
            cn.ism.mekck.advancement.NetworkChefProgress.onDatapackSync(event.getPlayer());
        }
    }

    /** 服务端 tick：处理有界重试队列（仅在队列非空时执行）。 */
    @SubscribeEvent
    public static void onServerTick(net.minecraftforge.event.TickEvent.ServerTickEvent event) {
        if (event.phase == net.minecraftforge.event.TickEvent.Phase.END && event.getServer() != null) {
            cn.ism.mekck.advancement.NetworkChefProgress.onServerTick(event.getServer());
        }
    }
}
