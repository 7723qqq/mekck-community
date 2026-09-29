package cn.ism.mekck.client;

import cn.ism.mekck.UniversalCuttingMachine;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;

import java.lang.reflect.Method;

/**
 * GuideME 联动的客户端实现（仅在装 GuideME 时被 {@code GuideMECompat} 反射加载）。
 * <p>
 * 按键策略：GuideME 自带全局热键（默认 G，key.guideme.guide），长按打开手持物品对应
 * 指南页——因此本模组<b>不注册自己的按键</b>，只需：注册 mekck 指南书 + 页面 frontmatter
 * 声明 item_ids（GuideME 自动给这些物品加 tooltip 提示并支持长按打开）。
 * 这里仅保留书注册与按书 id 打开（供命令/未来指南物品使用）。
 * </p>
 */
public final class GuideMECompatImpl {
    private static final ResourceLocation GUIDE_ID = new ResourceLocation(UniversalCuttingMachine.MOD_ID, "guide");
    /** 本模组指南的内容目录：assets/mekck/mekckguide/ */
    private static final String GUIDE_FOLDER = "mekckguide";
    /**
     * 为其他模组撰写的指南（本模组只提供内容与注册；命名空间 {@code mekguide} 是**未来独立新 mod** 的命名空间，
     * 内容位于 {@code assets/mekguide/<folder>/}——将来拆 mod 时只需搬走目录与注册代码，正文零改动）。
     */
    private static final ResourceLocation MEK_GUIDE_ID = new ResourceLocation("mekguide", "mek");
    /** 通用机械：扩展（Mekanism Extras）指南，内容位于 assets/mekguide/meke/。 */
    private static final ResourceLocation MEKE_GUIDE_ID = new ResourceLocation("mekguide", "meke");
    /** 通用机械：更多机器（mekmm）指南（F13），内容位于 assets/mekguide/mekmm/。 */
    private static final ResourceLocation MEKMM_GUIDE_ID = new ResourceLocation("mekguide", "mekmm");

    private GuideMECompatImpl() {
    }

    /**
     * 客户端初始化：注册指南书（幂等）。
     * <p>
     * GuideME 20.1.15 的 GuideBuilder 构造器<b>已自动</b> {@code index(new ItemIndex())} 与
     * {@code index(new CategoryIndex())}（反编译确认），且 {@code register} 默认 true。
     * 因此这里不再手动调用 {@code index(...)}——旧实现用错误的反射签名
     * {@code getMethod("index", PageIndex.class)}（把 PageIndex 的 Class 对象当成单参类型匹配，
     * 但真实重载是 {@code index(PageIndex)} 或 {@code index(Class, T)}），触发
     * {@code NoSuchMethodException} 被外层 catch 吞掉，书籍注册流程提前中断。
     * 现只需（可选）显式 register(true) 后 build() 即可完成注册，ItemIndex 索引默认建立。
     * </p>
     */
    public static void registerGuidebook() {
        registerOne(GUIDE_ID, GUIDE_FOLDER);
        registerOne(MEK_GUIDE_ID, "mek");
        registerOne(MEKE_GUIDE_ID, "meke");
        registerOne(MEKMM_GUIDE_ID, "mekmm");
    }

    /** 注册一本指南（反射，未装 GuideME 时静默）。 */
    private static void registerOne(ResourceLocation guideId, String folderName) {
        try {
            Class<?> guideBuilderClass = Class.forName("guideme.GuideBuilder");
            Class<?> guideClass = Class.forName("guideme.Guide");
            Method builder = guideClass.getMethod("builder", ResourceLocation.class);
            Object b = builder.invoke(null, guideId);
            Method folder = guideBuilderClass.getMethod("folder", String.class);
            Object b2 = folder.invoke(b, folderName);
            // ⚠️ 刻意**不调用** defaultLanguage(...)：
            //   AE2 的可用范例只写 builder(id).folder(x).extension(...).build()，没有 defaultLanguage。
            //   2026-09-15 实测：带 defaultLanguage("zh_cn") 时注册成功但**页面全部加载不出来**
            //   （打开时提示 Page 'mekguide:index.md' could not be found），去掉后再测。
            Object b3 = b2;
            // register 默认 true，但显式调用以确保行为明确；不重复注册索引（构造器已默认加 ItemIndex/CategoryIndex）
            Method register = guideBuilderClass.getMethod("register", boolean.class);
            Object b4 = register.invoke(b3, true);
            Method build = guideBuilderClass.getMethod("build");
            build.invoke(b4);
            // 成功路径也留痕：排查"指南打不开"时，日志里能直接看到本模组的注册结论
            com.mojang.logging.LogUtils.getLogger().info(
                    "[mekck] GuideME guidebook registered: {} (folder={})", guideId, folderName);
        } catch (Throwable t) {
            // 明确、有限的诊断日志（不静默吞掉）：注册失败时提示用户书籍未注册
            com.mojang.logging.LogUtils.getLogger().error(
                    "[mekck] GuideME registerGuidebook failed for {}: {} (guidebook NOT registered)",
                    guideId, t.toString());
        }
    }

