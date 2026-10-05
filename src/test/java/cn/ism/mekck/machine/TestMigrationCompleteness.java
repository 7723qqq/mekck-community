package cn.ism.mekck.machine;

import cn.ism.mekck.TestSourceText;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * <b>迁移完整性护栏</b> —— 每迁一台机器，必须把整条链路接完。
 *
 * <h3>为什么要有它（本轮的两次教训）</h3>
 * 电力研磨机迁移时，我两次漏掉东西，<b>都不是测试发现的，是用户追问才发现的</b>：
 * <ol>
 *   <li><b>GUI 手绘</b>：新屏自己重写基类已有的东西（因为共享基类的类型参数绑死了工厂）；
 *   <li><b>AE2 能力</b>：新 tile 只 implements {@code MenuProvider}，把旧 BE 的
 *       {@code INetworkPullable} 整份丢了，「网络拉料/自动补料/面板下单」静默消失。
 * </ol>
 * 两次都是「编译通过 + 测试全绿」。根因是：<b>迁移的完成度没有一份可执行的清单</b>，
 * 全靠人记 —— 而人（我）记不住。
 *
 * <h3>本类守的清单（每一项都对着一次真实踩坑）</h3>
 * 以「已迁到 Mek 原生基类的 tile」为集合，逐个断言：
 * <table border="1">
 *   <caption>迁移清单与判据</caption>
 *   <tr><th>#</th><th>项</th><th>漏了的症状</th></tr>
 *   <tr><td>1</td><td>注册三件套成组 {@code register(bus)}</td>
 *       <td>运行期「注册表里没有这个 id」，方块放下去变空气</td></tr>
 *   <tr><td>2</td><td>客户端屏幕绑定</td><td>右键开界面直接崩</td></tr>
 *   <tr><td>3</td><td>创造模式物品栏</td><td>玩家拿不到，只能在 JEI 里看见</td></tr>
 *   <tr><td>4</td><td>方块名语言键</td><td>显示 raw key</td></tr>
 *   <tr><td>5</td><td>旧的 {@code instanceof} 分支已删</td>
 *       <td>留着会让后来者以为那条路还活着（口径 §2.4 明写）</td></tr>
 * </table>
 *
 * <p>另有几条已单独成护栏，本类不重复：能力面见
 * {@code TestAe2Hardening#migratedTilesKeepTheirAe2Capability}；旧存档迁移见
 * {@code TestLegacyMachineNbtMigration}；战利品表见 {@code TestFactoryLootTableSustainData}。</p>
 */
public class TestMigrationCompleteness {

    private static final Path REGISTRY_HINT = Path.of("src/main/java/cn/ism/mekck");

    /**
     * 已迁机器：<b>类简名 → 方块注册名</b>。
     *
     * <p>这份表<b>刻意写死</b>而不是自动扫描：它是「我认为已经迁完了」的声明，
     * 新迁一台就必须来登记一次 —— 而登记这个动作本身会逼人把清单过一遍。
     * 自动扫描反而会让「漏配的那台」因为扫不到而静默逃过所有断言。</p>
     */
    private static final Map<String, String> MIGRATED = new LinkedHashMap<>();

    static {
        MIGRATED.put("GrindingMachineTile", "electric_grinding_machine");
        MIGRATED.put("GrillBlockEntity", "electric_grill");
        MIGRATED.put("WineCellarBlockEntity", "wine_cellar");
        MIGRATED.put("UniversalCuttingMachineTile", "universal_cutting_machine");
        // 2026-10-06：坚果爆炒机（旧 NutRoasterBlockEntity → 新 NutRoasterTile）。
        MIGRATED.put("NutRoasterTile", "nut_roaster");
        // 2026-10-06：急冻制冰机（旧 IceMakerBlockEntity → 新 IceMakerTile）。
        MIGRATED.put("IceMakerTile", "ice_maker");
    }

    // ── 1. 注册三件套 ────────────────────────────────────────────────────

    /**
     * <b>注册三件套必须成组出现。</b>
     *
     * <p>只造 {@code RegistryObject} 壳子、忘了 {@code register(bus)}，
     * 字段非 null、<b>编译也通过</b>，但内容一个都没进 Forge 注册表。
     * 本仓种植切配工厂当年就漏过一次（实机启动才炸：
     * {@code Registry Object not present: mekck:planting_cutting_factory}）。</p>
     */
    @Test
    public void everyMigratedBlockRegistersItsTripletOnTheBus() throws IOException {
        String main = TestSourceText.read(
                "src/main/java/cn/ism/mekck/UniversalCuttingMachine.java");
        String registry = TestSourceText.read("src/main/java/cn/ism/mekck/registry/MekCkFactories.java")
                + TestSourceText.read("src/main/java/cn/ism/mekck/registry/MekCkStandaloneMachines.java");
        List<String> missing = new ArrayList<>();
        for (String id : MIGRATED.values()) {
            // ⚠️ 不从方块 id 猜常量名（`electric_grinding_machine` 的常量其实叫
            // `GRINDING_MACHINE_*`）—— 那样会把「命名不符合我的猜测」误报成缺陷。
            // 正确做法：从注册行 `XXX_HANDLE = XXX_BLOCKS_REG.register("<id>",` 反推前缀。
            String prefix = registrationPrefixOf(registry, id);
            if (prefix == null) {
                missing.add(id + "：在注册表里找不到 `*_BLOCKS_REG.register(\"" + id + "\"` "
                        + "—— 无法判定三件套，请确认它真的走 Mek 注册器");
                continue;
            }
            // ⚠️ 逐个断言三件套，**不是**「总共有 3 个就行」——
            // 第一版写成 `count >= 3`，变异测试（删掉 GRINDING_MACHINE_CONTAINERS_REG.register(bus)）
            // 实测**照旧全绿**：剩下 blocks/items/tiles 正好 3 个，把缺的那个盖住了。
            // 而漏 container 的后果是实打实的：客户端 MenuScreens.register 一取就抛
            // Registry Object not present（本仓 planting 工厂当年就是这么炸的）。
            List<String> pieces = List.of("BLOCKS", "ITEMS", "TILES", "CONTAINERS");
            List<String> absent = new ArrayList<>();
            for (String piece : pieces) {
                boolean present = Pattern.compile("(?m)^.*" + Pattern.quote(prefix) + "\\w*_" + piece
                                + "_REG\\.register\\(bus\\)")
                        .matcher(main).find();
                if (!present) {
                    absent.add(piece);
                }
            }
            // 允许的例外：某些机器没有独立的 ITEMS 注册器（物品随方块走）。
            // 但 BLOCKS / TILES / CONTAINERS 三者缺一不可 —— 缺任何一个都会在实机上炸。
            List<String> required = List.of("BLOCKS", "TILES", "CONTAINERS");
            List<String> missingRequired = new ArrayList<>();
            for (String piece : required) {
                if (absent.contains(piece)) {
                    missingRequired.add(piece);
                }
            }
            if (!missingRequired.isEmpty()) {
                missing.add(id + "（前缀 " + prefix + "_）：缺少 " + String.join(" / ", missingRequired)
                        + " 的 register(bus)");
            }
        }
        assertEquals("这些方块的三件套没有成组 register(bus) —— "
                        + "字段非 null、编译通过，但注册表里没有它：\n  " + String.join("\n  ", missing),
                List.of(), missing);
    }

    /** 从 `XXX_HANDLE = XXX_BLOCKS_REG.register("<id>",` 反推常量前缀（返回 `XXX`）。 */
    private static String registrationPrefixOf(String registry, String id) {
        Matcher m = Pattern.compile(
                        "(\\w+?)_BLOCKS_REG\\.register\\(\"" + Pattern.quote(id) + "\"")
                .matcher(registry);
        return m.find() ? m.group(1) : null;
    }

    // ── 2 & 3. 客户端绑定 + 创造栏 ───────────────────────────────────────

    /**
     * <b>屏幕绑定与创造栏必须都接上。</b>
     *
     * <p>漏前者 ⇒ 右键开界面崩；漏后者 ⇒ 玩家在创造栏里找不到这台机器。</p>
     */
    @Test
    public void everyMigratedBlockIsBoundOnTheClientAndInTheCreativeTab() throws IOException {
        String clientEvents = TestSourceText.read(
                "src/main/java/cn/ism/mekck/client/ClientEvents.java");
        String factories = TestSourceText.read("src/main/java/cn/ism/mekck/registry/MekCkFactories.java")
                + TestSourceText.read("src/main/java/cn/ism/mekck/registry/MekCkStandaloneMachines.java");

        List<String> missing = new ArrayList<>();
        for (String id : MIGRATED.values()) {
            String prefix = registrationPrefixOf(factories, id);
            if (prefix == null) {
                continue; // 上一条断言已报「找不到注册行」，这里不重复报
            }
            // 容器常量名的约定：随该机器的 *_CONTAINERS_REG 走，名字与 HANDLE 同前缀；
            // 但这台机器也可能用 MACHINE_CONTAINER 之类，所以两种形态都接受。
            boolean screenBound = Pattern.compile(
                            "(?m)^.*" + Pattern.quote(prefix) + "\\w*_CONTAINER\\.get\\(\\).*MenuScreens\\.register")
                    .matcher(clientEvents).find()
                    || Pattern.compile("(?m)^.*MenuScreens\\.register\\(\\s*" + Pattern.quote(prefix))
                    .matcher(clientEvents).find();
            if (!screenBound) {
                missing.add(id + "（前缀 " + prefix + "_）：ClientEvents 里没有 MenuScreens.register 绑定"
                        + "（右键开界面会崩）");
            }
            // 创造栏有两种历史写法，都接受：新的 `XXX_HANDLE.getItemStack()`（Mek 的
            // BlockRegistryObject）与旧的 `XXX_ITEM.get()`（本仓早期的 RegistryObject<Item>）。
            // 只认其中一种会把「用了另一种写法但确实登记了」误报成缺陷（本轮实测撞到：
            // electric_grill 用 GRILL_ITEM.get()、切菜机用 MACHINE_ITEM.get()）。
            boolean inCreativeTab = Pattern.compile(
                            "event\\.accept\\(\\s*" + Pattern.quote(prefix) + "\\w*_(?:HANDLE\\.getItemStack\\(\\)|ITEM\\.get\\(\\))")
                    .matcher(factories).find();
            if (!inCreativeTab) {
                missing.add(id + "（前缀 " + prefix + "_）：创造模式物品栏里没有 "
                        + prefix + "_HANDLE.getItemStack() 或 " + prefix + "_ITEM.get()"
                        + "（玩家拿不到这台机器）");
            }
        }
        assertEquals("这些机器漏了客户端绑定或创造栏登记：\n  " + String.join("\n  ", missing),
                List.of(), missing);
    }

    // ── 4. 语言键 ────────────────────────────────────────────────────────

    /**
     * <b>方块名与容器名两份语言键都要有。</b>
     *
     * <p>缺了玩家看到的是 raw key。⚠️ 这里<b>必须同时覆盖两个前缀</b>，
     * 因为它们画在不同地方：</p>
     * <ul>
     *   <li>{@code block.mekck.<注册名>} —— 物品栏 / 提示里的<b>方块名</b>；</li>
     *   <li>{@code container.mekck.<注册名>} —— GUI <b>标题</b>画的键
     *       （由 {@code ContainerTypeDeferredRegister} 按容器注册名派生）。</li>
     * </ul>
     *
     * <p><b>本轮实测的漏洞（用户看界面才发现，不是测试发现的）</b>：第一版只查
     * {@code block.mekck.*}，于是 4 台单独迁移的机器（电力研磨机 / 电力烧烤架 /
     * 酒窖 / 通用切菜机）带着 <b>raw key 的 GUI 标题</b>通过了全部断言 ——
     * 6 大工厂家族的 {@code container.mekck.*} 恰好齐全，把这一类问题盖住了。
     * 判据必须覆盖「玩家能看到的每一个前缀」，而不是只覆盖最先想到的那个。</p>
     */
    @Test
    public void everyMigratedBlockHasItsNameAndContainerKeyInBothLanguages() throws IOException {
        String en = TestSourceText.read("src/main/resources/assets/mekck/lang/en_us.json");
        String zh = TestSourceText.read("src/main/resources/assets/mekck/lang/zh_cn.json");
        List<String> missing = new ArrayList<>();
        // 两个前缀都要查：block 是物品名，container 是 GUI 标题。
        List<String> prefixes = List.of("block.mekck.", "container.mekck.");
        for (String id : MIGRATED.values()) {
            for (String prefix : prefixes) {
                String key = prefix + id;
                if (!en.contains("\"" + key + "\"")) {
                    missing.add(key + "（en_us 缺）");
                }
                if (!zh.contains("\"" + key + "\"")) {
                    missing.add(key + "（zh_cn 缺）");
                }
            }
        }
        assertEquals("这些名字缺语言键（玩家会看到 raw key；container.* 缺的是 GUI 标题）：\n  "
                        + String.join("\n  ", missing),
                List.of(), missing);
    }

    // ── 5. 旧 instanceof 分支已删 ────────────────────────────────────────

    /**
     * <b>旧 BE 类必须已经删掉，且派发点上不能再引用它。</b>
     *
     * <p>本仓的口径（§2.4）：每台机器迁到 Mek 原生体系时，{@code MekckAe2} /
     * {@code SideConfigPacket} / {@code UpgradeInstallHandler} 里按<b>旧 BE 类型</b>
     * 派发的 {@code instanceof} 分支必须<b>删除</b>，而不是留着 ——
     * 留着会让后来者误以为那条路还活着。</p>
     */
    @Test
    public void migratedMachinesHaveNoLeftoverLegacyTypeReferences() throws IOException {
        List<String> leftovers = new ArrayList<>();
        String[] dispatchPoints = {
                "src/main/java/cn/ism/mekck/ae2/MekckAe2.java",
                "src/main/java/cn/ism/mekck/network/SideConfigPacket.java",
                "src/main/java/cn/ism/mekck/upgrade/UpgradeInstallHandler.java",
        };
        for (String path : dispatchPoints) {
            String src = TestSourceText.read(path);
            for (String legacy : LEGACY_TYPES) {
                if (src.contains(legacy)) {
                    leftovers.add(path + " 仍引用旧类型 " + legacy);
                }
            }
        }
        assertEquals("旧 BE 已被删除，但派发点仍在引用它 —— "
                        + "要么把分支删掉，要么说明为什么还需要它：\n  "
                        + String.join("\n  ", leftovers),
                List.of(), leftovers);
    }

    /** 已删除的旧 BE 类简名。新迁一台时把它的旧类名加进来。
     *  <p>2026-10-06：坚果爆炒机迁移时加入 {@code NutRoasterBlockEntity}（新类 {@code NutRoasterTile}）；
     *  同日急冻制冰机加入 {@code IceMakerBlockEntity}（新类 {@code machine/icemaker/IceMakerTile}）。
     *  ⚠️ 本表的判据是「派发点不得再引用**已删除的**旧类名」——指向新 tile 类型的分支
     * 不属于本表范围（例如 {@code MekckAe2} 里坚果爆炒机那几条已改指 NutRoasterTile
     * 的活分支，见该文件相应位置的注释）。</p> */
    private static final String[] LEGACY_TYPES = {
            "ElectricGrindingMachineBlockEntity",
            "UniversalCuttingMachineBlockEntity",
            "CuttingMachineFactoryBlockEntity",
            "GrindingFactoryBlockEntity",
            "GrillFactoryBlockEntity",
            "CookingFactoryBlockEntity",
            "SkeweringFactoryBlockEntity",
            "PlantingCuttingFactoryBlockEntity",
            "NutRoasterBlockEntity",
            // 2026-10-06：急冻制冰机（新类 machine/icemaker/IceMakerTile）。
            "IceMakerBlockEntity",
    };

    // ── 判据不许空转 ────────────────────────────────────────────────────

    /** 确认登记表本身不是空的、且每台机器的类文件真的存在。 */
    @Test
    public void theMigratedRegistryIsNotVacuous() throws IOException {
        assertTrue("登记表是空的，上面五条断言全部空转", MIGRATED.size() >= 4);
        for (String name : MIGRATED.keySet()) {
            boolean found = false;
            try (var files = Files.walk(REGISTRY_HINT)) {
                found = files.anyMatch(p -> p.getFileName().toString().equals(name + ".java"));
            }
            assertTrue("登记表里的 " + name + " 在源码里找不到 —— 改名后必须同步本表，"
                    + "否则它守护的那台机器已静默逃出所有断言", found);
        }
    }
}
