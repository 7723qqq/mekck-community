package cn.ism.mekck.machine;

import cn.ism.mekck.TestSourceText;
import org.junit.Test;

import java.io.IOException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 「逐路独立计时」的护栏。
 *
 * <h3>为什么需要它</h3>
 * 迁移前本模组的机器是「一个批次计时器 + 到点后一次处理全部槽」，进度条只有一条；
 * 上游 Mek 的 {@code TileEntityFactory} 是 {@code int[] progress}，一路一个独立计时器。
 * 这次把机器改成与上游同构之后，最容易悄悄退化的两点是：
 * <ul>
 *   <li><b>一路停摆拖垮全部</b>：某一路没输入 / 没配方 / 产物装不下时，旧写法是整批一起停；
 *       新写法必须只清它自己那一路。</li>
 *   <li><b>进度数组被重新分配</b>：{@code container.trackArray} 给每个下标建的
 *       {@code SyncableInt} 直接持有数组引用，换实例之后同步通道仍在写旧数组，
 *       GUI 读到的进度会永远停在换实例那一刻 —— 症状与「完全没有同步」一模一样。</li>
 * </ul>
 */
public class TestMekCkPerLaneProgress {

    private static final String TILE = "src/main/java/cn/ism/mekck/machine/MekCkMachineTile.java";
    private static final String EXECUTOR = "src/main/java/cn/ism/mekck/machine/MekCkRecipeExecutor.java";

    /** 一路走满 cycle 个 tick 才归零并回报「该加工了」。 */
    @Test
    public void aLaneReportsCompletionExactlyOncePerCycle() {
        int[] progress = new int[1];
        int cycle = 5;
        for (int tick = 1; tick < cycle; tick++) {
            assertFalse("第 " + tick + " tick 不该完成", MekCkMachineTile.advanceLane(progress, 0, cycle));
            assertEquals(tick, progress[0]);
        }
        assertTrue("第 " + cycle + " tick 必须完成", MekCkMachineTile.advanceLane(progress, 0, cycle));
        assertEquals("完成后进度归零", 0, progress[0]);
    }

    /** 各路互不影响：推进第 0 路不会动到第 1 路。 */
    @Test
    public void lanesDoNotShareACounter() {
        int[] progress = new int[3];
        int cycle = 4;
        for (int tick = 0; tick < cycle; tick++) {
            MekCkMachineTile.advanceLane(progress, 0, cycle);
        }
        assertEquals("第 0 路走满一轮后归零", 0, progress[0]);
        assertEquals("第 1 路没被推进", 0, progress[1]);
        assertEquals("第 2 路没被推进", 0, progress[2]);

        MekCkMachineTile.advanceLane(progress, 2, cycle);
        assertEquals("只推进第 2 路", 1, progress[2]);
        assertEquals(0, progress[0]);
        assertEquals(0, progress[1]);
    }

    /** cycle = 1 时每 tick 都完成（与旧实现「每 tick 一个批次」同口径）。 */
    @Test
    public void cycleOfOneCompletesEveryTick() {
        int[] progress = new int[1];
        for (int tick = 0; tick < 3; tick++) {
            assertTrue(MekCkMachineTile.advanceLane(progress, 0, 1));
            assertEquals(0, progress[0]);
        }
    }

    /** 执行器接口必须是逐路契约，不能再有整机一次的 {@code tick}。 */
    @Test
    public void executorContractIsPerLane() throws IOException {
        String src = TestSourceText.read(EXECUTOR);
        assertTrue("接口必须声明 canProcess",
                src.contains("boolean canProcess(MekCkMachineTile tile, int index);"));
        assertTrue("接口必须声明 process",
                src.contains("void process(MekCkMachineTile tile, int index);"));
        assertTrue("接口必须声明 processCount",
                src.contains("default int processCount(MekCkMachineTile tile)"));
        assertFalse("整机一次的 tick 必须已经删掉",
                src.contains("void tick(MekCkMachineTile tile, int slotCount)"));
    }

    /** 进度数组只分配一次 —— 见类注释第二条。 */
    @Test
    public void progressArrayIsNeverReallocated() throws IOException {
        String src = TestSourceText.read(TILE);
        String body = TestSourceText.methodBody(src, "private int[] progressArray() {");
        assertFalse("找不到 progressArray 的方法体（签名改了？）", body.isEmpty());
        assertEquals("progressArray 里必须只有一处 new int[...]（重新分配会让同步通道指向旧数组）",
                1, countOccurrences(body, "new int["));
        assertTrue("progressArray 必须只在 workProgress == null 时分配",
                body.contains("if (workProgress == null)"));
    }

    /** 工作循环必须逐路判定、逐路加工，而不是「到点一次处理全部槽」。 */
    @Test
    public void workCycleAdvancesEachLaneSeparately() throws IOException {
        String src = TestSourceText.read(TILE);
        String body = TestSourceText.methodBody(src, "private void workCycle() {");
        assertFalse("找不到 workCycle 的方法体（签名改了？）", body.isEmpty());
        assertTrue("必须逐路问 canProcess", body.contains("exec.canProcess(this, i)"));
        assertTrue("必须逐路推进进度", body.contains("advanceLane(progress, i, cycle)"));
        assertTrue("完成时只加工这一路", body.contains("exec.process(this, i)"));
        assertFalse("不得再出现整机一次的 executor().tick(...)", body.contains("executor().tick("));
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int at = haystack.indexOf(needle);
        while (at >= 0) {
            count++;
            at = haystack.indexOf(needle, at + needle.length());
        }
        return count;
    }
}
