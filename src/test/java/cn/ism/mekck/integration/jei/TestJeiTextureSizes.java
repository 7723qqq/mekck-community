package cn.ism.mekck.integration.jei;

import cn.ism.mekck.TestSourceText;
import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * JEI 分类贴图尺寸护栏（M5-2）。
 *
 * <h3>为什么值得单独一个测试</h3>
 * {@code PlantingCuttingRecipeCategory} 的产物槽底图 {@code output_wide.png} 是
 * <b>42×26</b> 的「两格宽」底图，而 {@code drawTexture} 助手把绘制尺寸同时当贴图尺寸
 * （{@code blit(texture, x, y, 0, 0, w, h, w, h)}），于是整张 42×26 被压进 18×18
 * 四边形、描边横向压扁。{@code blit} 的 textureWidth/Height 必须是贴图真实尺寸 ——
 * 这是纯静态可查的客观错误，所以在这里读 PNG 的 IHDR 逐条比对。
 *
 * <p>本文件同时承载 M5-4 的护栏（JEI 分类 {@code setRecipe} 不得裸解引用
 * {@code Minecraft.getInstance().level}）—— 任务只允许新增本文件与
 * {@code TestGroundOutlineEdges} 两个护栏文件，而两者都是 JEI 源码形态断言。</p>
 */
public class TestJeiTextureSizes {

    private static final Path JEI_DIR = Path.of("src/main/java/cn/ism/mekck/integration/jei");
    private static final Path ASSETS_DIR = Path.of("src/main/resources/assets/mekck");

    /** 贴图常量声明：{@code ResourceLocation NAME = new ResourceLocation("mekck", "textures/....png");} */
    private static final Pattern TEXTURE_CONST = Pattern.compile(
            "ResourceLocation\\s+(\\w+)\\s*=\\s*new\\s+ResourceLocation\\s*\\(\\s*\"mekck\"\\s*,\\s*\"([^\"]+\\.png)\"\\s*\\)");

    /** int 常量声明：{@code static final int NAME = 42;} */
    private static final Pattern INT_CONST = Pattern.compile(
            "static\\s+final\\s+int\\s+(\\w+)\\s*=\\s*(-?\\d+)\\s*;");

    /** 裸解引用：{@code Minecraft.getInstance().level.} —— M5-4 的缺陷形态。 */
    private static final Pattern BARE_LEVEL_DEREF = Pattern.compile(
            "Minecraft\\s*\\.\\s*getInstance\\s*\\(\\s*\\)\\s*\\.\\s*level\\s*\\.");

    // ── M5-2：blit 的贴图尺寸必须等于 PNG 真实尺寸 ───────────────────────

    /**
     * 每个贴图绘制调用的「贴图尺寸」实参必须等于对应 PNG 的真实尺寸（读 IHDR）。
     *
     * <p>{@code blit} 取 9 参形式（texture, x, y, u, v, w, h, textureWidth, textureHeight），
     * 最后两个实参就是贴图尺寸；{@code drawTexture} 助手把绘制尺寸原样转发为贴图尺寸
     * （由 {@link #drawTextureHelperForwardsTextureSizeVerbatim} 钉住），所以它的调用点
     * 查最后两个实参同样成立。</p>
     */
    @Test
    public void everyTextureDrawDeclaresTheRealTextureSize() throws IOException {
        List<String> offenders = new ArrayList<>();
        int checked = 0;
        for (Path file : jeiSources()) {
            String code = TestSourceText.read(file.toString());
            Map<String, String> textures = textureConstants(code);
            Map<String, Integer> ints = intConstants(code);

            for (String call : calls(code, "blit")) {
                List<String> args = splitArgs(call);
                if (args.size() != 9) {
                    continue; // 其它 blit 重载不画 mekck 贴图
                }
                String asset = textures.get(args.get(0));
                if (asset == null) {
                    continue; // 非本模组贴图常量（或助手形参）
                }
                checked++;
                Integer w = evalInt(args.get(7), ints);
                Integer h = evalInt(args.get(8), ints);
                if (w == null || h == null) {
                    offenders.add(file.getFileName() + ": blit(" + args.get(0)
                            + ") 的贴图尺寸参数无法静态求值：" + args.get(7) + ", " + args.get(8));
                    continue;
                }
                int[] real = pngSize(asset);
                if (w != real[0] || h != real[1]) {
                    offenders.add(file.getFileName() + ": blit(" + args.get(0) + ") 声明贴图 "
                            + w + "×" + h + "，PNG 真实 " + real[0] + "×" + real[1]);
                }
            }

            for (String call : calls(code, "drawTexture")) {
                List<String> args = splitArgs(call);
                if (args.size() < 6) {
                    continue;
                }
                String asset = textures.get(args.get(1));
                if (asset == null) {
                    continue;
                }
                checked++;
                Integer w = evalInt(args.get(args.size() - 2), ints);
                Integer h = evalInt(args.get(args.size() - 1), ints);
                if (w == null || h == null) {
                    offenders.add(file.getFileName() + ": drawTexture(" + args.get(1)
                            + ") 的尺寸参数无法静态求值：" + args.get(args.size() - 2) + ", " + args.get(args.size() - 1));
                    continue;
                }
                int[] real = pngSize(asset);
                if (w != real[0] || h != real[1]) {
                    offenders.add(file.getFileName() + ": drawTexture(" + args.get(1) + ") 按 "
                            + w + "×" + h + " 画，PNG 真实 " + real[0] + "×" + real[1]);
                }
            }
        }
        assertTrue("判据在空转：没扫到任何贴图绘制调用", checked >= 10);
        assertEquals("blit/drawTexture 的贴图尺寸必须等于 PNG 真实尺寸（读 IHDR）：", List.of(), offenders);
    }

