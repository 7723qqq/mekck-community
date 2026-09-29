package cn.ism.mekck.util;

/**
 * 极端堆叠（最高 21 亿）下的计数运算工具。
 *
 * <p>本模组的槽位上限是 {@link #MAX_COUNT}。把所有数量运算集中到这里，是为了保证：</p>
 * <ul>
 *   <li><b>永不溢出</b>——一律先转 {@code long} 再夹紧，绝不出现负数（负数会被当成"空槽/无空间"，
 *       进而静默丢物品或重复生产）；</li>
 *   <li><b>复杂度与数量无关</b>——这里只做算术，任何地方都不得按数量循环。</li>
 * </ul>
 */
public final class CountMath {

    /** 单个槽位的最大数量（与各机器 {@code getSlotLimit} 一致）。 */
    public static final int MAX_COUNT = Integer.MAX_VALUE - 1;

    private CountMath() {
    }

    /** a×b，夹紧到 [0, cap]。 */
    public static int mulClamp(int cap, int a, int b) {
        long r = (long) a * (long) b;
        return (int) Math.min(cap, Math.max(0L, r));
    }

    /** a×b×c，夹紧到 [0, cap]。 */
    public static int mulClamp(int cap, int a, int b, int c) {
        long r = (long) a * (long) b * (long) c;
        return (int) Math.min(cap, Math.max(0L, r));
    }

    /** a+b，夹紧到 [0, MAX_COUNT]。 */
    public static int addClamp(int a, int b) {
        long r = (long) a + (long) b;
        return (int) Math.min(MAX_COUNT, Math.max(0L, r));
    }

    // ── long 版（210 亿级批量搬运用） ──

    /** a×b，夹紧到 [0, cap]（long 版）。 */
    public static long mulClamp(long cap, long a, long b) {
        if (a <= 0L || b <= 0L) return 0L;
        if (a > cap / b) return cap;
        return Math.min(cap, a * b);
    }

    /** a+b，夹紧到 [0, cap]（long 版）。 */
    public static long addClamp(long cap, long a, long b) {
        if (a <= 0L) return Math.max(0L, Math.min(cap, b));
        if (b <= 0L) return Math.max(0L, Math.min(cap, a));
        if (a > cap - b) return cap;
        return a + b;
    }

    /** 已有 {@code existing} 件时再放入 {@code incoming} 件是否仍在槽位上限内（long 比较，不溢出）。 */
    public static boolean canStack(int existing, int incoming, int slotLimit) {
        return (long) existing + (long) incoming <= Math.min(slotLimit, MAX_COUNT);
    }
}
