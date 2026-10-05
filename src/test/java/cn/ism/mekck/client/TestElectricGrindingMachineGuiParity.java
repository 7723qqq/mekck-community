package cn.ism.mekck.client;

import cn.ism.mekck.TestSourceText;
import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 电力研磨机的「与 Mek 原生 GUI 逐项对齐」护栏。
 *
 * <h3>守的是哪一类缺陷</h3>
 * 本机在 MekCK 里的定位就是一台 Mek 基础电力机器，所以它的 GUI 应当与
 * {@code mekanism.client.gui.machine.GuiElectricMachine}（粉碎机 / 富集仓用的那个）
 * 逐项一致。本轮实机比对时发现六处不一致，<b>没有一处会让编译或其余测试变红</b>：
 * <table border="1">
 *   <caption>六处偏差与症状</caption>
 *   <tr><th>#</th><th>偏差</th><th>玩家看到的</th></tr>
 *   <tr><td>1</td><td>菜单把玩家背包覆写到 y=101（Mek 是 84）</td>
 *       <td>「Inventory」标签悬空 29px；快捷栏被推到 y=159..177，最后一行画到 166 的面板外面</td></tr>
 *   <tr><td>2</td><td>输入槽没挂 {@code NO_MATCHING_RECIPE}、输出槽没挂 {@code NO_SPACE_IN_OUTPUT}</td>
 *       <td>槽位不报警：放了做不了的料 / 产物堵住，界面毫无提示</td></tr>
 *   <tr><td>3</td><td>能源条没挂 {@code NOT_ENOUGH_ENERGY}、进度条没挂 {@code INPUT_DOESNT_PRODUCE_OUTPUT}</td>
 *       <td>缺电、以及「有料却做不出东西」都没有视觉反馈</td></tr>
 *   <tr><td>4</td><td>能源 tab 传的是 {@code tile::getLastUsage}（Mek 传 {@code tile::getActive}）</td>
 *       <td>停机时也显示「正在消耗」，读数与机器状态不符</td></tr>
 * </table>
 *
 * <h3>为什么只能靠源码形态钉住</h3>
 * 槽位告警与进度条告警都是<b>纯运行期行为</b>：{@code tracksWarnings(...)} 少一行、
 * 或 {@code .warning(...)} 少一句，编译照样过、其余测试照样绿 —— 因为供给器是
 * 每帧在客户端被求值的，静态分析看不到它「没被挂上」。本仓在
 * {@code mekContainerScreensEnableDynamicSlots} 那条上已经栽过同一形态。
 *
 * <p>而告警位还有一个更隐蔽的失效方式：<b>光有供给器、没挂进容器同步通道</b>。
 * 那会让客户端那份字段永远停在默认的 {@code false}（见
 * {@code TestClientValueSync} 的整段说明），表现为「联机时告警永不出现」。</p>
 */
public class TestElectricGrindingMachineGuiParity {

    private static final String SCREEN =
            "src/main/java/cn/ism/mekck/client/ElectricGrindingMachineScreen.java";
    private static final String TILE =
            "src/main/java/cn/ism/mekck/machine/grinding/GrindingMachineTile.java";
    private static final String MENU =
            "src/main/java/cn/ism/mekck/menu/ElectricGrindingMachineMenu.java";

    /**
     * 读源码并<b>把空白压成单空格</b>。
     *
     * <p>不开这一步的话，判据会变成「格式断言」：换行位置一改（例如
     * {@code SyncableBoolean.create(\n this::isInputDoesntProduceOutput, ...)}）
     * 就会红，而它本意是钉住<b>内容</b>。本仓已有这类噪声的先例
     * （注册中枢拆分时一批断言跟着改路径），所以统一在此处消除。</p>
     */
    private static String flat(String path) throws IOException {
        return TestSourceText.read(path).replaceAll("\\s+", " ");
    }

