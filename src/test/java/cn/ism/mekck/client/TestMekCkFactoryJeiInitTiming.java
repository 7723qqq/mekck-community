package cn.ism.mekck.client;

import cn.ism.mekck.TestSourceText;
import org.junit.Test;

import java.io.IOException;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * JEI 分类表的<b>建表时机</b>护栏。
 *
 * <h3>为什么需要它</h3>
 * Mek 10.4.16 的 {@code MekanismJEIRecipeType} 规范构造器有一个字段赋值次序地雷：
 * 紧凑构造器体在偏移 14 就调 {@code uid()}（读 {@code this.lazyUid}），
 * 而 {@code putfield lazyUid} 在偏移 39 —— <b>字段赋值排在最后</b>。
 * 它只在 {@code allKnownTypes == null} 时走这条路径，而 {@code findType()}
 * 正是把它置 null 的那一步，发生在 <b>JEI 的插件回调</b>（催化剂注册）里。
 *
 * <p>后果是实测过的：懒加载版本在打开烧烤工厂 GUI 时整局崩溃 ——</p>
 * <pre>
 *   NoClassDefFoundError: Could not initialize class cn.ism.mekck.client.MekCkFactoryJei
 *   Caused by: NullPointerException
 *     at mekanism.client.jei.MekanismJEIRecipeType.uid(MekanismJEIRecipeType.java:122)
 *     at mekanism.client.jei.MekanismJEIRecipeType.&lt;init&gt;(MekanismJEIRecipeType.java:115)
 *     at cn.ism.mekck.client.MekCkFactoryJei.of(MekCkFactoryJei.java:94)
 *     at cn.ism.mekck.client.MekCkFactoryJei.&lt;clinit&gt;(MekCkFactoryJei.java:66)
 * </pre>
 *
 * <p>修法是「在 {@code FMLClientSetupEvent} 里提前建表」——那时
 * {@code allKnownTypes} 还没被置空，构造器走 {@code else} 分支、不碰 {@code uid()}。
 * 这条不变量<b>没有任何运行时症状能提示它被破坏</b>（破坏了就是崩溃），
 * 所以只能用源码断言钉住。</p>
 */
public class TestMekCkFactoryJeiInitTiming {

    private static final String JEI_BRIDGE = "src/main/java/cn/ism/mekck/client/MekCkFactoryJei.java";
    /**
     * {@code MekCkFactoryJei.init()} 的调用点。
     *
     * <p>原先它写在主类的客户端事件内部类里；注册中枢拆分时那个内部类升格为
     * {@code cn.ism.mekck.client.ClientEvents}，所以这里的路径跟着搬了一次家。</p>
     */
    private static final String MOD = "src/main/java/cn/ism/mekck/client/ClientEvents.java";

    /** 桥必须有一个公开的 init() 入口，供客户端初始化时触发 {@code <clinit>}。 */
    @Test
    public void bridgeExposesAnInitEntryPoint() throws IOException {
        String source = TestSourceText.read(JEI_BRIDGE);
        assertTrue("必须有 public static void init()", source.contains("public static void init()"));
        assertTrue("类必须是 public（调用方在 cn.ism.mekck 包）", source.contains("public final class MekCkFactoryJei"));
    }

    /** 客户端初始化必须真的调它，且带 JEI 存在守卫。 */
    @Test
    public void clientSetupCallsInitBehindTheJeiGuard() throws IOException {
        String source = TestSourceText.read(MOD);
        int call = source.indexOf("MekCkFactoryJei.init()");
        assertTrue("FMLClientSetupEvent 里必须调 MekCkFactoryJei.init()", call >= 0);
        // 守卫必须紧邻在调用之前 —— 本类引用 mezz.jei.*，没装 JEI 时加载即 NoClassDefFoundError。
        String before = source.substring(Math.max(0, call - 400), call);
        assertTrue("调用点必须被 isLoaded(\"jei\") 守卫住",
                before.contains("isLoaded(\"jei\")"));
    }

    /**
     * 建表失败必须退化成「没有分类」而不是抛出去。
     *
     * <p>{@code of()} 在 {@code <clinit>} 里跑；异常抛出去会让整个类初始化失败，
     * 之后每次开工厂 GUI 都是 {@code NoClassDefFoundError} 崩溃。</p>
     */
    @Test
    public void constructionFailureDegradesInsteadOfCrashing() throws IOException {
        String source = TestSourceText.read(JEI_BRIDGE);
        int of = source.indexOf("private static MekanismJEIRecipeType<?>[] of(");
        assertTrue("必须有 of() 工厂方法", of >= 0);
        int end = source.indexOf("\n    }", of);
        String body = source.substring(of, end);
        assertTrue("构造必须包在 try 里", body.contains("try {"));
        assertTrue("失败必须 catch 住", body.contains("catch (Throwable"));
        assertTrue("失败必须返回空分类而不是 null", body.contains("return NONE;"));
    }

    /** 分类表必须是静态常量（只构造一次）—— 理由见类注释的 recipeTypeInstanceCache。 */
    @Test
    public void categoryTableIsBuiltOnce() throws IOException {
        String source = TestSourceText.read(JEI_BRIDGE);
        assertTrue("表必须是 static final 的 EnumMap",
                source.contains("private static final Map<MekCkFactoryType, MekanismJEIRecipeType<?>[]> BY_TYPE"));
        // 取数口只许查表，不许现场构造 —— 现场构造正是崩溃路径。
        String lookup = TestSourceText.methodBody(source,
                "static MekanismJEIRecipeType<?>[] categoriesOf(MekCkMachineTile tile) {");
        assertTrue("categoriesOf 必须存在", !lookup.isEmpty());
        assertFalse("categoriesOf 不得现场构造 MekanismJEIRecipeType",
                lookup.contains("new MekanismJEIRecipeType"));
    }
}
