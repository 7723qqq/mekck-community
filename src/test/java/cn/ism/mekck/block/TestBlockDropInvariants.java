package cn.ism.mekck.block;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * 方块掉落「源码不变量」测试。
 *
 * <p><b>为什么需要</b>：本模组几乎所有机器方块都覆写 {@code getDrops} 返回
 * {@code List.of()}（抑制战利品表），改由 {@code onRemove} 自行掉落方块本体。
 * 这是刻意的设计，但代价是——<b>只要漏掉 {@code onRemove} 里的自掉落，方块被破坏后就会
 * 连本体一起消失</b>，且没有任何报错。{@code central_kitchen} 与
 * {@code sandwich_assembler} 就这样上线过：两者都需要稀缺材料合成，挖掉即永久损失、
 * 且机器内物品一并蒸发。</p>
 *
 * <p>本测试把这条隐含契约显式化：凡覆写 {@code getDrops} 返回 {@code List.of()} 的方块类，
 * 其 {@code onRemove} 必须存在掉落路径——要么掉自己（{@code new ItemStack(this)}），
 * 要么调用 {@code dropContents()} 之类的内容物掉落并另有战利品表兜底。
 * 由于本模组这些方块没有战利品表，实际要求是前者。</p>
 */
public class TestBlockDropInvariants {

    private static final Path BLOCK_DIR = Path.of("src", "main", "java", "cn", "ism", "mekck", "block");

    /** 表示「本方块不靠战利品表掉落」的写法。 */
    private static final String SUPPRESS_LOOT = "public List<ItemStack> getDrops";

    /**
     * 这些方块是多结构方块的「附属占位块」（由主方块统一管理），不应出现在掉落检查里。
     */
    private static final List<String> ALLOWED_WITHOUT_SELF_DROP = List.of(
            "BioreactorBoundingBlock.java"   // 生物反应堆的绑定块，由 BioreactorBlock 统一移除
    );