    /**
     * 四个告警的<b>挂载点</b>必须齐全，且都挂在 Mek 挂的那一个控件上。
     *
     * <p>判据是「控件构造 + 紧随其后的 {@code .warning(类型, 供给器)}」同时出现。
     * 只查 {@code WarningType} 出现在文件里是不够的 —— 那样把告警从能源条挪到别处、
     * 或挂到完全不相关的控件上，判据都会照旧通过。</p>
     */
    @Test
    public void theFourMekWarningsAreWiredOnTheSameWidgetsAsMek() throws IOException {
        String screen = flat(SCREEN);
        List<String> missing = new ArrayList<>();

        // 竖直能源条：上游 GuiElectricMachine(164,15) + NOT_ENOUGH_ENERGY。
        if (!screen.contains("new GuiVerticalPowerBar(this, tile.getEnergyContainer(), 164, 15)")) {
            missing.add("能源条不是上游的 (164,15) GuiVerticalPowerBar(getEnergyContainer())");
        }
        if (!screen.contains("warning(WarningType.NOT_ENOUGH_ENERGY, tile::isNotEnoughEnergy)")) {
            missing.add("能源条没挂 WarningType.NOT_ENOUGH_ENERGY（缺电时整条不闪红）");
        }

        // 进度条：上游 GuiElectricMachine 用 ProgressType.BAR、(86,38) + INPUT_DOESNT_PRODUCE_OUTPUT。
        if (!screen.contains("ProgressType.BAR, this, 86, 38")) {
            missing.add("进度条不是上游的 ProgressType.BAR (86,38)");
        }
        if (!screen.contains(
                "warning(WarningType.INPUT_DOESNT_PRODUCE_OUTPUT, tile::isInputDoesntProduceOutput)")) {
            missing.add("进度条没挂 WarningType.INPUT_DOESNT_PRODUCE_OUTPUT"
                    + "（有料却产不出东西时不告警）");
        }

        assertEquals("电力研磨机屏与 GuiElectricMachine 的告警挂载点不一致：\n  "
                        + String.join("\n  ", missing),
                List.of(), missing);
    }

    /**
     * 能源 tab 的第三个参数必须是 {@code tile::getActive}（{@code BooleanSupplier}）。
     *
     * <p>上游那处 {@code invokedynamic} 的 MethodHandle 实为
     * {@code TileEntityMekanism.getActive()Z} —— javap 读 {@code GuiElectricMachine}
     * 的 {@code BootstrapMethods} 定的。传成 {@code getLastUsage}（那是
     * {@code FloatingLongSupplier}，是<b>另一个重载</b>）会恒显示「正在消耗」。</p>
     *
     * <p>同时钉住「{@code getLastUsage} 不再被本屏使用」：它连同 tile 上的那个字段
     * 一并删掉了，留着就是一段没人读的死值。</p>
     */
    @Test
    public void energyTabReadsActiveNotLastUsage() throws IOException {
        String screen = flat(SCREEN);
        assertTrue("能源 tab 必须传 tile::getActive（上游 GuiElectricMachine 传的就是它）",
                screen.contains("new GuiEnergyTab(this, tile.getEnergyContainer(), tile::getActive)"));
        assertTrue("本屏不该再用 tile::getLastUsage（getActive 才是上游那个重载）",
                !screen.contains("tile::getLastUsage"));
    }

    /**
     * 两个槽的告警必须挂在 {@code tracksWarnings} 上，且类型正确。
     *
     * <p>链路：{@code BasicInventorySlot.tracksWarnings} 把供给器存进 {@code warningAdder}
     * 字段 → {@code createContainerSlot()} 交给 {@code InventoryContainerSlot} →
     * {@code GuiMekanism.addSlots()} 调 {@code addWarnings(GuiSlot)} 转交到槽位控件。
     * 少了第一环，后面两环都还在，但槽位什么都不会闪。</p>
     */
    @Test
    public void bothSlotsTrackTheirMekWarnings() throws IOException {
        String tile = flat(TILE);
        List<String> missing = new ArrayList<>();
        if (!tile.contains("tracksWarnings(w -> w.warning(WarningType.NO_MATCHING_RECIPE, "
                + "this::isNoMatchingRecipe))")) {
            missing.add("输入槽没挂 WarningType.NO_MATCHING_RECIPE（放了做不了的料不闪红框）");
        }
        if (!tile.contains("tracksWarnings(w -> w.warning(WarningType.NO_SPACE_IN_OUTPUT, "
                + "this::isNoSpaceInOutput))")) {
            missing.add("输出槽没挂 WarningType.NO_SPACE_IN_OUTPUT（产物堵住不闪蓝框）");
        }
        assertEquals("槽位告警没接上：\n  " + String.join("\n  ", missing), List.of(), missing);
    }

