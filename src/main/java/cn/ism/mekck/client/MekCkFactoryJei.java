package cn.ism.mekck.client;

import cn.ism.mekck.integration.jei.JEIPlugin;
import cn.ism.mekck.machine.MekCkFactoryType;
import cn.ism.mekck.machine.MekCkMachineTile;
import mekanism.client.jei.MekanismJEIRecipeType;
import mezz.jei.api.recipe.RecipeType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumMap;
import java.util.Map;

/**
 * 工厂进度条 → JEI 配方分类的桥。
 *
 * <h3>它解决什么</h3>
 * 上游 {@code GuiFactory.addProgress} 给每条进度条挂
 * {@code .jeiCategories(MekanismJEIRecipeType.SMELTING)} 之类的分类，
 * 于是 JEI 里<b>把鼠标移到进度条上就能弹出该机器能做的配方页</b>。
 * 本模组六个工厂屏此前一条都没挂，进度条在 JEI 眼里是块死图。
 *
 * <h3>为什么必须单独一个类，且只在 JEI 存在时加载</h3>
 * 本类引用 {@link JEIPlugin}，而 {@code JEIPlugin} 引用 {@code mezz.jei.*}。
 * JEI 在 {@code mods.toml} 里<b>不是依赖</b>（可选），所以没装 JEI 的客户端上
 * 一旦加载本类就会 {@code NoClassDefFoundError}。调用方
 * {@link MekCkFactoryScreenBase#jeiCategoriesOf} 用
 * {@code ModList.get().isLoaded("jei")} 守卫，JVM 只在守卫通过时才解析本类。
 *
 * <h3>为什么用 {@code MekanismJEIRecipeType} 而不是直接给 JEI 的 {@code RecipeType}</h3>
 * {@code GuiProgress.jeiCategories} 的参数类型就是 {@code MekanismJEIRecipeType}——
 * 这是 Mek 刻意的设计：那个类<b>不引用任何 JEI 类</b>（源码里写着
 * "Do not use any classes from JEI here as this is to allow us to safely keep JEI optional
 * while referencing from our GUIs"），所以 GUI 侧可以无条件引用它。
 * Mek 的 {@code GuiElementHandler} 在 JEI 存在时才把它转成真正的
 * {@code new RecipeType<>(uid, recipeClass)}。
 *
 * <h3>为什么必须从 {@link JEIPlugin} 的常量派生，不能另写字面量</h3>
 * JEI 的 {@code RecipeType.equals} <b>同时比较 UID 与配方类</b>。另写一个
 * {@code RecipeType.create("mekck", "grinding", Recipe.class)} 与分类注册用的那个
 * 不相等，点击进度条会落到一个永远没有分类的类型上。
 *
 * <h3>⚠️ 为什么必须由 {@link #init()} 在客户端初始化时提前建表</h3>
 * Mek 10.4.16 的 {@code MekanismJEIRecipeType} 规范构造器有一个<b>字段赋值次序地雷</b>。
 * 实测其字节码：
 * <pre>
 *   4: getstatic     allKnownTypes
 *   7: ifnonnull     27
 *  13: aload_0
 *  14: invokevirtual uid()          ← 读 this.lazyUid，此刻还是 null
 *  18: invokeinterface Map.put
 *  27: getstatic     allKnownTypes
 *  31: invokeinterface Set.add
 *  37: aload_0
 *  39: putfield      lazyUid          ← 字段赋值排在最后
 * </pre>
 * 也就是说：只要 {@code allKnownTypes == null}，构造就必 NPE。
 * Mek 自己的常量全部在 {@code MekanismJEIRecipeType.<clinit>} 里创建，那时
 * {@code allKnownTypes} 还是 {@code new HashSet<>()}（非 null），走 {@code else} 分支、
 * 不碰 {@code uid()}，所以 Mek 自己没事。而 {@code findType()} 会把它置 null ——
 * 那一步发生在 <b>JEI 的插件回调</b>里（{@code CatalystRegistryHelper} 注册催化剂时）。
 * 于是「JEI 启动之后再新建 {@code MekanismJEIRecipeType}」必然 NPE，
 * 表现为打开工厂 GUI 时整局崩溃（{@code NoClassDefFoundError: Could not initialize class
 * cn.ism.mekck.client.MekCkFactoryJei}）。
 *
 * <p>所以本类<b>不能懒加载</b>：表必须在 {@code FMLClientSetupEvent} 里就建好，
 * 那时 {@code allKnownTypes} 尚未被置空。{@link #init()} 就是那个触发点，
 * 由 {@code UniversalCuttingMachine.ClientEvents.onClientSetup} 在 JEI 存在时调用。</p>
 *
 * <p>{@link #of} 另有 try/catch 兜底：万一将来 Mek 改了实现、或某个别的模组提前
 * 触发了 {@code findType}，退化成「进度条没有 JEI 分类」并留一条日志，
 * 而不是把游戏打崩。</p>
 *
 * <h3>为什么分类数组要缓存成静态常量</h3>
 * {@code MekanismJEIRecipeType} 是 record，其 {@code lazyUid} 分量是
 * {@code Lazy} 实例，而 {@code Lazy} 不覆写 {@code equals} ⇒ 两次构造出的
 * 同 UID 对象<b>互不相等</b>。Mek 的 {@code MekanismJEI.recipeTypeInstanceCache}
 * 以它为键，每次开屏都新建实例会让那个静态 Map 无限增长。故此处只构造一次。
 */
