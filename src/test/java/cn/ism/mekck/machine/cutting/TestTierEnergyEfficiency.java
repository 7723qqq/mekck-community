package cn.ism.mekck.machine.cutting;

import cn.ism.mekck.CuttingMachineFactoryTier;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 档位能效乘数 —— 「高阶更省电，因为贵」。
 *
 * <h3>加之前是什么样</h3>
 * 旧扣电公式 {@code ceil(20 × 速度² × 能量卡)} 里那个 20 与档位无关，
 * {@code tier.energyPerTick} 的 60/100/…/500 在工厂 BE 里<b>只被用作 {@code == 0} 判断</b>
 * （免能耗档），不参与任何扣电算式。于是 12 个档位的<b>单位产出能耗完全一样</b>，
 * 高阶只是并行多、贵在并行而不是贵在电。
 *
 * <h3>能跑什么、不能跑什么</h3>
 * <ul>
 *   <li><b>能跑真行为</b>：公式本身被提成包级静态
 *       {@link CuttingFactoryTile#baseEnergyPerTick(double, double, double)}，
 *       三个入参全是纯 double，裸 JVM 里直接调（不必 {@code boot()}）。
 *       {@link CuttingMachineFactoryTier} 也能加载——{@code MekCkMachineTile} 的
 *       {@code <clinit>} 本来就在遍历它。</li>
 *   <li><b>只能读源码</b>：配置那半边。{@code MekckConfig} 在裸 JVM 里加载即抛
 *       （见 {@code TestCuttingRecipeInvariants} 的类注释），真 tile 也造不出来，
 *       所以「配置项存在、默认值取自枚举、公式确实读了它」这三条钉在源码文本上，
 *       与本仓库既有的做法同源（{@code TestMekCkSlot.limitComesFromMekckConfig}）。</li>
 * </ul>
 *
 * <p>整条链在游戏里的样子<b>本环境无法验证</b>：Farmer's Delight 自身 mixin 崩溃
 * （2026-09-10 起既有问题），游戏起不来。玩家能观察到的部分（能量条下降速度）
 * 全部落在 {@code test} 之外。</p>
 */
public class TestTierEnergyEfficiency {

    private static final Path TILE =
            Path.of("src", "main", "java", "cn", "ism", "mekck", "machine", "cutting", "CuttingFactoryTile.java");
    private static final Path CONFIG =
            Path.of("src", "main", "java", "cn", "ism", "mekck", "config", "MekckConfig.java");

    /**
     * 钉死 12 档的默认取值。
     *
     * <p>这是<b>平衡参数</b>，不是实现细节——所以刻意逐档写死而不是只断言「单调递减」。
     * 将来要调曲线，改这里等于强迫改表的人在同一处看到自己动了哪一档。
     * NEBULA 取 0 而非 0.05 的理由见 {@link CuttingMachineFactoryTier#energyEfficiency}。</p>
     */
    @Test
    public void perTierDefaultsAreTheAgreedCurve() {
        assertEquals(1.00, CuttingMachineFactoryTier.BASIC.energyEfficiency, 1e-9);
        assertEquals(0.80, CuttingMachineFactoryTier.ADVANCED.energyEfficiency, 1e-9);
        assertEquals(0.65, CuttingMachineFactoryTier.ELITE.energyEfficiency, 1e-9);
        assertEquals(0.50, CuttingMachineFactoryTier.ULTIMATE.energyEfficiency, 1e-9);
        assertEquals(0.40, CuttingMachineFactoryTier.ABSOLUTE.energyEfficiency, 1e-9);
        assertEquals(0.32, CuttingMachineFactoryTier.SUPREME.energyEfficiency, 1e-9);
        assertEquals(0.25, CuttingMachineFactoryTier.COSMIC.energyEfficiency, 1e-9);
        assertEquals(0.20, CuttingMachineFactoryTier.INFINITE.energyEfficiency, 1e-9);
        assertEquals(0.15, CuttingMachineFactoryTier.BLAZE.energyEfficiency, 1e-9);
        assertEquals(0.10, CuttingMachineFactoryTier.CRYSTAL_MATRIX.energyEfficiency, 1e-9);
        assertEquals(0.00, CuttingMachineFactoryTier.NEBULA.energyEfficiency, 1e-9);
        assertEquals(0.00, CuttingMachineFactoryTier.SINGULARITY.energyEfficiency, 1e-9);
    }

    /**
     * 曲线必须<b>逐档不增</b>——这是「高阶更省电」这句话唯一的形式化表述。
     *
     * <p>少了这条，上面那张表完全可以被改成「先降后升」而仍然全部断言通过。</p>
     */
    @Test
    public void noHigherTierCostsMorePerUnitOutputThanTheOneBelow() {
        CuttingMachineFactoryTier[] tiers = CuttingMachineFactoryTier.values();
        for (int i = 1; i < tiers.length; i++) {
            assertTrue(tiers[i].name + "(" + tiers[i].energyEfficiency + ") 不得比 "
                            + tiers[i - 1].name + "(" + tiers[i - 1].energyEfficiency + ") 更费电",
                    tiers[i].energyEfficiency <= tiers[i - 1].energyEfficiency);
        }
    }

    /**
     * 无升级时，单 tick 基准能耗必须逐档下降，并落在 {@code ceil(20 × 乘数)} 上。
     *
     * <p>20/16/13/10/8/7/5/4/3/2/0/0 这串数就是玩家在能量条上能感觉到的比例。</p>
     */
    @Test
    public void baseCostPerTickFallsMonotonicallyAndRoundsUpOnce() {
        int[] expected = {20, 16, 13, 10, 8, 7, 5, 4, 3, 2, 0, 0};
        CuttingMachineFactoryTier[] tiers = CuttingMachineFactoryTier.values();
        assertEquals("档位数与断言表必须同步", expected.length, tiers.length);
        for (int i = 0; i < tiers.length; i++) {
            assertEquals(tiers[i].name + " 的单 tick 基准能耗",
                    expected[i], CuttingFactoryTile.baseEnergyPerTick(1.0, 1.0, tiers[i].energyEfficiency));
        }
    }

    /**
     * 乘数必须在 {@code ceil} <b>之前</b>进式子，否则 SUPREME 会被少收。
     *
     * <p>{@code 20 × 0.32 = 6.4}：先取整再乘会被 {@code (int)} 向零截断成 6，
     * 先乘再 {@code ceil} 才是 7。12 档里只有 SUPREME 的 {@code 20 × 乘数} 不是整数，
     * 所以这条是<b>唯一一个</b>能被「顺序写反」抓到症状的档——也正因如此必须钉住。</p>
     */
    @Test
    public void supremeRoundsUpInsteadOfTruncating() {
        assertEquals(7, CuttingFactoryTile.baseEnergyPerTick(1.0, 1.0, 0.32));
        // 反证：把乘数挪到 ceil 之后，就得对乘积再截断一次（Java 的 (int) 向零截断），
        // 结果是 6，比正确值少 1。
        assertEquals("若实现改成先取整再乘，这里应当变成 6 —— 说明顺序被写反了", 6,
                (int) (Math.ceil(20.0) * 0.32));
    }

    /** 免能耗档的乘数是 0，公式自己就该算出 0，不需要额外分支。 */
    @Test
    public void zeroEfficiencyCostsNothing() {
        assertEquals(0, CuttingFactoryTile.baseEnergyPerTick(1.0, 1.0, 0.0));
        // 速度/能量卡再高也一样：0 × 任何有限值还是 0。
        assertEquals(0, CuttingFactoryTile.baseEnergyPerTick(64.0, 0.000_1, 0.0));
    }

    /**
     * 夹紧：配置被调成天文数字 / 负数都不能让扣减额翻负或溢出。
     *
     * <p>{@code defineInRange} 的上界是 10，正常配置走不到这里；这条防的是
     * 手改 toml、以及将来把上界调高之后的行为。</p>
     */
    @Test
    public void pathologicalMultipliersClampInsteadOfOverflowing() {
        assertEquals(Integer.MAX_VALUE, CuttingFactoryTile.baseEnergyPerTick(1e9, 1e9, 10.0));
        assertEquals(0, CuttingFactoryTile.baseEnergyPerTick(1.0, 1.0, -1.0));
    }

    /**
     * 源码不变量：扣电公式确实读了配置里的能效乘数。
     *
     * <p>只看 {@code baseEnergyPerTick} 的行为测不出「乘数可配」——
     * 上面那些断言传的都是枚举默认值，配置读没读进来是同一条路径。
     * 这里钉住调用点确实走 {@code MekckConfig.getTierEnergyEfficiency(tier)}，
     * 且旧式子的其余部分（免能耗短路、{@code active}、{@code mulClamp}）没被动过。</p>
     */
    @Test
    public void energyFormulaReadsEfficiencyFromConfig() throws IOException {
        String source = Files.readString(TILE, StandardCharsets.UTF_8);
        assertTrue("energyPerLanePerTick 必须把档位的能效乘数喂进公式",
                source.contains("MekckConfig.getTierEnergyEfficiency(tier)"));
        assertTrue("公式入口必须仍是 baseEnergyPerTick(sp, cons, eff)",
                source.contains("baseEnergyPerTick(speedMult, consumptionMult,"));
        // 旧式子的其余两段逐字还在：免能耗短路 / 走 mulClamp 夹紧。
        assertTrue("免能耗档短路不能被能效乘数取代", source.contains("tier.energyPerTick == 0"));
        assertTrue("外层乘法仍要保留溢出夹紧",
                source.contains("CountMath.mulClamp(Integer.MAX_VALUE, baseEnergyPerTick, stackMultiplier())"));
        // 并行数不再乘在这里：它由基类 workCycle 的逐路扣减自然乘出来（跑几路扣几份）。
        assertFalse("并行数不该再乘进单路公式", source.contains("baseEnergyPerTick, active, stackMultiplier()"));
    }

    /**
     * 源码不变量：配置里确实有 12 个逐档项，默认值取自枚举。
     *
     * <p>钉三件事：① 段名 {@code energy_efficiency}；② 键名带档位名（否则整合包作者
     * 分不清哪一项管哪一档）；③ 默认值写的是 {@code tier.energyEfficiency} 而不是
     * 又抄一遍字面量——抄一遍的话，枚举改了配置不会跟着改，注释与实际值就分家了。</p>
     */
    @Test
    public void configExposesOnePerTierEfficiencyWithTheEnumDefault() throws IOException {
        String source = Files.readString(CONFIG, StandardCharsets.UTF_8);
        assertTrue("必须有 energy_efficiency 配置段", source.contains(".push(\"energy_efficiency\")"));
        assertTrue("默认值必须取自枚举，不能在这里再抄一份字面量",
                source.contains("defineInRange(tier.name + \"_energy_efficiency\", tier.energyEfficiency"));
        assertTrue("必须逐档遍历 12 档", source.contains("TIER_ENERGY_EFFICIENCY.put(tier, BUILDER"));
        assertTrue("必须有对外读取口", source.contains("public static double getTierEnergyEfficiency("));
    }
}
