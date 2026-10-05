package cn.ism.mekck.util;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

/**
 * 冷萃攻击索敌工具：在以机器为中心的球形范围内收集候选目标，按距离升序返回。
 * <p>
 * 半径上限已放开至 {@link Integer#MAX_VALUE}，不能无条件用
 * {@link Level#getEntitiesOfClass(Class, AABB, Predicate)} 查询：半径极大时
 * AABB 远超世界边界，{@code EntitySectionStorage} 的 section 坐标打包成 long
 * 后溢出（start &gt; end），直接抛 {@code IllegalArgumentException} 崩溃
 * （2026-08-29 实测崩溃根因）。
 * </p>
 * <p>
 * 因此大半径时改为直接遍历 {@code ServerLevel} 已加载的全部实体按球形距离过滤——
 * 这也是“无限半径”的语义上限：只有已加载区块内的实体才可能被索敌。
 * </p>
 */
public final class IceTargetSearch {

    /**
     * 攻击半径的<b>硬上限</b> —— 4 台机器的 {@code setRadius} / {@code adjustRadius} 共用这一道闸。
     *
     * <h3>为什么必须封顶（第三轮审查实测的 TPS 杀手）</h3>
     * 四个 BE 的 {@code setRadius} 原本<b>只有下限没有上限</b>（{@code Math.max(4, r)}），
     * {@code adjustRadius} 更是直白地 {@code Math.min(…, Integer.MAX_VALUE)}。
     * 而半径来自网络包：{@code IceAttackConfigPacket} 的 {@code value} 是裸 {@code readInt}，
     * 走到 {@code case 2 -> machine.setRadius(value)}，包头注释自己写着
     * 「服务端钳制到 ≥4，<b>上限不限</b>」。{@code PacketGuard.INTERACT_RANGE_SQR = 64}
     * 意味着任何玩家站到机器 8 格内就能发这个包。
     *
     * <p>配合 {@code IceTargetSearch} 的大半径分支（遍历 {@code serverLevel.getEntities().getAll()}）
     * 与「创造升级让 {@code attackTimer = 1}」，就是：<b>一台机器 + 一个包 = 永久的每 tick
     * 全服实体遍历 + 距离判定 + 排序</b>。多台即直接吃掉 TPS。</p>
     *
     * <p>512 这个数还有个附带问题：它同时是 {@link #AABB_SCAN_MAX_RADIUS} 的旧值，
     * 而 AABB 快速路径的成本是「与 AABB 相交的每一个 section」，半径 512 ⇒ 约
     * {@code 65³ ≈ 27 万} 次 section 查找<b>每次攻击一次</b> —— 比它自己那条
     * 「遍历全实体」的兜底路径<b>更贵</b>。见 {@link #AABB_SCAN_MAX_RADIUS}。</p>
     */
    public static final int MAX_ATTACK_RADIUS = 256;

    /** 攻击半径的下限（GUI 与包的共同下界）。 */
    public static final int MIN_ATTACK_RADIUS = 4;

    /**
     * 目标类型的<b>合法取值数</b>：{@code 0 = 敌对 / 1 = 全部 / 2 = 动物}。
     *
     * <p>四个攻击型 BE 各自的 {@code TARGET_HOSTILE / TARGET_ALL / TARGET_ANIMAL} 常量都是
     * 0/1/2，因此这里只用一个上界即可覆盖全部。与半径同理：<b>不要在四个 BE 里各写一份夹紧</b>。</p>
     */
    public static final int TARGET_TYPE_COUNT = 3;

    /**
     * 把目标类型夹到 {@code [0, TARGET_TYPE_COUNT - 1]}。
     *
     * <h3>为什么需要它</h3>
     * {@code IceAttackConfigPacket} 的 {@code mode == 0} 会把客户端给的 {@code value} 直接交给
     * {@code setTargetType(...)}。半径那一支早已走 {@link #clampAttackRadius}，但类型这一支
     * <b>四个 BE 全都原样赋值</b>。虽然 {@code matchesTarget} 的 {@code switch} 有
     * {@code default -> hostile} 兜底、不会崩，但越界值会：
     * <ul>
     *   <li>写进存档（{@code tag.putInt("TargetType", ...)}）并在重载后继续生效；</li>
     *   <li>让 GUI 的显示（{@code t == 0 ? hostile : t == 1 ? all : animal}）与实际行为不一致，
     *       玩家看到的是「动物」而机器打的是「敌对」。</li>
     * </ul>
     * 与半径保持同一道闸，不一致本身就是缺陷。
     */
    public static int clampTargetType(int type) {
        return Math.max(0, Math.min(TARGET_TYPE_COUNT - 1, type));
    }

    // ==================== 索敌结果缓存 ====================

    /**
     * 候选列表的复用 tick 数。
     *
     * <p>4 = 0.2 秒。取值理由同 {@code ChocolateCannonBlockEntity#CANDIDATE_CACHE_TICKS}：
     * 目标的选择与伤害判定仍然每 tick 重算，只有「重新扫一遍世界里的实体」被节流，
     * 因此可见影响仅是<b>新进入射程的敌人最多晚 0.2 秒被发现</b>。</p>
     */
    public static final int CANDIDATE_CACHE_TICKS = 4;

