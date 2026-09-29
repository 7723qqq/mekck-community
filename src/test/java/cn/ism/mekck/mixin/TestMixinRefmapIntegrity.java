package cn.ism.mekck.mixin;

import org.junit.Assume;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 守住 {@code mekck.refmap.json} 不腐烂 —— 这是「生产环境启动即崩」那条缺陷的回归护栏。
 *
 * <h3>为什么需要它</h3>
 * {@code MixinItemStack} 打的是**原版**方法 {@code ItemStack.save} / {@code ItemStack.of}。
 * 原版方法在 SRG 环境下叫 {@code m_41739_} / {@code m_41712_}，**必须**靠 refmap 翻译；
 * 没有 refmap 时 Mixin 0.8.5 会去找硬编码的 {@code mixin.refmap.json}（名字对不上），
 * 于是 {@code @Inject} 匹配不到任何目标 —— 而 {@code mekck.mixins.json} 是
 * {@code "required": true}，**游戏直接在 Bootstrap 阶段终止**。
 *
 * <p>真实证据（2026-09-30 实例日志）：
 * <pre>
 *   [FATAL] Mixin apply failed mekck.mixins.json:MixinItemStack -> ItemStack:
 *     InvalidInjectionException: @Inject annotation on mekck$writeBigCount could not
 *     find any targets matching 'save' in net.minecraft.world.item.ItemStack. No refMap loaded.
 * </pre>
 *
 * <h3>为什么这份 refmap 是手写的，因而更需要护栏</h3>
 * 本轮曾尝试用 MixinGradle + Mixin 注解处理器自动生成，但处理器会对 4 个
 * {@code remap = false} 的 mod 类 mixin 报成员级错误（合成 lambda 名、
 * {@code $VALUES}/{@code UPGRADES} 这类 javac 合成字段没有映射），直接让 {@code compileJava} 失败；
 * 而 {@code disableTargetValidator} / {@code @Pseudo} 只能消掉其中一条。
 * 因此改为手写 {@code src/main/resources/mekck.refmap.json}。
 *
 * <p><b>手写的代价就是「会腐烂」</b>：只要有人在 {@code MixinItemStack} 里新加一条
 * {@code @Inject(method = "另一个原版方法")}，缺陷就会**以完全相同的形态**回来，
 * 而编译、单测、打包全都不会报错。本测试就是钉住这一点。
 */
public class TestMixinRefmapIntegrity {

    private static final String MIXIN_DIR = "src/main/java/cn/ism/mekck/mixin";
    private static final String CONFIG = "src/main/resources/mekck.mixins.json";
    private static final String REFMAP = "src/main/resources/mekck.refmap.json";
    private static final String BUILD_GRADLE = "build.gradle";

    private static String read(String path) throws IOException {
        return Files.readString(Path.of(path), StandardCharsets.UTF_8);
    }

    /** 配置里必须声明 refmap，且名字与手写的那份一致。 */
    @Test
    public void configDeclaresTheRefmapWeShip() throws IOException {
        String config = read(CONFIG);
        assertTrue("mekck.mixins.json 必须声明 \"refmap\"；不声明时 Mixin 会回退去找硬编码的 "
                        + "mixin.refmap.json，与本模组生成的名字对不上 ⇒ 生产环境启动崩",
                config.contains("\"refmap\""));
        assertTrue("refmap 字段的值必须是 mekck.refmap.json（与 src/main/resources 下那份同名）",
                config.contains("mekck.refmap.json"));
        assertTrue("src/main/resources/mekck.refmap.json 必须存在（它会被打进 jar 根目录）",
                Files.exists(Path.of(REFMAP)));
    }

