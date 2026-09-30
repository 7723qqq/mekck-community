package cn.ism.mekck.machine;

import mekanism.api.Action;
import mekanism.api.AutomationType;
import mekanism.api.IContentsListener;
import mekanism.api.math.FloatingLong;
import mekanism.common.capabilities.energy.BasicEnergyContainer;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 「机器每 tick 真的扣掉能量」—— 2026-09-29 修的那个洞。
 *
 * <h3>这个洞是什么</h3>
 * 阶段 2 Task 4 交付时，{@code MekCkMachineTile.workCycle} 里的扣电写成
 * {@code energyContainer.extract(cost, EXECUTE, AutomationType.EXTERNAL)}。
 * 但本机容器由 {@code MachineEnergyContainer.input(tile, listener)} 建成，而
 * {@code javap} 实测该工厂方法把 {@code notExternal} 传给了 <b>canExtract</b>、
 * {@code alwaysTrue} 传给了 <b>canInsert</b>；
 * {@code BasicEnergyContainer.extract} 开头又是
 * 「{@code if (!canExtract.test(type)) return ZERO;}」。
 * 于是那一行<b>一 FE 都扣不下来</b>：机器照常加工、进度条照常走、能量条却永不掉。
 *
 * <h3>为什么这里能跑真行为测试</h3>
 * 真 tile 在裸 JVM 里造不出来（构造链要 {@code CuttingMachineFactoryBlock} 与 Mek 的注册表），
 * 但扣电这一步被收进了包级静态方法 {@link MekCkMachineTile#deductEnergy}，
 * 它的全部输入就是「一个容器 + 一个扣减额」。于是本测试可以拿一个与生产
 * <b>同谓词形状</b>的 {@link BasicEnergyContainer} 直接调它，
 * 断言「扣减额为正 ⇒ 存量真的减少」。
 *
 * <p>「同谓词形状」的可信度依据：这里传的就是 {@code MachineEnergyContainer.input}
 * 所传的那两个<b>同一个静态单例</b>（{@code BasicEnergyContainer.notExternal} /
 * {@code alwaysTrue}），不是本测试自己造的等价物。连接两者的最后一环——
 * {@code input()} 的传参顺序——是 {@code javap} 读数，不是本测试能验的，
 * 已写进 {@link MekCkMachineTile#deductEnergy} 的注释。
 * （{@code TestRandomizeUpgradeBranches.machineInputContainerAcceptsInsertsButRejectsExternalExtract}
 * 钉的是同一组谓词在容器上的行为，两者合起来覆盖整条链。）</p>
 */
public class TestEnergyCostAutomation {

    private static final IContentsListener NOOP_LISTENER = () -> {
    };

    private static final Path TILE =
            Path.of("src", "main", "java", "cn", "ism", "mekck", "machine", "MekCkMachineTile.java");

    /** 与 {@code MachineEnergyContainer.input} 同谓词形状的容器：canExtract=notExternal、canInsert=alwaysTrue。 */
    private static BasicEnergyContainer machineShapedContainer(long max) {
        return BasicEnergyContainer.create(FloatingLong.create(max),
                BasicEnergyContainer.notExternal, BasicEnergyContainer.alwaysTrue, NOOP_LISTENER);
    }

    /**
     * <b>主断言：扣减额为正时，能量容器真的减少。</b>
     *
     * <p>先灌 400，再按真机上会出现的扣减额（20 = {@code ENERGY_PER_PROCESS}）扣 3 tick，
     * 断言存量逐 tick 减少、总量正好等于扣减额 × tick 数。
     * 传 EXTERNAL 的那次已经能扣动了（见 {@code TestRandomizeUpgradeBranches}
     * 里「EXTERNAL 被整条拒掉」那条相反方向的断言）。</p>
     */
    @Test
    public void positiveCostActuallyDrainsTheContainer() {
        BasicEnergyContainer container = machineShapedContainer(1000);
        container.insert(FloatingLong.create(400), Action.EXECUTE, AutomationType.MANUAL);

        int costPerTick = 20;
        for (int tick = 1; tick <= 3; tick++) {
            MekCkMachineTile.deductEnergy(container, costPerTick);
            assertEquals("第 " + tick + " tick 扣电后存量不对", 400L - (long) costPerTick * tick,
                    container.getEnergy().longValue());
        }
    }

    /**
     * 反向对照：换成 EXTERNAL 就<b>一 FE 都扣不动</b>。
     *
     * <p>这条不是重复断言容器语义（那条在 {@code TestRandomizeUpgradeBranches}），
     * 它是<b>把同一个夹具换一种 AutomationType</b>再跑一遍，好让读者看到
     * 「唯一变量就是 AutomationType」——修的正是这个变量，不是别的什么。</p>
     */
    @Test
    public void theSameContainerIgnoresExternalExtraction() {
        BasicEnergyContainer container = machineShapedContainer(1000);
        container.insert(FloatingLong.create(400), Action.EXECUTE, AutomationType.MANUAL);

        FloatingLong taken = container.extract(FloatingLong.create(20), Action.EXECUTE, AutomationType.EXTERNAL);

        assertTrue("EXTERNAL 抽取被 canExtract=notExternal 整条拒掉，返回 ZERO", taken.isZero());
        assertEquals("被拒时存量一分不动", 400L, container.getEnergy().longValue());
    }

    /**
     * 免耗电档 / 随机化卡的 {@code cost == 0} 走到 deductEnergy 时必须是空操作。
     *
     * <p>生产代码在 {@code workCycle} 里有 {@code if (cost > 0)} 挡着，这里补的是
     * 「万一哪天那个 if 被去掉，这里也不会倒扣能量」——{@code FloatingLong.create(0)}
     * 本身就是 ZERO，{@code extract} 会在 {@code amount.isZero()} 那一支原样返回。</p>
     */
    @Test
    public void zeroCostIsANoOp() {
        BasicEnergyContainer container = machineShapedContainer(1000);
        container.insert(FloatingLong.create(400), Action.EXECUTE, AutomationType.MANUAL);

        MekCkMachineTile.deductEnergy(container, 0);

        assertEquals("扣减额为 0 时存量不变", 400L, container.getEnergy().longValue());
    }

    /**
     * 源码不变量：<b>全文件不得再有直接调 {@code extract} 的地方</b>。
     *
     * <p>钉的是「唯一入口」这条结构，而不是逐字匹配某个 AutomationType 字面量：
     * 只要扣电还走 {@code deductEnergy}，AutomationType 选错也会被本类的
     * 真行为断言挡下（{@code deductEnergy} 一旦传回 EXTERNAL，
     * {@link #positiveCostActuallyDrainsTheContainer} 立刻变红）。反过来说，
     * 若有人绕过 {@code deductEnergy} 自己再写一处 {@code energyContainer.extract(...)}，
     * 这条会先红。</p>
     */
    @Test
    public void deductEnergyIsTheOnlyExtractionSite() throws IOException {
        String source = Files.readString(TILE, StandardCharsets.UTF_8);
        assertTrue("workCycle 必须走 deductEnergy", source.contains("deductEnergy(energyContainer, perLaneCost)"));
        assertFalse("不得绕过 deductEnergy 直接抽取能量（AutomationType 会退回 EXTERNAL 的老坑）",
                source.contains("energyContainer.extract("));
    }
}
