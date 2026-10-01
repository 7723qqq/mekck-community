package cn.ism.mekck;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * 源码文本断言的<b>共享工具</b>。
 *
 * <h3>为什么要有这个类</h3>
 * 本仓库有多条护栏靠<b>读源码文本</b>做结构断言（菜单槽位边界、容器同步面、
 * 客户端符号隔离、订单物品安全……）。它们反复踩到<b>同一个坑</b>，而且是三次：
 * <ol>
 *   <li>修 {@code CentralKitchenMenu} 的 off-by-one 时，我在新写的 javadoc 里逐字引用了
 *       {@code addSlot(new Slot(new SampleContainer(machine), ...))}，
 *       {@code MACHINE_SLOT_CTOR} 匹配到了<b>注释里</b>那一行，报出
 *       「机器槽起点 14283 > 玩家槽 5845」这种看似有理、实则莫名的失败；</li>
 *   <li>同一轮的另一个测试里，我在 javadoc 里引用了未夹紧的
 *       {@code existing.grow(remainder.getCount())}，被「反向断言无未夹紧 grow」那条抓到；</li>
 *   <li>又一次，我在注释里解释了 {@code iter.remove()} 的危害，
 *       而断言「iter.remove 必须在 if 之内」把<b>注释里那一句</b>当成了代码。</li>
 * </ol>
 *
 * <p>三次都指向同一件事：<b>断言应该看代码，不看散文</b>。而「要求写注释的人别贴代码」
 * 是一条靠人守的约定 —— 本仓库的注释密度极高（大量中文说明承载决策依据），
 * 引用邻近代码是自然而然的写法。所以约定站不住，工具才站得住。</p>
 *
 * <p>于是把「剥注释 + 取方法体」收成一处，让所有护栏共用：
 * 以后新增的源码断言测试<b>应当</b>先读本类的 {@link #read} 而不是自己
 * {@code Files.readString}。</p>
 */
public final class TestSourceText {

    private TestSourceText() {
    }

    /** 读一个源码文件并<b>剥掉注释</b>。结构性断言一律走这里。 */
    public static String read(String path) throws IOException {
        return stripComments(Files.readString(Path.of(path), StandardCharsets.UTF_8));
    }

    /** 读一个源码文件并剥掉注释，异常包成 unchecked（用于 lambda 里的断言）。 */
    public static String readUnchecked(String path) {
        try {
            return read(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * 剥掉块注释与行注释。
     *
     * <p>按 Java 词法粗粒度处理，不处理字符串字面量里的 {@code //}。本仓库的断言关心的是
     * {@code new Slot(} / {@code static final int} / {@code existing.grow(} 这类模式，
     * 它们不会出现在字符串字面量里，所以这个精度足够 —— 而它比「要求注释里别贴代码」
     * 可靠得多：后者已经失效三次。</p>
     */
    public static String stripComments(String src) {
        return src.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("(?m)//.*$", " ");
    }

    /**
     * 取某个方法（或初始化器）的完整方法体，含签名。
     *
     * <p>找不到签名时返回 {@code ""}，由调用方断言 —— 静默返回一个「看起来对」的片段
     * 才是这类测试最危险的失败方式。</p>
     *
     * @param signature 方法签名片段，唯一到足以定位，例如 {@code "public boolean hasOrder() {"}
     */
    public static String methodBody(String src, String signature) {
        int i = src.indexOf(signature);
        if (i < 0) {
            return "";
        }
        int start = src.indexOf('{', i);
        if (start < 0) {
            return "";
        }
        int depth = 0;
        for (int j = start; j < src.length(); j++) {
            char c = src.charAt(j);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return src.substring(i, j + 1);
                }
            }
        }
        return src.substring(i);
    }

    /** 取某个字段的声明（从 {@code " name ="} 到行尾分号）。 */
    public static String fieldDeclaration(String src, String name) {
        int i = src.indexOf(" " + name + " =");
        if (i < 0) {
            return "";
        }
        int semi = src.indexOf(";", i);
        return semi < 0 ? "" : src.substring(i, semi);
    }

    // ══════════════════════════════════════════════════════════════════
    //  「注册中枢」的源码面
    // ══════════════════════════════════════════════════════════════════

    /** 模组入口类：{@code cn/ism/mekck/UniversalCuttingMachine.java}。 */
    private static final String MOD_ENTRY = "src/main/java/cn/ism/mekck/UniversalCuttingMachine.java";

    /** 注册中枢拆分后的注册类目录。 */
    private static final Path REGISTRY_DIR = Path.of("src", "main", "java", "cn", "ism", "mekck", "registry");

    /**
     * 列出「注册中枢」的全部源码：入口类 + {@code registry/} 下的类，入口类在前。
     *
     * <p>顺序固定（入口类先，其余按文件名排序），这样跨文件边界的断言
     * （例如「方块注册一定紧邻它的物品注册」）仍然可比。</p>
     */
    public static List<String> registrySources() throws IOException {
        List<String> out = new ArrayList<>();
        out.add(MOD_ENTRY);
        if (Files.isDirectory(REGISTRY_DIR)) {
            try (Stream<Path> files = Files.list(REGISTRY_DIR)) {
                files.filter(p -> p.getFileName().toString().endsWith(".java"))
                        .map(p -> REGISTRY_DIR.resolve(p.getFileName()).toString().replace('\\', '/'))
                        .sorted()
                        .forEach(out::add);
            }
        }
        return out;
    }

    /**
     * 读「注册中枢」的完整源码面（<b>不</b>剥注释；要剥的用 {@link #readRegistryCode()}）。
     *
     * <h3>为什么要有这个拼接入口</h3>
     * 有 7 条护栏断言的是<b>整体形态</b>而不是某一个文件：方块↔物品注册名的一一对应、
     * 6 个逐档家族的 tile 闸门各走各的表、制冰工厂开关的三个使用点、JEI 催化剂按家族表遍历……
     * 注册中枢从 1 个 2265 行的文件拆成 {@code registry/} 下若干类之后，若让它们跟着每次拆分
     * 改路径，这些断言会变成「拆一次红一次」的噪音 —— 而它们本意是钉住<b>内容</b>
     * （「注册项都写了没有」），不是钉住文件名。
     * <p>所以提供拼接入口：拆分再多次，内容断言不用动；哪天真的改了注册写法，
     * 它们照样会红 —— 就像它们在拆分前一样。</p>
     */
    public static String readRegistry() throws IOException {
        StringBuilder sb = new StringBuilder();
        for (String path : registrySources()) {
            if (!Files.isRegularFile(Path.of(path))) {
                throw new IOException("注册中枢源码不存在：" + path + "（测试需在仓库根目录运行）");
            }
            sb.append(Files.readString(Path.of(path), StandardCharsets.UTF_8)).append('\n');
        }
        return sb.toString();
    }

    /** 同 {@link #readRegistry()}，但剥掉注释 —— 供「某标识符不该出现」类断言使用。 */
    public static String readRegistryCode() throws IOException {
        return stripComments(readRegistry());
    }

    /** 读多个文件并按给定顺序拼接。 */
    public static String readAll(String... paths) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (String path : paths) {
            sb.append(Files.readString(Path.of(path), StandardCharsets.UTF_8)).append('\n');
        }
        return sb.toString();
    }
}
