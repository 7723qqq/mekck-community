package cn.ism.mekck;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

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
}
