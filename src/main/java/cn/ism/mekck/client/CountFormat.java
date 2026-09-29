package cn.ism.mekck.client;

import java.util.Locale;

/**
 * 大堆叠数量的紧凑显示。
 * <p>
 * 机器槽位上限是 21 亿，原版会把这些数字原样画出来（或按 1000 件以下正常显示）；
 * 各屏幕原先各自复制了一份 {@code k}/{@code M} 格式化，且**没有十亿档**——21 亿会显示成
 * "2147.5M"。这里统一为 k / M / B 三档，并固定 {@link Locale#ROOT}（避免某些语言环境下
 * 小数点变成逗号）。
 * </p>
 */
public final class CountFormat {

    private CountFormat() {
    }

    /** ≥10 亿 → x.xB，≥100 万 → x.xM，≥1000 → x.xk；不足 1000 返回 null（交由原版显示）。 */
    public static String compact(int count) {
        if (count >= 1_000_000_000) {
            return String.format(Locale.ROOT, "%.1fB", count / 1_000_000_000.0);
        }
        if (count >= 1_000_000) {
            return String.format(Locale.ROOT, "%.1fM", count / 1_000_000.0);
        }
        if (count >= 1_000) {
            return String.format(Locale.ROOT, "%.1fk", count / 1000.0);
        }
        return null;
    }
}