    /**
     * 四个告警位必须<b>挂进容器同步通道</b>。
     *
     * <p>这条是上一类的反面：供给器每帧在客户端求值，而判据要查配方表、
     * 只有服务端有权威值。不挂同步 ⇒ 客户端那四个字段恒为 {@code false}，
     * 症状是「单机看着正常、联机永远不报警」—— 本仓在切菜机的进度条上
     * 栽过完全同形的一次（见 {@code TestClientValueSync}）。</p>
     */
    @Test
    public void theFourWarningFlagsRideTheContainerSyncChannel() throws IOException {
        // flat() 只把换行压成单空格，而 create( 后面可能折行（本类 javadoc 举的正是这个例子），
        // 于是再去掉全部空白：判据只认 token 序列，不认折行位置。
        String tile = flat(TILE).replace(" ", "");
        List<String> missing = new ArrayList<>();
        for (String getter : List.of("isNoMatchingRecipe", "isNoSpaceInOutput",
                "isNotEnoughEnergy", "isInputDoesntProduceOutput")) {
            if (!tile.contains("SyncableBoolean.create(this::" + getter)) {
                missing.add(getter + " 没挂 SyncableBoolean —— 客户端恒读默认 false，联机永不报警");
            }
        }
        assertEquals("这些告警位没进同步通道：\n  " + String.join("\n  ", missing),
                List.of(), missing);
    }

    /**
     * 菜单不得再覆写 {@code getInventoryYOffset()}。
     *
     * <p>上游 {@code GuiElectricMachine} 用的是 {@code MekanismContainer} 的默认
     * {@code BASE_Y_OFFSET = 84}，屏幕侧则不动 {@code inventoryLabelY}（= 原版
     * {@code imageHeight - 94} = 72），两者间距 12px 正是原版口径。</p>
     *
     * <p>本类此前覆写成 101，同时屏幕没跟着改标签 ⇒ 标签悬空、且快捷栏
     * （101 + 58 = 159，槽底 177）整行画到 166 高的面板外面。
     * 故意<b>不</b>断言「84 这个数字」—— 数字藏在 Mek 的父类里，
     * 判据要钉的是「交给父类决定」这件事。</p>
     */
    @Test
    public void menuLeavesTheInventoryOffsetToMek() throws IOException {
        String menu = flat(MENU);
        assertTrue("ElectricGrindingMachineMenu 不该覆写 getInventoryYOffset()："
                        + "上游 GuiElectricMachine 用的是 MekanismContainer 的默认值，"
                        + "覆写会让「Inventory」标签与玩家背包的间距偏离原版",
                !menu.contains("getInventoryYOffset"));
    }

    /** 判据不许空转：三个文件都真的读到了，且关键锚点还在。 */
    @Test
    public void criteriaStillMatchSomething() throws IOException {
        String screen = flat(SCREEN);
        String tile = flat(TILE);
        String menu = flat(MENU);
        assertTrue("ElectricGrindingMachineScreen 里找不到 addGuiElements，判据已失效",
                screen.contains("protected void addGuiElements()"));
        assertTrue("GrindingMachineTile 里找不到 getInitialInventory，判据已失效",
                tile.contains("protected IInventorySlotHolder getInitialInventory"));
        assertTrue("ElectricGrindingMachineMenu 的类声明没读到，判据已失效",
                menu.contains("class ElectricGrindingMachineMenu"));
    }
}