    /**
     * <b>dev 运行必须打开 refmap 的运行期翻译</b>，否则手写的 SRG refmap 会把开发环境打崩。
     *
     * <h3>这条护栏守的是与 {@link #configDeclaresTheRefmapWeShip} <b>相反</b>方向的故障</h3>
     * 上一条守「生产环境」：没有 refmap 就没有 SRG 名，{@code @Inject} 匹配不到原版方法。
     * 本条守「开发环境」：refmap <b>在</b>，可它里面写的是 <b>SRG 名</b>（{@code m_41739_}），
     * 而 {@code runClient} / {@code runServer} / {@code runGameTestServer} 与 ForgeGradle 6
     * 生成的 IDE run config 跑的是 <b>mojmap</b> jar。Mixin 0.8.5 只有在
     * {@code mixin.env.remapRefMap} 为真时才用 {@code RemappingReferenceMapper} 把 refmap
     * 翻译回 mojmap；否则拿 SRG 名去 mojmap 类里找目标 —— 找不到，
     * 而 {@code mekck.mixins.json} 是 {@code "required": true} ⇒ <b>Bootstrap 阶段硬崩</b>。
     *
     * <p>同形态的既有实证：本仓 {@code run/logs/latest.log} 里 Farmer's Delight 自己的
     * SRG refmap 在 dev 环境被逐字使用后的匹配失败记录。</p>
     *
     * <p>为什么不能用 mixingradle 插件兜：它会顺带接管注解处理器，而 AP 会对 4 个
     * {@code remap = false} 的 mod 类 mixin 报成员级错误（见 {@code STATUS.md} §六.4），
     * 那条路已确认走不通。所以这两条 {@code jvmArgs} 是唯一的环节，必须钉住。</p>
     */
    @Test
    public void devRunsRemapTheRefmapBackToMojmap() throws IOException {
        String gradle = read(BUILD_GRADLE);
        assertTrue("build.gradle 必须给 dev 运行传 -Dmixin.env.remapRefMap=true："
                        + "手写 refmap 里是 SRG 名，而 runClient/runServer 跑的是 mojmap jar，"
                        + "不翻译则 @Inject 匹配不到原版方法 ⇒ Bootstrap 阶段硬崩",
                gradle.contains("mixin.env.remapRefMap=true"));
        assertTrue("build.gradle 必须给 dev 运行传 -Dmixin.env.refMapRemappingFile=<SRG→mojmap 的 tsrg>",
                gradle.contains("mixin.env.refMapRemappingFile"));
        // 三个 run 任务都要接上：只接 runClient 的话 runServer / runGameTestServer 照样崩。
        for (String task : new String[]{"runClient", "runServer", "runGameTestServer"}) {
            assertTrue("build.gradle 的 refmap 翻译段落必须覆盖 " + task
                            + "；漏掉任何一个 run 任务都会在 Bootstrap 阶段崩",
                    gradle.contains("'" + task + "'"));
        }
        // 路径必须动态解析：FG 缓存里的 mcp_config 目录名带 MCP 快照时间戳，
        // 写死就是 build.gradle 自己注释里禁止的「本机 + 本次缓存布局的快照」。
        assertTrue("SRG→mojmap 映射文件必须动态定位（含 '1.20.1-' 快照目录名的匹配），"
                        + "不能写死 ~/.gradle/caches 下的具体路径",
                gradle.contains("srg_to_official_1.20.1.tsrg") && gradle.contains("1.20.1-"));
    }

    /**
     * 配置里每一个**需要重映射**的 mixin，它的每一条 {@code method = "..."} 选择器
     * 都必须在 refmap 里有条目。
     *
     * <p>判据：源码里的 {@code @Mixin} 注解**不含** {@code remap = false} 即视为需要重映射
     * （Mixin 的默认值是 {@code remap = true}）。这类 mixin 打 mod 类时也会「碰巧能用」
     * ——mod 类名与方法名在 SRG 下不改——但那只是因为没被翻译，不是设计意图，
     * 所以这里统一要求它们进 refmap。
     */
    @Test
    public void everyRemappedSelectorHasARefmapEntry() throws IOException {
        String config = read(CONFIG);
        String refmap = read(REFMAP);
        Set<String> mixinNames = mixinClassNames(config);
        assertTrue("配置里应当至少列出 7 个 mixin", mixinNames.size() >= 7);

        List<String> missing = new ArrayList<>();
        for (String name : mixinNames) {
            String source = read(MIXIN_DIR + "/" + name + ".java");
            if (isRemapFalse(source)) {
                continue; // 打 mod 类且显式关掉重映射：选择器不需要翻译
            }
            String block = refmapBlock(refmap, "cn/ism/mekck/mixin/" + name);
            if (block == null) {
                missing.add(name + "（refmap 里没有它的条目）");
                continue;
            }
            for (String selector : methodSelectors(source)) {
                if (!block.contains("\"" + selector + "\"")) {
                    missing.add(name + " 的选择器 \"" + selector + "\"");
                }
            }
        }
        assertTrue("这些 mixin 选择器没有 refmap 条目 ⇒ 生产环境启动即崩"
                        + "（\"@Inject could not find any targets ... No refMap loaded\"）。"
                        + "手写 refmap 时必须同步补上对应的 SRG 名：\n  " + String.join("\n  ", missing),
                missing.isEmpty());
    }

