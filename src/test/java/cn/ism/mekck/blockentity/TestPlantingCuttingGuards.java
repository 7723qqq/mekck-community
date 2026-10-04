package cn.ism.mekck.blockentity;

import cn.ism.mekck.TestSourceText;
import org.junit.Test;

import java.io.IOException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 种植切配站 C2 修复的护栏（源码形态）。
 *
 * <h3>它钉的是哪个 bug</h3>
 * 旧实现三件事叠在一起：
 * <ol>
 *   <li>{@code canFitOutputs} 用<b>同一个</b> {@code existing} 逐个试插、不模拟累积：
 *       输出槽为空时任意多个不同产物都判「装得下」；</li>
 *   <li>{@code insertOutput} 只写 OUTPUT_SLOT 一格，第二个不同产物既不能并格也不会
 *       掉落，局部 {@code stack} 被丢弃 ⇒ <b>静默销毁</b>；</li>
 *   <li>{@code getFinalOutputs} 每次调用都掷副产物骰，而它被 {@code canFitOutputs}
 *       （每 tick）与 {@code completeRecipe}（完成时）各调一次 ⇒ 副产物概率 1.0
 *       且输出槽装不下副产物时，预检永远失败、{@code progress} 每 tick 清零 ⇒
 *       <b>机器永久卡死</b>。</li>
 * </ol>
 *
 * <h3>为什么是源码形态</h3>
 * 该 BE 的构造链需要 {@code BlockEntityType} / {@code Level}，裸 JVM 里造不出来
 * （同 {@code TestNbtPersistenceInvariants} 的说明）。这里钉住的是修复的<b>结构</b>：
 * 预检不掷骰、在副本上模拟、只要求主产物进得去；完成时只掷一次骰；余量走掉落兜底。
 *
 * <h3>为什么预检只要求「第一个产物」进得去</h3>
 * 输出槽只有一格，而 plantcut 配方常有多个不同产物（FD 切割结果，如
 * {@code chicken → chicken_cuts + bone_meal}）。若要求全部产物都装得下，
 * 多产物配方会永远开不了工 —— 那正是旧实现的卡死形态。所以预检只保证主产物
 * 能进槽（其余产物装不下时掉落），机器对多产物配方保持可用。
 */
public class TestPlantingCuttingGuards {

    private static final String BE =
            "src/main/java/cn/ism/mekck/blockentity/PlantingCuttingStationBlockEntity.java";

    private static String read(String path) throws IOException {
        return TestSourceText.read(path);
    }

    private static String methodBody(String src, String signature) {
        return TestSourceText.methodBody(src, signature);
    }

    private static int count(String haystack, String needle) {
        int n = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + needle.length())) {
            n++;
        }
        return n;
    }

    /**
     * 预检不得掷副产物骰：{@code canFitOutputs} 里不得出现 {@code getFinalOutputs}
     * 或 {@code nextFloat}。旧实现每 tick 掷一次，与完成时的掷骰互相独立 ⇒
     * 副产物概率 1.0 且输出槽装不下副产物时永久卡死。
     */
    @Test
    public void precheckDoesNotRollTheSecondaryDice() throws IOException {
        String body = methodBody(read(BE), "private boolean canFitOutputs(");
        assertFalse("找不到 canFitOutputs", body.isEmpty());
        assertFalse("预检不得掷副产物骰（getFinalOutputs / nextFloat）：旧实现每 tick 掷一次，"
                        + "与完成时的掷骰互相独立 ⇒ 副产物概率 1.0 且输出槽装不下副产物时永久卡死",
                body.contains("getFinalOutputs") || body.contains("nextFloat"));
    }

    /**
     * 预检必须在 handler 副本上模拟，且只要求主产物（第一个产物）进得去。
     */
    @Test
    public void precheckSimulatesOnACopyAndGatesOnlyTheFirstOutput() throws IOException {
        String body = methodBody(read(BE), "private boolean canFitOutputs(");
        assertTrue("预检必须在 handler 副本上模拟（旧实现直接读真槽、用同一个 existing 逐个试插）",
                body.contains("BigStackItemHandler") && body.contains("setStackInSlot"));
        assertFalse("不得再用旧的 tryInsert(existing, ...) 非累积试插", body.contains("tryInsert("));
        assertTrue("只预检第一个产物能否进槽", body.contains("mainResults.get(0)"));
        assertFalse("不得遍历全部产物要求逐个装下：单槽装不下两种物品，"
                        + "多产物配方（FD 切割结果）会永远开不了工",
                body.contains("for (ItemStack"));
    }

    /**
     * 完成时只掷一次骰：产物集在 {@code serverTick} 里算好一次并传给
     * {@code completeRecipe}，预检与产出不再各掷一次。
     */
    @Test
    public void completionRollsOnceAndPassesTheOutputSet() throws IOException {
        String src = read(BE);
        String tick = methodBody(src, "public static void serverTick(");
        assertFalse("找不到 serverTick", tick.isEmpty());
        assertEquals("完成路径只掷一次骰（getFinalOutputs 在 serverTick 里只出现一次）",
                1, count(tick, "getFinalOutputs("));
        assertTrue("产物集必须传入 completeRecipe",
                tick.contains("completeRecipe(level, outputs)"));
        String complete = methodBody(src, "private void completeRecipe(");
        assertFalse("找不到 completeRecipe", complete.isEmpty());
        assertTrue("completeRecipe 必须接收产物集参数", complete.contains("NonNullList<ItemStack> outputs"));
    }

    /**
     * 余量必须掉落兜底，不得静默销毁：旧实现只写 OUTPUT_SLOT 一格，
     * 第二个不同产物既不能并格也不会掉落，局部 stack 被丢弃。
     */
    @Test
    public void overflowIsDroppedNotDiscarded() throws IOException {
        String src = read(BE);
        String complete = methodBody(src, "private void completeRecipe(");
        assertTrue("insertOutput 的余量必须走 BigStackDrops.dropAbove 兜底",
                complete.contains("BigStackDrops.dropAbove"));
        assertTrue("必须显式判定余量非空", complete.contains("!remainder.isEmpty()"));
        String insert = methodBody(src, "private static ItemStack insertOutput(");
        assertFalse("找不到 insertOutput(handler, stack, slot)", insert.isEmpty());
        assertTrue("insertOutput 必须返回余量（旧实现把余量留在局部变量里丢掉）",
                insert.contains("return remainder"));
    }

    /**
     * 预检副本必须覆写 {@code getSlotLimit} 委托真 handler。
     *
     * <p>{@code BigStackItemHandler} 未覆写 {@code getSlotLimit}，继承
     * {@code ItemStackHandler} 的默认 64；而真 items 的 OUTPUT_SLOT 上限是
     * {@code Integer.MAX_VALUE}。输出槽堆到 64 个后预检永远失败、{@code progress}
     * 每 tick 清零 ⇒ 机器停摆。</p>
     */
    @Test
    public void precheckCopyDelegatesSlotLimitToRealHandler() throws IOException {
        String body = methodBody(read(BE), "private boolean canFitOutputs(");
        assertFalse("找不到 canFitOutputs", body.isEmpty());
        assertTrue("canFitOutputs 的模拟副本必须覆写 getSlotLimit 委托真 handler："
                        + "BigStackItemHandler 默认 64，而输出槽上限是 Integer.MAX_VALUE，"
                        + "输出槽堆到 64 个后预检永远失败、机器停摆",
                body.contains("public int getSlotLimit(int slot)")
                        && body.contains("return items.getSlotLimit(slot);"));
    }
}
