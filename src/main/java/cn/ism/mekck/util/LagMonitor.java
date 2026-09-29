package cn.ism.mekck.util;

import net.minecraft.core.BlockPos;

/**
 * 服务器延迟监控与动态调速器。
 *
 * 通过测量每tick实际耗时（MSPT），使用指数移动平均平滑后判断卡顿等级，
 * 并为 autoIO 频率和 FastTransfer 物品预算提供动态参数：
 *
 *   等级0 正常  (avg < 35ms)：每 tick 执行 IO，物品不限量（= 21 亿/方向）、流体不限量
 *   等级1 轻度  (avg ≥ 40ms)：每 4 tick 执行 IO，物品 2.68 亿/方向、流体 1048576 mB/方向
 *   等级2 严重  (avg ≥ 60ms)：每 10 tick 执行 IO，物品 3355 万/方向、流体 131072 mB/方向
 *
 * 使用滞回区间防止在阈值附近反复跳级：
 *   1→0 需要 avg < 30ms；2→1 需要 avg < 45ms。
 */
public final class LagMonitor {

    private LagMonitor() {
    }

    private static long tickStartNano = 0;
    private static float avgTickTime = 0; // 毫秒
    private static int lagLevel = 0;      // 0=正常 1=轻度 2=严重
    private static boolean initialized = false;

    /** 在 ServerTickEvent.Phase.START 调用。 */
    public static void onTickStart() {
        tickStartNano = System.nanoTime();
    }

    /** 在 ServerTickEvent.Phase.END 调用。 */
    public static void onTickEnd() {
        float tickMs = (System.nanoTime() - tickStartNano) / 1_000_000.0f;
        if (!initialized) {
            avgTickTime = tickMs;
            initialized = true;
        } else {
            // 指数移动平均，权重 0.1，约 10 tick（0.5s）收敛
            avgTickTime = avgTickTime * 0.9f + tickMs * 0.1f;
        }
        updateLagLevel();
    }

    private static void updateLagLevel() {
        switch (lagLevel) {
            case 0 -> {
                if (avgTickTime >= 40) lagLevel = 1;
                if (avgTickTime >= 60) lagLevel = 2;
            }
            case 1 -> {
                if (avgTickTime >= 60) lagLevel = 2;
                else if (avgTickTime < 30) lagLevel = 0;
            }
            case 2 -> {
                if (avgTickTime < 45) lagLevel = 1;
                if (avgTickTime < 30) lagLevel = 0;
            }
        }
    }

    /** 当前卡顿等级（0=正常, 1=轻度, 2=严重）。 */
    public static int getLagLevel() {
        return lagLevel;
    }

    /** 近期平均 tick 耗时（毫秒），供调试/显示用。 */
    public static float getAvgTickTime() {
        return avgTickTime;
    }

    /**
     * autoIO 执行间隔（tick）。
     * 正常每 tick 全速；轻度 4 tick；严重 10 tick。
     * 配合 {@link #shouldRunIO(long, BlockPos)} 按方块位置错开相位，
     * 避免所有机器在同一 tick 集中 IO 造成周期性尖峰。
     */
    public static int getAutoIOInterval() {
        return switch (lagLevel) {
            case 2  -> 10;
            case 1  -> 4;
            default -> 1;
        };
    }

    /**
     * 每方向每 tick 最多移动的物品数。
     * 正常 1048576，轻度 262144，严重 32768。
     * <p>
     * 传输开销按<strong>调用次数</strong>计（每个槽一次 insertItem / extractItem），
     * 与单次搬运的物品数量无关：搬 64 件和搬 100 万件都是一次调用。因此这里的上限只用来
     * 「防止单台机器在一 tick 内挪走过多内容」，而不是性能保护——放宽它对 CPU 没有影响，
     * 却让大堆叠（本模组槽位上限为 Integer.MAX_VALUE-1）能在少数几 tick 内搬完，
     * 而不是被 32768 卡上几十 tick。
     * </p>
     */
    public static int getMaxItemsPerDirection() {
        return switch (lagLevel) {
            // 正常档不设上限（= 单个槽位的最大数量 21 亿）：一次调用即可搬走整堆，
            // 开销与搬运量无关；轻度/严重档再按档位回收。
            case 2  -> 33_554_432;
            case 1  -> 268_435_456;
            default -> Integer.MAX_VALUE;
        };
    }

    /**
     * 每方向每 tick 最多搬运的物品数（long 版，供大宗路径使用）。
     * 正常档不设上限：一次调用即可搬完整批（210 亿 = 一次调用，成本与数量无关）。
     */
    public static long getMaxItemsPerDirectionLong() {
        return switch (lagLevel) {
            case 2  -> 33_554_432L;
            case 1  -> 268_435_456L;
            default -> Long.MAX_VALUE;
        };
    }

    /**
     * 每方向每 tick 最多转移的流体 / 气体量（mB）。
     * 正常 256000，轻度 64000，严重 8000。
     * <p>
     * 与物品同理：一次 fill/drain 的开销与转移量无关，原先硬编码的 1000 mB/面/tick
     * 会让 16000 mB 的机器罐体需要 16 tick 才能灌满，纯属人为限速。
     * </p>
     */
    public static int getMaxFluidPerDirection() {
        return switch (lagLevel) {
            // 一次 fill/drain 的开销与转移量无关：正常档直接"能装多少搬多少"
            case 2  -> 131_072;
            case 1  -> 1_048_576;
            default -> Integer.MAX_VALUE;
        };
    }

    /**
     * 相位错开的 IO 调度：以方块坐标哈希为相位偏移，
     * 使降档期间各机器的 IO 均匀分布在不同 tick 上，
     * 而不是全体机器在同一 tick 齐发（避免自激式卡顿尖峰）。
     */
    public static boolean shouldRunIO(long gameTime, BlockPos pos) {
        int interval = getAutoIOInterval();
        if (interval <= 1) return true;
        long h = pos.asLong();
        long offset = (h ^ (h >>> 32)) & 0xFFFFL;
        return Math.floorMod(gameTime + offset, interval) == 0;
    }
}