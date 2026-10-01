package cn.ism.mekck;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 注册中枢拆分后的<b>类初始化契约</b>护栏。
 *
 * <h3>它挡的是什么事故</h3>
 * 注册项是在各注册类的静态初始化器里加进 {@code MekCkRegistries} 那些
 * {@code DeferredRegister} 的，而 {@code DeferredRegister} 是在 {@code register(bus)} 时
 * 挂上注册事件监听器、<b>事件触发时</b>才读那张表。于是有一个静默失败窗口：
 * 某个注册类若因为「没人引用它」而晚于注册事件才初始化，它的条目<b>一个都不会注册</b> ——
 * 方块放下去变空气、菜单取不到，日志里<b>没有任何报错</b>。
 *
 * <p>本仓已经为这件事付过一次代价（种植切配工厂三件套漏了一次 {@code register(bus)}），
 * 拆分把「碰不碰得到某个类」变得更依赖运气：入口类不再持有全部字段，
 * 新加一个注册类时没有任何编译器会提醒你「得有人在构造器里碰一下它」。</p>
 *
 * <h3>三条断言</h3>
 * <ol>
 *   <li>每个注册类都提供 {@code public static void init()}（空实现即可，作用是强制类初始化）；</li>
 *   <li>{@code MekCkRegistries#registerAll} 逐个触碰它们 ——
 *       这里用<b>目录扫描</b>得出名单，而不是写死清单：写死的话，
 *       新增注册类会让人误以为「已经接好了」，而实际上没人碰它；</li>
 *   <li>入口类的构造器调用 {@code registerAll}（否则前两条形同虚设）。</li>
 * </ol>
 */
public class TestRegistryInitContract {

    /** 不参与触碰的类：它自己就是那张条目表（{@code init()} 无意义），也是入口。 */
    private static final String TABLE_OWNER = "MekCkRegistries";

    private static final Path REGISTRY_DIR =
            Path.of("src", "main", "java", "cn", "ism", "mekck", "registry");

    private static final String ENTRY = "src/main/java/cn/ism/mekck/UniversalCuttingMachine.java";

    /**
 * 目录里<b>真正往注册表加条目</b>的注册类名单（按文件名排序，便于报错定位）。
 *
 * <p>「是否加条目」是自动判定的，而不是维护一份豁免名单：辅助类
 * （{@code MekCkRegistrySupport}，只有几个取句柄的方法）没有条目，也就不需要被触碰；
 * 而一旦有人给它加了注册项，它就自动进入名单。
 * <p>豁免名单在这种位置几乎必然腐化 —— 加一个辅助类就得记得往清单里补一行，
 * 不补就会被误报，补了却没人复核。加条目这个判据不需要维护。</p>
 */
    private static List<String> registryClasses() throws IOException {
        List<String> out = new ArrayList<>();
        try (Stream<Path> files = Files.list(REGISTRY_DIR)) {
            files.map(p -> p.getFileName().toString())
                    .filter(n -> n.endsWith(".java"))
                    .map(n -> n.substring(0, n.length() - 5))
                    .filter(n -> !TABLE_OWNER.equals(n))
                    .sorted()
                    .forEach(n -> {
                        try {
                            if (registersEntries(read(n))) {
                                out.add(n);
                            }
                        } catch (IOException e) {
                            throw new java.io.UncheckedIOException(e);
                        }
                    });
        }
        return out;
    }

    private static String read(String cls) throws IOException {
        return Files.readString(REGISTRY_DIR.resolve(cls + ".java"), StandardCharsets.UTF_8);
    }

    /**
     * 这个类是否真的往注册表里加条目（{@code DeferredRegister} 或 {@code .register(…)}）。
     *
     * <p><b>只看代码，不看注释</b>：{@code MekCkRegistrySupport} 的 javadoc 里就写着
     * 「{@code TILES_REG.register(blockHandle, …)} 要求先有方块」——那是解释，
     * 不是注册项。剥注释这件事本仓已经栽过三次（见 {@link TestSourceText} 的类注释），
     * 这里不再重复同样的错。</p>
     */
    private static boolean registersEntries(String src) {
        String code = TestSourceText.stripComments(src);
        return code.contains("DeferredRegister") || code.contains(".register(");
    }

    /** 1) 每个注册类都要有触碰式初始化入口。 */
    @Test
    public void everyRegistryClassExposesATouchEntryPoint() throws IOException {
        List<String> missing = new ArrayList<>();
        for (String cls : registryClasses()) {
            String src = read(cls);
            if (!src.contains("public static void init()")) {
                missing.add(cls);
            }
        }
        assertEquals("这些注册类没有 init()，无法被强制类初始化（条目会静默不注册）：" + missing,
                List.of(), missing);
    }

    /** 2) 名单里的每一个都要被 {@code registerAll} 碰到。 */
    @Test
    public void registerAllTouchesEveryRegistryClass() throws IOException {
        String src = read(TABLE_OWNER);
        List<String> missing = new ArrayList<>();
        for (String cls : registryClasses()) {
            if (!src.contains(cls + ".init();")) {
                missing.add(cls);
            }
        }
        assertEquals("MekCkRegistries#registerAll 没有触碰：" + missing
                        + " —— 新增注册类必须登记进去，否则它的条目不会注册且不报错",
                List.of(), missing);
        assertTrue("registerAll 里没有把延迟注册器挂到总线（触碰了也没用：监听器没挂上）",
                src.contains("BLOCKS.register(bus);") && src.contains("ITEMS.register(bus);"));
    }

    /**
     * 3) 入口类构造器必须走这条路，且<b>顺序</b>是「先触碰（registerAll）→ 再挂 Mek 家族注册器」。
     *
     * <p>顺序也是契约的一部分：{@code MekCkFactories} 的静态块要读同包里更早声明的字段，
     * 反过来写就可能拿到尚未赋值的 blank final。</p>
     */
    @Test
    public void entryConstructorCallsRegisterAllFirst() throws IOException {
        String code = TestSourceText.read(ENTRY);
        String ctor = TestSourceText.methodBody(code,
                "public UniversalCuttingMachine(FMLJavaModLoadingContext context) {");
        assertTrue("构造器里没有 MekCkRegistries.registerAll(bus)", ctor.contains("MekCkRegistries.registerAll(bus);"));
        int all = ctor.indexOf("MekCkRegistries.registerAll(bus);");
        int firstFamilyReg = ctor.indexOf(".register(bus);", all);
        assertTrue("构造器里一个家族注册器都没有挂到总线", firstFamilyReg > 0);
        assertTrue("registerAll 必须在家族注册器之前（触碰顺序是契约）", all < firstFamilyReg);
    }
}