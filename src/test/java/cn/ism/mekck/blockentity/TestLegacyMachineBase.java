package cn.ism.mekck.blockentity;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 「红石 + 能量」这两项的收敛护栏 —— 已迁到 {@link MekCkLegacyMachine} 的机器不许把副本抄回去。
 *
 * <h3>为什么是这两项</h3>
 * 第四轮实测 14 台遗留 BE：能量与红石的存档读写是<b>逐字同形</b>的
 * （各 1 个字段声明、2 处写盘、2 处读盘；读能量的 {@code receiveEnergy} 循环连写法都一样）。
 * 而侧面配置（各家编码口径不同，有的 {@code byte[]} 有的 int）、物品槽（槽数/堆叠/侧向敏感性
 * 全不一样）、热量、AE2 各台差异大，强行统一会动到存档格式或逼出一堆钩子 ——
 * <b>不做</b>。只收敛「不依赖档位、也不依赖槽位排布」的那部分。
 *
 * <p>注意这 14 台<b>没有档位</b>（无 {@code CuttingMachineFactoryTier}），
 * 所以整台迁到 {@code MekCkMachineTile}（按档位建模）这条路对它们不成立；
 * 本基类是那条不成立的路上唯一能收口的公共地基。</p>
 */
public class TestLegacyMachineBase {

    private static final Path BE_DIR = Path.of("src/main/java/cn/ism/mekck/blockentity");

    private static String read(Path file) throws IOException {
        assertTrue("找不到 " + file + "（源码测试需在仓库根目录运行）", Files.isRegularFile(file));
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    private static String stripComments(String src) {
        return src.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("(?m)//.*$", " ");
    }

    /** 已 extends MekCkLegacyMachine 的全部遗留 BE。 */
    private static List<Path> allMigrated() throws IOException {
        List<Path> out = new ArrayList<>();
        try (Stream<Path> files = Files.walk(BE_DIR)) {
            for (Path file : files.filter(p -> p.toString().endsWith("BlockEntity.java")).sorted().toList()) {
                if (Files.isRegularFile(file)
                        && read(file).contains("extends MekCkLegacyMachine")) {
                    out.add(file);
                }
            }
        }
        return out;
    }

    /**
     * 断言 1：已迁机器不再自带红石字段、不再自己写盘读盘。
     *
     * <p>只查<b>代码</b>（剥掉注释），否则那段「基类统一写」的说明注释会把自己判失败。</p>
     */
    @Test
    public void migratedMachinesDoNotReintroduceCopies() throws IOException {
        List<Path> migrated = allMigrated();
        Set<String> offenders = new TreeSet<>();
        for (Path file : migrated) {
            String code = stripComments(read(file));
            String name = file.getFileName().toString();
            if (code.contains("private RedstoneControl redstoneControl")) {
                offenders.add(name + "：又自带 redstoneControl 字段（基类已持有）");
            }
            if (code.contains("private boolean redstonePowered")) {
                offenders.add(name + "：又自带 redstonePowered 字段（基类已持有）");
            }
            if (code.contains("putInt(\"RedstoneControl\"")) {
                offenders.add(name + "：又自己写 RedstoneControl 键（基类统一写）");
            }
            if (code.contains("putBoolean(\"RedstonePowered\"")) {
                offenders.add(name + "：又自己写 RedstonePowered 键（基类统一写）");
            }
            if (code.contains("while (remainingEnergy > 0)")) {
                offenders.add(name + "：又自己灌能量（基类 load 统一做）");
            }
        }
        // 防空转：得真的扫到几台，否则「没发现副本」是假的。
        assertTrue("已迁机器一台都没扫到，护栏空转了（migrated=" + migrated.size() + "）",
                migrated.size() >= 2);
        assertEquals("已迁到 MekCkLegacyMachine 的机器里又出现了副本：\n  " + String.join("\n  ", offenders),
                Set.of(), offenders);
    }

    /**
     * 断言 2：基类必须保住三个存档键，键名与迁移前<b>逐字一致</b>。
     *
     * <p>这是存档兼容的底线。键名一旦漂移，既有存档里那份就再也读不回来 ——
     * 而这类回归<b>不报错</b>：机器只是安静地回到默认值（能量清零 / 红石归 DISABLED）。
     * 写成三条独立断言而不是循环拼接：拼接表达式既难读，又容易在改写时静默变成恒真。</p>
     */
    @Test
    public void baseKeepsTheThreeSaveKeysVerbatim() throws IOException {
        String base = stripComments(read(BE_DIR.resolve("MekCkLegacyMachine.java")));
        assertTrue("基类不见了存档键 Energy —— 既有存档那份会读不回来",
                base.contains("putInt(\"Energy\""));
        assertTrue("基类不见了存档键 RedstoneControl",
                base.contains("putInt(\"RedstoneControl\""));
        assertTrue("基类不见了存档键 RedstonePowered",
                base.contains("putBoolean(\"RedstonePowered\""));
        // 能量读档必须走 receiveEnergy 循环（Forge 的 EnergyStorage 没有公开 setter，只能这么灌；
        // 且单次 receiveEnergy 受 maxReceive 夹断，大容器灌不满）
        assertTrue("基类 load 必须用 receiveEnergy 循环灌能量",
                base.contains("receiveEnergy"));
    }

    /**
     * 断言 3：每个已迁机器都要对红石存档<b>表态</b>。
     *
     * <p>没有红石功能的机器（如陈化窖）必须覆写 {@code usesRedstone()} 返回 {@code false} ——
     * 否则基类会给它凭空写上两个红石键。读取侧无害（没红石逻辑的机器压根不看），
     * 但那是一份<b>凭空多出来的存档格式</b>，对存档做 diff / 对账 / 第三方工具都是噪声。</p>
     */
    @Test
    public void machinesWithoutRedstoneLogicOptOut() throws IOException {
        List<Path> migrated = allMigrated();
        Set<String> missing = new TreeSet<>();
        for (Path file : migrated) {
            String code = stripComments(read(file));
            boolean hasRedstoneLogic = code.contains("redstoneControl")
                    || code.contains("hasNeighborSignal");
            boolean optedOut = code.contains("usesRedstone()") && code.contains("return false;");
            // 有红石逻辑的机器用基类默认的 true 即可、无需覆写；
            // 只有「无红石逻辑却没退出」才是问题（基类会给它凭空写两个红石键）。
            if (hasRedstoneLogic) {
                continue;
            }
            if (!optedOut) {
                missing.add(file.getFileName() + "：有红石逻辑却没覆写 usesRedstone()"
                        + "（要支持红石就确认基类默认 true 即可，无需覆写）");
            }
        }
        assertEquals("这些已迁机器的红石存档口径没表态：\n  " + String.join("\n  ", missing),
                Set.of(), missing);
    }
}
