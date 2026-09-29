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
 * 随机化卡（{@code mekck:upgrade_randomize}）在切菜工厂上附带的三条机械分支
 * —— 阶段 2 Task 4.7。
 *
 * <h3>这三条分支的旧出处</h3>
 * 旧 {@code CuttingMachineFactoryBlockEntity} 里，三条分支全在
 * {@code serverTick} 的<b>同一段</b>，且由同一个 {@code hasCreative} 布尔驱动
 * （该变量的定义在第 376 行的注释里：{@code "Creative upgrade: fill energy to
 * max, no consumption, 1 tick process time"}）：
 * <pre>
 *   第 377 行  boolean hasCreative = machine.hasCreativeUpgrade();
 *   第 378~380 行  if (hasCreative) { energy.receiveEnergy(max - stored, false); }   ← 自动补满
 *   第 387 行  int effectiveProcessTime = hasCreative ? 1
 *                          : Math.max(1, (int) (PROCESS_TIME / speedMult));              ← 1 tick 批次
 *   第 388 行  int baseEnergyPerTick = hasCreative ? 0 : ...;
 *   第 414 行  int energyPerTick = activeSlots &gt; 0 &amp;&amp; !hasCreative ? ... : 0;      ← 免耗电
 * </pre>
 * <b>「自动补满」补的是能量容器，不是任何物品槽</b>：旧实现里输入槽 / 产物槽都不受
 * 创造卡影响，AE2 补料路径也与之无关（旧 BE 的 {@code supportsAutoPull()} 恒 true、
 * {@code getNetworkPullInputs()} 不看卡）。这与「自动补料」是两件事。
 *
 * <h3>为什么一半是真行为测试、一半是源码不变量</h3>
 * <ul>
 *   <li><b>真行为</b>：三处判定都被做成包级 {@code static}
 *       （{@code gatedEnergyCost} / {@code gatedTicksPerCycle} / {@code energyToRefill}），
 *       因此可以在裸 JVM 里直接跑（{@code MekCkMachineTile} 本身能被加载，
 *       {@code <clinit>} 不碰注册表——实测本类 12 条断言在裸 JVM 里跑得通）。
 *       另外两条断言直接拿<b>真的</b> {@code BasicEnergyContainer} 跑 Mek 自己的闸门语义，
 *       把「字节码读数」升级成「可执行证据」。</li>
 *   <li><b>源码不变量</b>：真 tile 造不出来（构造链要 {@code CuttingMachineFactoryBlock} 与 Mek 的
 *       注册表，见 {@code TestMekCkSlot} 的类注释），所以「钩子默认关、切菜 tile 开」
 *       这件事只能钉在源码上。这与本仓库既有的做法同源
 *       （{@code TestMekCkSlot.tileBuildsItsSlotsThroughMekCkSlot} 等）。</li>
 * </ul>
 *
 * <p><b>刻意不在这里断言旧 BE 的源码</b>：它已被标记为阶段 2 Task 5 待删，
 * 任何读它源码的断言都会在 Task 5 那天集体变红。旧语义只在本类注释里以行号留档。</p>
 */
public class TestRandomizeUpgradeBranches {

    private static final String TILE =
            "src/main/java/cn/ism/mekck/machine/MekCkMachineTile.java";
    private static final String CUTTING =
            "src/main/java/cn/ism/mekck/machine/cutting/CuttingFactoryTile.java";

    // ── 分支一「免耗电」：置 0，不是乘 0 ────────────────────────────────

    /**
     * 旧第 414 行是 {@code activeSlots > 0 && !hasCreative ? mulClamp(...) : 0}：
     * 有卡时整个扣减额被<b>换成 0</b>。因此断言必须是「恰好 0」，
     * 而不能是「乘 0」——后者在扣减额为 0 时与本实现不可区分，
     * 差别只在「闸门是否被绕过」这一层：置 0 之后
     * {@code hasEnergyFor(0)} 恒真（{@code cost <= 0 || ...}），
     * 机器在能量存量为 0 时照样推进。
     */
    @Test
    public void freeEnergyZeroesTheCostRatherThanScalingIt() {
        int perTick = 20 * 81 * 64;   // 高倍率切菜工厂的一 tick 扣减额，刻意取一个非 0 大数
        assertEquals("装卡后扣减额必须是 0，不是乘 0", 0, MekCkMachineTile.gatedEnergyCost(true, perTick));
        assertEquals("不装卡时逐字保持原公式", perTick, MekCkMachineTile.gatedEnergyCost(false, perTick));
    }

    /**
     * 装卡时能量闸门不会被卡住：基类 {@code hasEnergyFor(cost)} 是
     * {@code cost <= 0 || stored >= cost}，置 0 之后恒真。
     * 这里把该判据原样写出来跑一遍——它是「免耗电」在<b>能量为 0</b>时仍然
     * 能开工的根据（旧第 422 行 {@code energy.getEnergyStored() >= energyPerTick}）。
     */
    @Test
    public void zeroCostLetsTheGatePassOnAnEmptyBuffer() {
        int stored = 0;
        int cost = MekCkMachineTile.gatedEnergyCost(true, 20 * 81);
        boolean allowed = cost <= 0 || stored >= cost;
        assertTrue("免耗电时能量为 0 也应当放行（否则机器永远不动）", allowed);
    }

    // ── 分支二「1 tick 批次」：换掉门槛，不是调大速度 ───────────────────

    /**
     * 旧 {@code PROCESS_TIME == 200}（旧类第 56 行）。有卡那一支连
     * {@code PROCESS_TIME / speedMult} 都不算，所以速度卡对批次长度的影响
     * 在装卡后完全消失——断言「有卡时无论原门槛多大都是 1」。
     */
    @Test
    public void collapseReplacesTheThresholdInsteadOfScalingIt() {
        assertEquals("装卡后一个批次 1 tick 走完", 1, MekCkMachineTile.gatedTicksPerCycle(true, 200));
        assertEquals("不装卡时逐字保持 200/速度倍率", 200, MekCkMachineTile.gatedTicksPerCycle(false, 200));
    }

    /** 无卡那一支的下限不能被覆写破坏：{@code ticksPerWorkCycle()} 返回 0/负数时闸门不能卡死。 */
    @Test
    public void noCardBranchStillFloorsAtOneTick() {
        assertEquals(1, MekCkMachineTile.gatedTicksPerCycle(false, 0));
        assertEquals(1, MekCkMachineTile.gatedTicksPerCycle(false, -5));
    }

    // ── 分支三「自动补满」：真行为测试 ────────────────────────────────

    /**
     * <b>把「灌一笔负缺口」的真后果钉住。</b>
     *
     * <p>本测试最初断言它会倒扣存量——<b>那条断言是错的，已按实测更正</b>。
     * {@code IEnergyContainer.getNeeded()} 的默认实现是
     * {@code max(0, maxEnergy - stored)}（实测该 default 方法字节码偏移 0~21：
     * {@code FloatingLong.ZERO.max(maxEnergy.subtract(stored))}），<b>本来就夹到 0</b>；
     * 于是 {@code insert} 在偏移 17~26 处判定 {@code needed.isZero()} 后原样返回，
     * 存量纹丝不动。
     *
     * <p>结论：{@link MekCkMachineTile#energyToRefill} 里那道比较<b>不是</b>防溢出，
     * 省的是一次空调用。把这条跑出来而不是继续推理，是为了省下一个「据字节码断言
     * 灌负数会倒扣」的假想 bug——那是本任务开工时真写错过一次的注释。</p>
     */
    @Test
    public void rawDeficitInsertIsAHarmlessNoOp() {
        // 容量 400，却装了 1000 —— 对应「拆掉能量卡后容量下调」
        // （实测 BasicEnergyContainer.setEnergy 不夹上限：字节码偏移 0~23 只是赋值 + 回调）。
        BasicEnergyContainer container = newContainer(FloatingLong.create(400));
        container.setEnergy(FloatingLong.create(1000));

        // 旧 BE 的写法是 receiveEnergy(max - stored) = receiveEnergy(400 - 1000) = -600。
        FloatingLong deficit = container.getMaxEnergy().subtract(container.getEnergy());
        container.insert(deficit, Action.EXECUTE, AutomationType.MANUAL);

        assertEquals("负缺口被 getNeeded() 夹成 0，insert 走 isZero 分支原样返回，存量不变",
                1000L, container.getEnergy().longValue());
    }

    /** 守卫的行为：已满 → 不插；超容 → 不插；未满 → 恰好补到上限。 */
    @Test
    public void refillOnlyTopsUpAndNeverDrains() {
        FloatingLong max = FloatingLong.create(1000);

        assertTrue("已满时不该补", MekCkMachineTile.energyToRefill(max, FloatingLong.create(1000)).isZero());
        assertTrue("超容时同样不补（这一格每 tick 都会走到，省掉一次 insert）",
                MekCkMachineTile.energyToRefill(max, FloatingLong.create(1500)).isZero());
        assertEquals("半满时补到上限", 500L,
                MekCkMachineTile.energyToRefill(max, FloatingLong.create(500)).longValue());
        assertEquals("空容器时补满整格", 1000L,
                MekCkMachineTile.energyToRefill(max, FloatingLong.ZERO).longValue());
    }

    /** 补满之后能量确实到上限（走一遍真容器，确认守卫不会拦掉正常补能）。 */
    @Test
    public void refillReachesTheCapOnARealContainer() {
        BasicEnergyContainer container = newContainer(FloatingLong.create(1000));
        container.insert(FloatingLong.create(400), Action.EXECUTE, AutomationType.MANUAL);

        FloatingLong need = MekCkMachineTile.energyToRefill(
                container.getMaxEnergy(), container.getEnergy());
        container.insert(need, Action.EXECUTE, AutomationType.MANUAL);

        assertEquals(1000L, container.getEnergy().longValue());
    }

    /**
     * <b>本机能量容器的 AutomationType 闸门：能插不能抽（方向与直觉相反）。</b>
     *
     * <p>{@code MachineEnergyContainer.input(tile, listener)} 传的是
     * {@code notExternal} / {@code alwaysTrue}，而 {@code BasicEnergyContainer} 构造器
     * 字节码偏移 19~26 是「第二个参数 → canExtract、第三个 → canInsert」。
     * 所以本机容器实际是 <b>canExtract = notExternal、canInsert = alwaysTrue</b>：
     * 任何类型都灌得进去，而 EXTERNAL 抽不出来。
     *
     * <p>本测试用 4 参 {@code create(max, canExtract, canInsert, listener)} 造出同形状的
     * 夹具（该重载的参数名由它自己那两句 requireNonNull 文案坐实：
     * 「Extraction validity check」/「Insertion validity check」），把这条跑成断言。
     *
     * <p><b>为什么这条必须留在这里</b>：它钉的是<b>容器语义</b>，而容器语义决定了
     * {@link MekCkMachineTile#workCycle} 的扣电只能走
     * {@code AutomationType.MANUAL}（2026-09-29 起；此前那处写死 EXTERNAL，
     * 于是机器实际从不扣能量——阶段 2 Task 4 交付的既有问题，报告见
     * {@code .superpowers/sdd/2026-09-29-mekck-phase1-upgrade-system/
     * energy-extract-and-efficiency-report.md}）。
     * 「扣得动」那一半断言在 {@code TestEnergyCostAutomation} 里。
     * 顺带说明「自动补满」这条分支不受影响：它走 insert，畅通。</p>
     */
    @Test
    public void machineInputContainerAcceptsInsertsButRejectsExternalExtract() {
        BasicEnergyContainer container = BasicEnergyContainer.create(FloatingLong.create(1000),
                BasicEnergyContainer.notExternal, BasicEnergyContainer.alwaysTrue, NOOP_LISTENER);

        // insert 一侧：canInsert = alwaysTrue，EXTERNAL 也放行 ⇒ 补能用哪种类型都行
        container.insert(FloatingLong.create(400), Action.EXECUTE, AutomationType.EXTERNAL);
        assertEquals("canInsert 是 alwaysTrue，EXTERNAL 也灌得进去", 400L, container.getEnergy().longValue());

        // extract 一侧：canExtract = notExternal，EXTERNAL 被整条拒掉
        // （注意与 insert 不对称：extract 被拒时返回 ZERO，insert 被拒时原样返回请求量，
        //  见该类 insert 偏移 20~21 的 aload_1/areturn 与 extract 偏移 27~30 的 getstatic ZERO）
        FloatingLong taken = container.extract(FloatingLong.create(100), Action.EXECUTE, AutomationType.EXTERNAL);
        assertTrue("EXTERNAL 抽取被拒：返回 ZERO", taken.isZero());
        assertEquals("被拒时存量不变", 400L, container.getEnergy().longValue());

        // MANUAL 抽得动 —— 这就是那处 extract 该用的类型
        container.extract(FloatingLong.create(100), Action.EXECUTE, AutomationType.MANUAL);
        assertEquals("MANUAL 抽取生效", 300L, container.getEnergy().longValue());
    }

    // ── 源码不变量：默认关、切菜开 ───────────────────────────────────

    /**
     * 基类三个钩子默认必须全是 {@code false}。
     *
     * <p>默认「都要」会让 5 个尚未接线的家族凭空白送三个机械收益；
     * 默认「都不要」才是「无随机化卡时的原行为」。</p>
     */
    @Test
    public void baseHooksDefaultToNo() throws IOException {
        String source = read(TILE);
        for (String hook : new String[]{
                "randomizeGrantsFreeEnergy", "randomizeCollapsesWorkCycle", "randomizeRefillsEnergy"}) {
            int signature = source.indexOf("protected boolean " + hook + "() {");
            assertTrue("基类必须有钩子 " + hook, signature >= 0);
            int body = source.indexOf("return false;", signature);
            assertTrue("钩子 " + hook + " 的默认实现必须是 return false;",
                    body >= 0 && body - signature < 120);
        }
    }

    /** 切菜 tile 必须把三个钩子<b>都</b>覆写成「装了卡才生效」，漏一个就是漏一个分支。 */
    @Test
    public void cuttingTileTurnsAllThreeOnViaTheCard() throws IOException {
        String source = read(CUTTING);
        for (String hook : new String[]{
                "randomizeGrantsFreeEnergy", "randomizeCollapsesWorkCycle", "randomizeRefillsEnergy"}) {
            int at = source.indexOf("protected boolean " + hook + "() {");
            assertTrue("切菜 tile 必须覆写 " + hook, at >= 0);
            assertTrue(hook + " 必须由随机化卡决定，不能无条件返回 true",
                    source.indexOf("return hasRandomizeUpgrade();", at) >= 0
                            && source.indexOf("return hasRandomizeUpgrade();", at) - at < 120);
        }
        assertTrue("切菜 tile 必须读 MekCkUpgradeRefs.randomize()（而不是读字段或 valueOf）",
                read(TILE).contains("MekCkUpgradeRefs.randomize()"));
    }

    /**
     * 三条分支必须真的挂在 tick 上，而不是只定义了没人调。
     * 每条都点出它在 {@code onUpdateServer} / {@code workCycle} 里的落点。
     */
    @Test
    public void allThreeBranchesAreActuallyWiredIntoTheTick() throws IOException {
        String source = read(TILE);
        assertTrue("自动补满必须挂在闸门之前（与旧第 378~380 行同位置）",
                source.contains("if (randomizeRefillsEnergy()) {"));
        assertTrue("免耗电必须过闸门", source.contains("gatedEnergyCost(randomizeGrantsFreeEnergy(), energyPerWorkTick())"));
        assertTrue("1 tick 批次必须过 effectiveTicksPerWorkCycle",
                source.contains("gatedTicksPerCycle(randomizeCollapsesWorkCycle(), ticksPerWorkCycle())"));
        // 补能走的是 gate，不许有人绕过它直接灌缺口
        assertFalse("补能必须经过 energyToRefill 的守卫",
                source.contains("energyContainer.insert(max.subtract(stored)"));
    }

    /**
     * 切菜 tile 的 {@code energyPerWorkTick()} 必须保持「无卡时的原公式」，
     * 免耗电只在基类闸门上做。
     */
    @Test
    public void cuttingEnergyFormulaStaysCardFree() throws IOException {
        String source = read(CUTTING);
        int start = source.indexOf("protected int energyPerWorkTick() {");
        assertTrue("必须仍有 energyPerWorkTick 覆写", start >= 0);
        int end = source.indexOf("\n    }", start);
        String body = source.substring(start, end);
        assertFalse("扣减公式里不得夹带随机化卡判断", body.contains("andomize"));
        assertTrue("扣减公式必须仍是 baseEnergyPerTick × active × stackMult",
                body.contains("CountMath.mulClamp"));
    }

    // ── 辅助 ────────────────────────────────────────────────────────────

    private static final IContentsListener NOOP_LISTENER = () -> {
    };

    private static BasicEnergyContainer newContainer(FloatingLong max) {
        return BasicEnergyContainer.create(max, NOOP_LISTENER);
    }

    private static String read(String relativePath) throws IOException {
        return Files.readString(Path.of(relativePath), StandardCharsets.UTF_8);
    }
}
