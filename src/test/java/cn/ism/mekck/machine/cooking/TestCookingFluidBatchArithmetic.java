package cn.ism.mekck.machine.cooking;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * 钉住烹饪工厂的流体批量口径 —— <b>防止「扣了料但流体没扣」回归</b>。
 *
 * <h3>它钉的是哪个 bug</h3>
 * 旧 {@code CookingFactoryBlockEntity.getMaxConsumableCount} 用
 * {@code fluidTank.totalOf(type)} 算批量，而 {@code totalOf} 是<b>跨罐求和</b>；
 * 实际扣减的 {@code drainOf} 却要求<b>单个罐</b>里有足量。
 * 于是水被拆成两罐（600 + 600 mB）、每份配方要 1000 mB 时：
 * <pre>
 *   批量 = totalOf / 1000 = 1200 / 1000 = 1   ← 以为能做 1 份
 *   扣料 → 出货 → drainOf(true, 1000) → 两罐都不够，一滴没扣
 * </pre>
 * 净效果是<b>凭空造物品</b>。本轮改成
 * {@link CookingFactoryExecutor#batchForFluidAmounts}，与扣减同口径。
 *
 * <h3>为什么只测这一层</h3>
 * {@code FluidStack} 与 {@code Fluids.WATER} 在裸 JVM 里会触发 Minecraft 的引导
 * 初始化（{@code ExceptionInInitializerError}），真罐 {@code IExtendedFluidTank}
 * 更造不出来。所以本测试打的是 {@code batchForFluidAmounts}——算术本身与
 * 「跨罐取最大」的口径都在那一层，{@code batchForFluid} 只是把罐读成两个数组。
 * 同 {@code TestRandomizeUpgradeBranches} 与 {@code TestSkeweringToolBatchArithmetic}
 * 的处理。
 */
public class TestCookingFluidBatchArithmetic {

    private static boolean[] mask(int length, int... trueIndices) {
        boolean[] out = new boolean[length];
        for (int i : trueIndices) {
            out[i] = true;
        }
        return out;
    }

    // ── 跨罐求和 vs 单罐足量：这就是那个 bug 的分水岭 ──────────────────

    /**
     * 600 + 600 两罐水、每份要 1000 mB → <b>0 份</b>。
     *
     * <p>旧实现在这里会算成 1 份（{@code totalOf} 得 1200），然后扣了料却扣不脱水。
     * 这条断言就是那个 bug 的直接反证。</p>
     */
    @Test
    public void splitWaterCannotSatisfyASingleLargeRequirement() {
        int[] amounts = {600, 600};
        boolean[] bothWater = {true, true};
        assertEquals("两罐各 600 mB 凑不出单罐 1000 mB 的一份（旧的跨罐求和会算成 1 份）",
                0, CookingFactoryExecutor.batchForFluidAmounts(amounts, bothWater, 1000));
    }

    /** 单罐 1200 mB、每份 1000 → 1 份。 */
    @Test
    public void singleTankDividesAsExpected() {
        assertEquals(1, CookingFactoryExecutor.batchForFluidAmounts(
                new int[]{1200}, new boolean[]{true}, 1000));
    }

    /** 单罐 3000 mB、每份 1000 → 3 份。 */
    @Test
    public void batchScalesWithTheSingleTankAmount() {
        assertEquals(3, CookingFactoryExecutor.batchForFluidAmounts(
                new int[]{3000}, new boolean[]{true}, 1000));
    }

    /**
     * 跨罐取<b>最大</b>而不是求和：1200 + 200 mB、每份 1000 → 1 份（不是 1.4 → 1）。
     *
     * <p>这一条与第一条合起来说明：口径是「某个罐能单独供几份」，
     * 而不是「所有罐合起来能供几份」。</p>
     */
    @Test
    public void takesTheMaxPerTankNotTheSum() {
        assertEquals(1, CookingFactoryExecutor.batchForFluidAmounts(
                new int[]{1200, 200}, new boolean[]{true, true}, 1000));
        // 对照：求和口径会给出 1（(1200+200)/1000），但 3 罐各 400 求和同样是 1——
        // 真正能区分两种口径的是这条：三个各 500 的罐，求和 = 1，单罐最大 = 0。
        assertEquals(0, CookingFactoryExecutor.batchForFluidAmounts(
                new int[]{500, 500, 500}, new boolean[]{true, true, true}, 1000));
    }

    // ── 空罐 / 空需求 ──────────────────────────────────────────────────

    /**
     * 没有罐 = 一种流体都没有 → 0 份。
     *
     * <p>这条钉的是本轮真实修过的一个缺陷：{@code tanks == null} 最初与
     * {@code perUnit <= 0} 落在同一条「不限制批量」的分支上，返回 MAX_VALUE。
     * 那样 {@code batchSize} 的 {@code Math.min} 会拿到上界，于是需要流体的配方
     * 在完全没有流体时仍被判为可做 —— 与本类要修的旧 bug 是同一类故障，
     * 只是从「跨罐求和」换成了「没罐也算无限」。</p>
     */
    @Test
    public void noTanksMeansNoFluidAtAll() {
        assertEquals(0, CookingFactoryExecutor.batchForFluidAmounts(null, null, 1));
        assertEquals(0, CookingFactoryExecutor.batchForFluidAmounts(new int[0], new boolean[0], 1));
        assertEquals(0, CookingFactoryExecutor.batchForFluidAmounts(
                new int[]{0}, new boolean[]{true}, 1));
    }

    /** 长度不一致 = 数据损坏，返回 0（判不可做）而不是猜。 */
    @Test
    public void mismatchedArrayLengthsYieldZero() {
        assertEquals(0, CookingFactoryExecutor.batchForFluidAmounts(
                new int[]{1000, 1000}, new boolean[]{true}, 1000));
    }

    /** {@code perUnit <= 0} = 这张配方不用流体 ⇒ 不限制批量。 */
    @Test
    public void noFluidRequirementDoesNotCapTheBatch() {
        assertEquals(Integer.MAX_VALUE,
                CookingFactoryExecutor.batchForFluidAmounts(null, null, 0));
        assertEquals(Integer.MAX_VALUE,
                CookingFactoryExecutor.batchForFluidAmounts(new int[0], new boolean[0], -5));
    }

    // ── 匹配掩码 ────────────────────────────────────────────────────────

    /**
     * 不匹配的罐完全不参与。
     *
     * <p>对应「水不满足奶需求」：机器的水罐与奶罐都走同一条算术，
     * 差别只在喂进来的 {@code matches} 掩码——掩码由
     * {@code isWater} / {@code isNotWater} 产生，那两个判据需要真 {@code FluidStack}，
     * 无法在裸 JVM 里跑，所以这里只钉「掩码生效」这一半。</p>
     */
    @Test
    public void unmatchedTanksAreIgnoredEntirely() {
        int[] amounts = {5000, 1000};
        boolean[] onlySecondIsMilk = {false, true};
        assertEquals("第一罐（5 罐水）不该满足奶需求",
                1, CookingFactoryExecutor.batchForFluidAmounts(amounts, onlySecondIsMilk, 1000));
    }

    /** 全不匹配 = 0。 */
    @Test
    public void noMatchingTankYieldsZero() {
        assertEquals(0, CookingFactoryExecutor.batchForFluidAmounts(
                new int[]{5000, 5000}, mask(2), 1000));
    }
}
