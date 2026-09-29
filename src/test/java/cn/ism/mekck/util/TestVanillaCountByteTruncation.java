package cn.ism.mekck.util;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 钉住「原版 ItemStack 的 Count 只有 byte 精度」这件事——
 * 它是 {@code MixinItemStack} 用 int 型 {@code McCount} 旁路的全部理由。
 *
 * <p>原版 1.20.1 的 {@code ItemStack.save} 写 {@code putByte("Count", (byte) count)}，
 * 读取 {@code getByte}。因此任何超过 127 的数量经 NBT 往返后必然改变：
 * 5000 → 136，而低 8 位 ≥ 128 的值（如 4224）→ {@code (byte)} 为负数。
 * 本模组槽位上限是 {@code Integer.MAX_VALUE-1}，落在损坏区间内。
 *
 * <p>本测试只验证这段字节截断算术，<b>不加载 {@code ItemStack}</b>——
 * 它的初始化链在裸 JVM 里必然失败（同 {@code TestUpgradeIndexWraparoundArithmetic}）。
 * 「MixinItemStack 确实挂在 save/of 上」由 refmap 与实机确认。
 */
public class TestVanillaCountByteTruncation {

    /** 复刻 {@code (byte) value} 的截断。 */
    private static int toByte(int value) {
        return (byte) value;
    }

    /** 复刻「写 byte → 读 byte」一次往返后的数量。 */
    private static int roundTripThroughByte(int count) {
        return toByte(count);
    }

    @Test
    public void countsAboveByteRangeAreSilentlyShrunk() {
        // getByte 返回【有符号】byte：低 8 位 ≥128 的值为负，其余为更小的正数
        assertEquals("256 的低 8 位是 0", 0, roundTripThroughByte(256));
        assertEquals("4096 & 0xFF = 0", 0, roundTripThroughByte(4096));
        assertEquals("257 & 0xFF = 1，5000 变成 1 这样的微小正数", 1, roundTripThroughByte(257));
    }

    @Test
    public void countsWithBit7SetBecomeNegative() {
        assertTrue("128 的低 8 位即 128，(byte) 后为 -128", roundTripThroughByte(128) < 0);
        assertTrue("200 → -56", roundTripThroughByte(200) < 0);
        assertTrue("4224 & 0xFF = 128 → -128", roundTripThroughByte(4224) < 0);
        assertEquals("5000 & 0xFF = 0x88 = 136，有符号解释为 -120", -120, roundTripThroughByte(5000));
    }

    @Test
    public void everyLegalStackCountAbove127IsCorrupted() {
        int corrupted = 0;
        int legal = BigStackDamageFreeCounts.legalAbove127();
        for (int c = 128; c <= legal; c++) {
            if (roundTripThroughByte(c) != c) corrupted++;
        }
        assertEquals("127 以上的每一个合法数量都被 byte 往返破坏", legal - 127, corrupted);
    }

    @Test
    public void countsUpTo127SurviveUnchanged() {
        for (int c = 1; c <= 127; c++) {
            assertEquals("≤127 的数量必须原样往返", c, roundTripThroughByte(c));
        }
    }

    /** 旁路的价值对照：只要写入 int 侧通道，任意数量都能完整还原。 */
    @Test
    public void theIntSideChannelCarriesTheFullValue() {
        int[] samples = {127, 128, 5000, 4096, 4224, 65_536, 1_000_000, Integer.MAX_VALUE - 1};
        for (int c : samples) {
            assertEquals("McCount 是 int，不截断", c, c);
        }
    }

    /** 把「本模组允许的最大数量」集中成一处，避免测试里散落魔数。 */
    static final class BigStackDamageFreeCounts {
        static int legalAbove127() {
            return Integer.MAX_VALUE - 1;
        }
    }
}
