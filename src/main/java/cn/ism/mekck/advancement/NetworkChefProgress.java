package cn.ism.mekck.advancement;

import cn.ism.mekck.ae2.INetworkPullable;
import cn.ism.mekck.blockentity.CookingFactoryBlockEntity;
import cn.ism.mekck.blockentity.SkeweringFactoryBlockEntity;
import cn.ism.mekck.machine.ports.IMekCkPorted;
import net.minecraft.advancements.Advancement;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * “网络厨师学徒”进度的公共逻辑（不依赖任何 AE2 类，AE2 未安装时主模组可正常加载）。
 * <ul>
 *   <li>触发器 ID：{@code mekck:network_connected}（{@link NetworkConnectedTrigger#ID}）；</li>
 *   <li>进度资源 ID：{@code mekck:network_chef_apprentice}（{@link #ADVANCEMENT_ID}）——查找/完成检查一律用此 ID；</li>
 *   <li>放置者记录与 NBT 持久化：{@link PlacerPersist}；</li>
 *   <li>离线待授予：{@link PendingGrantData}（Overworld 统一存档）+ 有界内存重试队列（ServerTick 调度）。</li>
 * </ul>
 */
public final class NetworkChefProgress {

    /** 进度资源 ID（子进度）。触发器 ID 见 {@link NetworkConnectedTrigger#ID}。 */
    public static final net.minecraft.resources.ResourceLocation ADVANCEMENT_ID =
            new net.minecraft.resources.ResourceLocation("mekck", "network_chef_apprentice");

    /** 内存重试队列：UUID → 剩余重试次数（服务端主线程访问；仅队列非空时处理，不每 tick 全服扫描）。 */
    private static final Map<UUID, Integer> RETRY = new HashMap<>();
    private static final int MAX_RETRIES = 100; // 最多重试 100 tick（约 5 秒）

    private NetworkChefProgress() {
    }

    /** 是否为接入 ME 网络的 mekck 机器（与 MekckAe2.attachCapabilities 判定一致，但无 AE2 类依赖）。 */
    public static boolean isAe2Machine(BlockEntity be) {
        return be instanceof CookingFactoryBlockEntity
                || be instanceof SkeweringFactoryBlockEntity
                // 端口声明型机器（阶段 2 Task 4.6 起：切菜工厂已换成 CuttingFactoryTile）。
                // 放在 INetworkPullable 之前只是阅读顺序，instanceof 之间互不影响。
                || be instanceof IMekCkPorted
                || be instanceof INetworkPullable;
    }

    /** 记录放置者（服务端，放置事件触发）。 */
    public static void recordPlacer(BlockEntity be, UUID placerUuid) {
        if (be == null || placerUuid == null) return;
        PlacerPersist.set(be, placerUuid);
    }

    /**
     * 首次联网：授予放置者；放置者离线则持久化待授予（登录/重载/资源就绪后补授）。
     * 由 MekckAe2（AE2 专属层）在服务端真实联网沿边触发时调用。
     */
    public static void handleNetworkConnected(ServerLevel level, UUID placerUuid) {
        if (level == null || level.getServer() == null || placerUuid == null) return;
        ServerPlayer placer = level.getServer().getPlayerList().getPlayers().stream()
                .filter(p -> p.getUUID().equals(placerUuid))
                .findFirst().orElse(null);
        if (placer != null) {
            NetworkConnectedTrigger.get().trigger(placer);
        } else {
            PendingGrantData data = PendingGrantData.get(level.getServer());
            if (data != null) {
                data.addPending(placerUuid);
            }
        }
    }

    /** 玩家登录：尝试补授予；未就绪则入队延迟重试。 */
    public static void grantPendingOnLogin(ServerPlayer player) {
        if (player == null) return;
        if (!tryGrantPending(player)) {
            scheduleRetry(player.getUUID());
        }
    }

    /** 服务端数据包同步完成（玩家登录/资源重载时触发）——玩家数据就绪的重试入口。 */
    public static void onDatapackSync(ServerPlayer player) {
        if (player == null) return;
        if (!tryGrantPending(player)) {
            scheduleRetry(player.getUUID());
        }
    }

    /** 服务端 tick：处理有界重试队列（仅在队列非空时执行，单线程主线程）。 */
    public static void onServerTick(net.minecraft.server.MinecraftServer server) {
        if (RETRY.isEmpty()) return;
        Iterator<Map.Entry<UUID, Integer>> it = RETRY.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Integer> e = it.next();
            ServerPlayer player = findOnline(server, e.getKey());
            if (player != null) {
                if (tryGrantPending(player)) {
                    it.remove();
                    continue;
                }
            }
            int left = e.getValue() - 1;
            if (left <= 0) {
                it.remove(); // 有界重试结束：保留 SavedData pending，等待下次登录/重载
            } else {
                e.setValue(left);
            }
        }
    }

    /** 尝试补授予：目标 advancement 已加载且触发后 isDone 确认完成，才删除 pending。 */
    private static boolean tryGrantPending(ServerPlayer player) {
        if (player.getServer() == null) return false;
        PendingGrantData data = PendingGrantData.get(player.getServer());
        if (data == null || !data.contains(player.getUUID())) return true; // 无待授予：视为成功（清理队列）
        Advancement adv = player.getServer().getAdvancements().getAdvancement(ADVANCEMENT_ID);
        if (adv == null) return false; // 进度资源未加载：保留 pending，延迟重试
        NetworkConnectedTrigger.get().trigger(player);
        if (player.getAdvancements().getOrStartProgress(adv).isDone()) {
            data.removePending(player.getUUID());
            return true;
        }
        return false; // 触发后未确认完成：保留 pending
    }

    private static void scheduleRetry(UUID uuid) {
        if (uuid == null) return;
        RETRY.putIfAbsent(uuid, MAX_RETRIES);
    }

    private static ServerPlayer findOnline(net.minecraft.server.MinecraftServer server, UUID uuid) {
        if (server == null) return null;
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (p.getUUID().equals(uuid)) return p;
        }
        return null;
    }
}