    /**
     * {@code drawTexture} 助手必须把「绘制尺寸」原样转发为「贴图尺寸」
     * （{@code blit(texture, x, y, 0, 0, w, h, w, h)}）：上面的调用点检查查的是绘制尺寸，
     * 只有这条成立时它才等价于「贴图尺寸 == PNG 真实尺寸」。
     */
    @Test
    public void drawTextureHelperForwardsTextureSizeVerbatim() throws IOException {
        Pattern forward = Pattern.compile(
                "blit\\(\\s*texture\\s*,\\s*x\\s*,\\s*y\\s*,\\s*0\\s*,\\s*0\\s*,\\s*(\\w+)\\s*,\\s*(\\w+)\\s*,"
                        + "\\s*(\\w+)\\s*,\\s*(\\w+)\\s*\\)");
        int helpers = 0;
        for (Path file : jeiSources()) {
            String code = TestSourceText.read(file.toString());
            String body = TestSourceText.methodBody(code, "private static void drawTexture(");
            if (body.isEmpty()) {
                continue;
            }
            helpers++;
            Matcher m = forward.matcher(body);
            assertTrue(file.getFileName() + " 的 drawTexture 不再转发贴图尺寸：" + body, m.find());
            assertEquals(file.getFileName() + " 的 drawTexture 把绘制尺寸与贴图尺寸写成了不同参数 —— "
                            + "调用点检查只查绘制尺寸，管不住贴图尺寸",
                    m.group(1) + "," + m.group(2), m.group(3) + "," + m.group(4));
        }
        assertTrue("没扫到任何 drawTexture 助手，判据在空转", helpers >= 2);
    }

    // ── M5-4：setRecipe 不得裸解引用 level ───────────────────────────────

