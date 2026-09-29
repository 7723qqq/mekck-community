package cn.ism.mekck.network;

import net.minecraft.core.BlockPos;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * PacketGuard 纯逻辑测试（普通 JVM，与 TestTavernBrewBatch 同口径）。
 *
 * <p>覆盖两类曾经真实存在的缺陷：</p>
 * <ul>
 *   <li><b>解码前不夹紧长度</b>：{@code readVarInt()} 是攻击者可控值，直接
 *       {@code new ArrayList<>(n)} 会让一个几十字节的包申请 GB 级数组并 OOM。
 *       锁死 {@link PacketGuard#clampCount} 的上下界。</li>
 *   <li><b>包处理器不做接近性校验</b>：只查 {@code hasChunkAt}（= 区块已加载）不等于
 *       「玩家就在旁边」，任意客户端都能改远处机器。锁死半径常量，并确认 null 输入被拒。</li>
 * </ul>
 */
public class TestPacketGuard {

    // ================== clampCount：DoS 防护 ==================

    @Test
    public void clampCountPassesThroughInRange() {
        assertEquals(0, PacketGuard.clampCount(0));
        assertEquals(1, PacketGuard.clampCount(1));
        assertEquals(64, PacketGuard.clampCount(64));
        assertEquals(PacketGuard.MAX_DECODE_ELEMENTS, PacketGuard.clampCount(PacketGuard.MAX_DECODE_ELEMENTS));
    }

    @Test
    public void clampCountCapsAttackerSuppliedLength() {
        // 不夹紧时 n = Integer.MAX_VALUE 会让 new ArrayList<>(n) 立即 OOM
        assertEquals(PacketGuard.MAX_DECODE_ELEMENTS, PacketGuard.clampCount(Integer.MAX_VALUE));
        assertEquals(PacketGuard.MAX_DECODE_ELEMENTS,
                PacketGuard.clampCount(PacketGuard.MAX_DECODE_ELEMENTS + 1));
    }

    @Test
    public void clampCountRejectsNegative() {
        // ArrayList 负容量会抛 IllegalArgumentException，解码发生在 Netty 线程 ⇒ 需先归零
        assertEquals(0, PacketGuard.clampCount(-1));
        assertEquals(0, PacketGuard.clampCount(Integer.MIN_VALUE));
    }

    @Test
    public void maxDecodeElementsStaysBounded() {
        // 上界本身不得被调到失去防护意义的量级（现实最长清单是 ME 配方表，数百条）
        assertTrue("MAX_DECODE_ELEMENTS 应留有现实余量", PacketGuard.MAX_DECODE_ELEMENTS >= 1024);
        assertTrue("MAX_DECODE_ELEMENTS 不应大到等同于无防护",
                PacketGuard.MAX_DECODE_ELEMENTS <= 65536);
    }

    // ================== 接近性半径：与菜单口径一致 ==================

    @Test
    public void interactRangeMatchesMenuStillValid() {
        // 必须与本模组所有菜单的 stillValid 及原版 AbstractContainerMenu 同值（8 格 = 64.0D）。
        // 若此值被改动，需同步复核 menu/*.java 的 distanceToSqr 判定，否则会出现
        // 「GUI 还开着但按钮全部无效」或「GUI 已关但仍可远程操作」的空隙。
        assertEquals(64.0D, PacketGuard.INTERACT_RANGE_SQR, 0.0D);
    }

    @Test
    public void allowedRejectsNullInputs() {
        assertFalse("null 玩家必须被拒", PacketGuard.allowed(null, new BlockPos(0, 0, 0)));
        assertFalse("null 坐标必须被拒", PacketGuard.allowed(null, null));
    }

    @Test
    public void targetReturnsNullWhenDenied() {
        // 校验未通过时必须返回 null，调用方据此 return；不得回落到「直接查方块实体」
        assertEquals(null, PacketGuard.target(null, new BlockPos(10, 64, 10)));
    }
}
