package cn.ism.mekck.network;

import cn.ism.mekck.TestSourceText;
import org.junit.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * AE2 面板只读请求的「合并/节流 + 首请求必回」护栏（第六轮 Task 4）。
 *
 * <h3>为什么要它</h3>
 * {@code NetworkRecipeRequestPacket} / {@code NetworkMissingRequestPacket} 只过距离校验，
 * 落到 {@code MekckAe2.panelEntries} 时曾<b>每次</b>都做全量样板重建并遍历全网库存 ——
 * 客户端连点/快速切换即可打满服务端主线程。修复把它改为「最短刷新间隔内复用缓存」，
 * 且<b>不丢弃任何请求</b>（打开面板的首个请求必刷，否则面板空白）。
 *
 * <p>这里守两件事：一是节流<b>逻辑</b>（纯函数，普通 JVM 可跑）；二是节流<b>被用上</b>
 * 以及「必回响应」的<b>源码形态</b>（用 {@link TestSourceText} 剥注释后断言，
 * 避免注释里的示意代码被当成真代码）。</p>
 */
public class TestAe2PanelThrottle {

    private static final String NETWORK = "src/main/java/cn/ism/mekck/network/";
    private static final String AE2 = "src/main/java/cn/ism/mekck/ae2/MekckAe2.java";

    // ================== 一、节流逻辑（纯函数） ==================

    @Test
    public void firstRequestAlwaysRefreshes() {
        // 从未刷新（lastRefreshTick < 0）＝含面板打开的首个请求 → 必须真实刷新，否则回空缓存、面板空白。
        assertTrue(PacketGuard.panelRefreshDue(1000L, -1L, PacketGuard.PANEL_REFRESH_MIN_TICKS));
    }

    @Test
    public void sameWindowDoesNotRefresh() {
        assertFalse(PacketGuard.panelRefreshDue(1000L, 1000L, 10));
        assertFalse(PacketGuard.panelRefreshDue(1009L, 1000L, 10));
    }

    @Test
    public void windowElapsedRefreshes() {
        assertTrue(PacketGuard.panelRefreshDue(1010L, 1000L, 10));
        assertTrue(PacketGuard.panelRefreshDue(5000L, 1000L, 10));
    }

    @Test
    public void clockRewindRefreshesConservatively() {
        // 存档重载 / 时钟回拨时 now < last，间隔不可比：保守刷新一次。
        assertTrue(PacketGuard.panelRefreshDue(900L, 1000L, 10));
    }

    @Test
    public void burstCollapsesToOneRebuildAndAlwaysYieldsResult() {
        // 模拟 entriesForPanel：窗口内 N 次调用只触发 1 次真实刷新，且每次都返回结果（首请求必回）。
        long last = -1L;
        int rebuilds = 0;
        List<String> cached = null;
        for (int i = 0; i < 50; i++) {
            if (PacketGuard.panelRefreshDue(1000L, last, PacketGuard.PANEL_REFRESH_MIN_TICKS)) {
                last = 1000L;
                rebuilds++;
                cached = List.of("entry");
            }
            assertNotNull("任何一次请求都必须有结果可回（不得空白/丢弃）", cached);
        }
        assertEquals("窗口内全量重建次数必须 ≤ 1", 1, rebuilds);
    }

    @Test
    public void minIntervalStaysInSaneRange() {
        // 下限：必须真的能合并连点；上限：不能长到让面板刷新明显过期（周期 tick 重建是 40）。
        assertTrue(PacketGuard.PANEL_REFRESH_MIN_TICKS >= 5);
        assertTrue(PacketGuard.PANEL_REFRESH_MIN_TICKS <= 40);
    }

    // ================== 二、源码形态（节流被用上 + 必回响应） ==================

    @Test
    public void requestPacketsRouteThroughCoalescedEntryAndAlwaysRespond() throws IOException {
        String recipe = TestSourceText.read(NETWORK + "NetworkRecipeRequestPacket.java");
        assertTrue("配方请求必须走合并入口 getNetworkCraftableMap（其内部即节流的 panelEntries）",
                recipe.contains("getNetworkCraftableMap"));
        assertTrue("配方请求必须无条件回响应，不得静默丢弃",
                recipe.contains("ModMessages.sendToPlayer"));

        String missing = TestSourceText.read(NETWORK + "NetworkMissingRequestPacket.java");
        assertTrue("缺料请求必须走合并入口 describeNetworkMissing（其内部即节流的 panelEntries）",
                missing.contains("describeNetworkMissing"));
        assertTrue("缺料请求必须无条件回响应，不得静默丢弃",
                missing.contains("ModMessages.sendToPlayer"));
    }

    @Test
    public void panelEntriesGoThroughThrottledCacheEntry() throws IOException {
        String src = TestSourceText.read(AE2);

        String panel = TestSourceText.methodBody(src, "List<PatternEntry> panelEntries(BlockEntity be)");
        assertFalse("源码里找不到 panelEntries，判据失效", panel.isEmpty());
        assertTrue("panelEntries 必须走带节流的 entriesForPanel", panel.contains("entriesForPanel"));
        assertFalse("panelEntries 不得无条件全量重建（连点会打满主线程）",
                panel.contains("refreshPatterns"));

        String entry = TestSourceText.methodBody(src, "List<PatternEntry> entriesForPanel()");
        assertFalse("源码里找不到 entriesForPanel，判据失效", entry.isEmpty());
        assertTrue("entriesForPanel 必须用 PacketGuard.panelRefreshDue 判定是否刷新",
                entry.contains("PacketGuard.panelRefreshDue"));
        assertTrue("entriesForPanel 必须复用缓存结果 provider.entries",
                entry.contains("provider.entries"));
    }

    @Test
    public void requestEntryPointsRouteThroughPanelEntries() throws IOException {
        // 补全链路：包处理器 → getNetworkCraftableMap / describeNetworkMissing → panelEntries（节流）。
        String src = TestSourceText.read(AE2);
        assertTrue("getNetworkCraftableMap 必须经 panelEntries 取样板（否则绕过节流）",
                TestSourceText.methodBody(src, "Map<String, Integer> getNetworkCraftableMap(BlockEntity be)")
                        .contains("panelEntries"));
        assertTrue("describeNetworkMissing 必须经 panelEntries 取样板（否则绕过节流）",
                TestSourceText.methodBody(src, "String describeNetworkMissing(BlockEntity be, String recipeId, int quantity)")
                        .contains("panelEntries"));
    }

    @Test
    public void scanIsNotVacuous() throws IOException {
        // 判据不空转：确认源码里真的存在这两个入口与节流常量（写法一变就会静默变绿）。
        String src = TestSourceText.read(AE2);
        assertTrue(src.contains("entriesForPanel"));
        assertTrue(src.contains("PacketGuard.panelRefreshDue"));
        assertTrue(TestSourceText.read(NETWORK + "PacketGuard.java").contains("PANEL_REFRESH_MIN_TICKS"));
    }
}