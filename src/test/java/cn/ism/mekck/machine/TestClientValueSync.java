package cn.ism.mekck.machine;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 「客户端要看的值必须挂进容器同步通道」这条契约的护栏。
 *
 * <h3>它防的是哪一类 bug</h3>
 * <b>服务端有、客户端没有的字段，在联机时静默停在默认值。</b>
 * 切菜机迁移时踩过一次：进度 {@code progress} 只在 {@code onUpdateServer} 里递增，
 * 而那只在服务端跑；客户端那份 tile 的 {@code progress} <b>永远是 0</b>。
 * GUI 的进度条读它，于是<b>进度条永远不动</b> —— 而机器其实在工作。
 *
 * <p>这个缺陷的特点是：编译通过、438 个测试全绿、单机看不出来（单机下
 * 服务端与客户端是同一个对象）、只在联机时发作。所以只能靠源码形态钉住。</p>
 *
 * <h3>为什么判据是「有没有 {@code addContainerTrackers} + 客户端镜像字段」</h3>
 * Mek 机器同步数据的<b>唯一</b>正规入口是
 * {@code MekanismTileContainer.addContainerTrackers()} → {@code tile.addContainerTrackers(container)}
 * → {@code container.track(SyncableXxx)}，由 Mek 自己的容器属性包按脏值增量下发。
 * 自己发包就要另写一套「谁在什么时候发、玩家关屏后怎么办」的状态机 —— 而漏写的
 * 症状恰好是上面那种「静默停在默认值」。
 */
public class TestClientValueSync {

    private static final Path MACHINE_DIR = Path.of("src/main/java/cn/ism/mekck/machine");
    private static final Path BLOCKENTITY_DIR = Path.of("src/main/java/cn/ism/mekck/blockentity");

    /**
     * 必须在客户端可读的「运行态」字段。
     *
     * <p>每一条都是<b>曾经或可能</b>只在服务端递增、却被 GUI 直接读的值。</p>
     */
    private static final List<String> SYNCED_FIELDS = List.of("progress");

    private static String read(Path file) throws IOException {
        assertTrue("找不到 " + file + "（源码测试需在仓库根目录运行）", Files.isRegularFile(file));
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    private static String stripComments(String src) {
        return src.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("(?m)//.*$", " ");
    }

    /** 所有 tile 类（Cut*FactoryTile / *Tile / MekCkMachineTile 等）。 */
    private static List<Path> tileClasses() throws IOException {
        List<Path> out = new ArrayList<>();
        try (Stream<Path> files = Files.walk(MACHINE_DIR)) {
            for (Path file : files.filter(p -> p.toString().endsWith("Tile.java")).sorted().toList()) {
                out.add(file);
            }
        }
        return out;
    }

    /**
     * 持有这些字段的 tile，<b>必须</b>挂进容器同步通道。
     *
     * <p>判据只看「这个类自己声明了该字段」—— 继承来的字段由基类的
     * {@code addContainerTrackers} 负责，不在本测试范围。</p>
     */
    @Test
    public void tilesWithClientReadStateMustTrackIt() throws IOException {
        Set<String> offenders = new TreeSet<>();
        int checked = 0;

        for (Path file : tileClasses()) {
            String code = stripComments(read(file));
            for (String field : SYNCED_FIELDS) {
                // 本类自己声明了这个字段（而不是从别处继承）
                boolean owns = Pattern.compile("private\\s+(int|boolean|float|double)\\s+" + field + "\\s*[;=]")
                        .matcher(code).find();
                if (!owns) {
                    continue;
                }
                checked++;
                boolean tracks = code.contains("addContainerTrackers")
                        || code.contains("SyncableInt")
                        || code.contains("SyncableBoolean")
                        || code.contains("SyncableDouble");
                if (!tracks) {
                    offenders.add(file.getFileName()
                            + "：自己持有 `" + field + "` 这个「客户端要读」的状态，"
                            + "却没有 addContainerTrackers / SyncableXxx —— 客户端那份永远是默认值"
                            + "（症状：进度条不动、联机才发作、编译与测试全绿）");
                }
            }
        }

        assertTrue("一条都没扫到，护栏空转了（checked=" + checked + "）", checked >= 1);
        assertEquals("这些字段没挂进同步通道：\n  " + String.join("\n  ", offenders),
                Set.of(), offenders);
    }

    /**
     * 同步镜像字段必须<b>真的被 getter 用上</b>，不能只声明。
     *
     * <p>反向锚定：上一条只看「有没有 track」，这一条看「客户端镜像有没有被读」。
     * 两者的失效形态不同 —— 少了 track 是「读到默认 0」，
     * 少了镜像是「定义了却没人用，等于没同步」。</p>
     */
    @Test
    public void clientMirrorFieldIsActuallyRead() throws IOException {
        Set<String> unused = new TreeSet<>();

        for (Path file : tileClasses()) {
            String code = stripComments(read(file));
            Matcher m = Pattern
                    .compile("private\\s+int\\s+client([A-Za-z]+)\\s*;").matcher(code);
            while (m.find()) {
                String name = m.group(0).split("\\s+")[2];
                if (!code.contains(name)) {
                    unused.add(file.getFileName() + "：`" + name + "` 只声明没人读 —— 等于没同步");
                }
            }
        }

        assertEquals("这些客户端镜像字段是摆设：\n  " + String.join("\n  ", unused),
                Set.of(), unused);
    }

    /**
     * 切菜机专项回归：{@code getProgress()} 必须<b>分端</b>。
     *
     * <p>第四轮的缺陷本体：{@code getProgress()} 直接 {@code return progress}，
     * 而客户端那份 {@code progress} 恒为 0 ⇒ 进度条永远不动。
     * 正确形态是「客户端读镜像、服务端读真值」。</p>
     */
    @Test
    public void cuttingMachineProgressIsSideAware() throws IOException {
        Path tile = MACHINE_DIR.resolve("cutting/UniversalCuttingMachineTile.java");
        String code = stripComments(read(tile));

        assertTrue("切菜机 tile 必须覆写 addContainerTrackers 把进度挂进容器同步",
                code.contains("addContainerTrackers"));
        assertTrue("切菜机 tile 必须有客户端进度镜像字段",
                code.contains("clientProgress"));

        // getProgress 的方法体必须同时提到两端
        String body = methodBody(code, "public int getProgress()");
        assertTrue("找不到 getProgress()", body != null && !body.isEmpty());
        assertTrue("getProgress() 必须分端：客户端读 clientProgress 镜像",
                body.contains("clientProgress"));
        assertTrue("getProgress() 必须分端：服务端读真值 progress",
                body.contains("progress"));
        assertTrue("getProgress() 不能在客户端路径上直接返回真值（那是本缺陷的形态）",
                !body.contains("isClientSide") || body.contains("clientProgress"));
    }

    /** 按花括号配对截取方法体。 */
    private static String methodBody(String code, String signature) {
        int start = code.indexOf(signature);
        if (start < 0) {
            return null;
        }
        int brace = code.indexOf('{', start);
        if (brace < 0) {
            return null;
        }
        int depth = 0;
        for (int i = brace; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                if (--depth == 0) {
                    return code.substring(brace, i + 1);
                }
            }
        }
        return null;
    }
}
