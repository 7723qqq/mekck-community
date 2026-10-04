package cn.ism.mekck.blockentity;

import cn.ism.mekck.entity.FerreroEntity;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 巧克力大炮「飞行中伤害预留」的注册表，按“世界实例”严格管理。
 * <p>
 * 大炮发射费列罗后，为每枚飞行中的弹药登记一条 {@link ReservedShot}：记录它的预测爆炸中心（精确 Vec3）、
 * 保守可预期伤害、爆炸范围、所属世界实例与发射时刻。后续所有（同一世界内的）大炮索敌时，
 * 通过 {@link #reservedDamageOn} 累加落在某个敌人身上的飞行中预留伤害，据此扣除已经被
 * “预计可被炸死”的敌人收益，避免重复开火浪费弹药。
 * </p>
 * <p>
 * <b>世界作用域</b>：记录用 {@link ServerLevel} 的弱引用标识所属世界实例。读取、超时清理都要求
 * “同实例”才生效，因此同 JVM 中即使维度相同、不同世界实例也不会互相读取预留；世界卸载后弱引用失效，
 * 对应记录在下次 {@link #pruneExpired} 时被清除，不长期强引用已卸载世界（无泄漏）。
 * </p>
 * <p>
 * <b>伤害语义</b>：预留只记录“保守的可预期伤害”（原始伤害 × {@link #RESERVATION_DAMAGE_RATIO}），
 * <em>绝不宣称必杀</em>。真实伤害结算受护甲/伤害减免/无敌/处决白名单/免疫等分支影响，且索敌不触发真实
 * 伤害事件；因此按折扣后的下限估算，宁可少扣预留（可能多打一发的余量），也不因高估预留而漏打或误判必杀。
 * </p>
 * <p>
 * <b>清理时机</b>：正常爆炸 / 落出世界 / 任何移除路径由弹体显式 {@link #release}（幂等）；
 * 若大炮被拆除、实体在异常路径未能回报，则由属于该世界的 {@link #pruneExpired} 超时兜底。
 * </p>
 */
public final class ChocolateCannonReservations {

    /** 单发弹药从发射到落地的安全超时（刻）。正常情况下 5 个方块下落约 5 刻，这里留足余量。 */
    public static final long MAX_FLIGHT_TICKS = 100;

    /**
     * 预留伤害策略：统一用原始伤害（不乘任何折扣）。
     * 对“可处决、无特殊减免”的普通目标，原始伤害=预计清空其剩余血线的量，因此 180 伤害会覆盖 180 血目标，
     * 使第二发与其他大炮都看到剩余=0 → 不再重复开火；对 hp > 伤害 的目标自然保留剩余可补射。
     * 对可能受护甲/减免/免疫影响而无法保证处决的目标（{@link FerreroEntity#isExecutionWhitelisted}），
     * {@link #reservedDamageOn} 返回 0 预留 —— 保守处理：这类目标始终保留剩余收益，允许补射，不把“预计伤害”冒充“真实必杀保证”。
     * 本策略刻意区分“预计有效伤害”（普通目标，用于阻止重复开火）与“真实保证伤害”（白名单目标，恒为 0 预留）。
     */

    /** 飞行中弹药的预留记录。 */
    public record ReservedShot(
            ResourceKey<Level> dimension,
            WeakReference<ServerLevel> world,
            Vec3 center,
            UUID projectileId,
            float damage,
            float range,
            long spawnTick) {
    }

    private static final Map<UUID, ReservedShot> ACTIVE = new ConcurrentHashMap<>();

    private ChocolateCannonReservations() {
    }

    /** 为大炮刚发射的 {@code projectile} 登记一条预留，返回该弹的预留键（弹药 UUID）。 */
    public static UUID reserve(ChocolateCannonBlockEntity cannon, FerreroEntity projectile,
                               Vec3 center, float damage, byte flags) {
        if (cannon == null || projectile == null || center == null) return null;
        Level level = cannon.getLevel();
        if (!(level instanceof ServerLevel serverLevel)) return null;
        UUID key = projectile.getUUID();
        long tick = serverLevel.getServer() != null ? serverLevel.getServer().getTickCount() : serverLevel.getGameTime();
        ACTIVE.put(key, new ReservedShot(level.dimension(),
                new WeakReference<>(serverLevel), center, key,
                damage, // 原始伤害；处决白名单目标的保守降级在 reservedDamageOn 层处理
                FerreroEntity.getExplosionRange(flags), tick));
        return key;
    }

    /** 弹体命中/落出世界/被销毁时调用：立即释放本弹的预留（幂等，重复调用安全）。 */
    public static void release(FerreroEntity projectile) {
        if (projectile == null) return;
        ACTIVE.remove(projectile.getUUID());
    }

    /**
     * 预留伤害的最终口径（纯函数，生产与源码级测试共用同一实现，避免口径漂移）。
     * 对处决白名单目标（可能受护甲/减免/免疫影响，处决不保证）强制返回 0 —— 这些目标始终保留剩余收益、允许补射；
     * 对普通目标返回累计的原始预留伤害（180 预留会覆盖 180 剩余生命 → 第二发及它炮不重复开火）。
     */
    public static float reservedDamageForTarget(boolean executionWhitelisted, float rawReservation) {
        return executionWhitelisted ? 0.0F : rawReservation;
    }

    /**
     * 累加<em>同一世界实例</em>内所有飞行中弹药对 {@code enemy} 的（原始伤害）预留，
     * 并通过 {@link #reservedDamageForTarget} 应用处决白名单保守口径。
     */
    public static float reservedDamageOn(Level level, LivingEntity enemy) {
        if (level == null || enemy == null) return 0.0F;
        if (ACTIVE.isEmpty()) return 0.0F;
        float total = 0.0F;
        // 碰撞箱只取一次：原先每个 (候选敌人 × 每条预留) 组合都会走
        // isEntityInBlast → AABB.ofSize + getBoundingBox，两处分配；这里是纯算术判定，零分配。
        net.minecraft.world.phys.AABB box = enemy.getBoundingBox();
        for (ReservedShot rs : ACTIVE.values()) {
            if (!sameWorld(rs, level)) continue;
            if (blastHitsBox(rs.center(), rs.range(), box)) {
                total += rs.damage();
            }
        }
        return reservedDamageForTarget(FerreroEntity.isExecutionWhitelisted(enemy), total);
    }

    /**
     * 与 {@code AABB.ofSize(center, range, range, range).intersects(box)} 完全等价的纯算术判定（零分配）。
     * 中心坐标直接取 {@code center.x/y/z}，等价于原先传 {@code Vec3} 的做法。
     */
    private static boolean blastHitsBox(net.minecraft.world.phys.Vec3 center, float range, net.minecraft.world.phys.AABB box) {
        double half = range / 2.0;
        return box.minX < center.x + half && box.maxX > center.x - half
                && box.minY < center.y + half && box.maxY > center.y - half
                && box.minZ < center.z + half && box.maxZ > center.z - half;
    }

    /**
     * 按世界实例做超时兜底清理：只处理目标世界 {@code level} 的记录。
     * 规则：记录所属世界已卸载（弱引用已失效）→ 一律移除；记录仍属于 {@code level} 但已超龄 → 移除。
     * 使用目标世界自己的时间 {@code level.getGameTime()}，因此不同世界不会互相误删；时间回拨时差值非正不会误删、也不会永久滞留（下轮正常推进后仍会清理）。
     */
    public static void pruneExpired(Level level) {
        if (level == null) {
            return;
        }
        long now = (level instanceof ServerLevel slv && slv.getServer() != null)
                ? slv.getServer().getTickCount()   // 服务器单调 tick，不受 /time set 或存档回拨影响
                : level.getGameTime();
        ACTIVE.values().removeIf(rs -> {
            ServerLevel world = rs.world() == null ? null : rs.world().get();
            if (world == null) return true;          // 所属世界已卸载 → 清
            if (world != level) return false;        // 别的世界实例的预留不动
            return now - rs.spawnTick() > MAX_FLIGHT_TICKS;
        });
    }

    /**
     * 确定性清理：移除指定世界实例的全部预留。world 卸载 / 服务器停止时由调用方显式调用。
     */
    public static void clearWorld(ServerLevel world) {
        if (world == null) return;
        ACTIVE.values().removeIf(rs -> rs.world() != null && rs.world().get() == world);
    }

    /** 确定性清理：清空全部预留（服务器整体停止/切换存档时调用）。 */
    public static void clearAll() {
        ACTIVE.clear();
    }

    private static boolean sameWorld(ReservedShot rs, Level level) {
        ServerLevel world = rs.world() == null ? null : rs.world().get();
        return world == level; // 同一 ServerLevel 实例才视为同世界
    }
}
