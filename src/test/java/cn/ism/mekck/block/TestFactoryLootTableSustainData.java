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
import java.util.List;
import java.util.stream.Stream;

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
     * 必须被搬进掉落物的 BE 存档键：Mek 标准 6 条 + MekCK 自有 7 条。
     *
     * <p>自有键的权威定义在 {@code machine/MekCkMachineTile} 的「键契约」注释
     * （{@code MekCkSlots} / {@code mekckExecutor} / {@code MekCkWorkProgress} / {@code MekCkNative} /
     * {@code GasTank} / {@code FluidTanks} / {@code MekckPlacerUuid}），那里同时是
     * {@code ISustainedData.readSustainedData} 的读取侧。两边任何一处拼写不同都是静默丢失：
     * {@code copy_nbt} 对不存在的 source 键是**跳过**，不报错。</p>
     */
    private static final List<String> REQUIRED_SOURCES = List.of(
            "componentUpgrade", "componentConfig", "componentEjector", "controlType",
            "EnergyContainers", "Items",
            "MekCkSlots", "mekckExecutor", "MekCkWorkProgress", "MekCkNative",
            "GasTank", "FluidTanks", "MekckPlacerUuid");

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
