package cn.ism.mekck.block;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
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
}
