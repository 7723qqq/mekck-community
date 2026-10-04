package cn.ism.mekck.network;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 客户端 → 服务端包的访问校验。
 *
 * <p><b>为什么必须校验</b>：这些包全部由客户端构造，服务端只拿到一个 {@link BlockPos}。
 * 早期各处只做 {@code level.hasChunkAt(pos)} —— 那只能说明「区块已加载」，
 * <b>不构成接近性校验</b>：任何客户端都能把坐标指向世界中任意已加载的机器并改其状态
 * （侧面配置、升级装卸、订单下发、从 ME 网络抽料）。</p>
 *
 * <p><b>半径取 64.0（8 格）</b>：与本模组所有菜单的 {@code stillValid} 和原版
 * {@code AbstractContainerMenu} 的判定一致。取同一数值可保证「菜单还开着 ⟺ 包被接受」，
 * 不会出现「GUI 能操作但按钮无效」的空隙。</p>
 *
 * <p><b>方向断言见 {@link #fromServer}</b>：反向（客户端伪造 S2C 包）此前完全没有校验。</p>
 */
public final class PacketGuard {

    private static final Logger LOGGER = LoggerFactory.getLogger(PacketGuard.class);

    /**
     * 允许交互的距离平方上限（8 格）。
     *
     * <p>用平方值做比较可省掉 {@link Math#sqrt}；与原版
     * {@code ServerPlayer#canInteractWith} 系列实现保持同一量纲。</p>
     */
    public static final double INTERACT_RANGE_SQR = 64.0D;

    /**
     * 解码器允许预分配的元素数上限。
     *
     * <p>解码发生在 Netty 线程，此时还谈不上「合法性」，但 {@code readVarInt()} 是纯攻击者
     * 可控值：直接 {@code new ArrayList<>(n)} 会让一个几十字节的包申请 GB 级数组并触发
     * {@code OutOfMemoryError}。因此预分配前一律先夹紧。</p>
     *
     * <p>取 4096：本模组最长的清单是 ME 网络可合成配方表，实际量级在数百条；4096 留足余量，
     * 同时把单包的内存放大倍数从 ~2^31 压到 ~2^12。</p>
     */
    public static final int MAX_DECODE_ELEMENTS = 4096;

    private PacketGuard() {
    }

    /**
     * 把网络上读到的元素数夹到合法区间。
     *
     * <p>只夹紧预分配大小，<b>不改变协议</b>：随后仍按原始计数逐个读取，
     * 因此越界的包会在读流耗尽时正常抛错，而不是被静默截断成一份看似合法的数据。</p>
     */
    public static int clampCount(int raw) {
        return Math.max(0, Math.min(raw, MAX_DECODE_ELEMENTS));
    }

    // ==================== 昂贵只读请求的节流（第三轮补）====================

    /**
     * 昂贵请求的最小间隔（tick）。
     *
     * <p>5 = 250 ms。取值理由：人类在 GUI 上点「预览」或改数量的节奏远快于这个值，
     * 所以对正常玩家是<b>不可感知</b>的；而它把一个玩家可无限触发的昂贵操作
     * 压到每秒 4 次。</p>
     */
    private static final int EXPENSIVE_COOLDOWN_TICKS = 5;

    /** 玩家 UUID → (上次放行时的游戏时刻, 上次放行的请求指纹)。 */
    private static final java.util.Map<java.util.UUID, long[]> EXPENSIVE_COOLDOWN =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 「昂贵只读/预览」请求的节流闸：同一玩家在冷却期内<b>只放行与上次完全相同</b>的请求。
     *
     * <h3>为什么需要它</h3>
     * {@code CentralKitchenBlockEntity.previewOrder} 与 {@code placeOrder} 都会走
     * {@code KitchenCraftingPlan.solve} → {@code buildReverseIndex}，对已安装系列的
     * <b>全部</b> {@code recipeTypes} 逐条取 {@code recipe.getResultItem(...)} 并新建 HashMap。
     * {@code RecipeCache} 只缓存了配方<b>列表</b>，{@code getResultItem} 每次都真调。
     * 装满 18 个系列时这是每包一次全模组配方扫描。
     *
     * <p>而 {@code mode == 0} 的<b>预览不消耗任何材料</b>，客户端可以纯刷 ——
     * 一个玩家发几百个包就能把服务端主线程打满。</p>
     *
     * <h3>为什么是「同请求去重」而不是「一律拒绝」</h3>
     * 一律拒绝会让「连点两次预览」第二次没反应，看起来像 bug。而 GUI 的自然操作里
     * <b>重复同一个请求</b>本来就是幂等的（结果一样），所以：冷却期内只有
     * <b>与上次完全相同</b>的请求被静默放行（省掉重复计算），任何<b>不同</b>的请求被拒。
     * 玩家的下一次真实操作（换了配方/数量）仍然立即生效。</p>
     *
     * <p>被拒时<b>不回错误提示</b>：节流是内部实现细节，不是玩家的错误。
     * 静默丢弃即可（客户端下一次真实操作自然会拿到新结果）。</p>
     *
     * @param fingerprint 请求指纹；同指纹在冷却期内视为重复，直接放行
     * @return true = 放行；false = 冷却中且请求不同，应静默忽略
     */
    public static boolean expensiveRequest(ServerPlayer player, long fingerprint) {
        if (player == null) {
            return false;
        }
        long now = player.level() == null ? 0 : player.level().getGameTime();
        long[] slot = EXPENSIVE_COOLDOWN.get(player.getUUID());
        if (slot == null) {
            EXPENSIVE_COOLDOWN.put(player.getUUID(), new long[]{now, fingerprint});
            return true;
        }
        if (slot[1] == fingerprint) {
            // 重复请求：结果必然相同，放行但**不刷新时刻**。
            // 刷新时刻会让「连点」变成永远通不过（每次都被当成新请求）的反面极端。
            return true;
        }
        if (now - slot[0] < EXPENSIVE_COOLDOWN_TICKS) {
            return false;
        }
        slot[0] = now;
        slot[1] = fingerprint;
        return true;
    }

    /** 玩家离开时清掉其节流记录（不清理也不影响正确性，只是回收 Map）。 */
    public static void forgetCooldown(ServerPlayer player) {
        if (player != null) {
            EXPENSIVE_COOLDOWN.remove(player.getUUID());
        }
    }

    // ==================== 面板只读请求的最短刷新间隔（第六轮补）====================

    /**
     * 面板只读请求（ME 可下单列表 / 缺料清单）触发全量样板重建的最短间隔（tick）。
     *
     * <p>10 = 0.5s。取值理由：客户端连点刷新、快速切换配方/数量时，同一批请求会在几 tick
     * 内到达，0.5s 窗口足以把它们合并成一次重建；而超过 0.5s 的真实操作会立刻重建，
     * 面板不会「过期」。一次全量重建要重扫全网库存并逐条配方构建样板
     * （{@code MekckAe2.refreshPatterns}），必须限制频率，否则客户端可无限刷新打满服务端主线程。</p>
     */
    public static final int PANEL_REFRESH_MIN_TICKS = 10;

    /**
     * 本次面板请求是否应执行真实刷新（纯逻辑，便于普通 JVM 单测）。
     *
     * <p><b>只回答「要不要重建样板」，从不代表「丢弃请求」</b>：返回 false 时调用方仍必须用
     * 上次缓存的结果回响应。这正是与 {@link #expensiveRequest} 的取舍差别 —— 后者在冷却期
     * 对不同请求返回 false 即「静默丢弃」，若照搬到面板上会让打开面板的首个请求落空、面板空白；
     * 面板是只读的，窗口内缓存结果与实时结果的差异可忽略，所以选择
     * <b>合并（reuse）而非丢弃（drop）</b>，保证「任何请求都得到响应，首个请求必刷」。</p>
     *
     * @param nowTick          服务端当前游戏刻（{@code Level#getGameTime}）
     * @param lastRefreshTick  上次真实刷新的刻；{@code <0} 表示从未刷新
     * @param minIntervalTicks 最短间隔
     * @return true = 应真实刷新；false = 复用缓存结果
     */
    public static boolean panelRefreshDue(long nowTick, long lastRefreshTick, int minIntervalTicks) {
        if (lastRefreshTick < 0) {
            // 从未刷新（含面板打开的首个请求）→ 必须刷新，否则会回空缓存造成面板空白。
            return true;
        }
        if (nowTick < lastRefreshTick) {
            // 存档重载 / 时钟回拨：间隔不可比，保守地刷新一次。
            return true;
        }
        return nowTick - lastRefreshTick >= minIntervalTicks;
    }

    /**
     * 该玩家是否有权操作此坐标上的方块实体。
     *
     * @return 坐标区块已加载<b>且</b>玩家在 {@link #INTERACT_RANGE_SQR} 之内
     */
    public static boolean allowed(ServerPlayer player, BlockPos pos) {
        if (player == null || pos == null) return false;
        Level level = player.level();
        if (level == null || !level.hasChunkAt(pos)) return false;
        return player.distanceToSqr(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D)
                <= INTERACT_RANGE_SQR;
    }

    /**
     * 通过校验后返回该坐标的方块实体，否则返回 null。
     *
     * <p>调用方仍应自行做 {@code instanceof} 类型判定；本方法只负责「能不能碰」，
     * 不负责「是不是我认识的那台机器」。</p>
     */
    public static BlockEntity target(ServerPlayer player, BlockPos pos) {
        return allowed(player, pos) ? player.level().getBlockEntity(pos) : null;
    }

    // ==================== 方向断言（服务端 → 客户端包）====================

    /**
     * 「这是不是真的来自服务端」—— S2C 包处理器的第一行。
     *
     * <h3>为什么需要它</h3>
     * Forge 的 {@code SimpleChannel} 不会因为包在 {@code ModMessages} 里登记的方向而拒绝
     * 反向投递：客户端完全可以构造一个本该由服务端下发的包发给服务端，
     * 于是 {@code handle} 里的<b>客户端分支代码会在服务端执行</b>。
     * 对专用服务端而言这是一条从未被审过的路径（写静态表 / 反射加载客户端桥 / 空实现）。
     *
     * <p>这与 {@link #allowed} 是<b>互补</b>而非重复：{@code allowed} 回答
     * 「客户端能不能碰这台机器」，本方法回答「这个包该不该由这一侧处理」。
     * C2S 包靠前者，S2C 包靠后者 —— 现在 22 个包（17 C2S + 5 S2C）全部受检：
     * 17 个 C2S 都走 {@code allowed/target}，5 个 S2C 都走 {@code fromServer}
     * （数字由 {@code TestPacketGuardCoverage} 机械校验，改包时以测试为准）。
     * 而 5 个 S2C 一个方向校验都没有。</p>
     *
     * <p>被拒时只记一次 WARN 并返回 false，调用方应立刻 {@code setPacketHandled(true)}
     * 并返回 —— <b>不要</b>继续 enqueueWork。</p>
     *
     * @param packetName 仅用于日志，取 {@code getClass().getSimpleName()} 或字面量
     * @return true = 方向正确，可以处理；false = 客户端伪造的 S2C 包，应丢弃
     */
    public static boolean fromServer(String packetName, NetworkEvent.Context ctx) {
        if (ctx == null) {
            return false;
        }
        if (ctx.getDirection() == NetworkDirection.PLAY_TO_CLIENT) {
            return true;
        }
        LOGGER.warn("丢弃方向错误的包 {}：期望 PLAY_TO_CLIENT，实际 {} —— "
                        + "多半是客户端伪造了 S2C 包（本模组所有 S2C 包都应由服务端下发）",
                packetName, ctx.getDirection());
        return false;
    }
}
