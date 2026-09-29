package cn.ism.mekck.util;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;

/**
 * 钉住 {@code MultiFluidHandler} 两个 drain 重载的「不多抽、不吞掉」性质。
 *
 * <p>{@code MultiFluidHandler} 是暴露给外部管道的 capability，缺陷可被玩家直接触达：
 *
 * <ul>
 *   <li><b>复制</b>：原 {@code drain(FluidStack, …)} 给每个匹配罐都传<b>完整</b>请求量。
 *       3 罐各 1000 mB 时请求 500 mB 会抽出 1500 mB，凭空多出 1000 mB。</li>
 *   <li><b>吞掉</b>：原 {@code drain(int, …)} 是<b>先抽后判</b>流体类型，遇到异种流体才
 *       {@code break}，而那一步已经把流体从罐里扣掉且不退还。水 + 岩浆双罐时
 *       请求 2000 会吞掉 1000 mB 岩浆。</li>
 * </ul>
 *
 * <p>本测试用极小的假罐复刻修复后的两段循环，<b>不加载 {@code FluidStack} / Forge</b>——
 * 它们的初始化链在裸 JVM 里必然失败（同 {@code TestUpgradeIndexWraparoundArithmetic}）。
 * 「这两段循环确实接在 capability 上」由源码位置固定，由 GameTest 在游戏内确认。
 */
public class TestMultiFluidDrainArithmetic {

    /** 假罐：只记流体名与 mB 数。 */
    private static final class Tank {
        final String fluid;
        int amount;

        Tank(String fluid, int amount) {
            this.fluid = fluid;
            this.amount = amount;
        }
    }

    /** 复刻修复后的 {@code drain(FluidStack, …)}：返回 {流体, 抽出量}，null 表示空。 */
    private static String[] drainByRequest(List<Tank> tanks, String wantFluid, int wantAmount) {
        String resultFluid = null;
        int drained = 0;
        int remaining = wantAmount;
        for (Tank t : tanks) {
            if (remaining <= 0) break;
            if (t.amount <= 0 || !t.fluid.equals(wantFluid)) continue;
            int take = Math.min(remaining, t.amount);
            t.amount -= take;
            if (resultFluid == null) resultFluid = wantFluid;
            drained += take;
            remaining -= take;
        }
        return drained == 0 ? null : new String[]{resultFluid, String.valueOf(drained)};
    }

    /** 复刻修复后的 {@code drain(int, …)}。 */
    private static String[] drainByAmount(List<Tank> tanks, int maxDrain) {
        String resultFluid = null;
        int drained = 0;
        for (Tank t : tanks) {
            if (t.amount <= 0) continue;
            int want = maxDrain - drained;
            if (want <= 0) break;
            if (resultFluid != null && !t.fluid.equals(resultFluid)) break;
            // 真实的 IFluidTank.drain(int, …) 最多只返回罐内存量，假罐必须同样钳制
            int take = Math.min(want, t.amount);
            t.amount -= take;
            if (resultFluid == null) resultFluid = t.fluid;
            drained += take;
        }
        return drained == 0 ? null : new String[]{resultFluid, String.valueOf(drained)};
    }

    // ── 复制 ──────────────────────────────────────────────────────────────

    /** 3 罐各 1000 mB，请求 500：只准抽 500，旧实现会返回 1500。 */
    @Test
    public void multiTankRequestDrainsAtMostTheRequestedAmount() {
        List<Tank> tanks = new ArrayList<>(List.of(new Tank("water", 1000), new Tank("water", 1000), new Tank("water", 1000)));
        String[] r = drainByRequest(tanks, "water", 500);
        assertEquals("500", r[1]);
        assertEquals("三罐合计仍应为 2500（1000+1000+1000-500）",
                2500, tanks.stream().mapToInt(t -> t.amount).sum());
    }

    /** 旧实现的实际行为：每罐各抽 500 → 1500，凭空多出 1000 mB。 */
    @Test
    public void theOldPerTankRequestWouldHaveDuplicatedFluid() {
        int total = 0;
        for (int i = 0; i < 3; i++) total += Math.min(500, 1000);   // 每罐都按完整请求量
        assertEquals("这正是要防的复制量", 1500, total);
    }

    /** 请求量大于单罐存量时，逐罐递减仍应精确到总量。 */
    @Test
    public void requestLargerThanOneTankSpreadsAcrossTanks() {
        List<Tank> tanks = new ArrayList<>(List.of(new Tank("water", 300), new Tank("water", 300), new Tank("water", 300)));
        String[] r = drainByRequest(tanks, "water", 700);
        assertEquals("700", r[1]);
        assertEquals(200, tanks.stream().mapToInt(t -> t.amount).sum());
    }

    /** 只匹配到一部分罐时，不得凭空产生流体。 */
    @Test
    public void partialMatchStillNeverExceedsTheRequest() {
        List<Tank> tanks = new ArrayList<>(List.of(new Tank("lava", 5000), new Tank("water", 400)));
        String[] r = drainByRequest(tanks, "water", 900);
        assertEquals("只有 400 mB 水可抽", "400", r[1]);
        assertEquals("岩浆不得被动", 5000, tanks.get(0).amount);
    }

    // ── 吞掉 ──────────────────────────────────────────────────────────────

    /** 水 + 岩浆，请求 2000：岩浆必须原封不动留在罐里。 */
    @Test
    public void mismatchedFluidIsNotDrainedAtAll() {
        List<Tank> tanks = new ArrayList<>(List.of(new Tank("water", 1000), new Tank("lava", 1000)));
        String[] r = drainByAmount(tanks, 2000);
        assertEquals("只应返回水", "water", r[0]);
        assertEquals("1000", r[1]);
        assertEquals("岩浆必须完好无损（先抽后判会吞掉这 1000 mB）", 1000, tanks.get(1).amount);
    }

    /** 同种流体多罐：drain(int) 应正常跨罐合并。 */
    @Test
    public void sameFluidAcrossTanksStillMerges() {
        List<Tank> tanks = new ArrayList<>(List.of(new Tank("water", 600), new Tank("water", 600)));
        String[] r = drainByAmount(tanks, 1000);
        assertEquals("1000", r[1]);
        assertEquals(200, tanks.stream().mapToInt(t -> t.amount).sum());
    }

    /** 流体不匹配导致的提前退出不得影响已返回部分。 */
    @Test
    public void earlyExitStillReturnsWhatWasDrained() {
        List<Tank> tanks = new ArrayList<>(List.of(new Tank("water", 400), new Tank("lava", 900), new Tank("water", 500)));
        String[] r = drainByAmount(tanks, 1500);
        assertEquals("只抽到第一个罐的量", "400", r[1]);
        assertEquals("水罐被抽空", 0, tanks.get(0).amount);
        assertEquals("岩浆原封不动", 900, tanks.get(1).amount);
        assertEquals("第三罐不得被触及", 500, tanks.get(2).amount);
    }
}
