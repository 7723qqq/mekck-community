package cn.ism.mekck.recipe;

import cn.ism.mekck.machine.skewering.SkeweringFactoryExecutor;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 钉住串烧的「签子不消耗」批量算术——<b>防止除零回归</b>。
 *
 * <p>背景：批量按「三种材料各还剩多少」分别算再取小（{@code batchSize}），其中签子那一支
 * 读的是 {@code ingredientCount}——<b>注意它管的是签子，不是主料</b>（主料恒 1）。
 *
 * <p>{@code mekck:skewering} 的签子<b>不消耗</b>（自有配方 toolCount=0：签子不消耗、也不返还），
 * 因此 {@code ingredientCount} 恒为 0。
 * 旧实现写的是 {@code toolAvailable / toolCount}，{@code toolCount == 0} 时除零，
 * {@code ArithmeticException} 被外层 {@code catch (Exception) { return 0; }} 吞掉，
 * 净效果是批量恒 0 → 配方列表恒空 → 订单设不了 → <b>机器完全惰性且无任何日志</b>。
 *
 * <h3>本测试直接调生产代码</h3>
 * 早期版本在测试里<b>重抄了一遍</b>那段算术，于是它钉的是测试自己而不是机器——
 * 生产代码改回除零，测试照样全绿。现在断言打在
 * {@link SkeweringFactoryExecutor#batchForMaterial} 上。
 * 那个方法只吃两个 int、不碰 {@code Ingredient} / {@code BlockEntity}，
 * 因此能在裸 JVM 里跑（同 {@code TestUpgradeIndexWraparoundArithmetic} 的处理）。
 *
 * <p>若哪天有人认为「签子也该扣，count 设 1 就完了」，
 * {@link #zeroToolCountStillRequiresAPresentTool()} 就是他们的反驳依据。
 */
public class TestSkeweringToolBatchArithmetic {

    /** 签子不消耗时，1 根签子也应允许开工，而不是被判成「材料不够」恒返回 0。 */
    @Test
    public void zeroToolCountDoesNotCapTheBatchAtZero() {
        int limit = SkeweringFactoryExecutor.batchForMaterial(1, 0);
        assertTrue("toolCount=0 时不应把批量压到 0（那正是除零被吞后的表象）", limit > 0);
    }

    /** 签子不消耗时，多根签子也不该把批量限制在签子数量上。 */
    @Test
    public void zeroToolCountLeavesBatchUnlimitedByToolCount() {
        assertEquals("1 根签子与 64 根签子的批量上限应相同（都不受签子限制）",
                SkeweringFactoryExecutor.batchForMaterial(1, 0),
                SkeweringFactoryExecutor.batchForMaterial(64, 0));
    }

    /** 一根签子都没有时仍然不能开工——「不消耗」不等于「不需要」。 */
    @Test
    public void zeroToolCountStillRequiresAPresentTool() {
        assertEquals("没有签子时应判定无法开工", 0, SkeweringFactoryExecutor.batchForMaterial(0, 0));
    }

    /** 外部模组 barbequesdelight 的老路径：toolCount > 0 时行为不变，仍按签子数分批。 */
    @Test
    public void positiveToolCountStillDividesAsBefore() {
        assertEquals(4, SkeweringFactoryExecutor.batchForMaterial(8, 2));
        assertEquals("签子不够（1 根要 2 个）时应判无法开工", 0, SkeweringFactoryExecutor.batchForMaterial(1, 2));
        assertEquals("一根签子都没有时应判无法开工", 0, SkeweringFactoryExecutor.batchForMaterial(0, 1));
    }

    /**
     * 主料恒按 1 个一批分批（{@code batchSize} 里写死 {@code perUnit = 1}），
     * 所以主料个数就是批量上限——这条钉住「主料不会被除成 0 批」。
     */
    @Test
    public void mainIngredientIsCountedOnePerSkewer() {
        assertEquals(5, SkeweringFactoryExecutor.batchForMaterial(5, 1));
        assertEquals(0, SkeweringFactoryExecutor.batchForMaterial(0, 1));
    }
}
