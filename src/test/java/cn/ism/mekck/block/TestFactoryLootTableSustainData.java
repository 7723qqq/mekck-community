package cn.ism.mekck.block;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * 已迁移到 Mek {@code BlockTile} 的 6 个工厂家族（12 档 × 6 = 72 个方块）的
 * <b>战利品表守恒契约</b>回归测试。
 *
 * <h3>为什么需要</h3>
 * <p>迁到 Mek 体系后，「破坏方块掉什么」完全交给战利品表：Mek 的
 * {@code BlockMekanism.onRemove} 只做拆绑定方块 + {@code blockRemoved()}（空实现）+ {@code super}，
 * <b>不会</b>把方块实体序列化进掉落物；Mek 自己的机器靠战利品表里的
 * {@code copy_nbt source=block_entity} 把内容搬进 item 的 {@code mekData} 子标签，
 * 再由 {@code BlockMekanism.setPlacedBy} 读回。因此：</p>
 * <ul>
 *   <li><b>表不存在</b> → 方块破坏后什么都不掉（烹饪工厂 12 档曾整体缺失）；</li>
 *   <li><b>表存在但没有 copy_nbt</b> → 掉一个裸方块，库存/能量/升级卡全丢；</li>
 *   <li><b>target 不写 {@code mekData.} 前缀</b> → 放下时 {@code setPlacedBy} 读不到，同样全丢。</li>
 * </ul>
 * <p>三者都是<b>静默</b>的：编译通过、进游戏不报错，只在玩家挖掉机器时才发作。</p>
 *
 * <h3>覆盖范围</h3>
 * <p>只覆盖「已迁移」的 6 个家族。{@code ice_factory} 不在此列：{@code ICE_FACTORY_ENABLED = false}
 * 时方块整段不注册（{@code UniversalCuttingMachine}），没有战利品表也不会有掉落。
 * 14 个自研 {@code BaseEntityBlock} 机器同样不在此列——它们覆写 {@code getDrops} 返空、
 * 由 {@code onRemove} 自掉落，另有 {@link TestBlockDropInvariants} 覆盖。</p>
 */
public class TestFactoryLootTableSustainData {

    private static final Path LOOT_DIR = Path.of("src", "main", "resources", "data", "mekck", "loot_tables", "blocks");

    /** 与 {@code CuttingMachineFactoryTier} 的枚举顺序一致（12 档）。 */
    private static final List<String> TIERS = List.of(
            "basic", "advanced", "elite", "ultimate", "absolute", "supreme",
            "cosmic", "infinite", "blaze", "crystal_matrix", "nebula", "singularity");

    /** 与 {@code CuttingMachineFactoryTier#getXxxBlockId} 的后缀一致。 */
    private static final List<String> FAMILIES = List.of(
            "cutting_factory", "grinding_factory", "grill_factory",
            "planting_cutting_factory", "skewering_factory", "cooking_factory");

    /**
     * 必须被搬进掉落物的 BE 存档键：Mek 标准 6 条 + MekCK 自有 8 条。
     *
     * <p>MekCK 自有键的权威清单<b>不在这里</b>，而是
     * {@link #lootTablesCopyExactlyTheKeysSaveAdditionalWrites()} 从写侧源码
     * （{@code MekCkMachineTile.saveAdditional} 及其家族覆写、{@code PlacerPersist}）
     * 解析出来的键常量。本清单只是它的镜像，且会被该测试逐字比对——因此不存在
     * 「本清单把旧键（v1 的 {@code MekCkWorkProgress}）钉成契约」的余地：
     * 写侧自 v2 起只写 {@code MekCkWorkProgressArray}，战利品表也必须复制它。
     * 两边任何一处拼写不同都是静默丢失：{@code copy_nbt} 对不存在的 source 键是**跳过**，不报错。</p>
     */
    private static final List<String> REQUIRED_SOURCES = List.of(
            "componentUpgrade", "componentConfig", "componentEjector", "controlType",
            "EnergyContainers", "Items",
            "MekCkSlots", "mekckExecutor", "MekCkWorkProgressArray", "MekCkSorting", "MekCkNative",
            "GasTank", "FluidTanks", "MekckPlacerUuid");

    /** Mek 原生 {@code super.saveAdditional} 自己写的 6 条键（不在 MekCK 源码里，只能按 Mek 契约登记）。 */
    private static final List<String> VANILLA_MEK_KEYS = List.of(
            "componentUpgrade", "componentConfig", "componentEjector", "controlType",
            "EnergyContainers", "Items");