    /** 整个 JEI 源码目录不得出现 {@code Minecraft.getInstance().level.} 裸解引用。 */
    @Test
    public void setRecipeNeverDereferencesLevelWithoutNullCheck() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path file : jeiSources()) {
            String code = TestSourceText.read(file.toString());
            Matcher m = BARE_LEVEL_DEREF.matcher(code);
            while (m.find()) {
                offenders.add(file.getFileName() + ":" + lineOf(code, m.start()));
            }
        }
        assertEquals("level 可空（标题界面为 null），裸解引用会 NPE；先落局部变量再判空"
                + "（对齐 JEIPlugin.registerRecipes 的写法）：", List.of(), offenders);
    }

    /** 5 个曾裸解引用的分类必须都补上判空（而不是把解引用挪到别处）。 */
    @Test
    public void fiveCategoriesGuardLevelBeforeUse() throws IOException {
        List<String> files = List.of(
                "BeverageAssemblyRecipeCategory.java",
                "ExtractingRecipeCategory.java",
                "FerreroRecipeCategory.java",
                "GrapePressingRecipeCategory.java",
                "PackagingRecipeCategory.java");
        for (String name : files) {
            String code = TestSourceText.read(JEI_DIR.resolve(name).toString());
            String body = TestSourceText.methodBody(code, "public void setRecipe(");
            assertFalse(name + " 没扫到 setRecipe 方法体，判据失效（方法被改名？）", body.isEmpty());
            assertTrue(name + " 的 setRecipe 没有对 level 判空", body.contains("level != null"));
            assertFalse(name + " 的 setRecipe 仍在裸解引用 level",
                    BARE_LEVEL_DEREF.matcher(body).find());
        }
    }

    // ── 判据不空转 ───────────────────────────────────────────────────────

    /** 正则既能抓住已知缺陷形态，也不误伤已判空的写法。 */
    @Test
    public void scanIsNotVacuous() {
        assertTrue("正则抓不住已知缺陷形态，护栏失效",
                BARE_LEVEL_DEREF.matcher(
                        "recipe.getResultItem(Minecraft.getInstance().level.registryAccess())").find());
        assertFalse("正则把已判空的写法也误判为缺陷",
                BARE_LEVEL_DEREF.matcher(
                        "Level level = Minecraft.getInstance().level; if (level != null) { level.registryAccess(); }").find());
    }

    // ── 工具 ─────────────────────────────────────────────────────────────

    private static List<Path> jeiSources() throws IOException {
        try (Stream<Path> files = Files.list(JEI_DIR)) {
            return files.filter(p -> p.getFileName().toString().endsWith(".java"))
                    .sorted()
                    .toList();
        }
    }

    /** 贴图常量名 → assets/mekck 下的相对路径。 */
    private static Map<String, String> textureConstants(String code) {
        Map<String, String> out = new HashMap<>();
        Matcher m = TEXTURE_CONST.matcher(code);
        while (m.find()) {
            out.put(m.group(1), m.group(2));
        }
        return out;
    }

    /** int 常量名 → 值。 */
    private static Map<String, Integer> intConstants(String code) {
        Map<String, Integer> out = new HashMap<>();
        Matcher m = INT_CONST.matcher(code);
        while (m.find()) {
            out.put(m.group(1), Integer.parseInt(m.group(2)));
        }
        return out;
    }

    /** 取 {@code name(} 调用的实参文本（跳过方法声明；括号配对，忽略字符串内的括号）。 */
    private static List<String> calls(String code, String name) {
        List<String> out = new ArrayList<>();
        int from = 0;
        while (true) {
            int i = code.indexOf(name + "(", from);
            if (i < 0) {
                break;
            }
            from = i + name.length() + 1;
            String before = code.substring(Math.max(0, i - 24), i);
            if (before.contains("void ")) {
                continue; // 方法声明，不是调用
            }
            int end = matchingParen(code, i + name.length());
            if (end < 0) {
                continue;
            }
            out.add(code.substring(i + name.length() + 1, end));
        }
        return out;
    }

    private static int matchingParen(String code, int openIdx) {
        int depth = 0;
        boolean inString = false;
        for (int i = openIdx; i < code.length(); i++) {
            char c = code.charAt(i);
            if (inString) {
                if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    /** 顶层逗号切分实参。 */
    private static List<String> splitArgs(String args) {
        List<String> out = new ArrayList<>();
        int depth = 0;
        boolean inString = false;
        int start = 0;
        for (int i = 0; i < args.length(); i++) {
            char c = args.charAt(i);
            if (inString) {
                if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            } else if (c == ',' && depth == 0) {
                out.add(args.substring(start, i).trim());
                start = i + 1;
            }
        }
        out.add(args.substring(start).trim());
        return out;
    }

    /** 求值：整数 literal 或本文件的 int 常量；其余返回 null。 */
    private static Integer evalInt(String expr, Map<String, Integer> ints) {
        String e = expr.trim();
        if (e.matches("-?\\d+")) {
            return Integer.parseInt(e);
        }
        return ints.get(e);
    }

    /** 读 PNG 的 IHDR 真实尺寸（宽, 高）。 */
    private static int[] pngSize(String assetPath) throws IOException {
        Path png = ASSETS_DIR.resolve(assetPath);
        assertTrue("贴图不存在：" + png, Files.isRegularFile(png));
        byte[] head = new byte[24];
        try (InputStream in = Files.newInputStream(png)) {
            assertEquals("PNG 头不足 24 字节：" + png, 24, in.readNBytes(head, 0, 24));
        }
        assertEquals("不是 PNG 文件：" + png, 0x89504E47, readInt(head, 0));
        assertEquals("IHDR 缺失：" + png, 0x49484452, readInt(head, 12));
        return new int[]{readInt(head, 16), readInt(head, 20)};
    }

    private static int readInt(byte[] b, int off) {
        return ((b[off] & 0xFF) << 24) | ((b[off + 1] & 0xFF) << 16)
                | ((b[off + 2] & 0xFF) << 8) | (b[off + 3] & 0xFF);
    }

    private static int lineOf(String text, int index) {
        int line = 1;
        for (int i = 0; i < index && i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }
}
