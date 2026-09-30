package cn.ism.mekck.machine;

import cn.ism.mekck.TestSourceText;
import org.junit.Test;

import java.io.IOException;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 阶段 3 迁移的<b>用途回归</b>护栏。
 *
 * <h3>为什么需要它</h3>
 * 第三轮把 6 个家族的方块实体从自研 {@code blockentity/*FactoryBlockEntity} 换成了
 * {@code MekCkMachineTile} 体系。逐方法差分（从 git 历史取回被删的旧 BE，全仓库
 * grep 判定是「改名/上移」还是「真丢失」）查出<b>两处真实丢失</b>：
 * <ol>
 *   <li><b>烧烤 / 烹饪工厂的热容</b>。旧 BE 有真正的热容；迁移后
 *       {@code getInitialHeatCapacitors} 落回默认空容器 ⇒ 机器「声称能处理热」却
 *       没有容量，旁边的加热线圈无处可灌、本机也不向环境散热。而 README 承诺
 *       「产热机器可与通用机械的热力设备互通」。</li>
 *   <li><b>种植切配的营养液「从槽灌注」与槽位准入校验</b>。旧 BE 的
 *       {@code fillTankFromSlot()} 是玩家喂这台机器的<b>主要入口</b>，删旧 BE 时没重建。</li>
 * </ol>
 *
 * <p>这类丢失的共同点：<b>编译通过、单测通过、打包通过</b>，只是功能静默消失。
 * 所以本类把它们钉成断言。</p>
 */
public class TestFactoryMigrationParity {

    private static final String TILE = "src/main/java/cn/ism/mekck/machine/MekCkMachineTile.java";
    private static final String GRILL = "src/main/java/cn/ism/mekck/machine/grill/GrillFactoryTile.java";
    private static final String COOKING = "src/main/java/cn/ism/mekck/machine/cooking/CookingFactoryTile.java";
    private static final String PLANTING = "src/main/java/cn/ism/mekck/machine/plantingcutting/PlantingCuttingFactoryTile.java";

    /**
     * 烧烤与烹饪必须提供热容 —— 迁移前它们<b>有</b>，迁移后丢了。
     *
     * <p>走的是 Mek 原生钩子 {@code getInitialHeatCapacitors}，而不是自己实现
     * {@code getHeatCapacitors} —— 后者在 {@code TileEntityMekanism} 里是
     * <b>final</b>（实测 javap），子类覆写会编译失败。</p>
     *
     * <h3>⚠️ 本断言的能力边界（变异测试实测，务必连同结论一起读）</h3>
     * 这是<b>源码文本级</b>断言。它能防住「整体删掉热容覆写」「忘了 addCapacitor」
     * 「返回了 null」这类<b>迁移回归</b>（也就是本轮真实发生的那类丢失）。
     * 但它<b>防不住</b>「保留调用却让它不执行」这种语义变异 ——
     * 变异测试实测：把 {@code heatCapacitor = BasicHeatCapacitor.create(…)}
     * 改成 {@code if (false) heatCapacitor = …}，本测试<b>全绿</b>。
     * <p>原因很直白：文本断言看不出控制流。要防住那一类，需要能在裸 JVM 里造出
     * 真 tile 并读回 {@code getHeatCapacitorCount} 的测试基础设施 ——
     * 那要求 {@code BlockEntityType} 与 Mek 的 {@code Attribute} 注册表，
     * 本仓库当前的测试环境（纯 JUnit + 少量 {@code Bootstrap}）还做不到。
     * 这里如实标注，不假装覆盖到了。</p>
     */
    @Test
    public void grillAndCookingProvideARealHeatCapacitor() throws IOException {
        for (String family : new String[]{GRILL, COOKING}) {
            String src = TestSourceText.read(family);
            String body = TestSourceText.methodBody(src, "protected mekanism.common.capabilities.holder.heat.IHeatCapacitorHolder getInitialHeatCapacitors(");
            assertFalse(family + " 必须覆写 getInitialHeatCapacitors", body.isEmpty());
            assertTrue(family + " 的热容必须真的被创建并交给 holder",
                    body.contains("BasicHeatCapacitor.create(")
                            && body.contains("builder.addCapacitor("));
            assertTrue(family + " 必须覆写 addHeatFromEnergy（迁移前旧 BE 把耗电按发电效率转成废热）",
                    src.contains("protected void addHeatFromEnergy(int energyUsed)"));
            assertTrue(family + " 的 addHeatFromEnergy 必须真的注入热量",
                    TestSourceText.methodBody(src, "protected void addHeatFromEnergy(int energyUsed) {")
                            .contains("handleHeat("));
        }
    }

    /**
     * 另 4 个家族<b>不得</b>被白送热容。
     *
     * <p>它们迁移前就没有热容（切菜 / 研磨 / 种植切配 / 穿串）。
     * 把热容放到 {@code MekCkMachineTile} 基类会给 4 个家族凭空多出热容与散热行为
     * —— 那是<b>平衡变更</b>，不是修复。基类的默认实现必须返回 {@code null}。</p>
     */
    @Test
    public void theOtherFourFamiliesMustNotGainHeatForFree() throws IOException {
        String base = TestSourceText.read(TILE);
        String body = TestSourceText.methodBody(base,
                "protected mekanism.common.capabilities.holder.heat.IHeatCapacitorHolder getInitialHeatCapacitors(");
        assertFalse("基类必须声明该钩子", body.isEmpty());
        assertTrue("基类默认实现必须返回 null（无热容）", body.contains("return null;"));

        for (String family : new String[]{
                "src/main/java/cn/ism/mekck/machine/cutting/CuttingFactoryTile.java",
                "src/main/java/cn/ism/mekck/machine/grinding/GrindingFactoryTile.java",
                "src/main/java/cn/ism/mekck/machine/skewering/SkeweringFactoryTile.java",
                PLANTING}) {
            assertFalse(family + " 不得覆写 getInitialHeatCapacitors（该家族迁移前没有热容）",
                    TestSourceText.read(family).contains("getInitialHeatCapacitors("));
        }
    }

    /**
     * 耗能转废热的调用点必须存在，且在扣能量<b>之后</b>。
     *
     * <p>迁移前旧 BE 的次序是 {@code extractEnergy(...); addHeatFromEnergy(...); progress++;}，
     * 顺序反了会让「本 tick 还没扣的电」也发热。</p>
     */
    @Test
    public void wasteHeatIsAddedAfterTheEnergyIsDeducted() throws IOException {
        String base = TestSourceText.read(TILE);
        String cycle = TestSourceText.methodBody(base, "private void workCycle() {");
        int deduct = cycle.indexOf("deductEnergy(energyContainer, perLaneCost);");
        int heat = cycle.indexOf("addHeatFromEnergy(perLaneCost);");
        assertTrue("workCycle 必须扣能量", deduct >= 0);
        assertTrue("workCycle 必须把耗能转成废热（迁移前旧 BE 有这一步）", heat >= 0);
        assertTrue("addHeatFromEnergy 必须在 deductEnergy 之后（次序与迁移前逐字一致）", heat > deduct);
    }

    /**
     * 种植切配必须能「从营养液槽灌注进罐」。
     *
     * <p>迁移前 {@code PlantingCuttingFactoryBlockEntity.fillTankFromSlot()} 是
     * {@code onContentsChanged} 路径上的一步：玩家把装了营养液的容器放进槽里，罐自动满。
     * 删整份旧 BE 时没有重建，而那是喂这台机器的主要入口。</p>
     */
    @Test
    public void plantingCuttingCanStillBeFedFromTheNutrientSlot() throws IOException {
        String src = TestSourceText.read(PLANTING);
        assertTrue("必须有 fillTankFromSlot（从槽内容器灌注营养液罐）",
                src.contains("public boolean fillTankFromSlot()"));
        String fill = TestSourceText.methodBody(src, "public boolean fillTankFromSlot() {");
        assertFalse("找不到 fillTankFromSlot 方法体", fill.isEmpty());
        assertTrue("必须先 SIMULATE 再 EXECUTE（否则「先抽干容器、后发现罐满了」会把容器掏空）",
                fill.contains("Action.SIMULATE") && fill.contains("Action.EXECUTE"));
        assertTrue("必须跳过罐不收的化学品（不能把别的气体灌进机器）",
                fill.contains("nutrientTank.isValid("));
        assertTrue("必须有触发点：覆写基类的 onFamilyContentsChanged",
                src.contains("protected void onFamilyContentsChanged()"));
        // ⚠️ 必须查**方法体**，不能只查覆写存在 —— 变异测试实测：把方法体里的
        // `fillTankFromSlot();` 删掉（覆写仍在，只是变成空实现）时，
        // 「src.contains("protected void onFamilyContentsChanged()")」照样成立 ⇒ 假绿。
        assertTrue("onFamilyContentsChanged 的方法体里必须真的调 fillTankFromSlot"
                        + "（只看覆写存在会把「空覆写」当成已接上）",
                TestSourceText.methodBody(src, "protected void onFamilyContentsChanged() {")
                        .contains("fillTankFromSlot()"));
    }

    /**
     * 槽位内容变化必须真的能触发家族钩子。
     *
     * <p>这一条专门防一个很隐蔽的时序陷阱：{@code getInitialInventory} 由
     * {@code TileEntityMekanism} 的<b>构造器</b>调用，而字段初始化器在
     * {@code super(...)} <b>之后</b>才跑 —— 所以「把 listener 存成字段再传下去」
     * 会让所有槽拿到 {@code null} listener，回调静默失效且不报任何错。
     * 正确做法是在方法内部用局部变量包一层。</p>
     */
    @Test
    public void contentsChangedActuallyReachesTheFamilyHook() throws IOException {
        String base = TestSourceText.read(TILE);
        String inv = TestSourceText.methodBody(base,
                "protected IInventorySlotHolder getInitialInventory(IContentsListener listener) {");
        assertTrue("getInitialInventory 必须把 Mek 传进来的 listener 与家族钩子组合起来",
                inv.contains("onFamilyContentsChanged()"));
        assertFalse("不得把 listener 存成字段再传下去：该方法在 super 构造期被调用，"
                        + "而字段初始化器之后才跑 ⇒ 槽位会拿到 null listener，回调静默失效",
                base.contains("private final IContentsListener"));
        // 槽位构造必须用组合后的那个
        assertTrue("输入槽必须用组合后的 listener", inv.contains("MekCkSlot.input(slotLimit, combined"));
        assertTrue("输出槽必须用组合后的 listener", inv.contains("MekCkSlot.output(slotLimit, combined"));
        assertTrue("家族额外槽必须用组合后的 listener", inv.contains("appendExtraSlots(builder, combined)"));
    }

    /**
     * 营养液槽必须只接受带气体能力的容器。
     *
     * <p>迁移前 {@code isItemValid} 是 {@code slot == nutrientSlot → isValidGasContainer(stack)}。
     * 新体系的 {@code MekCkSlot.input} 谓词是 {@code alwaysTrueBi}（与 Mek 一致），
     * 所以不补这道校验的话，玩家放个普通物品进去界面照收不误、却永远不生效 ——
     * 比直接拒绝更让人困惑。</p>
     */
    @Test
    public void nutrientSlotRejectsNonGasContainers() throws IOException {
        String src = TestSourceText.read(PLANTING);
        assertTrue("必须有 isValidGasContainer", src.contains("public static boolean isValidGasContainer("));
        assertTrue("营养液槽必须用带准入谓词的 MekCkSlot.inputFiltered",
                src.contains("MekCkSlot.inputFiltered("));
        assertTrue("谓词必须就是 isValidGasContainer",
                src.contains("(stack, type) -> isValidGasContainer(stack)"));
    }
}