    @Test
    public void blocksThatSuppressLootTableMustDropThemselves() throws IOException {
        if (!Files.isDirectory(BLOCK_DIR)) {
            fail("找不到方块源码目录（测试需在项目根目录运行）：" + BLOCK_DIR.toAbsolutePath());
        }

        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(BLOCK_DIR)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String name = file.getFileName().toString();
                if (ALLOWED_WITHOUT_SELF_DROP.contains(name)) continue;

                String src = Files.readString(file, StandardCharsets.UTF_8);
                if (!src.contains(SUPPRESS_LOOT)) continue;   // 用战利品表，不在本测试范围

                boolean dropsSelf = src.contains("new ItemStack(this)");
                if (!dropsSelf) {
                    offenders.add(name + "：getDrops 返回空表，但 onRemove 未掉落方块本体");
                }
            }
        }

        assertTrue("以下方块抑制了战利品表却又不掉落自身 —— 破坏后会连方块一起消失：\n  "
                        + String.join("\n  ", offenders),
                offenders.isEmpty());
    }

    @Test
    public void everySuppressedLootTableBlockHasALootTableOrSelfDrop() throws IOException {
        // 说明：本模组这些方块**故意不提供**战利品表（靠 onRemove 自掉落）。
        // 本测试只确认「抑制战利品表」与「自掉落」成对出现——
        // 若某方块既抑制了战利品表、又没有自掉落，它在游戏里就是静默消失的。
        // 与上面第一个测试的区别：这里额外要求 dropContents 型方块也必须掉本体，
        // 因为本模组这些方块没有战利品表兜底。
        if (!Files.isDirectory(BLOCK_DIR)) {
            fail("找不到方块源码目录：" + BLOCK_DIR.toAbsolutePath());
        }

        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(BLOCK_DIR)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String name = file.getFileName().toString();
                if (ALLOWED_WITHOUT_SELF_DROP.contains(name)) continue;

                String src = Files.readString(file, StandardCharsets.UTF_8);
                if (!src.contains(SUPPRESS_LOOT)) continue;

                // 内容物掉落（dropContents）与本体掉落是两件事，必须分别存在
                boolean dropsContents = src.contains("dropContents()");
                boolean dropsSelf = src.contains("new ItemStack(this)");
                if (dropsContents && !dropsSelf) {
                    offenders.add(name + "：只掉了内容物（dropContents）却没掉方块本体");
                } else if (!dropsContents && !dropsSelf) {
                    offenders.add(name + "：既无内容物掉落也无本体掉落");
                }
            }
        }
        assertTrue("以下方块的掉落路径不完整：\n  " + String.join("\n  ", offenders),
                offenders.isEmpty());
    }

    /**
     * 抑制战利品表的方块<b>不许</b>同时又带一张战利品表。
     *
     * <h3>为什么</h3>
     * 前面两个测试确立了契约：这些方块靠 {@code onRemove} 自掉落。
     * 实测 1.20.1 的破坏链路是
     * {@code Block.dropResources} → {@code BlockStateBase.getDrops(Builder)} →
     * {@code BlockBehaviour.getDrops(BlockState, LootParams.Builder)} ——
     * 正是被覆写成 {@code List.of()} 的那个方法，所以<b>战利品表永不被查询</b>。
     *
     * <p>于是同时存在一张表是<b>纯误导</b>：文件在那里、格式正确、JSON 合法，
     * 但永远不生效。第四轮就在这上面栽过 —— 代码注释据此断定
     * 「制冰工厂缺 12 张战利品表 ⇒ 开了开关方块破坏后什么都不掉」，
     * 而真实原因是它走 {@code onRemove} 根本不需要表。</p>
     *
     * <p>更糟的是它同时是个<b>地雷</b>：哪天有人删掉 {@code getDrops} 覆写想「恢复标准掉落」，
     * 就会变成 {@code onRemove} 掉一个 + 战利品表再掉一个 = <b>双倍掉落</b>。</p>
     *
     * <p>方块类 → 注册 id 的映射从 {@code UniversalCuttingMachine} 的
     * {@code register("<id>", XxxBlock::new)} 现场解析，不写死名单 ——
     * 写死名单的话新增方块就漏检。</p>
     */
    @Test
    public void blocksThatSuppressLootTableMustNotAlsoShipOne() throws IOException {
        Path lootDir = Path.of("src", "main", "resources", "data", "mekck", "loot_tables", "blocks");
        Path registrySrc = Path.of("src", "main", "java", "cn", "ism", "mekck", "UniversalCuttingMachine.java");
        if (!Files.isDirectory(BLOCK_DIR) || !Files.isRegularFile(registrySrc)) {
            fail("找不到源码或战利品表目录（测试需在项目根目录运行）");
        }

        // 方块类简名 → 注册 id
        Map<String, String> classToId = new HashMap<>();
        Matcher reg = Pattern.compile("register\\(\\s*\"([a-z0-9_]+)\"\\s*,\\s*(\\w+)\\s*::\\s*new")
                .matcher(Files.readString(registrySrc, StandardCharsets.UTF_8));
        while (reg.find()) {
            classToId.put(reg.group(2), reg.group(1));
        }

        List<String> offenders = new ArrayList<>();
        int checked = 0;
        try (Stream<Path> files = Files.walk(BLOCK_DIR)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String name = file.getFileName().toString();
                String src = Files.readString(file, StandardCharsets.UTF_8);
                if (!src.contains(SUPPRESS_LOOT)) continue;      // 用战利品表，不在本测试范围
                if (ALLOWED_WITHOUT_SELF_DROP.contains(name)) continue;

                String simpleName = name.substring(0, name.length() - ".java".length());
                String id = classToId.get(simpleName);
                if (id == null) {
                    // 注册用的是全限定名（如 cn.ism.mekck.block.SimpleMachineBlock::new 包在
                    // lambda 里），简名解析不出来。这类方块的掉落契约由上面两个测试覆盖
                    // （getDrops 返空 + onRemove 自掉落），这里跳过而不是硬凑映射 ——
                    // 硬凑一个错的映射比漏检更糟，会把不相干的表误判成死表。
                    continue;
                }
                checked++;
                if (Files.isRegularFile(lootDir.resolve(id + ".json"))) {
                    offenders.add(name + "（注册名 " + id + "）：抑制了战利品表，却又带了一张"
                            + "永不被查询的表 —— 既误导，又在 getDrops 覆写被删时会变成双倍掉落");
                }
            }
        }

        assertTrue("护栏空转了：一张表都没检查到（checked=" + checked + "）", checked >= 10);
        assertTrue("以下方块抑制战利品表却又带着一张死表：\n  " + String.join("\n  ", offenders),
                offenders.isEmpty());
    }
}
