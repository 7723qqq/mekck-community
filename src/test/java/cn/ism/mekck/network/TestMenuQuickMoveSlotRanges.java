package cn.ism.mekck.network;

import cn.ism.mekck.util.MekCkTransfer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * 菜单 {@code quickMoveStack} 的「单槽目标区间」不变量 —— 钉住 shift-click 复制物品那一类缺陷。
 *
 * <h3>被钉住的故障形态</h3>
 * {@code quickMoveStack} 的 else 分支只在 {@code index >= machineSlotCount} 时进入，也就是
 * <b>被点的是玩家背包槽</b>。它的单槽目标区间 {@code [X, X+1)} 本该指向某个机器槽。
 * 一旦 {@code X} 落在玩家背包区间（或不小于 machineSlotCount），被点槽就可能正好等于 X，
 * 而原版 {@code AbstractContainerMenu#moveItemStackTo} 与
 * {@link MekCkTransfer#moveItemStackTo} 的合并阶段都<b>不检查 {@code mayPlace}、也不检查「同槽」</b>：
 * <pre>
 *   isSameItemSameTags(source, slot.getItem())  // 同槽时必然为真（同一个对象）
 *   total = existing.getCount() + source.getCount()   // = 2c
 *   if (total <= capacity) { source.setCount(0); existing.setCount(total); }  // 同一对象 ⇒ 最终 2c
 * </pre>
 * 于是「1 个红石 shift-click 一次」变成 2 个，可无限放大。历史缺陷（本轮修复）：
 * {@code GrillMenu} 用 {@code MACHINE_SLOT_COUNT(=4)} 当能源槽下标（能源槽自己就是菜单下标 4）；
 * {@code UniversalCuttingMachineMenu} /
 * {@code SkeweringMachineMenu} / {@code SmartCookingPotMenu} 拿 <b>handler 常量</b>
 * {@code SLOT_POWER}（=5 / 89 / 92）当<b>菜单下标</b>，于是区间落在玩家背包第 0 格。
 *
 * <p><b>{@code GrillMenu} 已不在 {@link #MENUS} 表里</b>：烧烤架迁到 Mek 原生
 * {@code TileEntityConfigurableMachine} 体系后，{@code GrillMenu} 不再手写
 * {@code quickMoveStack}（由 {@code MekanismContainer} 接管），本类的四条断言对它已无对象。
 * 它上面的历史缺陷记录保留，因为那是 A3 这条规则的来源。</p>
 *
 * <h3>为什么一部分断言只能读源码</h3>
 * 真菜单造不出来：{@code AbstractContainerMenu} 的子类构造链要 {@code MenuType}、
 * {@code BlockEntityType} 与 Mek 的 {@code Attribute} 注册表，裸 JVM 里到不了。
 * 所以「槽位布局 → 目标下标」这段用<b>源码不变量</b>钉（与
 * {@code TestMekCkPersistedSlotCoverage} 同一手法），只有「self-range 为什么危险」
 * 这一段用真 {@code Slot} + 真 {@code ItemStack} 在裸 JVM 里跑成断言。
 *
 * <h3>本测试检查的四条</h3>
 * <ol>
 *   <li><b>A1 数值型单槽目标必须落在机器槽区</b>：{@code X < machineSlots}。</li>
 *   <li><b>A2 非数值型单槽目标必须是「菜单下标」</b>：要么是构造期
 *       {@code <name> = slots.size();} 捕获的字段（值必然 &lt; 玩家背包起点），
 *       要么在表里登记为派生局部量（每条都要给理由）。写 handler 常量当菜单下标会被这条挡住。</li>
 *   <li><b>A3 分区边界常量必须等于真实机器槽数</b>：{@code MACHINE_SLOT_COUNT = N}
 *       写小 1 就等于把最后一个机器槽划给玩家分支 —— 这正是 GrillMenu 的原始缺陷。</li>
 *   <li><b>A4 机器槽必须全部排在玩家背包槽之前</b>：整张下标表的前提。</li>
 * </ol>
 *
 * <p>另加一条「新增菜单必须登记」的守卫：{@code menu/} 下任何出现
 * {@code ItemStack quickMoveStack(Player} 的文件都必须在 {@link #MENUS} 表里，
 * 否则新增菜单会绕过全部断言。</p>
 *
 * <h3>Mek 容器菜单：只登记、跳过 A1–A4</h3>
 * 迁到 {@code MekanismTileContainer} 的菜单（如 {@code WineCellarMenu}）槽全部由
 * {@code MekanismContainer.addSlots()} 建，源码里<b>没有任何槽构造器</b> ——
 * A1–A4 的输入（单槽目标、分区边界常量、机器槽/玩家槽的源码位置）都不存在，
 * 强行套用只会得到假红或假绿。这类菜单在表里以 {@code mekContainer=true} 登记：
 * <b>A0 照常生效</b>（出现 {@code quickMoveStack} 就必须登记），A1–A4 跳过。
 * 它们的路由正确性由各自的专项护栏守（{@code WineCellarMenu} 见
 * {@code TestWineCellarRegressions}）。</p>
 */
public class TestMenuQuickMoveSlotRanges {

    @BeforeClass
    public static void boot() {
        net.minecraft.SharedConstants.tryDetectVersion();
        try {
            net.minecraft.server.Bootstrap.bootStrap();
        } catch (Throwable ignored) {
            // Forge 网络钩子在未变换的 classpath 上必然失败；注册表此时已就绪（同 TestMekCkPersistedSlotCoverage）。
        }
    }

    // ================== 源码工具 ==================

    private static final String MENU_DIR = "src/main/java/cn/ism/mekck/menu/";
    private static final String SRC_ROOT = "src/main/java";

    /** quickMoveStack 的方法签名（出现过即说明该菜单有手写下标逻辑）。 */
    private static final String QMS_SIGNATURE = "ItemStack quickMoveStack(Player";

    /**
     * 单槽目标区间：{@code moveItemStackTo(stack, X, X + 1, false)} 与
     * {@code MekCkTransfer.moveItemStackTo(stack, slots, X, X + 1, false)} 两种形态。
     * 第 3 参必须是第 2 参的 {@code + 1}，所以多槽区间（如 {@code FLUID_SLOT_1, FLUID_SLOT_2 + 1}）不会被误匹配。
     */
    private static final Pattern SINGLE_TARGET = Pattern.compile(
            "\\bmoveItemStackTo\\(\\s*stack\\s*,\\s*(?:slots\\s*,\\s*)?([A-Za-z0-9_.]+)\\s*,\\s*\\1\\s*\\+\\s*1\\s*,\\s*false\\s*\\)");

    /** 玩家背包槽：{@code new Slot(inventory, ..)} / {@code new Slot(playerInventory, ..)}。 */
    private static final Pattern PLAYER_SLOT = Pattern.compile("new Slot\\(\\s*(?:inventory|playerInventory)\\s*,");

    /**
     * 去掉注释再交给结构性正则。
     *
     * <p><b>为什么必须有这一步</b>：本类用源码文本做结构断言，而<b>源码里的注释同样是文本</b>。
     * 修 {@code CentralKitchenMenu} 那个 off-by-one 时，我在新加的 javadoc 里逐字引用了
     * {@code addSlot(new Slot(new SampleContainer(machine), SANDWICH_SAMPLE_SLOT, ...))}，
     * 于是 {@link #MACHINE_SLOT_CTOR} 匹配到了<b>注释里</b>那一行，给出
     * 「机器槽起点 14283 > 玩家槽起点 5845」这种看似有理、实则莫名其妙的失败。</p>
     *
     * <p>危害不只是这一次测试红：<b>任何</b>后来在注释里讲解槽位布局的人都会踩到，
     * 而且失败信息会把人引向完全错误的方向。断言应该看代码，不看散文。</p>
     *
     * <p>实现上按 Java 词法粗粒度处理：先剥块注释（{@code /*…*}，支持嵌套以外的
     * 常规写法），再剥行注释（{@code //}）。字符串字面量里的 {@code //} 会被误剥，
     * 但本类关心的模式（{@code new Slot(} / {@code static final int}）不会出现在
     * 字符串里，所以这个精度对本用例足够——而它比"要求注释里别贴代码"这种约定可靠得多。</p>
     */
    private static String stripComments(String src) {
        // 已收进共享工具：同一个坑本轮踩了三次（详见 TestSourceText 的类注释）。
        return cn.ism.mekck.TestSourceText.stripComments(src);
    }

    /** 机器类槽的构造器名（用于「机器槽必须排在玩家槽之前」这条结构断言）。 */
    private static final Pattern MACHINE_SLOT_CTOR = Pattern.compile(
            "new (?:InputSlot|OutputSlot|UpgradeSlot|PowerSlot|StorageSlot|StoreSlot|TankSlot|GrowthSlot"
                    + "|NutrientSlot|ColdBrewSlot|FerreroSlot|FluidSlot|MachineSlot|SlotItemHandler"
                    + "|ModuleContainer|OutputContainer|SampleContainer)\\b");

    private static String read(String relativePath) throws IOException {
        return Files.readString(Path.of(relativePath), StandardCharsets.UTF_8);
    }

    /** 类简单名 → 源文件路径（用于把 {@code SomeBlockEntity.SLOT_X} 解析成整数）。 */
    private static Map<String, String> classIndex;

    private static Map<String, String> classIndex() throws IOException {
        if (classIndex != null) {
            return classIndex;
        }
        Map<String, String> index = new HashMap<>();
        try (Stream<Path> walk = Files.walk(Path.of(SRC_ROOT))) {
            walk.filter(p -> p.toString().endsWith(".java")).forEach(p -> {
                String name = p.getFileName().toString();
                index.put(name.substring(0, name.length() - ".java".length()),
                        p.toString().replace('\\', '/'));
            });
        }
        classIndex = index;
        return index;
    }

    /**
     * 把 int 表达式解析成数值：字面量 / 本文件 {@code static final int N = <expr>;} /
     * {@code SomeClass.CONST}（去那个类的源码里读）/ 简单的 {@code A + B}。解析不出返回 null。
     */
    private static Integer resolve(String expr, String fileSrc) throws IOException {
        if (expr == null) {
            return null;
        }
        String e = expr.trim();
        if (e.isEmpty()) {
            return null;
        }
        if (e.matches("\\d+")) {
            return Integer.valueOf(e);
        }
        Matcher dotted = Pattern.compile("([A-Za-z0-9_]+)\\.([A-Z0-9_]+)").matcher(e);
        if (dotted.matches()) {
            String path = classIndex().get(dotted.group(1));
            if (path == null) {
                return null;
            }
            Matcher c = Pattern.compile("static final int " + Pattern.quote(dotted.group(2)) + "\\s*=\\s*(\\d+)")
                    .matcher(read(path));
            return c.find() ? Integer.valueOf(c.group(1)) : null;
        }
        Matcher local = Pattern.compile("static final int " + Pattern.quote(e) + "\\s*=\\s*([^;]+);").matcher(fileSrc);
        if (local.find()) {
            return resolve(local.group(1), fileSrc);
        }
        int plus = e.indexOf('+');
        if (plus > 0) {
            Integer left = resolve(e.substring(0, plus), fileSrc);
            Integer right = resolve(e.substring(plus + 1), fileSrc);
            if (left != null && right != null) {
                return left + right;
            }
        }
        // 乘法。**这条是被真实缺口逼出来的**（第三轮）：
        // CentralKitchenMenu 的 VISIBLE_STORAGE = STORAGE_ROWS * STORAGE_COLS，
        // 而本方法原先只认 `+` —— 于是 `MACHINE_SLOT_COUNT` 一旦被提取成常量、
        // A3 第一次真正开始校验这个菜单时，就报「分区边界表达式解析不出来」。
        //
        // 更值得记的是**为什么此前没报**：那时 MACHINE_SLOT_COUNT 还不存在，
        // 边界是 quickMoveStack 里的局部量 `int machineSlots = ...`，
        // `decl.find()` 落空 → `continue` → 整个菜单被**静默跳过**。
        // 也就是说 A3 对 15 个菜单里至少这一个从未真正生效过，
        // 而「解析不出来」与「不适用」在原实现里是同一个出口。
        int star = e.indexOf('*');
        if (star > 0) {
            Integer left = resolve(e.substring(0, star), fileSrc);
            Integer right = resolve(e.substring(star + 1), fileSrc);
            if (left != null && right != null) {
                return left * right;
            }
        }
        return null;
    }

    private static List<String> singleSlotTargets(String src) {
        List<String> out = new ArrayList<>();
        Matcher m = SINGLE_TARGET.matcher(src);
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }

    private static boolean capturesSlotsSize(String src, String name) {
        return Pattern.compile("\\b" + Pattern.quote(name) + "\\s*=\\s*slots\\.size\\(\\)\\s*;").matcher(src).find();
    }

    private static boolean declaresLocalInt(String src, String name) {
        return Pattern.compile("int\\s+" + Pattern.quote(name) + "\\s*=").matcher(src).find();
    }

    /** 取 {@code quickMoveStack} 的方法体（签名 → 其后第一个「四空格 + 右花括号」）。 */
    private static String quickMoveBody(String src) {
        int start = src.indexOf(QMS_SIGNATURE);
        assertTrue("源码里找不到 quickMoveStack", start > 0);
        int end = src.indexOf("\n    }", start);
        assertTrue("quickMoveStack 没有闭合", end > start);
        return src.substring(start, end);
    }

    /**
     * 本轮 C1 修复涉及的 6 个菜单：能源槽目标必须是构造期捕获的菜单下标字段，
     * {@code quickMoveStack} 体内不得再出现 handler 常量 {@code SLOT_POWER}。
     *
     * <p>这是对 A1 的补充：A1 只能查数值（{@code SLOT_POWER} 恰好等于边界时才会失败），
     * 而这条直接钉住「用 handler 常量当菜单下标」这个写法本身。
     * 其余菜单的 {@code MainInput} 类常量目标（如 {@code INPUT_SLOT = 0}）由 A1 的数值检查覆盖——
     * 输入槽在所有菜单里都排在第一位，字符串形态的禁令在这里会误伤正确代码。</p>
     */
    // ElectricGrindingMachineMenu 已于阶段 3 样板迁移中移出：它改用 Mek 的
    // MekanismTileContainer，不再手写 quickMoveStack，能源槽下标由 Mek 自己管，
    // 「handler 常量当菜单下标」这一缺陷形态在本文件里已不存在。
    private static final Set<String> POWER_SLOT_TARGET_MUST_BE_CAPTURED_FIELD = set(
            "SimpleMachineMenu.java",
            "SkeweringMachineMenu.java",
            "SmartCookingPotMenu.java");

    @Test
    public void powerSlotTargetIsAMenuIndexNotAHandlerConstant() throws IOException {
        for (String file : POWER_SLOT_TARGET_MUST_BE_CAPTURED_FIELD) {
            String src = stripComments(read(MENU_DIR + file));
            // 只看**目标表达式**，不看注释：修复说明里会提到 SLOT_POWER 这个名字。
            List<String> targets = singleSlotTargets(quickMoveBody(src));
            assertTrue(file + " 的 quickMoveStack 必须用构造期捕获的菜单下标字段 powerSlotIndex 作能源槽目标，"
                            + "实际目标是 " + targets,
                    targets.contains("powerSlotIndex"));
            for (String target : targets) {
                assertFalse(file + " 的单槽目标 " + target + " 仍在用 handler 常量 SLOT_POWER："
                                + "handler 下标与菜单下标在「菜单跳槽/重排」后不再相等，"
                                + "会指向另一个槽甚至玩家背包（被点槽等于它时 → 复制）",
                        target.endsWith("SLOT_POWER") || target.equals("SLOT_POWER"));
            }
            assertTrue(file + " 的 powerSlotIndex 必须由 slots.size() 捕获",
                    capturesSlotsSize(src, "powerSlotIndex"));
        }
    }

    // ================== 菜单表 ==================

    /**
     * 一个含手写 {@code quickMoveStack} 的菜单。
     *
     * @param machineSlots  {@code quickMoveStack} 里 {@code index < machineSlots} 用的那个<b>边界值</b>
     *                      （else 分支只在 {@code index >= 边界} 时进入，所以不等式必须是
     *                      {@code 目标 < 边界}，不是「目标 < 机器槽物理数量」——两者在
     *                      CentralKitchen 上差 1，见该行表注）；
     *                      {@code -1} = 布局随档位/配置变化，此时只允许捕获字段型目标
     * @param captured      允许出现的「非数值」目标：必须是本文件里
     *                      {@code <name> = slots.size();} 捕获的菜单下标字段
     * @param derivedLocals 允许出现的派生局部量，值是<b>必须在源码里原样出现的推导式</b>
     *                      （name → anchor）。白名单因此不是橡皮图章：改写法就得同步改锚点
     * @param note          机器槽数的推导理由（人工核对用，不参与断言）
     * @param mekContainer  <b>Mek 容器菜单</b>：槽全部由 {@code MekanismContainer.addSlots()} 建，
     *                      源码里没有任何槽构造器 ⇒ A1–A4 的输入不存在，只登记、跳过槽序断言。
     *                      A0（必须登记）对它照常生效。
     */
    private record Menu(String file, int machineSlots, Set<String> captured,
                        Map<String, String> derivedLocals, String note, boolean mekContainer) {

        Menu(String file, int machineSlots, Set<String> captured,
             Map<String, String> derivedLocals, String note) {
            this(file, machineSlots, captured, derivedLocals, note, false);
        }
    }

    private static Set<String> set(String... names) {
        return new LinkedHashSet<>(List.of(names));
    }

    /** 派生局部量白名单：name → 必须在源码里原样出现的推导式锚点。 */
    private static Map<String, String> map(String... nameAnchorPairs) {
        Map<String, String> out = new LinkedHashMap<>();
        for (int i = 0; i + 1 < nameAnchorPairs.length; i += 2) {
            out.put(nameAnchorPairs[i], nameAnchorPairs[i + 1]);
        }
        return out;
    }

    private static final List<Menu> MENUS = List.of(
            new Menu("BioreactorMenu.java", 18, set(), map(),
                    "4x4 输入(16) + 能源 + 储罐"),
            new Menu("CentralKitchenMenu.java", 84, set(), map(),
                    "真实机器槽 = VISIBLE_MODULES(20)+VISIBLE_STORAGE(54)+VISIBLE_OUTPUT(9)"
                            + " + 三明治样品槽(1) = 84。"
                            + "**本条在第三轮从 83 改成 84**：原代码边界是 83，漏算了构造器在输出区"
                            + "之后加的第 84 个槽（三明治样品槽，SANDWICH_SAMPLE_SLOT）。"
                            + "后果不是复制（该槽落在玩家分支、目标区间不含 83），而是"
                            + "「shift-点击样品 → canInstallModule 为假 → 并进 300 格存储区 → "
                            + "slot.set(EMPTY) 清空样品槽 → refreshDisplay 让它从视野消失」，"
                            + "玩家再也拿不回来。"
                            + "原注释说「取 83 是保守方向」—— 那只对 self-range 成立，"
                            + "而本菜单的缺陷形态是**功能槽被清空**，不是自指区间，"
                            + "所以正确修法是改代码而不是改表。"),
            new Menu("ChocolateCannonMenu.java", 14, set(),
                    map("target", "int target = ChocolateCannonBlockEntity.FERRERO_SLOT_BASE + tier.ordinal();"),
                    "输入/副输入/输出 + 速度/能量/创造 + 费列罗5 + 流体2 + 能源 = 3+3+5+2+1；"
                            + "target = FERRERO_SLOT_BASE(6) + 档位序号，上界由 ferreroTargetStaysInsideBoundary 数值验证"),
            new Menu("IceFactoryMenu.java", -1, set("powerSlotIndex", "creativeSlotIndex"),
                    map("speedIdx", "int speedIdx = processes * 2;",
                            "energyIdx", "int energyIdx = speedIdx + 1;",
                            "stackIdx", "int stackIdx = speedIdx + 2;",
                            "target", "int target = switch (cb) {"),
                    "槽数随 processes 变化；boundary=powerSlotIndex+1，speedIdx=processes*2 等 "
                            + "全部由现场量/档位推导（四个推导式都必须在源码里原样出现）"),
            new Menu("IceMakerMenu.java", 11, set(),
                    map("target", "int target = switch (cb) {"),
                    "输入/输出/速度/能量/创造 + 冷萃5 + 能源 = 11；target 取自冷萃档位 switch（CB_SLOT_1..5 = 5..9）"),
            new Menu("NutRoasterMenu.java", 6, set(), map(),
                    "输入/输出/速度/能量/创造/能源"),
            new Menu("PlantingCuttingStationMenu.java", 9, set(), map(),
                    "输入/营养液/输出 + 速度/能量/创造/气体 + 能源 + 生长 = 9"),
            new Menu("SandwichAssemblerMenu.java", 68, set(), map(),
                    "32 有序 + 27 材料 + 样品 + 输出 + 返还3 + 速度/能量/创造/能源 = TOTAL_SLOTS"),
            new Menu("SimpleMachineMenu.java", 10,
                    set("speedSlotIndex", "energySlotIndex", "creativeSlotIndex", "powerSlotIndex", "juiceSlotIndex"),
                    map(),
                    "最小变体（非扩展、非陈酿）= 5 输入 + 输出 + 3 升级 + 能源 = 10；"
                            + "扩展槽机器为 14、陈酿机为 13，目标全是捕获字段故与数值无关。"
                            + "取最小值是保守方向：任何字面量目标都必须 < 10"),
            new Menu("SkeweringMachineMenu.java", 89, set("powerSlotIndex"), map(),
                    "3 输入 + 产物 + 返还 + 速度 + 能量 + 81 存储 + 能源 = 8+STORAGE_SLOT_COUNT"),
            new Menu("SmartCookingPotMenu.java", 92, set("powerSlotIndex"), map(),
                    "6 输入 + 产物 + 返还 + 速度 + 能量 + 81 存储 + 能源 = 11+STORAGE_SLOT_COUNT"),
            new Menu("WineCellarMenu.java", -1, set(), map(),
                    "Mek 容器菜单（只登记、跳过 A1–A4）：槽全部由 MekanismContainer.addSlots() 建"
                            + "（升级 2 → 存储 9 → 电源 1 → 背包），源码里没有任何槽构造器，"
                            + "A1–A4 的输入不存在。quickMoveStack 只拦「玩家背包 → 机器」的能量物品"
                            + "（PowerSlotUtil.isValidEnergyItem：红石或带能量 capability 的物品），"
                            + "目标是 POWER_SLOT_INDEX = 2 + SLOT_POWER；其余交回 super（Mek 默认路由）。"
                            + "该路由的护栏在 TestWineCellarRegressions。",
                    true));

    // ── 已不在表里的菜单（从「自研 AbstractContainerMenu」迁到 Mek 容器）──────────
    //
    // ElectricGrindingMachineMenu：阶段 3 样板迁移后**不再手写 quickMoveStack**
    // （整机迁到 Mek 的 MekanismTileContainer，槽由 MekanismContainer.addSlots() 建，
    // shift-click 路由由 Mek 默认实现承担），A0–A4 的输入全部不存在，故整条移出本表。
    // 本类的 everyMenuWithQuickMoveStackIsRegistered 会用「实际有 quickMoveStack 的文件集」
    // 与表做全等比较，漏删或多留都会当场变红 —— 本条就是被它逼出来的。
    //
    // GrillMenu：迁到 Mek 原生 TileEntityConfigurableMachine 体系后不再手写 quickMoveStack
    // （由 MekanismContainer 接管），本类的四条断言对它已无对象。缺陷记录保留在类注释里，
    // 因为那是 A3 规则的来源。
    //
    // WineCellarMenu：2026-10-03 迁到 MekanismTileContainer 时手写的 quickMoveStack 整段删除；
    // 2026-10-04（M21）为恢复「能量物品优先进电源槽」的旧路由重新覆写，并以
    // mekContainer=true 登记进表 —— 只登记、跳过 A1–A4（Mek 容器菜单的槽由
    // MekanismContainer.addSlots() 建，源码里没有槽构造器，四条槽序断言的输入不存在）。
    // **A0 完整性检查没有被削弱**：menu/ 下任何出现 quickMoveStack 的文件仍必须登记。

    private static List<String> menuFilesWithQuickMove() throws IOException {
        List<String> files = new ArrayList<>();
        try (Stream<Path> list = Files.list(Path.of(MENU_DIR))) {
            for (Path p : list.sorted().toList()) {
                if (!p.toString().endsWith(".java")) {
                    continue;
                }
                if (read(p.toString().replace('\\', '/')).contains(QMS_SIGNATURE)) {
                    files.add(p.getFileName().toString());
                }
            }
        }
        return files;
    }

    // ================== A0：新增菜单必须登记 ==================

    @Test
    public void everyMenuWithQuickMoveStackIsRegistered() throws IOException {
        Set<String> actual = new TreeSet<>(menuFilesWithQuickMove());
        Set<String> registered = new TreeSet<>();
        for (Menu m : MENUS) {
            registered.add(m.file());
        }
        assertEquals("menu/ 下有多少个手写 quickMoveStack，MENUS 表就必须有多少条"
                + "（新增菜单若不入表，下面四条断言全都绕过它）", registered, actual);
    }

    // ================== A1 + A2：单槽目标必须落在机器槽区 ==================

    @Test
    public void singleSlotTargetsStayInsideMachineRegion() throws IOException {
        for (Menu m : MENUS) {
            if (m.mekContainer()) {
                continue; // Mek 容器菜单：槽由 MekanismContainer.addSlots() 建，源码无槽构造器，A1/A2 无对象
            }
            String src = stripComments(read(MENU_DIR + m.file()));
            List<String> targets = singleSlotTargets(src);
            for (String target : targets) {
                Integer value = resolve(target, src);
                if (value != null) {
                    if (m.machineSlots() >= 0) {
                        assertTrue(m.file() + " 的单槽目标 " + target + " = " + value
                                        + " 不小于机器槽数 " + m.machineSlots()
                                        + "：它会被当成玩家槽区间，被点槽正好等于它时 moveItemStackTo 会自我合并 → 堆叠翻倍",
                                value < m.machineSlots());
                    }
                } else if (m.captured().contains(target)) {
                    assertTrue(m.file() + " 的 " + target + " 必须由构造期 slots.size() 捕获"
                                    + "（否则它不是菜单下标，可能与玩家背包下标重合）",
                            capturesSlotsSize(src, target));
                } else if (m.derivedLocals().containsKey(target)) {
                    String anchor = m.derivedLocals().get(target);
                    assertTrue(m.file() + " 的派生量 " + target + " 必须以预期形式推导，锚点："
                                    + anchor + "（改写法就必须同步改锚点，白名单不能靠默认通过）",
                            src.contains(anchor));
                } else {
                    throw new AssertionError(m.file() + " 出现了未登记的单槽目标 " + target
                            + "：数值解析不出来，也不在 captured / derivedLocals 白名单里。"
                            + "请改用构造期 slots.size() 捕获的菜单下标，或在 MENUS 表里登记并写明理由。");
                }
            }
        }
    }

    // ================== A3：分区边界常量必须等于真实机器槽数 ==================

    @Test
    public void declaredBoundaryMatchesRealMachineSlotCount() throws IOException {
        for (Menu m : MENUS) {
            if (m.mekContainer()) {
                continue; // Mek 容器菜单：边界由 Mek 的槽装配决定，源码里没有可比的声明
            }
            if (m.machineSlots() < 0) {
                continue; // 布局随档位变化，没有静态边界常量可比
            }
            String src = stripComments(read(MENU_DIR + m.file()));
            Matcher decl = Pattern.compile("static final int MACHINE_SLOT_COUNT\\s*=\\s*([^;]+);").matcher(src);
            if (!decl.find()) {
                continue; // 用局部量算边界的菜单（SimpleMachineMenu 按机器类型分三档）
            }
            Integer boundary = resolve(decl.group(1), src);
            assertNotNull(m.file() + " 的分区边界表达式解析不出来：" + decl.group(1), boundary);
            assertEquals(m.file() + " 的 MACHINE_SLOT_COUNT(" + boundary + ") 与真实机器槽数("
                            + m.machineSlots() + ") 不一致：写小 1 就把最后一个机器槽划进了玩家分支，"
                            + "该槽被点击时会走进别分支并可能命中自身区间 → 复制",
                    m.machineSlots(), boundary.intValue());
        }
    }

    // ================== A4：机器槽必须排在玩家背包槽之前 ==================

    @Test
    public void machineSlotsAreAllocatedBeforePlayerInventory() throws IOException {
        for (Menu m : MENUS) {
            if (m.mekContainer()) {
                continue; // Mek 容器菜单：槽序由 MekanismContainer.addSlots() 决定，源码里没有槽构造器
            }
            String src = stripComments(read(MENU_DIR + m.file()));
            Matcher player = PLAYER_SLOT.matcher(src);
            assertTrue(m.file() + " 找不到玩家背包槽（new Slot(inventory/playerInventory, ..)）", player.find());
            int firstPlayerSlot = player.start();
            Matcher machine = MACHINE_SLOT_CTOR.matcher(src);
            int lastMachineSlot = -1;
            while (machine.find()) {
                lastMachineSlot = machine.start();
            }
            assertTrue(m.file() + " 的机器槽没有全部排在玩家背包槽之前：整张下标表的前提被破坏"
                            + "（机器槽起点 " + lastMachineSlot + "，玩家槽起点 " + firstPlayerSlot + "）",
                    lastMachineSlot >= 0 && lastMachineSlot < firstPlayerSlot);
        }
    }

    // ================== 行为：self-range 为什么危险 ==================

    /** 极简单格容器：只服务本轮断言（真 ItemStackHandler 需要注册表，这里用不上）。 */
    private static final class OneSlotContainer implements Container {
        private final ItemStack[] items;

        OneSlotContainer(int size) {
            items = new ItemStack[size];
            java.util.Arrays.fill(items, ItemStack.EMPTY);
        }

        @Override
        public int getContainerSize() {
            return items.length;
        }

        @Override
        public boolean isEmpty() {
            for (ItemStack s : items) {
                if (!s.isEmpty()) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public ItemStack getItem(int slot) {
            return items[slot];
        }

        @Override
        public ItemStack removeItem(int slot, int amount) {
            ItemStack s = items[slot];
            if (s.isEmpty()) {
                return ItemStack.EMPTY;
            }
            ItemStack split = s.split(amount);
            if (s.isEmpty()) {
                items[slot] = ItemStack.EMPTY;
            }
            return split;
        }

        @Override
        public ItemStack removeItemNoUpdate(int slot) {
            ItemStack s = items[slot];
            items[slot] = ItemStack.EMPTY;
            return s;
        }

        @Override
        public void setItem(int slot, ItemStack stack) {
            items[slot] = stack;
        }

        @Override
        public void setChanged() {
        }

        @Override
        public boolean stillValid(Player player) {
            return true;
        }

        @Override
        public void clearContent() {
            java.util.Arrays.fill(items, ItemStack.EMPTY);
        }

        @Override
        public int getMaxStackSize() {
            return 64;
        }
    }

    /**
     * self-range（区间包含源槽自身）不是一次安全转移。
     *
     * <p>源码侧的证据是 {@link MekCkTransfer#moveItemStackTo} 的合并分支：它用
     * {@code existing.getItem() == source.getItem()} + {@code isSameItemSameTags} 判同，
     * 同槽时 {@code existing} 与 {@code source} <b>是同一个对象</b>，于是
     * {@code total = 2c} 之后两次 {@code setCount} 落在同一对象上 ⇒ 最终 {@code 2c}。</p>
     *
     * <p><b>若将来给 MekCkTransfer 加上「同槽守卫」，这条断言会失败——那是好事</b>：
     * 那时 self-range 退化成「shift-click 静默无效」，仍然不是正确写法，请把本断言放宽为
     * {@code count <= 1} 并<b>保留</b> {@link #singleSlotTargetsStayInsideMachineRegion()} 的规则。</p>
     */
    @Test
    public void selfRangeMergeDuplicatesTheStack() {
        OneSlotContainer container = new OneSlotContainer(1);
        ItemStack held = new ItemStack(Items.CARROT, 1);
        container.setItem(0, held);
        Slot slot = new Slot(container, 0, 0, 0);

        assertSame("Slot.getItem() 必须返回容器里的活引用——自我合并的前提",
                held, slot.getItem());

        boolean moved = MekCkTransfer.moveItemStackTo(slot.getItem(), List.of(slot), 0, 1, false);

        assertTrue("self-range 会被当成「合并成功」", moved);
        assertEquals("self-range 合并把 1 变成了 2（复制）：这就是 C1 的机制", 2,
                container.getItem(0).getCount());
    }

    /** 对照组：区间不含源槽时，行为是正常搬运，总量守恒。 */
    @Test
    public void disjointRangeTransfersWithoutDuplicating() {
        OneSlotContainer container = new OneSlotContainer(2);
        container.setItem(0, new ItemStack(Items.CARROT, 1));
        Slot from = new Slot(container, 0, 0, 0);
        Slot to = new Slot(container, 1, 18, 0);

        boolean moved = MekCkTransfer.moveItemStackTo(from.getItem(), List.of(from, to), 1, 2, false);

        assertTrue("区间 [1,2) 不含源槽，应当正常搬运", moved);
        assertTrue("源槽应被清空", container.getItem(0).isEmpty());
        assertEquals("目标槽得到 1 个（没有翻倍）", 1, container.getItem(1).getCount());
    }
}
