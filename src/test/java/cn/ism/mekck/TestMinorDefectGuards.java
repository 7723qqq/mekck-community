package cn.ism.mekck;

import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Task 8（杂项确定缺陷批）里两处「源码形态」护栏。
 *
 * <h3>为什么只能钉源码</h3>
 * 这两处缺陷都不会在单测里以行为差异暴露：
 * <ul>
 *   <li>{@code GeneratorFs#deleteDirectoryRecursively} 未关闭的
 *       {@code Files.walk} 流，只在「删目录后立刻重建/覆盖」时才咬人（Windows 上句柄被占）；</li>
 *   <li>{@code ClientWorldEvents} 的 {@code BB_DIAG} 常驻 INFO 是「日志量」问题，
 *       没有可断言的行为。</li>
 * </ul>
 * 用源码形态钉住回归，与 {@code TestClientValueSync} / {@code TestRandomizeUpgradeBranches}
 * 的做法同源。
 */
public class TestMinorDefectGuards {

    private static final Path GENERATOR =
            Path.of("src/main/java/cn/ism/mekck/command/planting/GeneratorFs.java");
    private static final Path CLIENT_EVENTS =
            Path.of("src/main/java/cn/ism/mekck/client/ClientWorldEvents.java");

    /**
     * 读源码并剥注释：本类注释里逐字提到了旧形态（{@code Files.walk(dir).sorted}），
     * 不剥注释会把说明当成代码（本仓库已栽过三次，见 {@code TestSourceText}）。
     */
    private static String read(Path file) throws IOException {
        assertTrue("找不到 " + file + "（源码测试需在仓库根目录运行）", Files.isRegularFile(file));
        return TestSourceText.read(file.toString());
    }

    /**
     * 8.1：递归删除必须用 try-with-resources 关掉 {@code Files.walk} 的流。
     *
     * <p>{@code Files.walk} 返回的流持有打开的目录句柄；不关时删除过程中
     * Windows 会锁住目录，且失败是静默的（无异常、无日志）。断言两种形态：
     * 必须出现 try-with-resources；且不得再对 {@code Files.walk} 的返回值直接串操作。</p>
     */
    @Test
    public void recursiveDeleteClosesItsWalkStream() throws IOException {
        String source = read(GENERATOR);
        assertTrue("deleteDirectoryRecursively 必须用 try-with-resources 关闭 Files.walk 的流",
                source.contains("try (Stream<Path>"));
        assertFalse("不得再对 Files.walk 的返回值直接串操作（流未关闭）：Files.walk(dir).sorted(...)",
                source.contains("Files.walk(dir).sorted"));
    }

    /**
     * 8.2：绑定块右键诊断那条常驻 INFO 必须降为 DEBUG。
     *
     * <p>本模组机器多为多块结构，右键绑定块即触发一条；常驻 INFO 会随右键刷屏。
     * 降 DEBUG 后仍保留诊断能力（把 logger {@code mekck.BoundingDiag} 调到 DEBUG 即可）。</p>
     */
    @Test
    public void boundingBlockDiagnosticsDoNotLogAtInfo() throws IOException {
        String source = read(CLIENT_EVENTS);
        assertTrue("找不到 BB_DIAG 诊断字段", source.contains("BB_DIAG"));
        assertFalse("绑定块右键诊断不得常驻 INFO（会随右键刷屏）", source.contains("BB_DIAG.info("));
        assertTrue("绑定块右键诊断必须走 DEBUG（保留诊断能力）", source.contains("BB_DIAG.debug("));
    }
}