    /** 打开 mekck 指南（书 id：mekck:guide，GuideME 会打开 startPage index.md）。 */
    public static void openGuide() {
        openGuide(GUIDE_ID);
    }

    /**
     * 按书 id 打开指南 —— <b>本类的存在意义就在这里</b>。
     *
     * <p>⚠️ <b>为什么不能在别处反射 {@code Minecraft}：</b>生产环境的 Minecraft 类用 <b>SRG 名</b>
     * （{@code Minecraft.getInstance()} 实际叫 {@code m_91087_}）。我们自己的代码调用会在 reobf 时被转换，
     * 但<b>反射里的字符串不会</b> ⇒ {@code getMethod("getInstance")} 必抛 {@code NoSuchMethodException}。</p>
     *
     * <p>所以此处<b>直接引用</b> {@code Minecraft}（本类只在客户端经 Class.forName 加载，服务端永不触碰）。</p>
     */
    /**
     * 诊断：把 GuideME 实际加载到的**指南清单与各自的页数**打进日志。
     *
     * <p>为什么需要它：GuideME 的 {@code GuideReloadListener.apply()} 里是
     * {@code guide.setPages(map.getOrDefault(guide.getId(), Map.of()))} ——
     * <b>页面加载不出来时会静默塞一个空 map，一条日志都不打</b>，
     * 表现为「指南能打开但提示 Page 'xxx:index.md' could not be found」。</p>
     *
     * <p>反射的 {@code guideme.internal.GuideRegistry} 是 GuideME 自己的类（不会被 reobf 改名），安全。</p>
     */
    public static void logDiagnostics() {
        var log = com.mojang.logging.LogUtils.getLogger();
        try {
            Class<?> reg = Class.forName("guideme.internal.GuideRegistry");
            Object all = reg.getMethod("getAll").invoke(null);
            if (!(all instanceof java.util.Collection<?> guides)) {
                log.warn("[mekck] guide diagnostics: GuideRegistry.getAll() -> {}", all);
                return;
            }
            log.info("[mekck] === GuideME 诊断：已注册 {} 本指南 ===", guides.size());
            for (Object g : guides) {
                Class<?> gc = g.getClass();
                Object id = gc.getMethod("getId").invoke(g);
                Object folder = gc.getMethod("getContentRootFolder").invoke(g);
                Object pages = gc.getMethod("getPages").invoke(g);
                int n = pages instanceof java.util.Collection<?> pc ? pc.size() : -1;
                log.info("[mekck]   - {} (folder={}) => {} 页", id, folder, n);
                // 页数异常时把前几个真实 pageId 打出来，直接看 id 对不对
                if (pages instanceof java.util.Collection<?> pc && !pc.isEmpty()) {
                    int shown = 0;
                    for (Object page : pc) {
                        try {
                            Object pid = page.getClass().getMethod("pageId").invoke(page);
                            log.info("[mekck]       · {}", pid);
                        } catch (Throwable ignore) {
                            log.info("[mekck]       · {}", page);
                        }
                        if (++shown >= 5) break;
                    }
                    if (pc.size() > shown) log.info("[mekck]       · …（共 {} 页）", pc.size());
                }
                if (n == 0) {
                    log.warn("[mekck]   ⚠ 该书 0 页 —— 页面目录/后缀没被 GuideME 扫到（folder 与 assets 路径必须对得上）");
                }
            }
            log.info("[mekck] === 诊断结束（页数 0 = 页面没加载出来，即本次问题的根因）===");
        } catch (Throwable t) {
            log.error("[mekck] guide diagnostics failed", t);
        }
    }

    public static void openGuide(ResourceLocation guideId) {
        logDiagnostics();
        try {
            Class<?> guidesCommon = Class.forName("guideme.GuidesCommon");
            Method m = guidesCommon.getMethod("openGuide",
                    net.minecraft.world.entity.player.Player.class, ResourceLocation.class);
            com.mojang.logging.LogUtils.getLogger().info("[mekck] GuideME openGuide -> {}", guideId);
            m.invoke(null, Minecraft.getInstance().player, guideId);
        } catch (Throwable t) {
            com.mojang.logging.LogUtils.getLogger().error("[mekck] GuideME openGuide({}) failed", guideId, t);
        }
    }
}
