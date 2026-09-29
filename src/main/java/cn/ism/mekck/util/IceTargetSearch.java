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
