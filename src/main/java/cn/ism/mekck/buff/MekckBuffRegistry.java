package cn.ism.mekck.buff;

import cn.ism.mekck.UniversalCuttingMachine;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import org.jetbrains.annotations.Nullable;

import java.util.Set;

/**
 * F10 攻击增益白名单（拍板 Q8a）：集中定义「哪个 buff 源覆盖哪些攻击型目标」，
 * 并提供归属扫描（Q1a 5×5×3、Q3a 目标侧扫描 + 距最小 + {@code asLong()} 破平）。
 *
 * <p>本类只引用通用类（{@link Block} / {@link BlockPos} / {@link Level}），可在服务端安全调用。
 * 白名单用**惰性初始化**（非静态字段直接 {@code .get()}），避免注册期未就绪的时序问题；
 * 未来加入 {@code coffee_cannon}（喷射攻击型，被 juicer buff）时，只需在
 * {@link #juicerTargets()} 追加 {@code UniversalCuttingMachine.COFFEE_CANNON_BLOCK.get()} 一个元素。</p>
 */
public final class MekckBuffRegistry {

    private MekckBuffRegistry() {
    }

    private static Set<Block> juicerTargets;
    private static Set<Block> bakeryTargets;

    /** 被鲜果榨汁机 {@code juicer} buff 的喷射攻击型（当前仅 nut_roaster；未来 +coffee_cannon）。 */
    public static Set<Block> juicerTargets() {
        if (juicerTargets == null) {
            juicerTargets = Set.of(UniversalCuttingMachine.NUT_ROASTER_BLOCK.get());
            // 未来：Set.of(NUT_ROASTER_BLOCK.get(), COFFEE_CANNON_BLOCK.get())
        }
        return juicerTargets;
    }

    /** 被糕点烘焙机 {@code bakery_oven} buff 的散点射攻击型（当前仅 ice_maker）。 */
    public static Set<Block> bakeryTargets() {
        if (bakeryTargets == null) {
            bakeryTargets = Set.of(UniversalCuttingMachine.ICE_MAKER_BLOCK.get());
        }
        return bakeryTargets;
    }

    /** 给定目标方块，返回其 buff 源方块（juicer / bakery_oven）；非白名单目标返回 null。 */
    @Nullable
    public static Block sourceFor(@Nullable Block target) {
        if (target == null) return null;
        if (juicerTargets().contains(target)) return UniversalCuttingMachine.JUICER_BLOCK.get();
        if (bakeryTargets().contains(target)) return UniversalCuttingMachine.BAKERY_OVEN_BLOCK.get();
        return null;
    }

    /**
     * 目标侧 5×5×3 扫描（Q1a：dx∈[-2,2]、dy∈[-1,1]、dz∈[-2,2]，共 75 格）找最近的合法 buff 源。
     *
     * @param current 当前已记录的 owner（用于 Q3a「未加载 chunk 不清 owner」防抖）
     * @return 选定的源位置；无则 null
     */
    @Nullable
    public static BlockPos resolve(Level level, BlockPos self, Block source, @Nullable BlockPos current) {
        BlockPos best = null;
        long bestKey = Long.MAX_VALUE;
        double bestDist = Double.MAX_VALUE;
        int cx = self.getX(), cy = self.getY(), cz = self.getZ();
        for (int dx = -2; dx <= 2; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -2; dz <= 2; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) continue;
                    BlockPos p = self.offset(dx, dy, dz);
                    if (!level.isLoaded(p)) continue;
                    if (!level.getBlockState(p).is(source)) continue;
                    double ddx = p.getX() - cx, ddy = p.getY() - cy, ddz = p.getZ() - cz;
                    double dist = ddx * ddx + ddy * ddy + ddz * ddz;
                    long key = p.asLong();
                    if (dist < bestDist || (dist == bestDist && key < bestKey)) {
                        best = p;
                        bestKey = key;
                        bestDist = dist;
                    }
                }
            }
        }
        // Q3a：若本轮没找到源，但旧 owner 所在 chunk 尚未加载，则保留旧 owner（防抖，避免跨 chunk 边缘误清）
        if (best == null && current != null && !level.isLoaded(current)) {
            return current;
        }
        return best;
    }
}
