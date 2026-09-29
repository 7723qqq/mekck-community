package cn.ism.mekck.recipe;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 钉住串烧的「签子不消耗」批量算术——<b>防止除零回归</b>。
 *
 * <p>背景：{@code SkeweringFactoryBlockEntity.getMaxConsumableCount} 按反射契约读
 * {@code tool} / {@code ingredient} / {@code side} 与 {@code ingredientCount} / {@code sideCount}
 * 五个字段。其中签子那一支是：
 *
 * <pre>
 *   int toolCount = getCountField(recipe, "ingredientCount");   // 本项目里读的是【签子】的消耗数
 *   if (toolAvailable &lt; toolCount) return 0;
 *   minCount = Math.min(minCount, toolAvailable / toolCount);   // ← toolCount == 0 时除零
 * </pre>
 *
 * <p>{@code mekck:skewering} 的签子<b>不消耗</b>（产物完成后由 {@code completeRecipe}
 * 从输入槽 0 原样取回返还槽），因此 {@code ingredientCount} 恒为 0。除零抛出的
 * {@code ArithmeticException} 会被该方法外层的 {@code catch (Exception) { return 0; }} 吞掉，
 * 净效果是 {@code getMaxConsumableCount} 恒返回 0 →
 * {@code getAvailableRecipes()} 恒空 → 订单设不了 → <b>机器完全惰性且无任何日志</b>。
 *
 * <p>本测试只复刻这段算术，<b>不加载 {@code Ingredient} / {@code BlockEntity}</b>——
 * 它们的初始化链在裸 JVM 里必然失败（同 {@code TestUpgradeIndexWraparoundArithmetic} 的处理）。
 * 「这段算术确实接在机器的批量计算上」由源码位置固定，由 GameTest 在游戏内端到端确认。
 *
 * <p>若哪天有人认为「签子也该扣，count 设 1 就完了」，
 * 剩下的 {@code testToolCountZeroStillRequiresAPresentTool} 就是他们的反驳依据。
 */
public class TestSkeweringToolBatchArithmetic {

    /**
     * 复刻 {@code getMaxConsumableCount} 的签子分支。返回 {@code -1} 表示该分支应直接
     * {@code return 0}（无法开工）；否则返回它对批量的限制。
     */
    private static int toolBatchLimit(int toolAvailable, int toolCount) {
        if (toolAvailable <= 0) {
            return -1;
        }
        if (toolCount > 0) {
            if (toolAvailable < toolCount) {
                return -1;
            }
            return toolAvailable / toolCount;
        }
        // toolCount == 0：签子不消耗，只要求存在，不限制批量
        return Integer.MAX_VALUE;
    }

    /** 签子不消耗时，1 根签子也应允许开工，而不是被判成「材料不够」恒返回 0。 */
    @Test
    public void zeroToolCountDoesNotCapTheBatchAtZero() {
        int limit = toolBatchLimit(1, 0);
        assertTrue("toolCount=0 时不应把批量压到 0（那正是除零被吞后的表象）", limit > 0);
    }

    /** 签子不消耗时，多根签子也不该把批量限制在签子数量上。 */
    @Test
    public void zeroToolCountLeavesBatchUnlimitedByToolCount() {
        assertEquals("1 根签子与 64 根签子的批量上限应相同（都不受签子限制）",
                toolBatchLimit(1, 0), toolBatchLimit(64, 0));
    }

    /** 一根签子都没有时仍然不能开工——「不消耗」不等于「不需要」。 */
    @Test
    public void zeroToolCountStillRequiresAPresentTool() {
        assertEquals("没有签子时应判定无法开工", -1, toolBatchLimit(0, 0));
    }

    /** 外部模组 barbequesdelight 的老路径：toolCount > 0 时行为不变，仍按签子数分批。 */
    @Test
    public void positiveToolCountStillDividesAsBefore() {
        assertEquals(4, toolBatchLimit(8, 2));
        assertEquals("-1 表示无法开工（签子不够）", -1, toolBatchLimit(1, 2));
        assertEquals("-1 表示无法开工（一根签子都没有）", -1, toolBatchLimit(0, 1));
    }
}
