package cn.ism.mekck;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.Assert.assertTrue;

/**
 * 源码与资源的<b>文本完整性</b>护栏 —— 防止中文注释被写成替换字符（U+FFFD）而不自知。
 *
 * <h3>为什么需要它</h3>
 * 本项目的注释密度极高（大量中文说明承载着「为什么这么写」的决策依据），而一条注释
 * 被以错误编码解码后再存回去时，<b>javac 不会报任何错</b>：U+FFFD 是合法的 Unicode
 * 码位，它只是把「看不懂的字节」如实显示出来。这类损坏的代价不是编译失败，而是
 * 后人读注释时被误导 —— 而本项目里已经有若干注释是唯一的决策依据。
 *
 * <p>第三轮审查实测到 3 处：{@code GuiMekCkSideConfiguration:45}、
 * {@code PlantingCuttingFactoryExecutor:205}，以及审查过程中新写的一个文件。
 * 前两处都是历史遗留，第三处证明<b>写新文件时同样会踩</b>，所以靠人自查是不够的。</p>
 *
 * <h3>两条断言分别抓两种坏法</h3>
 * <ul>
 *   <li><b>不是合法 UTF-8</b>（严格解码抛 {@link CharacterCodingException}）：
 *       文件用 GBK/Latin-1 之类写过；</li>
 *   <li><b>是合法 UTF-8 但含 U+FFFD</b>：曾经按错误编码解码过再存回。这条更隐蔽 ——
 *       字节序列完全合法，只有内容已经毁了。</li>
 * </ul>
 *
 * <h3>扫描范围</h3>
 * {@code src/main} 与 {@code src/test} 下的 {@code .java / .json / .toml / .mcmeta /
 * .cfg / .md / .txt}。<b>刻意包含 {@code .java}</b>：第一轮审查只扫了资源文件，
 * 因此漏掉了源码里的 3 处 —— 这条注释本身就是那次漏扫的记录。
 */
public class TestTextEncodingIntegrity {

    private static final String[] TEXT_SUFFIXES = {
            ".java", ".json", ".toml", ".mcmeta", ".cfg", ".md", ".txt", ".lang", ".fsh"
    };

    /** 单个文件里允许出现的替换字符数上限。恒为 0。 */
    private static final int ALLOWED_REPLACEMENT_CHARS = 0;

    @Test
    public void everyTextFileIsValidUtf8AndFreeOfReplacementChars() throws IOException {
        List<String> notUtf8 = new ArrayList<>();
        List<String> withReplacementChar = new ArrayList<>();
        int scanned = 0;

        for (Path root : new Path[]{Paths.get("src", "main"), Paths.get("src", "test")}) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (Stream<Path> walk = Files.walk(root)) {
                for (Path path : (Iterable<Path>) walk.filter(Files::isRegularFile)::iterator) {
                    String name = path.getFileName().toString().toLowerCase();
                    if (!isText(name)) {
                        continue;
                    }
                    scanned++;
                    byte[] bytes = Files.readAllBytes(path);
                    if (bytes.length == 0) {
                        continue;
                    }
                    String decoded = strictDecodeOrNull(bytes);
                    if (decoded == null) {
                        notUtf8.add(path.toString());
                        continue;
                    }
                    int bad = countReplacementChars(decoded);
                    if (bad > ALLOWED_REPLACEMENT_CHARS) {
                        withReplacementChar.add(path + " (" + bad + " 个 U+FFFD)");
                    }
                }
            }
        }

        // 扫描数为 0 说明测试跑错了工作目录或路径被改过 —— 那比"没发现问题"更糟，
        // 静默通过的护栏等于没有护栏。
        assertTrue("没有扫到任何文本文件：护栏在空转（检查 src/main 与 src/test 是否存在）", scanned > 0);

        assertTrue("以下文件不是合法 UTF-8（疑似用 GBK/Latin-1 写过）：\n  " + String.join("\n  ", notUtf8),
                notUtf8.isEmpty());
        assertTrue("以下文件含 U+FFFD 替换字符（曾按错误编码解码后存回，注释已损坏）：\n  "
                        + String.join("\n  ", withReplacementChar),
                withReplacementChar.isEmpty());
    }

    private static boolean isText(String lowerName) {
        for (String suffix : TEXT_SUFFIXES) {
            if (lowerName.endsWith(suffix)) {
                return true;
            }
        }
        return false;
    }

    /** 严格 UTF-8 解码：任何非法字节序列都抛异常，而不是替换成 U+FFFD。 */
    private static String strictDecodeOrNull(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException e) {
            return null;
        }
    }

    private static int countReplacementChars(String s) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            // ⚠️ 必须用转义而不是字面量 '\uFFFD'：写成字面量会让**本文件自己**含一个
            // 替换字符，于是这条护栏第一条就把自己抓了（第一版就是这么翻车的）。
            if (s.charAt(i) == '\uFFFD') {
                n++;
            }
        }
        return n;
    }
}