    /**
     * 最强的一条：refmap 里写的 SRG 名必须与 **ForgeGradle 自己用的那份映射**一致。
     *
     * <p>{@code build/reobfJar/mappings.tsrg} 就是 {@code reobfJar} 重混淆所用的映射表。
     * 测试任务依赖 {@code copyJarToLibs → reobfJar}，所以跑 <b>test</b> 时它必然存在；
     * 单独跑测试或映射文件缺失时用 {@link Assume} 跳过，而不是失败。</p>
     *
     * <p>这条能挡住手写 refmap 最危险的错误形态：<b>名字写对了格式、写错了内容</b>
     * ——那种情况编译、打包、甚至「配置里声明了 refmap」全都看不出来，只有启动才炸。</p>
     */
    @Test
    public void srgNamesMatchTheMappingsForgeGradleUses() throws IOException {
        Path tsrg = Path.of("build/reobfJar/mappings.tsrg");
        Assume.assumeTrue("未找到 build/reobfJar/mappings.tsrg（未跑过 reobfJar），跳过 SRN 名核对",
                Files.exists(tsrg));

        String refmap = read(REFMAP);
        for (String method : List.of("save", "of")) {
            String expected = srgNameFor(Files.readString(tsrg, StandardCharsets.UTF_8),
                    "net/minecraft/world/item/ItemStack", method);
            assertTrue("mappings.tsrg 里应当有 ItemStack." + method, expected != null);
            String actual = refmapValue(refmap, "cn/ism/mekck/mixin/MixinItemStack", method);
            assertTrue("refmap 里应当有 MixinItemStack." + method + " 的条目", actual != null);
            assertTrue("MixinItemStack." + method + " 的 SRG 名与 ForgeGradle 实际用的是同一份映射不一致："
                            + "refmap 写的是 " + actual + "，而 mappings.tsrg 说 ItemStack." + method
                            + " → " + expected + "。写错名字的症状是启动时 "
                            + "\"could not find any targets ... No refMap loaded\"",
                    expected.equals(actual));
        }
    }

    // ── 源码/文本解析（不引 JSON 库，避免依赖测试 classpath 的传递依赖）──

    /** 配置里 {@code "mixins": [...]} 数组列出的类名。 */
    private static Set<String> mixinClassNames(String config) {
        Set<String> out = new LinkedHashSet<>();
        int at = config.indexOf("\"mixins\"");
        int start = config.indexOf('[', at);
        int end = config.indexOf(']', start);
        Matcher m = Pattern.compile("\"([A-Za-z0-9_$]+)\"").matcher(config.substring(start, end));
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }

    /** {@code @Mixin} 注解里是否显式写了 {@code remap = false}。 */
    private static boolean isRemapFalse(String source) {
        // 用 "@Mixin(" 而不是 "@Mixin"：类注释里也会出现这个词（例如「@Pseudo 不能去掉」那段）。
        Matcher m = Pattern.compile("@Mixin\\s*\\(").matcher(source);
        if (!m.find()) {
            return false;
        }
        int at = m.start();
        int end = source.indexOf(')', at);
        return end > at && source.substring(at, end).replace(" ", "").contains("remap=false");
    }

    /** 源码里所有 {@code method = "..."} 的选择器字面量。 */
    private static List<String> methodSelectors(String source) {
        List<String> out = new ArrayList<>();
        Matcher m = Pattern.compile("method\\s*=\\s*\"([^\"]+)\"").matcher(source);
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }

    /** refmap 文本里某个 mixin 类名对应的那一块（到下一个同级键为止的近似切片）。 */
    private static String refmapBlock(String refmap, String mixinClass) {
        String key = "\"" + mixinClass + "\"";
        int at = refmap.indexOf(key);
        if (at < 0) {
            return null;
        }
        int brace = refmap.indexOf('{', at);
        int depth = 0;
        for (int i = brace; i < refmap.length(); i++) {
            char c = refmap.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return refmap.substring(brace, i + 1);
                }
            }
        }
        return null;
    }

    /** 取 {@code "selector": "值"} 里的值。 */
    private static String refmapValue(String refmap, String mixinClass, String selector) {
        String block = refmapBlock(refmap, mixinClass);
        if (block == null) {
            return null;
        }
        Matcher m = Pattern.compile("\"" + Pattern.quote(selector) + "\"\\s*:\\s*\"([^\"]+)\"").matcher(block);
        return m.find() ? m.group(1) : null;
    }

    /**
     * 从 tsrg 里取某个类某个方法的 SRG 名，并拼成 Mixin 选择器要的完整描述符。
     *
     * <p>tsrg 形状（实测 {@code build/reobfJar/mappings.tsrg}）：
     * <pre>
     *   net/minecraft/world/item/ItemStack net/minecraft/world/item/ItemStack
     *     save (Lnet/minecraft/nbt/CompoundTag;)Lnet/minecraft/nbt/CompoundTag; m_41739_
     * </pre>
     * refmap 的值是「{@code L<owner>;<srgName><descriptor>}」，例如
     * {@code Lnet/minecraft/world/item/ItemStack;m_41739_(Lnet/minecraft/nbt/CompoundTag;)Lnet/minecraft/nbt/CompoundTag;}。
     */
    private static String srgNameFor(String tsrg, String owner, String method) {
        int at = tsrg.indexOf(owner + " ");
        while (at >= 0) {
            int lineEnd = tsrg.indexOf('\n', at);
            int next = tsrg.indexOf(owner + " ", at + 1);
            String block = tsrg.substring(at, next > 0 ? next : tsrg.length());
            Matcher m = Pattern.compile("^\\s+" + Pattern.quote(method) + "\\s+(\\([^\\s]*\\)[^\\s]+)\\s+(\\S+)\\s*$",
                    Pattern.MULTILINE).matcher(block);
            if (m.find()) {
                return "L" + owner + ";" + m.group(2) + m.group(1);
            }
            assertTrue(lineEnd > 0);
            at = next;
        }
        return null;
    }
}