    /** 写侧源码根目录。 */
    private static final Path MAIN_JAVA = Path.of("src", "main", "java", "cn", "ism", "mekck");

    private static String blockId(String tier, String family) {
        return tier + "_" + family;
    }

    /** 取出 entries[0]。 */
    private static JsonObject entry(JsonObject table) {
        JsonArray pools = table.getAsJsonArray("pools");
        assertNotNull("战利品表缺少 pools", pools);
        assertTrue("战利品表 pools 为空", pools.size() > 0);
        JsonArray entries = pools.get(0).getAsJsonObject().getAsJsonArray("entries");
        assertNotNull("战利品表缺少 entries", entries);
        assertTrue("战利品表 entries 为空", entries.size() > 0);
        return entries.get(0).getAsJsonObject();
    }

    /** 取某个 function 对象，找不到返回 null。 */
    private static JsonObject function(JsonObject entry, String name) {
        JsonArray functions = entry.getAsJsonArray("functions");
        if (functions == null) return null;
        for (JsonElement e : functions) {
            JsonObject fn = e.getAsJsonObject();
            if (fn.has("function") && name.equals(fn.get("function").getAsString())) return fn;
        }
        return null;
    }

    // ── 写侧源码解析（护栏的权威清单来源）────────────────────────────────

    private static String source(Path relativePath) throws IOException {
        return Files.readString(relativePath, StandardCharsets.UTF_8);
    }

    /** 取某个方法体的源码文本（从签名到第一个「四个空格 + 右花括号」）。 */
    private static String methodBody(String source, String signature) {
        int start = source.indexOf(signature);
        assertTrue("源码里找不到方法 " + signature, start > 0);
        int end = source.indexOf("\n    }", start);
        assertTrue("方法 " + signature + " 没有闭合", end > start);
        return source.substring(start, end);
    }

    /** 读一个 {@code String NAME = "值";} 常量。 */
    private static String stringConst(String source, String name, String where) {
        Matcher matcher = Pattern.compile("String " + name + "\\s*=\\s*\"([^\"]*)\"").matcher(source);
        assertTrue("找不到常量 " + where, matcher.find());
        return matcher.group(1);
    }

    /**
     * 解析某源文件的 {@code saveAdditional} 方法体里所有 {@code tag.putXxx(KEY, …)} 的 KEY，
     * 并把它解析成实际键名（形如 {@code MekCkSlotNbt.TAG_SLOTS} 的跨类常量会在同类里查）。
     *
     * <p>这样「权威键清单」与写侧同源：改了写侧的键常量、或删/加一条 {@code tag.put}，
     * 清单立刻跟着变，战利品表一侧不跟就会在 {@link #lootTablesCopyExactlyTheKeysSaveAdditionalWrites()}
     * 里变红——不需要手工维护两份可能互相漂移的清单。</p>
     */
    private static void collectWrittenKeys(String relativeJavaPath, Set<String> out) throws IOException {
        String src = source(Path.of(relativeJavaPath));
        String body = methodBody(src, "void saveAdditional(");
        Matcher matcher = Pattern.compile("\\btag\\.put[A-Za-z]*\\(\\s*([A-Za-z0-9_.]+)\\s*,").matcher(body);
        while (matcher.find()) {
            String identifier = matcher.group(1);
            if (identifier.contains(".")) {
                String[] parts = identifier.split("\\.", 2);
                String owner = source(MAIN_JAVA.resolve("machine").resolve(parts[0] + ".java"));
                out.add(stringConst(owner, parts[1], relativeJavaPath + " 里的 " + identifier));
            } else {
                out.add(stringConst(src, identifier, relativeJavaPath + " 里的 " + identifier));
            }
        }
    }

    @Test
    public void everyMigratedFactoryTierHasALootTable() throws IOException {
        if (!Files.isDirectory(LOOT_DIR)) {
            fail("找不到战利品表目录（测试需在项目根目录运行）：" + LOOT_DIR.toAbsolutePath());
        }
        List<String> missing = new ArrayList<>();
        for (String family : FAMILIES) {
            for (String tier : TIERS) {
                Path p = LOOT_DIR.resolve(blockId(tier, family) + ".json");
                if (!Files.exists(p)) missing.add(p.getFileName().toString());
            }
        }
        assertTrue("以下工厂方块没有战利品表 —— 破坏后连方块本体都不会掉（Mek 的 onRemove 不掉落内容）:\n  "
                + String.join("\n  ", missing), missing.isEmpty());
    }

