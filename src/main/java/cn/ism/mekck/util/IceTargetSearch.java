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

    /** 半径超过该值时不再构造 AABB 查询（该阈值下任意世界坐标的 section key 都不会溢出）。 */
    private static final double AABB_SCAN_MAX_RADIUS = 512.0;

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
