package cn.ism.mekck.util;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;

/**
 * 传送特效工具：末影珍珠式传送粒子 + 末影人传送音效。
 * <p>
 * 急冻制冰机/制冰工厂生成冰块、巧克力大炮生成费列罗巧克力时，
 * 都在弹体生成位置与机器位置各播放一次（服务端广播给附近玩家）。
 * </p>
 */
public final class TeleportFxUtil {

    private TeleportFxUtil() {
    }

    /**
     * 传送粒子数量：原版末影珍珠为 128，这里降到 24。
     * 单次弹射要放两处（生成点 + 机器位置），而急冻制冰机/大炮/爆炒机可以每 tick 各生成一发，
     * 128 会在"大批量弹射"时产生明显的网络包与客户端粒子开销。
     */
    private static final int PORTAL_PARTICLES = 24;

    /** 特效可见半径（格）：半径内没有玩家时整个特效（粒子 + 音效）直接跳过。 */
    private static final double FX_VISIBLE_RADIUS = 32.0D;

    /** 在指定位置播放传送粒子与传送音效。 */
    public static void playTeleport(ServerLevel level, double x, double y, double z) {
        level.sendParticles(net.minecraft.core.particles.ParticleTypes.PORTAL, x, y, z, PORTAL_PARTICLES, 0.5D, 1.0D, 0.5D, 0.2D);
        level.playSound(null, x, y, z, SoundEvents.ENDERMAN_TELEPORT, SoundSource.BLOCKS, 1.0F, 1.0F);
    }

    /** 附近是否有玩家在特效可见半径内（没有人看就不发粒子/音效，服务端零成本）。 */
    private static boolean hasNearbyPlayer(ServerLevel level, double x, double y, double z) {
        double r2 = FX_VISIBLE_RADIUS * FX_VISIBLE_RADIUS;
        for (net.minecraft.server.level.ServerPlayer player : level.players()) {
            if (player.distanceToSqr(x, y, z) <= r2) return true;
        }
        return false;
    }

    /** 在弹体生成位置与（可选的）机器位置播放传送特效。仅服务端调用。 */
    public static void play(Level level, double x, double y, double z, @Nullable BlockPos machinePos) {
        if (level.isClientSide || !(level instanceof ServerLevel serverLevel)) return;
        if (!hasNearbyPlayer(serverLevel, x, y, z)) return;
        playTeleport(serverLevel, x, y, z);
        if (machinePos != null) {
            playTeleport(serverLevel, machinePos.getX() + 0.5D, machinePos.getY() + 0.5D, machinePos.getZ() + 0.5D);
        }
    }
}