    @Test
    public void everyFactoryLootTableDropsItsOwnBlock() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (String family : FAMILIES) {
            for (String tier : TIERS) {
                Path p = LOOT_DIR.resolve(blockId(tier, family) + ".json");
                if (!Files.exists(p)) continue; // 由上一个测试负责报缺
                JsonObject table = JsonParser.parseString(Files.readString(p, StandardCharsets.UTF_8)).getAsJsonObject();
                JsonObject first = entry(table);
                String expected = "mekck:" + blockId(tier, family);
                String actual = first.has("name") ? first.get("name").getAsString() : "<无 name>";
                if (!expected.equals(actual)) {
                    offenders.add(p.getFileName() + "：掉落 " + actual + "，应为 " + expected);
                }
            }
        }
        assertTrue("战利品表掉错了方块（复制粘贴时最容易发生）:\n  " + String.join("\n  ", offenders),
                offenders.isEmpty());
    }

    @Test
    public void everyFactoryLootTableCopiesSustainDataIntoMekData() throws IOException {
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(LOOT_DIR)) {
            for (Path p : files.filter(f -> f.toString().endsWith("_factory.json")).toList()) {
                JsonObject table = JsonParser.parseString(Files.readString(p, StandardCharsets.UTF_8)).getAsJsonObject();
                JsonObject first = entry(table);

                JsonObject copyName = function(first, "minecraft:copy_name");
                if (copyName == null || !"block_entity".equals(copyName.get("source").getAsString())) {
                    offenders.add(p.getFileName() + "：缺少 copy_name(source=block_entity)，自定义名会丢");
                }

                JsonObject copyNbt = function(first, "minecraft:copy_nbt");
                if (copyNbt == null) {
                    offenders.add(p.getFileName() + "：缺少 copy_nbt —— 掉落的是裸方块，库存/能量/升级全丢");
                    continue;
                }
                if (!"block_entity".equals(copyNbt.get("source").getAsString())) {
                    offenders.add(p.getFileName() + "：copy_nbt 的 source 不是 block_entity");
                    continue;
                }
                JsonArray ops = copyNbt.getAsJsonArray("ops");
                if (ops == null || ops.size() == 0) {
                    offenders.add(p.getFileName() + "：copy_nbt 没有任何 ops");
                    continue;
                }
                List<String> sources = new ArrayList<>();
                for (JsonElement e : ops) {
                    JsonObject op = e.getAsJsonObject();
                    String src = op.get("source").getAsString();
                    String target = op.get("target").getAsString();
                    sources.add(src);
                    // 恢复路径是 BlockMekanism.setPlacedBy 读 item 的 mekData 子标签：
                    // target 不写 mekData. 前缀 = 数据搬过去也没人读，等价于丢失。
                    if (!target.startsWith("mekData.")) {
                        offenders.add(p.getFileName() + "：" + src + " 的 target「" + target + "」没有 mekData. 前缀");
                    }
                }
                for (String required : REQUIRED_SOURCES) {
                    if (!sources.contains(required)) {
                        offenders.add(p.getFileName() + "：缺少 " + required + " 的搬运 op");
                    }
                }
            }
        }
        assertTrue("以下战利品表不会保住机器内容:\n  " + String.join("\n  ", offenders),
                offenders.isEmpty());
    }

    /**
     * 核心护栏：每张战利品表复制的 <b>MekCK 自有键集合</b>必须<b>逐字等于</b>写侧实际写出的键集合。
     *
     * <h3>为什么不能只钉一份手抄清单</h3>
     * <p>本缺陷的形态正是「测试把 v1 旧键钉成契约」：写侧自 v2 起只写
     * {@code MekCkWorkProgressArray}（int 数组）与 {@code MekCkSorting}（boolean），
     * 而 72 张表还在复制 v1 的 {@code MekCkWorkProgress}（单个 int）。手抄清单
     * ({@link #REQUIRED_SOURCES}) 一旦与写侧各走各的，删掉写侧一条键、或改名，测试都不会响。</p>
     *
     * <p>因此这里把「权威清单」从写侧源码解析出来：{@code MekCkMachineTile.saveAdditional}
     * 的 5 条 literal 键 + {@code CookingFactoryTile} 的 {@code FluidTanks}
     * + {@code PlantingCuttingFactoryTile} 的 {@code GasTank}（两个家族的 {@code saveAdditional} 覆写）
     * + {@code PlacerPersist.KEY_UUID}（基类委托写出的放置者键）。战利品表的
     * MekCK 子集（复制键 − Mek 原生 6 条）必须与它集合相等，且 {@link #REQUIRED_SOURCES}
     * 也必须与「Mek 原生 6 条 ∪ 解析结果」一致。</p>
     *
     * <h3>变异点（删一条应让本测试变红）</h3>
     * <ol>
     *   <li>从任一战利品表删掉 {@code MekCkSorting} 的 op；</li>
     *   <li>把 {@code MekCkMachineTile.TAG_WORK_PROGRESS_ARRAY} 的值改名（写侧变了、表没变）；</li>
     *   <li>删掉 {@code saveAdditional} 里的 {@code tag.putBoolean(TAG_SORTING, sorting)}；</li>
     *   <li>在 {@code saveAdditional} 里新增一条 {@code tag.putXxx("Foo", …)} 而不加对应 op。</li>
     * </ol>
     */
    @Test
    public void lootTablesCopyExactlyTheKeysSaveAdditionalWrites() throws IOException {
        Set<String> written = new LinkedHashSet<>();
        collectWrittenKeys(
                "src/main/java/cn/ism/mekck/machine/MekCkMachineTile.java", written);
        collectWrittenKeys(
                "src/main/java/cn/ism/mekck/machine/cooking/CookingFactoryTile.java", written);
        collectWrittenKeys(
                "src/main/java/cn/ism/mekck/machine/plantingcutting/PlantingCuttingFactoryTile.java", written);
        written.add(stringConst(source(MAIN_JAVA.resolve("advancement").resolve("PlacerPersist.java")),
                "KEY_UUID", "PlacerPersist.KEY_UUID"));

        // 手抄清单必须与「Mek 原生 6 条 + 写侧解析结果」逐字一致：清单漂移在这里立刻变红。
        Set<String> expected = new LinkedHashSet<>(VANILLA_MEK_KEYS);
        expected.addAll(written);
        assertEquals("REQUIRED_SOURCES 与写侧源码解析结果不一致（清单漂移，或写侧改了键名）",
                expected, new HashSet<>(REQUIRED_SOURCES));

        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(LOOT_DIR)) {
            for (Path p : files.filter(f -> f.toString().endsWith("_factory.json")).toList()) {
                JsonObject table = JsonParser.parseString(Files.readString(p, StandardCharsets.UTF_8)).getAsJsonObject();
                JsonObject copyNbt = function(entry(table), "minecraft:copy_nbt");
                if (copyNbt == null) continue; // 缺 copy_nbt 由上一个测试报
                Set<String> copiedMekck = new LinkedHashSet<>();
                for (JsonElement e : copyNbt.getAsJsonArray("ops")) {
                    String src = e.getAsJsonObject().get("source").getAsString();
                    if (!VANILLA_MEK_KEYS.contains(src)) {
                        copiedMekck.add(src);
                    }
                }
                if (!copiedMekck.equals(written)) {
                    offenders.add(p.getFileName() + "：复制的 MekCK 键 " + copiedMekck
                            + "，但 saveAdditional 实际写的是 " + written);
                }
            }
        }
        assertTrue("战利品表复制的键集合与写侧写入的键集合不一致（拆机将静默丢状态）:\n  "
                + String.join("\n  ", offenders), offenders.isEmpty());
    }

    @Test
    public void disabledIceFactoryHasNoLootTableRequirement() {
        // 记录口径：ice_factory 的方块在 ICE_FACTORY_ENABLED=false 时整段不注册，
        // 因此本测试**不**要求它有任何战利品表。若哪天启用该开关，
        // 需要同时补 12 张 ice_factory 表，并把 "ice_factory" 加进 FAMILIES。
        Path ice = LOOT_DIR.resolve("basic_ice_factory.json");
        assertFalse("ice_factory 当前未注册（ICE_FACTORY_ENABLED=false）；若已启用，"
                + "请补 12 张 ice_factory 战利品表并把 \"ice_factory\" 加进 FAMILIES", Files.exists(ice));
    }
}
