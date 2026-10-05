package cn.ism.mekck.machine.icemaker;

import cn.ism.mekck.TestSourceText;
import cn.ism.mekck.item.ColdBrewTier;
import cn.ism.mekck.upgrade.MekCkUpgradeTracker;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * <b>冷萃「等级 ↔ 读条器」成对落盘</b>的护栏（2026-10-06 急冻制冰机迁到 Mek 原生体系时新增）。
 *
 * <h3>守的是哪条缺陷</h3>
 * 冷萃升级的状态被拆成两份：{@code installedColdBrew[i]}（等级，攻击档案读它）与
 * {@code coldBrewTrackers[i]} 的 {@code Installed}（读数，<b>卸载</b>读它）。
 * 只写等级、不写读条器会怎样：读档后 {@code getInstalled()} 回到 0，
 * 而 {@code IceMakerTile.uninstallUpgrade} 的第一道门正是它 ⇒ <b>冷萃升级卸不下来</b>。
 * 攻击照常工作，所以只有卸载坏掉 —— 静默、无日志。
 *
 * <h3>为什么既有护栏不够，需要这一条</h3>
 * <ul>
 *   <li>{@code TestNbtWriteReadSymmetry} 检的是「写过的键有没有读回」，
 *       而这里的形态是<b>少写了一个键</b>，不在它的判据面内；</li>
 *   <li>{@code TestIceCombatGuards#iceMakerColdBrewTrackerIsPersistedWithItsTier}
 *       只断言「读条器那个键出现过」——<b>成对</b>这件事（等级与读条器在同一个循环体里、
 *       同一个下标下、同一份上界上）它没有管：把 {@code installedColdBrew} 那半边的写出
 *       整段删掉，它照样全绿。</li>
 * </ul>
 *
 * <h3>判据分两层</h3>
 * <ol>
 *   <li><b>源码形态（成对）</b>：写出 / 读回都在<b>同一个循环体</b>内同时出现两个键，
 *       且循环上界同源（{@code COLD_BREW_SLOT_INDEXES.length}）。
 *       判据按<b>循环体切片</b>判定，不是全文件子串匹配 —— 全文件匹配会把
 *       「键在别处出现过」当成「成对」，那正是本仓栽过四次的老坑。</li>
 *   <li><b>行为（读条器状态机）</b>：{@link MekCkUpgradeTracker} 的
 *       {@code save → load} 往返、{@code installDirect(1)} 的补回语义、
 *       {@code uninstall(1)} 的卸下语义 —— 这些都是纯逻辑，裸 JVM 里能真跑
 *       （tile 本身造不出来：要 {@code BlockType} / 注册表，见
 *       {@code TestNbtPersistenceInvariants} 的类注释）。</li>
 * </ol>
 *
 * <h3>变异测试（实测记录）</h3>
 * <pre>
 *   ① 删掉 saveAdditional 里的 tag.putString("ColdBrew" + i, …)      → 红（写侧不成对）
 *   ② 删掉 saveAdditional 里的 tag.put("ColdBrewUpgradeTracker" + i,…) → 红（写侧不成对）
 *   ③ 把读条器的写出挪到循环外                                          → 红（不在同一循环体）
 *   ④ 删掉 readOwnState 里的 installDirect(1) 分支                      → 红（旧档补回丢了）
 *   ⑤ 把三个数组之一的长度改成别的常量                                   → 红（索引不同源）
 *   ⑥ 删掉 load → readOwnState 的调用                                    → 红（链路断了）
 *   全部复位后 → 绿。
 * </pre>
 */
public class TestIceMakerColdBrewPersistence {

    private static final String TILE =
            "src/main/java/cn/ism/mekck/machine/icemaker/IceMakerTile.java";

    /** 已安装等级的 NBT 键前缀（与读条器的键前缀同源同下标）。 */
    private static final String TIER_KEY = "\"ColdBrew\" + i";
    /** 读条器的 NBT 键前缀。 */
    private static final String TRACKER_KEY = "\"ColdBrewUpgradeTracker\" + i";
    /** 循环上界常量 —— 三张表都必须用它。 */
    private static final String BOUND = "COLD_BREW_SLOT_INDEXES.length";

    private static String tile() throws IOException {
        return TestSourceText.read(TILE);
    }

    // ================== 1. 写出侧：同一个循环体里成对 ==================

    /**
     * {@code saveAdditional} 的冷萃循环里必须<b>同时</b>出现等级键与读条器键。
     *
     * <p>这就是「成对写」：删掉任一半，本条立刻红。判据定位到那个含等级键的循环体，
     * 再要求读条器键在<b>同一个</b>循环体内 —— 把它搬到循环外（哪怕文件里还有这行字）
     * 也会红。</p>
     */
    @Test
    public void saveAdditionalWritesTierAndTrackerInsideTheSameLoop() throws IOException {
        String src = tile();
        String write = TestSourceText.methodBody(src, "void saveAdditional(CompoundTag tag) {");
        assertFalse("IceMakerTile 里找不到 saveAdditional（判据失配）", write.isEmpty());

        String loop = loopBodyContaining(write, TIER_KEY);
        assertTrue("saveAdditional 里找不到写出 ColdBrew{i} 的冷萃循环 —— "
                + "那台机器拆机后已安装的冷萃等级会静默丢失", loop != null);
        assertTrue("冷萃读条器必须与等级**写在同一个循环体里**（成对落盘）："
                        + "只写等级的话，重载后 getInstalled() 回到 0，而 uninstallUpgrade 的第一道门"
                        + "正是它 ⇒ 冷萃升级卸不下来（静默、无日志）",
                loop.contains(TRACKER_KEY));
        assertTrue("冷萃循环的上界必须与槽下标表同源（" + BOUND + "）："
                + "写死别的数字会让多出来的那一格只有一半状态被落盘",
                loop.contains(BOUND));
        // 等级那一半是有条件的（null = 未安装），读条器那一半**必须无条件写** ——
        // 否则「已装 0 件」与「压根没写过这个键」在读侧分不开，而读侧正是靠
        // 「有等级、没读条器」来识别旧档并补回 1 件的。
        int trackerAt = loop.indexOf(TRACKER_KEY);
        String trackerStmt = loop.substring(loop.lastIndexOf('\n', trackerAt) + 1,
                loop.indexOf(';', trackerAt) + 1);
        assertFalse("读条器的写出不得被 if 包起来（必须无条件写）："
                        + "否则读侧分不清「装过又卸干净」与「旧档没写这个键」，补回逻辑会误判",
                trackerStmt.contains("if ("));
    }

    // ================== 2. 读回侧：同一个循环体里成对 ==================

    /**
     * {@code readOwnState} 的冷萃循环里必须<b>同时</b>读回等级与读条器。
     *
     * <p>只写不读同样是缺陷：写出去的键没人读，等于没写。</p>
     */
    @Test
    public void readOwnStateReadsTierAndTrackerInsideTheSameLoop() throws IOException {
        String src = tile();
        String read = TestSourceText.methodBody(src, "void readOwnState(CompoundTag tag) {");
        assertFalse("IceMakerTile 里找不到 readOwnState（判据失配）", read.isEmpty());

        String loop = loopBodyContaining(read, TIER_KEY);
        assertTrue("readOwnState 里找不到读回 ColdBrew{i} 的冷萃循环", loop != null);
        assertTrue("读档必须在同一个循环体里把读条器的 Installed 读回（与写出用同一个键）",
                loop.contains(TRACKER_KEY));
        assertTrue("读档循环的上界必须与槽下标表同源（" + BOUND + "）", loop.contains(BOUND));
        assertTrue("旧存档（只有 ColdBrew{i}、没有读条器）必须按「已装 1 件」补回 —— "
                        + "否则这批存档重载后同样卸不下冷萃",
                loop.contains("installDirect(1)"));
    }

    /**
     * {@code load} 与 {@code readSustainedData} 都必须走 {@code readOwnState}。
     *
     * <p>两条都是「机器带着冷萃状态回来」的入口：前者是存档，后者是挖掉再放下
     * （战利品表把 {@code Items} 与自有键搬进 {@code mekData.*}，由
     * {@code BlockMekanism.setPlacedBy} 回灌）。只接一条的话，另一条路上的冷萃状态会静默丢。</p>
     */
    @Test
    public void bothStateEntryPointsGoThroughReadOwnState() throws IOException {
        String src = tile();
        String load = TestSourceText.methodBody(src, "void load(CompoundTag tag) {");
        assertFalse("IceMakerTile 里找不到 load（判据失配）", load.isEmpty());
        assertTrue("load 必须走 readOwnState", load.contains("readOwnState(tag)"));
        String sustained = TestSourceText.methodBody(src, "void readSustainedData(CompoundTag tag) {");
        assertFalse("IceMakerTile 里找不到 readSustainedData（判据失配）", sustained.isEmpty());
        assertTrue("readSustainedData 必须走 readOwnState —— 否则「挖掉再放下」会丢掉"
                        + "冷萃等级与读条器（战利品表搬回来的 mekData 就白搬了）",
                sustained.contains("readOwnState(tag)"));
    }

    // ================== 3. 三张表的下标必须同源 ==================

    /**
     * 槽对象字段<b>一律不得带初始化式</b> —— 这是本仓「构造期陷阱」的第二形态。
     *
     * <p>{@code getInitialInventory} 在<b>父类构造器内部</b>被回调，那一刻本类的字段初始化器
     * 还没跑。于是 {@code private final MekCkSlot[] coldBrewSlots = new MekCkSlot[5];}
     * 这种写法会在方法里对 {@code null} 赋值 —— 症状是「方块放下去建不出方块实体」，
     * 而<b>编译通过、全部单测全绿</b>（没有任何用例真的 new 出一个 tile）。</p>
     *
     * <p>判据：{@code getInitialInventory} 里赋值的那些槽字段，其声明行不得带 {@code = new} /
     * {@code = MekCkSlot} 之类的初始化式。</p>
     */
    @Test
    public void slotFieldsHaveNoInitializer() throws IOException {
        String src = tile();
        String body = TestSourceText.methodBody(src, "protected IInventorySlotHolder getInitialInventory(");
        assertFalse("IceMakerTile 里找不到 getInitialInventory（判据失配）", body.isEmpty());
        for (String field : new String[]{"inputSlot", "outputSlot", "creativeSlot", "coldBrewSlots", "energySlot"}) {
            assertTrue("getInitialInventory 里没有再给 " + field + " 赋值（槽位装配改了？）",
                    body.contains(field + " ="));
            assertFalse("槽字段 " + field + " 带了初始化式：它在父类构造器里被赋值时字段初始化器还没跑，"
                            + "会对 null 赋值 ⇒ 方块放下去建不出方块实体（编译通过、单测全绿）",
                    src.contains("private MekCkSlot[] " + field + " =")
                            || src.contains("private MekCkSlot " + field + " =")
                            || src.contains("private EnergyInventorySlot " + field + " ="));
        }
    }

    /**
     * {@code coldBrewSlots / installedColdBrew / coldBrewTrackers} 三张表必须<b>同长</b>。
     *
     * <p>它们用同一个 {@code i} 索引同一格：槽对象（放物品）、等级（攻击档案）、
     * 读条器（卸载的第一道门）。任何一张表长度对不上，表现都是错位的安装 / 卸载 —— 静默。</p>
     */
    @Test
    public void theThreeColdBrewTablesShareOneLength() throws IOException {
        String src = tile();
        assertTrue("coldBrewSlots 必须由 COLD_BREW_SLOT_INDEXES.length 派生",
                src.contains("new MekCkSlot[COLD_BREW_SLOT_INDEXES.length]"));
        assertTrue("installedColdBrew 必须由 COLD_BREW_SLOT_INDEXES.length 派生",
                src.contains("new ColdBrewTier[COLD_BREW_SLOT_INDEXES.length]"));
        String create = TestSourceText.methodBody(src, "static MekCkUpgradeTracker[] createColdBrewTrackers() {");
        assertFalse("IceMakerTile 里找不到 createColdBrewTrackers（判据失配）", create.isEmpty());
        assertTrue("coldBrewTrackers 必须与槽下标表同长（同一份上界常量）",
                create.contains("new MekCkUpgradeTracker[COLD_BREW_SLOT_INDEXES.length]"));
        assertTrue("coldBrewTrackers 每格上限必须是 1（一个槽只装一级）",
                create.contains("new MekCkUpgradeTracker(1)"));
        // 槽下标表本身必须把 5 个槽全列上（漏一个 = 那一格的冷萃永远装不进去）。
        String indexTable = src.substring(src.indexOf("COLD_BREW_SLOT_INDEXES = {"),
                src.indexOf("};", src.indexOf("COLD_BREW_SLOT_INDEXES = {")));
        for (String slot : new String[]{"CB_SLOT_1", "CB_SLOT_2", "CB_SLOT_3", "CB_SLOT_4", "CB_SLOT_5"}) {
            assertTrue("槽下标表少了 " + slot + "：那一格永远装不进冷萃", indexTable.contains(slot));
        }
        assertEquals("槽下标表必须正好 5 项（多一项会让三张表错位）",
                5, indexTable.split(",").length);
    }

    // ================== 4. 卸载读两侧：读条器 + 等级 ==================

    /**
     * {@code uninstallUpgrade} 的第一道门读<b>读条器</b>、之后读<b>等级</b>。
     *
     * <p>这就是「攻击档案读 {@code installedColdBrew}、卸载读读条器」这条口径的落点：
     * 两处都读、且都不可省 —— 少了等级那一步就不知道要还哪一级的物品，
     * 少了读条器那一步就是本条护栏的缘起（永远卸不下来）。</p>
     */
    @Test
    public void uninstallGatesOnTheTrackerAndGivesBackTheInstalledTier() throws IOException {
        String src = tile();
        String uninstall = TestSourceText.methodBody(src, "void uninstallUpgrade(byte mode, int slot) {");
        assertFalse("IceMakerTile 里找不到 uninstallUpgrade（判据失配）", uninstall.isEmpty());
        assertTrue("卸载的第一道门必须是读条器的 getInstalled() —— 这正是「等级与读条器必须成对落盘」的由来",
                uninstall.contains("coldBrewTrackers[index].getInstalled() <= 0"));
        assertTrue("卸载必须从 installedColdBrew 取要还回去的那一级（攻击档案读的也是它）",
                uninstall.contains("installedColdBrew[index]"));
        assertTrue("卸载必须把读条器与等级**一起**清掉（成对：只清一半会让两处读数分家）",
                uninstall.contains("coldBrewTrackers[index].uninstall(1)")
                        && uninstall.contains("installedColdBrew[index] = null"));
    }

    // ================== 5. 行为：读条器状态机（裸 JVM 可跑） ==================

    /**
     * {@code Installed} 必须能穿过 NBT 往返 —— 这是「成对落盘」那一半的字面语义。
     *
     * <p>纯逻辑：{@link MekCkUpgradeTracker} 不碰注册表，裸 JVM 里可跑。</p>
     */
    @Test
    public void trackerSurvivesItsOwnNbtRoundTrip() {
        MekCkUpgradeTracker tracker = new MekCkUpgradeTracker(1);
        assertEquals("初始未安装", 0, tracker.getInstalled());
        assertEquals("安装 1 件", 1, tracker.installDirect(1));
        assertEquals(1, tracker.getInstalled());

        MekCkUpgradeTracker reloaded = new MekCkUpgradeTracker(1);
        reloaded.load(tracker.save());
        assertEquals("重载后 Installed 必须还在 —— 丢了的话卸载的第一道门恒假，冷萃卸不下来",
                1, reloaded.getInstalled());
        assertEquals("卸载必须真的能卸下 1 件", 1, reloaded.uninstall(1));
        assertEquals(0, reloaded.getInstalled());
    }

    /**
     * 上限为 1 的读条器不得被读档撑到 > 1 —— 否则「一格装一级」的链式准入会被绕过。
     *
     * <p>{@code load} 里带 {@code min(max, ·)} 这就是它的锚点；把那一句改成直接赋值，
     * 本条立刻红（读条器的实现见 {@code MekCkUpgradeTracker.load}）。</p>
     */
    @Test
    public void trackerLoadClampsToItsMaximum() {
        MekCkUpgradeTracker one = new MekCkUpgradeTracker(1);
        net.minecraft.nbt.CompoundTag forged = new net.minecraft.nbt.CompoundTag();
        forged.putInt("Installed", 99);
        one.load(forged);
        assertEquals("Installed 必须被夹到上限 1", 1, one.getInstalled());

        net.minecraft.nbt.CompoundTag negative = new net.minecraft.nbt.CompoundTag();
        negative.putInt("Installed", -5);
        MekCkUpgradeTracker other = new MekCkUpgradeTracker(1);
        other.load(negative);
        assertEquals("负的 Installed 必须被夹到 0", 0, other.getInstalled());
    }

    /**
     * 五个冷萃槽的等级枚举取值必须够用（{@code ColdBrew{i}} 写的是 {@code name()}）。
     *
     * <p>判据不空转：等级名一旦被 {@code load} 里的 {@code valueOf} 认不出来，
     * 那一格会被当作「未安装」——攻击档案静默退回上一档。这里钉住枚举本身仍在。</p>
     */
    @Test
    public void everyColdBrewTierNameIsStillResolvable() {
        for (ColdBrewTier tier : ColdBrewTier.values()) {
            assertEquals("枚举名必须能被 name()/valueOf 往返（读档靠它）",
                    tier, ColdBrewTier.valueOf(tier.name()));
        }
        assertTrue("冷萃链至少要有 5 档（COLD / LOW_TEMP / FROST / DRAGON_FROST|QUEEN / HYPOTHERMIA）",
                ColdBrewTier.values().length >= 5);
    }

    // ================== 小工具 ==================

    /**
     * 取「包含 {@code needle} 的那个 {@code for} 循环」的完整文本（<b>含</b> {@code for (...)} 头与循环体）。
     *
     * <p>按花括号配对切片，而不是 {@code indexOf} 到下一个 {@code }} ——
     * 后者在循环体里还有 {@code if} 块时会截断，判据就成了半真半假的摆设。</p>
     *
     * <p>头必须一起返回：循环上界（{@code i &lt; COLD_BREW_SLOT_INDEXES.length}）写在头上，
     * 只取循环体会让「上界同源」那条断言永远失败。</p>
     *
     * @return 循环全文；找不到返回 {@code null}
     */
    private static String loopBodyContaining(String src, String needle) {
        int at = src.indexOf(needle);
        if (at < 0) {
            return null;
        }
        // 往回找最近的 for（本方法只服务于「键在循环里」这一形态）
        int loop = src.lastIndexOf("for (", at);
        if (loop < 0) {
            return null;
        }
        int brace = src.indexOf('{', loop);
        if (brace < 0) {
            return null;
        }
        int depth = 0;
        for (int i = brace; i < src.length(); i++) {
            char c = src.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return src.substring(loop, i + 1);
                }
            }
        }
        return null;
    }

    /** 供调试用：确认读的是仓库根目录下的那份源码。 */
    @Test
    public void theSourcePathStillExists() {
        assertTrue("找不到 " + TILE + "（本测试需在仓库根目录运行）",
                Files.isRegularFile(Path.of(TILE)));
        try {
            assertTrue("源码为空？", Files.readString(Path.of(TILE), StandardCharsets.UTF_8).length() > 1000);
        } catch (IOException e) {
            throw new AssertionError("读源码失败：" + e, e);
        }
    }
}