public final class MekCkFactoryJei {

    private static final Logger LOGGER = LoggerFactory.getLogger("MekCK/jei");

    /** 无分类（未声明家族、或该家族没有对应 JEI 分类）。 */
    private static final MekanismJEIRecipeType<?>[] NONE = new MekanismJEIRecipeType<?>[0];

    /**
     * 家族 → 分类。取<b>该工厂的主配方类型</b>，与上游 {@code GuiFactory.addProgress}
     * 一条进度条挂一个分类的做法一致。
     *
     * <p>部分工厂实际能吃多种配方（烧烤工厂还吃原版烟熏炉/篝火，穿串工厂还吃
     * 烧烤乐事的穿串，研磨工厂还吃森罗物语石磨）。这里只挂主类型：多挂会让 JEI
     * 弹出混合列表，与上游「一条进度条 = 一个分类」的语义不符；那些次要类型
     * 已经通过 {@code JEIPlugin.registerRecipeCatalysts} 挂成催化剂，在 JEI 里
     * 查得到。</p>
     */
    private static final Map<MekCkFactoryType, MekanismJEIRecipeType<?>[]> BY_TYPE =
            new EnumMap<>(MekCkFactoryType.class);

    static {
        BY_TYPE.put(MekCkFactoryType.CUTTING, of(JEIPlugin.CUTTING_TYPE));
        BY_TYPE.put(MekCkFactoryType.COOKING, of(JEIPlugin.COOKING_TYPE));
        BY_TYPE.put(MekCkFactoryType.PLANTING_CUTTING, of(JEIPlugin.PLANT_CUT_TYPE));
        BY_TYPE.put(MekCkFactoryType.GRINDING, of(JEIPlugin.GRINDING_TYPE));
        BY_TYPE.put(MekCkFactoryType.GRILLING, of(JEIPlugin.MEKCK_GRILLING_TYPE));
        BY_TYPE.put(MekCkFactoryType.SKEWERING, of(JEIPlugin.MEKCK_SKEWERING_TYPE));
        BY_TYPE.put(MekCkFactoryType.ICE, of(JEIPlugin.ICE_MAKE_TYPE));
    }

    private MekCkFactoryJei() {
    }

    /**
     * 提前把分类表建出来 —— <b>必须在 {@code FMLClientSetupEvent} 里调用</b>，
     * 且调用方要先确认 JEI 已安装（本类引用 {@code mezz.jei.*}）。
     *
     * <p>方法体是空的：它的唯一作用是触发本类的 {@code <clinit>}。
     * 时机理由见类注释的「为什么必须由 init() 提前建表」。</p>
     */
    public static void init() {
        // 触发 <clinit>，表在 static 块里建好。
    }

    /** 该机器进度条应挂的 JEI 分类；无则返回空数组。 */
    static MekanismJEIRecipeType<?>[] categoriesOf(MekCkMachineTile tile) {
        if (tile == null) {
            return NONE;
        }
        MekCkFactoryType type = tile.getFactoryType();
        if (type == null) {
            return NONE;
        }
        MekanismJEIRecipeType<?>[] categories = BY_TYPE.get(type);
        return categories == null ? NONE : categories;
    }

    /**
     * 把 JEI 的 {@code RecipeType} 转成 Mek 的 {@code MekanismJEIRecipeType}
     * （UID 与配方类逐字照搬）。
     *
     * <p>失败时返回空数组并留一条日志，而不是把异常抛出去 —— 本方法在
     * {@code <clinit>} 里跑，抛出去会让整个类初始化失败，之后每次开工厂 GUI
     * 都是 {@code NoClassDefFoundError} 崩溃。失败原因见类注释。</p>
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static MekanismJEIRecipeType<?>[] of(RecipeType<?> type) {
        try {
            return new MekanismJEIRecipeType[]{
                    new MekanismJEIRecipeType(type.getUid(), (Class) type.getRecipeClass())};
        } catch (Throwable t) {
            LOGGER.warn("无法为配方类型 {} 建立 Mek 的 JEI 分类，该工厂的进度条在 JEI 里将不可点击：{}",
                    type.getUid(), t.toString());
            return NONE;
        }
    }
}
