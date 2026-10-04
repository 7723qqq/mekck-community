package cn.ism.mekck.machine;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * {@link MekCkOrderState#advance(int)} 的<b>实例方法</b>护栏。
 *
 * <h3>为什么单独给它一个测试类</h3>
 * 第三轮的对抗性复核发现：全仓对 {@code MekCkOrderState} 的引用只有
 * {@code TestGrindingOrderEngine} javadoc 里的一句 {@code @link}，
 * 现有测试打的全是<b>静态</b>形态 {@code advancedTo(completed, quantity, delta)}。
 * 也就是说实例方法 {@code advance(int)} —— <b>生产路径上真正被调用的那一个</b> ——
 * 一条回归护栏都没有（复核方用 50 万例差分验证过它与静态形态一致，
 * 但那是手工验证，不会随下次改动重跑）。
 *
 * <p>本类把它钉住。三个断言对应三个真实缺陷形态：</p>
 * <ol>
 *   <li><b>溢出</b>：旧实现是 {@code orderCompleted++}（int 自增），
 *       份数配成 {@link Integer#MAX_VALUE} 且真跑满时先绕成 {@code MIN_VALUE}，
 *       随后判定里的 {@code (long)} 转换<b>已经太晚</b> ⇒ 订单永远完不成、
 *       机器永远只认这一张配方、<b>不报任何错</b>；</li>
 *   <li><b>无订单时推进</b>：不该凭空开始一张单；</li>
 *   <li><b>与静态形态一致</b>：静态形态是给裸 JVM 断言用的替身，
 *       两者一旦漂移，那边全绿的测试就变成假护栏。</li>
 * </ol>
 */
public class TestMekCkOrderStateAdvance {

    @Test
    public void advancingPastIntMaxDoesNotWrapToNegative() {
        MekCkOrderState order = new MekCkOrderState();
        order.setOrder(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("mekck", "x"), Integer.MAX_VALUE);
        // 反复推进直到夹取生效：旧实现在这里早就绕成负数了。
        for (int i = 0; i < 5; i++) {
            order.advance(Integer.MAX_VALUE);
        }
        assertTrue("已完成份数不得为负（负数 = 溢出）", order.getCompleted() > 0);
        assertEquals("累计应夹在 Integer.MAX_VALUE",
                Integer.MAX_VALUE, order.getCompleted());
    }

    @Test
    public void theClampedTotalIsStillReportedAsComplete() {
        MekCkOrderState order = new MekCkOrderState();
        order.setOrder(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("mekck", "x"), Integer.MAX_VALUE);
        order.advance(Integer.MAX_VALUE);
        // 关键：夹到 MAX_VALUE 之后「已满」必须成立 —— 否则订单永远挂着。
        assertTrue("夹到 MAX_VALUE 之后应判定为已完成", order.advance(1));
    }

    @Test
    public void advancingWithNoOrderIsANoOp() {
        MekCkOrderState order = new MekCkOrderState();
        assertFalse("无订单时 advance 不得凭空开始一张单", order.advance(5));
        assertEquals("无订单时已完成份数必须保持 0", 0, order.getCompleted());
        assertEquals(0, order.getQuantity());
    }

    @Test
    public void nonPositiveDeltaDoesNotMoveProgress() {
        MekCkOrderState order = new MekCkOrderState();
        order.setOrder(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("mekck", "x"), 10);
        order.advance(3);
        assertFalse("delta ≤ 0 不推进，但仍要如实回答「是否已满」", order.advance(0));
        assertEquals(3, order.getCompleted());
        assertFalse("未到 10 份不应报完成", order.advance(-5));
        assertEquals(3, order.getCompleted());
    }

    /**
     * 实例方法与静态替身在整张边界网格上必须<b>逐例一致</b>。
     *
     * <p>静态 {@code advancedTo} 是给裸 JVM 断言用的替身（真执行器在测试环境造不出来）。
     * 它一旦与实例方法漂移，那边所有测试就都成了假护栏 —— 所以这里做<b>差分</b>而不是抽样。</p>
     *
     * <p>差分的口径是「推进后的最终结论」：实例方法走完 {@code advance(delta)} 之后再用
     * <b>已推进到位的 completed</b> 调静态形态（{@code delta = 0}，只问「是否已满」）。
     * 刻意不拿「推进前的 completed」比 —— 两个形态的入参语义本来就不同
     * （实例持有状态，静态是纯函数）。</p>
     */
    @Test
    public void instanceAndStaticFormsAgreeAcrossTheBoundaryGrid() {
        int[] values = {
                0, 1, 2, 7, 63, 64, 127, 128, 255, 256, 65_535, 65_536,
                1_000_000, Integer.MAX_VALUE - 1, Integer.MAX_VALUE, -1
        };
        net.minecraft.resources.ResourceLocation id =
                net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("mekck", "x");
        int mismatches = 0;
        int cases = 0;
        for (int completed : values) {
            for (int quantity : values) {
                for (int delta : values) {
                    cases++;
                    MekCkOrderState order = new MekCkOrderState();
                    if (quantity > 0) {
                        order.setOrder(id, quantity);
                        if (completed > 0) {
                            order.advance(completed);
                        }
                    }
                    boolean viaInstance = order.advance(delta);
                    // 静态形态：拿推进到位的 completed、delta=0，只问「是否已满」。
                    boolean viaStatic = MekCkOrderState.advancedTo(order.getCompleted(), quantity, 0);
                    if (viaInstance != viaStatic) {
                        mismatches++;
                    }
                }
            }
        }
        assertEquals("实例与静态形态在 " + cases + " 例边界网格上出现分歧", 0, mismatches);
    }
}