    /**
     * 一台机器的索敌缓存。
     *
     * <p>四个攻击型 BE 里原本只有 {@code ChocolateCannonBlockEntity} 有这份缓存，
     * 而 {@code IceMakerTile} / {@code NutRoasterTile} 装上创造升级后
     * {@code attackTimer = 1} ⇒ <b>每 tick 攻击一次</b>，再叠上半径 &gt; 64 走
     * {@code getEntities().getAll()} 的全服实体遍历分支，两三台就能吃掉 TPS。
     * 抽到这里是为了让四台机器共用同一份实现，而不是各写一份。</p>
     *
     * <p><b>失效条件</b>：半径、目标类型变化，或缓存超过 {@link #CANDIDATE_CACHE_TICKS} tick。
     * 缓存里可能留有已死亡的实体，调用方在遍历时仍需判 {@code isAlive()}（原本就该判）。</p>
     */
    public static final class CandidateCache {
        private java.util.List<LivingEntity> cached;
        private long cachedTick = Long.MIN_VALUE;
        private int cachedRadius = -1;
        private int cachedTargetType = -1;

        /**
         * 取本 tick 的候选目标；缓存有效时直接复用，否则重新扫描。
         *
         * @param filter 目标类型过滤（与 {@link #findTargets} 的 {@code filter} 同义）
         */
        public java.util.List<LivingEntity> get(Level level, BlockPos machinePos, int radius,
                                                int targetType, Predicate<LivingEntity> filter) {
            long now = level.getGameTime();
            if (cached != null
                    && cachedRadius == radius
                    && cachedTargetType == targetType
                    && now >= cachedTick
                    && now - cachedTick < CANDIDATE_CACHE_TICKS) {
                return cached;
            }
            java.util.List<LivingEntity> found = findTargets(level, machinePos, radius, filter);
            cached = found;
            cachedTick = now;
            cachedRadius = radius;
            cachedTargetType = targetType;
            return found;
        }

        /** 半径/类型被外部改掉时由调用方主动清一次（不调也没错，最多多用 4 tick 旧值）。 */
        public void invalidate() {
            cached = null;
        }
    }

    /**
     * 半径把攻击半径夹到 {@code [MIN_ATTACK_RADIUS, MAX_ATTACK_RADIUS]}。
     *
     * <p>四个 BE 的两个半径 setter 共用这一个入口，<b>不要</b>在各 BE 里各写一份
     * {@code Math.max}/{@code Math.min} 组合 —— 那正是本条缺陷的成因。</p>
     */
    public static int clampAttackRadius(int radius) {
        return Math.max(MIN_ATTACK_RADIUS, Math.min(MAX_ATTACK_RADIUS, radius));
    }

    /**
     * 半径超过该值时不再构造 AABB 查询。
     *
     * <p><b>由 512 降到 64</b>。原值 512 是按「AABB 坐标不会溢出」定的，<b>没有算成本</b>：
     * {@code Level.getEntitiesOfClass} 会遍历与 AABB 相交的每一个实体 section，
     * 半径 512 意味着 section 范围 ±32 ⇒ 最多 {@code 65³ ≈ 27 万} 次 section 查找，
     * <b>每次攻击一次</b>。而半径超过几十之后，兜底那条「遍历 {@code getAll()}」
     * 反而<b>更便宜</b>——它是一次线性扫描，没有 section 查找的常数放大。
     * 两者相交点远低于 512，64 是个保守取值：覆盖绝大多数实际用法（AABB 快速路径
     * 仍是绝大多数机器的常态），同时把最坏情况从 27 万次降到 {@code 9³ = 729} 次。</p>
     */
    private static final double AABB_SCAN_MAX_RADIUS = 64.0;

    private IceTargetSearch() {
    }

    /**
     * 收集 {@code center} 球形半径内满足 {@code filter} 的存活生物，按距离升序。
     */
    public static List<LivingEntity> findTargets(Level level, BlockPos machinePos, int radius,
                                                 Predicate<LivingEntity> filter) {
        Vec3 center = new Vec3(machinePos.getX() + 0.5, machinePos.getY() + 0.5, machinePos.getZ() + 0.5);
        double r = radius;
        double rSqr = r * r;
        List<LivingEntity> candidates = new ArrayList<>();
        if (r <= AABB_SCAN_MAX_RADIUS) {
            // 小半径：区块段 AABB 快速路径
            candidates.addAll(level.getEntitiesOfClass(LivingEntity.class, AABB.ofSize(center, r, r, r),
                    e -> e.isAlive() && filter.test(e) && e.distanceToSqr(center) <= rSqr));
        } else if (level instanceof ServerLevel serverLevel) {
            // 大半径：遍历全部已加载实体（“无限半径”只能覆盖到已加载区块）
            for (Entity entity : serverLevel.getEntities().getAll()) {
                if (entity instanceof LivingEntity living && living.isAlive()
                        && filter.test(living) && living.distanceToSqr(center) <= rSqr) {
                    candidates.add(living);
                }
            }
        }
        candidates.sort(Comparator.comparingDouble(e -> e.distanceToSqr(center)));
        return candidates;
    }
}
