package cn.ism.mekck.lang;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Test;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 客户端 i18n 的<b>全域回归护栏</b>：{@code src/main/java} 下不许再有玩家可见的硬编码中文。
 *
 * <p>第 6 轮把扫描面从 {@code client/} 扩到<b>主源码全目录</b>：证据显示
 * {@code item/}、{@code blockentity/} 等包仍成片残留玩家可见中文（工具提示、
 * 订单提示），而这一版判据此前只扫 {@code client/}，{@code MAIN_SRC_DIR} 那条
 * 只是「判据不空转」的计数探针，管不住它们。扩面后新扫出的存量按
 * {@link #KNOWN_MIGRATION_DEBT} 登记为债，债清单由反向断言守着（见断言列表）。</p>
 *
 * <h3>这一轮做完了什么</h3>
 * 前一版只守住「已迁走的 45 处」（一个字面量清单 + 一个 10 文件白名单），
 * 类注释里明说「剩下 98 处一次全迁是独立的一轮工作」。这一轮就是那一轮：
 * 余下 85 处 UI 文案全部迁到 {@code gui.mekck.ui.*}，判据随之从
 * <b>清单式</b>升级成 <b>全域扫描式</b>，不再靠豁免名单维持。
 *
 * <h3>为什么判据必须是「扫」而不是「列」</h3>
 * 列清单的测试只会守住清单本身：谁在别处新写一句 {@code "半径:"} 它一声不吭。
 * 更糟的是清单会自我繁殖 —— 新的硬编码要么被加进清单（测试变长且越来越像摆设），
 * 要么被塞进白名单文件（变成盲区）。所以这里改成：<b>扫全目录，只对诊断文本开洞</b>。
 *
 * <h3>怎么区分 UI 文案与诊断文本（这是判据的关键）</h3>
 * 玩家<b>读不到</b>日志，所以日志里的中文根本不该 i18n。区分靠<b>数据流</b>而不是文本形状：
 * <ol>
 *   <li>本仓的 logger 写法是「先绑定变量、再调用」：
 *       {@code private static final Logger LOGGER = LoggerFactory.getLogger(...)}、
 *       {@code var log = com.mojang.logging.LogUtils.getLogger()}。
 *       判据先用 {@link #loggerBindings} 把这些<b>变量名</b>认出来（不写死任何名字，
 *       加一个新的 {@code XXX_LOGGER} 不需要改这里）。</li>
 *   <li>再判断字面量所在<b>语句</b>是否是 {@code <logger 变量>.<日志方法>(...)} 调用。
 *       是 ⇒ 诊断文本，放行；否 ⇒ 玩家能看见，判违规。</li>
 *   <li>唯一剩下的例外是<b>只写日志的私有辅助方法</b>（见 {@link #LOG_ONLY_SINKS}）。
 *       它是白名单，但由 {@link #diagnosticExemptionsStaySmall()} 设了闸 ——
 *       超过 4 项直接红，逼迫后来者改进判据而不是往清单里加。</li>
 * </ol>
 *
 * <p>这条区分是本文件存在的理由：没有它，要么把 11 条日志诊断也 i18n 成噪音，
 * 要么得到一份随改动不断变长的豁免清单（正是要避免的东西）。</p>
 *
 * <h3>五条断言各防什么</h3>
 * <ol>
 *   <li><b>{@link #noHardcodedCjkInPlayerVisibleText}</b> —— 主断言：回流即红
 *       （债清单里的文件除外）。</li>
 *   <li><b>{@link #diagnosticExemptionsStaySmall}</b> —— 防豁免清单腐化。</li>
 *   <li><b>{@link #newKeysExistInBothLanguagesAndAreReferenced}</b> ——
 *       防「只加进 zh_cn」导致英文客户端整片看到 raw key（本仓已因此栽过三次，
 *       见 {@link TestLangKeyParity}），以及「加了键却没换干净」。</li>
 *   <li><b>{@link #everyKnownMigrationDebtStillHasOffenders}</b> ——
 *       债清单的反向断言：每条必须真的还在违规，防清单变谎话。</li>
 *   <li><b>{@link #theDetectorStillFlagsASyntheticSample}</b> + <b>{@link #theDetectionPatternsStillMatchSomething}</b>
 *       —— 判据自身的生命周期自检，防止正则/词法器某天失配后本文件<b>永远全绿</b>。</li>
 * </ol>
 *
 * <h3>main 侧为什么需要扩充「非玩家可见 sink」判据</h3>
 * 扩到主源码后，扫出的字面量里有大量<b>玩家根本读不到</b>的文本。沿用 client/
 * 时代那条「所属语句里有没有 logger 调用」够不到它们，于是补齐三类依据，
 * 每条都在 {@link #isDiagnostic} 里写明类别（不缩小扫描面、也不加文件名白名单）：
 * <ol>
 *   <li><b>Forge 配置注释</b>（{@code BUILDER.comment("…")}）—— 只落进
 *       {@code .toml} 注释，不进任何界面；</li>
 *   <li><b>异常消息</b>（{@code throw new …("…")}）—— 只进堆栈/开发者日志；</li>
 *   <li><b>测试断言消息</b>（{@code assertXxx(cond, "…")}）—— dev 专用，玩家读不到。</li>
 * </ol>
 * 这三类之外仍有存量玩家可见文案（网络回传的提示、方块升级消息等），
 * 它们落在 {@link #KNOWN_MIGRATION_DEBT} 而不是被白名单藏起来。
 *
 * <h3>为什么自己写词法扫描而不用正则</h3>
 * 正则版（剥注释 + 匹配字面量）分不清 {@code LOGGER.warn("…{}…")} 里的 {@code {}}
 * 是日志占位符还是代码块边界：一旦把字符串<b>剥掉</b>再找语句边界，位置就全错位了。
 * {@link Lexer} 逐字符走一遍（字符串/字符/行注释/块注释都认），同时给出
 * 「字面量内容」与「所属语句的文本区间」，让诊断判定有准确的上下文。
 * 只有 60 行，换来的是判据可复核。
 */
public class TestNoHardcodedUiText {

    /** 扫描面：主源码全目录（第 6 轮从 {@code client/} 扩到全域）。 */
    private static final Path MAIN_SRC_DIR = Path.of("src/main/java");
    private static final Path LANG_DIR = Path.of("src/main/resources/assets/mekck/lang");

    /** 豁免项上限。超过就说明该改判据了（见类注释第 3 条）。 */
    private static final int MAX_LOG_ONLY_SINKS = 4;

    /**
     * 「只写日志」的私有辅助方法名 —— 传给它们的 CJK 参数玩家看不到，不该 i18n。
     *
     * <p>本仓只有 {@code ObjMeshLoader.fail(obj, reason, error)} 一个：它的方法体里
     * 只有 {@code LOGGER.error(...)}，但字面量与 logger 调用隔了一层，
     * 「语句里有没有 logger 调用」这条判据看不到，得显式登记。</p>
     *
     * <p>必须<b>极短</b>。每多一项就多一个「把 UI 文案塞进日志方法」的借口；
     * {@link #diagnosticExemptionsStaySmall()} 会守住这个预算。</p>
     */
    private static final List<String> LOG_ONLY_SINKS = List.of("fail(");

    /**
     * 存量债清单 —— 扩面到主源码后新扫出、但<b>本轮未迁</b>的玩家可见文案文件。
     *
     * <p>为什么用清单而不是加白名单判据：这些文件里的中文<b>确实是玩家可见的</b>
     * （方块升级提示、AE2 拉料/补料回执、订单预览文本、方块说明等），
     * 只是它们的迁移要么需要打通「服务器字符串 → 客户端 Component」（跨
     * {@code network/} + {@code client/}，超出本轮改动的文件集），要么属于
     * 其它任务的域。把判据放宽成「一遇到这些文件就放过」会让新文案继续漏进来；
     * 换成这份清单后，<b>只有清单内允许有存量，清单外一律要求 0</b>，
     * 且 {@link #everyKnownMigrationDebtStillHasOffenders} 断言「每条都真的还在违规」，
     * 迁完不清单会立刻报陈旧 —— 债只减不增。</p>
     *
     * <p>路径相对 {@link #MAIN_SRC_DIR}（即 {@code cn/ism/mekck/...}）。</p>
     */
    private static final List<String> KNOWN_MIGRATION_DEBT = List.of(
            // —— 玩家可见，硬编码在服务器侧：字符串经网络原样回传后由客户端 drawString 画出，
            //    简单地换成语言键会退化成显示 raw key，必须先改消息传输为 Component ——
            "cn/ism/mekck/blockentity/CentralKitchenBlockEntity.java",   // placeOrder/previewOrder 回执、预留失败原因
            "cn/ism/mekck/kitchen/KitchenCraftingPlan.java",             // 合成链求解失败原因（同上经 placeOrder 回传）
            "cn/ism/mekck/ae2/MekckAe2.java",                            // ME 拉料/补料回执与开关状态文本
            "cn/ism/mekck/compat/AE2Compat.java",                        // 未装 AE2 的回执
            "cn/ism/mekck/network/KitchenOrderPacket.java",              // 下单成功前缀（本文件不在本轮文件集内）
            "cn/ism/mekck/blockentity/SimpleMachineBlockEntity.java",    // 果汁/流体拒收 note
            "cn/ism/mekck/blockentity/SimpleMachineRecipes.java",        // 果汁/流体 intake note
            // —— 玩家可见，但属其它任务的域/文件（本轮文件集只含 MekCkBlockItem）——
            "cn/ism/mekck/item/BioreactorBlockItem.java",                // 与 MekCkBlockItem 同型「是/否」，同理可迁
            "cn/ism/mekck/block/ChocolateCannonBlock.java",
            //    电力研磨机方块已在阶段 3 样板迁移中移出本清单：迁到 Mek 的 BlockTile 后
            //      它不再覆写 use()（右键开界面交给 AttributeGui），那两条硬编码中文
            //      「已安装升级 / 无法安装升级」随之消失。护栏的陈旧条目自检正是为此存在。
            "cn/ism/mekck/block/IceFactoryBlock.java",
            "cn/ism/mekck/block/IceMakerBlock.java",
            "cn/ism/mekck/block/NutRoasterBlock.java",
            "cn/ism/mekck/block/PlantingCuttingStationBlock.java",
            "cn/ism/mekck/block/SimpleMachineBlock.java",
            "cn/ism/mekck/block/SkeweringMachineBlock.java",
            "cn/ism/mekck/block/SmartCookingPotBlock.java",              // 9 个方块的「已安装/无法安装升级」提示（可直接改 Component.translatable）
            "cn/ism/mekck/upgrade/UpgradeInstallHandler.java",           // 同上 + ME 终端下单开关文本
            "cn/ism/mekck/integration/jei/BioreactorRecipeCategory.java",// JEI 分类内的说明文本
            "cn/ism/mekck/command/PlantingRecipeGenerator.java",         // 操作员命令的调试输出（发到聊天栏）
            // —— 判据尚未覆盖的非玩家可见存量（本轮只给「注释/异常/断言」三类开口）——
            "cn/ism/mekck/config/MekckConfig.java",                      // tierCnName() 的档位中文名，仅供 .comment() 拼注释
            "cn/ism/mekck/registry/MekCkFactories.java"                  // findFactoryTile 的标签，只进异常消息
    );

    /**
     * logger 绑定：找出所有「被赋值为 {@code getLogger(...)}」的标识符。
     *
     * <p>实现是<b>锚点法</b>，而不是 {@code 赋值者\s*=\s*.*getLogger(}：
     * 逐个找到 {@code getLogger(}，再回退到它前面<b>最近的 {@code =}</b>，
     * 取那个 {@code =} 紧邻的标识符。这样写是因为朴素写法实测踩了两个坑：</p>
     * <ul>
     *   <li><b>注释</b>：{@code [^;]*?} 会跨行，于是 javadoc 里一句
     *       {@code {@code allKnownTypes == null}} 配上文提到的某个 {@code getLogger}
     *       就能凑出假绑定（{@code MekCkFactoryJei} 曾被认成 {@code allKnownTypes}）。</li>
     *   <li><b>非重叠匹配</b>：正则 {@code finditer} 不重叠，注解里的
     *       {@code @Mod.EventBusSubscriber(modid = ...)} 与紧跟其后的字段之间<b>没有分号</b>，
     *       于是 {@code modid =} 那个起点先匹配成功并把整段（<b>包括真正的
     *       {@code LOGGER =} </b>）吃掉，真正的 logger 变量反而不再被扫到
     *       （{@code ObjMeshLoader} 曾因此漏认）。</li>
     * </ul>
     * <p>调用方必须传<b>去注释后</b>的文本（见 {@link Lexer#codeText()}）。
     * 三个 logger 变量（{@code LOGGER} / {@code PREVIEW_LOGGER} / 局部 {@code log}）
     * 因此都被自动认出，将来新增一个也不必改这里。</p>
     */
    private static Set<String> loggerBindings(String codeText) {
        Set<String> names = new LinkedHashSet<>();
        Matcher calls = GET_LOGGER.matcher(codeText);
        while (calls.find()) {
            int at = calls.start();
            int assign = codeText.lastIndexOf('=', at);
            if (assign < 0 || assign < lastOf(codeText, ";{}".toCharArray(), at)) {
                continue;
            }
            int end = assign;
            while (end > 0 && Character.isWhitespace(codeText.charAt(end - 1))) {
                end--;
            }
            int start = end;
            while (start > 0 && Character.isJavaIdentifierPart(codeText.charAt(start - 1))) {
                start--;
            }
            if (start < end) {
                names.add(codeText.substring(start, end));
            }
        }
        return names;
    }

    private static int lastOf(String s, char[] chars, int before) {
        int best = -1;
        for (char c : chars) {
            best = Math.max(best, s.lastIndexOf(c, before));
        }
        return best;
    }

    /** {@code getLogger(} 调用点（logger 绑定的锚）。 */
    private static final Pattern GET_LOGGER = Pattern.compile("getLogger\\s*\\(");

    /** slf4j 的五个日志方法。 */
    private static final Pattern LOG_METHOD_CALL = Pattern.compile(
            "\\b(\\w+)\\s*\\.\\s*(trace|debug|info|warn|error)\\s*\\(");

    /**
     * 大写常量式 logger 名（{@code LOGGER} / {@code KITCHEN_LOG} / {@code SPLASH_LOG} …）。
     *
     * <p>补这条是因为跨类静态引用 logger 时 {@link #loggerBindings} 认不到：
     * {@code CreativeUpgradeFoodRotator.LOGGER.error("…")} 里 {@code LOGGER} 是<b>别的类</b>
     * 的字段，本文件没有对应的 {@code = getLogger(...)} 绑定。判据取「全大写且含 LOG」
     * 这一形态而非写死名字，避免把 UI 文案登记成豁免。</p>
     */
    private static final Pattern LOGGER_NAME = Pattern.compile("[A-Z_]*LOG[A-Z_]*$|Logger$");

    /**
     * {@code LogUtils.getLogger().info(...)} / {@code LoggerFactory.getLogger("x").warn(...)}
     * 这类没有中间变量的内联写法。
     */
    private static final Pattern LOGUTILS_LOG_CALL = Pattern.compile(
            "(?:LogUtils|LoggerFactory)\\s*\\.\\s*getLogger\\s*\\([^)]*\\)\\s*\\."
                    + "\\s*(trace|debug|info|warn|error)\\s*\\(");

    /** 异常构造（异常消息只进堆栈/开发者日志，不进任何界面）。 */
    private static final Pattern THROW_NEW = Pattern.compile("\\bthrow new\\b");

    /** 测试断言消息（dev 专用，玩家读不到）。 */
    private static final Pattern ASSERT_CALL = Pattern.compile("\\bassert[A-Z]\\w*\\s*\\(");

    /** CJK 统一表意文字 + 全角标点。 */
    private static final Pattern CJK = Pattern.compile(
            "[\\u4e00-\\u9fff\\u3000-\\u303f\\uff00-\\uffef]");

    /**
     * 本轮（第五轮）新增的 45 个语言键。
     *
     * <p>命名规则：{@code gui.mekck.ui.} + 语义簇，簇内用 {@code .} 分层。
     * 跨文件复用的短词（敌对/全部/动物、开/关、六个面名、四个面模式名、当前订单、
     * 温度）都落在<b>同一个</b>共享键上，由 2~4 个界面共同引用，
     * 而不是每个界面各造一份近义键。</p>
     */
    private static final List<String> NEW_KEYS = List.of(
            // 通用开关词（4 个界面共用）
            "gui.mekck.ui.on", "gui.mekck.ui.off",
            // 通用读数（4 个界面共用）
            "gui.mekck.ui.temperature", "gui.mekck.ui.current_order",
            "gui.mekck.ui.search_placeholder", "gui.mekck.ui.temp_control",
            // 目标类型（4 个界面共用）
            "gui.mekck.ui.target_type.hostile", "gui.mekck.ui.target_type.all",
            "gui.mekck.ui.target_type.animal",
            // 面配置模式（侧配窗口 + 覆盖层共用）
            "gui.mekck.ui.side_mode.none", "gui.mekck.ui.side_mode.pull_input",
            "gui.mekck.ui.side_mode.pull_storage", "gui.mekck.ui.side_mode.push_output",
            "gui.mekck.ui.side_mode.output",
            // 六个面（侧配窗口 + 覆盖层共用同一批）
            "gui.mekck.ui.side.front", "gui.mekck.ui.side.back", "gui.mekck.ui.side.top",
            "gui.mekck.ui.side.bottom", "gui.mekck.ui.side.left", "gui.mekck.ui.side.right",
            "gui.mekck.ui.side.slot",
            // 中央厨房过滤器
            "gui.mekck.ui.filter_mode.whitelist", "gui.mekck.ui.filter_mode.blacklist",
            "gui.mekck.ui.filter.hint_off", "gui.mekck.ui.filter.hint_whitelist",
            "gui.mekck.ui.filter.hint_blacklist",
            // 中央厨房模块条
            "gui.mekck.ui.auto", "gui.mekck.ui.module_installed",
            "gui.mekck.ui.module_not_installed", "gui.mekck.ui.module_power",
            // 订单窗口分页
            "gui.mekck.ui.order_status", "gui.mekck.ui.prev_page", "gui.mekck.ui.next_page",
            "gui.mekck.ui.preview",
            // 三明治组装机
            "gui.mekck.ui.side_config_short",
            "gui.mekck.ui.sandwich_mode.copy", "gui.mekck.ui.sandwich_mode.custom",
            "gui.mekck.ui.sandwich_mode.sequenced",
            "gui.mekck.ui.sandwich_count.copy", "gui.mekck.ui.sandwich_count.sequenced",
            "gui.mekck.ui.sandwich_count.remaining",
            // 种植切配台
            "gui.mekck.ui.growth_status.missing", "gui.mekck.ui.growth_status.tier_low",
            "gui.mekck.ui.growth_need", "gui.mekck.ui.growth_need_tier_low");

    /**
     * 第 6 轮为 {@code item/}、{@code blockentity/} 迁出文案新增的语言键。
     *
     * <p>命名沿用既有风格：共享开关词落 {@code gui.mekck.ui.*}，方块工具提示落
     * {@code tooltip.mekck.*}，中央厨房订单态落 {@code gui.mekck.kitchen.*}。
     * {@code tooltip.mekck.linked_mod.<modid>} 一批由代码<b>拼接</b>引用
     * （{@code "tooltip.mekck.linked_mod." + modId}），{@link #isReferenced} 认前缀拼法，
     * 不会误判成「加了键没引用」。</p>
     */
    private static final List<String> ROUND6_KEYS = List.of(
            // 通用是/否（tooltip 与方块物品共用）
            "gui.mekck.ui.yes", "gui.mekck.ui.no",
            // 中央厨房订单态与摘要
            "gui.mekck.kitchen.note.module_removed", "gui.mekck.kitchen.note.waiting_intermediate",
            "gui.mekck.kitchen.note.missing_fluid", "gui.mekck.kitchen.note.waiting_thread",
            "gui.mekck.kitchen.note.no_power",
            // 联动机器提示（模组名 + 前缀/分隔符）
            "tooltip.mekck.linked_mod.prefix", "tooltip.mekck.linked_mod.separator",
            "tooltip.mekck.linked_mod.youkaishomecoming", "tooltip.mekck.linked_mod.kaleidoscope_cookery",
            "tooltip.mekck.linked_mod.bakeries", "tooltip.mekck.linked_mod.trailandtales",
            "tooltip.mekck.linked_mod.meadow", "tooltip.mekck.linked_mod.farm_and_charm",
            "tooltip.mekck.linked_mod.brewery", "tooltip.mekck.linked_mod.drinkbeer",
            "tooltip.mekck.linked_mod.vinery", "tooltip.mekck.linked_mod.kaleidoscope_tavern",
            "tooltip.mekck.linked_mod.bakery", "tooltip.mekck.linked_mod.herbalbrews",
            "tooltip.mekck.linked_mod.simplytea",
            // 单机依赖/特殊提示
            "tooltip.mekck.needs_mod.barbequesdelight", "tooltip.mekck.needs_mod.someassemblyrequired",
            "tooltip.mekck.needs_mod.mekmm", "tooltip.mekck.needs_mekanism_extras",
            "tooltip.mekck.needs_avaritia", "tooltip.mekck.nutrient_reduction",
            "tooltip.mekck.sandwich_assembler.stacking_warning",
            "tooltip.mekck.central_kitchen.manual_only", "tooltip.mekck.central_kitchen.auto_expand",
            "tooltip.mekck.central_kitchen.dual_temp");

    // ── 1. 主断言：全域扫主源码，玩家可见文案不许有 CJK ────────────────

    @Test
    public void noHardcodedCjkInPlayerVisibleText() throws IOException {
        assertTrue("找不到主源码目录（测试需在仓库根目录运行）", Files.isDirectory(MAIN_SRC_DIR));

        List<String> offenders = new ArrayList<>();
        for (Path file : javaFilesUnder(MAIN_SRC_DIR)) {
            String rel = MAIN_SRC_DIR.relativize(file).toString().replace('\\', '/');
            // 债清单内的文件允许有存量，其「仍在违规」由反向断言单独守；清单外一律要求 0。
            if (KNOWN_MIGRATION_DEBT.contains(rel)) {
                continue;
            }
            for (String line : findOffenders(file)) {
                offenders.add(rel + ":" + line);
            }
        }
        assertEquals("主源码里这些 CJK 字面量是玩家可见的 UI 文案，应改用 gui.mekck.*/tooltip.mekck.* 语言键"
                        + "（日志诊断、Forge 配置注释、异常/断言消息不在此列，见 isDiagnostic）：\n  "
                        + String.join("\n  ", offenders),
                List.of(), offenders);
    }

    /**
     * 扫一个文件里所有「玩家可见」的 CJK 字面量，返回 {@code 行号 "文本" -> 语句} 形式的清单。
     *
     * <p>判据与主断言完全同源：先跑词法器再取 {@code codeText()}（commentRanges 是在遍历中攒出来的），
     * 用 {@link #loggerBindings} 认出 logger、再用 {@link #isDiagnostic} 排除非玩家可见 sink。</p>
     */
    private static List<String> findOffenders(Path file) throws IOException {
        String code = Files.readString(file, StandardCharsets.UTF_8);
        Lexer lexer = new Lexer(code);
        Set<String> loggers = loggerBindings(lexer.codeText());
        List<String> out = new ArrayList<>();
        for (Literal lit : lexer.stringLiterals()) {
            if (!CJK.matcher(lit.text).find()) {
                continue;
            }
            String statement = lexer.statementOf(lit);
            if (isDiagnostic(statement, loggers)) {
                continue;
            }
            out.add(lit.line + "  \"" + lit.text + "\"  ->  " + statement);
        }
        return out;
    }

    // ── 2. 防豁免清单腐化 ──────────────────────────────────────────────

    /**
     * 「只写日志的辅助方法」豁免项不得超过 {@link #MAX_LOG_ONLY_SINKS} 条。
     *
     * <p>不设这条的话，{@link #LOG_ONLY_SINKS} 会变成第二个字面量清单：
     * 每有人抱怨「这句该迁」，最省事的就是往里加一项。预算卡死之后，
     * 真的需要更多豁免时，正确的动作是<b>改进判据</b>
     * （比如识别「private 方法体里只有 logger 调用 ⇒ 整方法只写日志」），
     * 而不是让清单长下去。</p>
     */
    @Test
    public void diagnosticExemptionsStaySmall() {
        assertTrue("LOG_ONLY_SINKS 已膨胀到 " + LOG_ONLY_SINKS.size() + " 项（上限 " + MAX_LOG_ONLY_SINKS
                        + "）：判据需要改得更准，而不是把 UI 文案登记成豁免项",
                LOG_ONLY_SINKS.size() <= MAX_LOG_ONLY_SINKS);
    }

    /**
     * 债清单反向断言：{@link #KNOWN_MIGRATION_DEBT} 里<b>每一条都必须真的还在违规</b>。
     *
     * <p>参照 {@code TestNoClientSymbolsInCommonCode.KNOWN_VIOLATIONS} 的做法：
     * 它不检查「清单为空」，而检查「清单里的每一条都真的还存在」—— 有人把某个文件的
     * 文案迁干净却忘了从清单删条目时，清单会骗你说「还有债」从而掩盖新违规。
     * 债只减不增，靠的就是这条。</p>
     */
    @Test
    public void everyKnownMigrationDebtStillHasOffenders() throws IOException {
        List<String> stale = new ArrayList<>();
        for (String rel : KNOWN_MIGRATION_DEBT) {
            Path file = MAIN_SRC_DIR.resolve(rel);
            if (!Files.isRegularFile(file)) {
                stale.add(rel + "  →  文件已不存在，请从 KNOWN_MIGRATION_DEBT 里删掉");
                continue;
            }
            if (findOffenders(file).isEmpty()) {
                stale.add(rel + "  →  已不再有玩家可见 CJK，请从 KNOWN_MIGRATION_DEBT 里删掉");
            }
        }
        assertTrue("KNOWN_MIGRATION_DEBT 里有陈旧条目（清单会骗人，掩盖新违规）：\n  "
                + String.join("\n  ", stale), stale.isEmpty());
    }

    // ── 3. 新键在两份语言里都存在，且真的被引用 ─────────────────────────

    @Test
    public void newKeysExistInBothLanguagesAndAreReferenced() throws IOException {
        Set<String> en = keysOf(LANG_DIR.resolve("en_us.json"));
        Set<String> zh = keysOf(LANG_DIR.resolve("zh_cn.json"));

        List<String> checked = new ArrayList<>(NEW_KEYS);
        checked.addAll(ROUND6_KEYS);

        Set<String> missing = new TreeSet<>();
        for (String key : checked) {
            if (!en.contains(key)) missing.add("en_us 缺 " + key);
            if (!zh.contains(key)) missing.add("zh_cn 缺 " + key);
        }
        assertEquals("本轮新增的语言键有缺失：\n  " + String.join("\n  ", missing),
                Set.of(), missing);

        String allMain = readAllMainCode();
        Set<String> unreferenced = new TreeSet<>();
        for (String key : checked) {
            if (!isReferenced(key, allMain)) {
                unreferenced.add(key);
            }
        }
        assertEquals("这些新语言键没有任何代码引用（加了键却没换干净）：\n  "
                        + String.join("\n  ", unreferenced),
                Set.of(), unreferenced);
    }

    // ── 4. 判据自身的生命周期自检 ──────────────────────────────────────

    /**
     * 把一段<b>人工构造</b>的样本喂进判据，确认它仍能同时做到
     * 「抓住 UI 文案」与「放过日志诊断」。
     *
     * <p>为什么不能只靠 {@link #theDetectionPatternsStillMatchSomething}：
     * 那条量的是全仓残留量，client/ 清零后它就<b>与本测试完全无关</b>了 ——
     * 判据哪天整体失配（词法器写错、CJK 正则写窄），主断言会变成永远全绿，
     * 而它看起来在守着 85 处刚清掉的文案。喂合成样本不依赖仓库现状，
     * 因此是这层防线里唯一不会随治理推进而失效的一条。</p>
     */
    @Test
    public void theDetectorStillFlagsASyntheticSample() {
        String sample = """
                class Sample {
                    private static final Logger LOGGER = LoggerFactory.getLogger("x");
                    void draw() {
                        String a = "半径:";                 // UI：必须被抓
                        String b = "\\u534A\\u5F84:";        // UI（unicode 转义伪装）：必须被抓
                        LOGGER.warn("半径: 诊断文本");        // 日志：必须放过
                        fail(obj, "读取或解析失败", e);        // 只写日志的辅助：必须放过
                    }
                }
                """;
        Lexer lexer = new Lexer(sample);
        Set<String> loggers = loggerBindings(lexer.codeText());
        List<String> flagged = new ArrayList<>();
        for (Literal lit : lexer.stringLiterals()) {
            if (CJK.matcher(lit.text).find() && !isDiagnostic(lexer.statementOf(lit), loggers)) {
                flagged.add(lit.text);
            }
        }
        assertEquals("判据在合成样本上没抓出两条 UI 文案（直接中文 + unicode 转义伪装）—— "
                        + "判据已失配，主断言 noHardcodedCjkInPlayerVisibleText 会永远全绿",
                List.of("半径:", "半径:"), flagged);
    }

    /**
     * 确认「含 CJK 的字符串字面量」这个判据在全仓<b>确实还能匹配到东西</b>。
     *
     * <p>阈值从 20 提到 100：这一轮把 client/ 清到 0 之后，残留的 CJK 字面量
     * <b>全部</b>在 common 代码里（实测迁移前全仓 584，本轮后约 499），
     * 其中 {@code MekckConfig} 一个文件就占 170。原阈值 20 离现状太远，
     * 几乎只在正则彻底失配时才会响；提到 100 仍留着约 5 倍余量，
     * 却能在「词法器/正则被改坏、只认出零星几条」时更早报警。</p>
     *
     * <p>放在<b>全仓</b>而不是 client/ 上查是有意的：被治理的一侧迟早会清到零
     * （那正是迁移的目的），拿它当判据的燃料等于判据自己把自己判没了。</p>
     */
    @Test
    public void theDetectionPatternsStillMatchSomething() throws IOException {
        int cjk = 0;
        for (Path file : javaFilesUnder(MAIN_SRC_DIR)) {
            String code = Files.readString(file, StandardCharsets.UTF_8);
            for (Literal lit : new Lexer(code).stringLiterals()) {
                if (CJK.matcher(lit.text).find()) cjk++;
            }
        }
        assertTrue("含 CJK 的字符串字面量在全仓都匹配不到了 —— 判据的词法器或正则可能已失配"
                + "（实测现状约 499，阈值 100）", cjk >= 100);
    }

    // ── 诊断判定 ───────────────────────────────────────────────────────

    /**
     * 这个字面量是否<b>不</b>流向玩家可见位置（日志 / 配置注释 / 异常 / 断言）。
     *
     * <p>判据基于<b>数据流</b>而不是文本形状：玩家能在屏幕上看到 {@code "抽取失败"}
     * 这样的词，不能因为它像诊断就放过。五类依据依次是：</p>
     * <ol>
     *   <li>内联 logger 调用（{@code LogUtils/LoggerFactory.getLogger(...).warn(...)}）；</li>
     *   <li>绑定了变量名的 logger 调用（{@link #loggerBindings} 认出的变量，
     *       以及跨类静态引用的 {@link #LOGGER_NAME} 常量名）；</li>
     *   <li>{@link #LOG_ONLY_SINKS} 里「只写日志」的辅助方法；</li>
     *   <li>{@code .comment(...)} —— Forge 配置注释内容，只进 .toml，不进界面；</li>
     *   <li>{@code throw new ...} / {@code assertXxx(...)} —— 异常与测试断言消息，
     *       只进堆栈/开发者日志。扩面到主源码后这两类是最大宗的假阳性来源。</li>
     * </ol>
     * 每一条都只针对<b>一种明确的数据流</b>，不是按文件名/目录开洞，所以不会
     * 把新增的玩家可见文案一起放过。</p>
     *
     * @param statement 字面量所属语句（已去注释，由 {@link Lexer#statementOf} 给出）
     * @param loggers   该文件里认出的 logger 变量名
     */
    private static boolean isDiagnostic(String statement, Set<String> loggers) {
        if (LOGUTILS_LOG_CALL.matcher(statement).find()) {
            return true;
        }
        Matcher m = LOG_METHOD_CALL.matcher(statement);
        while (m.find()) {
            if (loggers.contains(m.group(1)) || LOGGER_NAME.matcher(m.group(1)).find()) {
                return true;
            }
        }
        if (statement.contains(".comment(")) {
            return true;
        }
        if (THROW_NEW.matcher(statement).find()) {
            return true;
        }
        if (ASSERT_CALL.matcher(statement).find()) {
            return true;
        }
        for (String sink : LOG_ONLY_SINKS) {
            if (statement.contains(sink)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 语言键是否真的被引用。
     *
     * <p>除了整键字面量，还认一种动态拼法：代码里出现前缀字面量
     * （{@code "gui.mekck.ui.target_type."}），后半段由三元/枚举在运行期补上
     * （{@code t == 0 ? "hostile" : ...}）。不认这种拼法会把
     * {@link #newKeysExistInBothLanguagesAndAreReferenced} 变成假红。</p>
     */
    private static boolean isReferenced(String key, String code) {
        if (code.contains("\"" + key + "\"")) {
            return true;
        }
        for (int i = key.length() - 1; i > 0; i--) {
            if (key.charAt(i) != '.') {
                continue;
            }
            if (code.contains("\"" + key.substring(0, i) + ".\"")) {
                return true;
            }
        }
        return false;
    }

    // ── 最小词法器 ──────────────────────────────────────────────────────

    /**
     * 一个字符串字面量。
     *
     * <p>语句用 {@code [stmtStart, stmtEnd)} 的<b>偏移区间</b>表示而不是现成的文本：
     * 文本要从 {@link Lexer#codeText()}（去注释视图）里现取，这样同一份偏移既能取
     * 语句、又能让字面量位置与原文对齐。</p>
     */
    private static final class Literal {
        final String text;
        final int stmtStart;
        final int stmtEnd;
        final int line;

        Literal(String text, int stmtStart, int stmtEnd, int line) {
            this.text = text;
            this.stmtStart = stmtStart;
            this.stmtEnd = stmtEnd;
            this.line = line;
        }
    }

    /**
     * 逐字符走一遍源码，抽出所有字符串字面量及其所属语句。
     *
     * <p>之所以不用正则：语句边界要靠 {@code ;} {@code &#123;} {@code &#125;} 切，
     * 而这些字符在字符串里同样合法（{@code "[mekck] {} 页"}、{@code "mod:{x}"}）——
     * 先剥字符串再找边界会让位置整体错位。这里让词法器<b>同时</b>认出字符串与边界，
     * 两件事才不会互相污染。</p>
     *
     * <p>不处理文本块（{@code """}）：本仓 client/ 没有用到，
     * 真要用时 {@link #theDetectorStillFlagsASyntheticSample} 会先红。</p>
     */
    private static final class Lexer {
        private final String src;
        private final List<Literal> out = new ArrayList<>();
        /** 注释区间 [start, end)，用来生成去注释视图。 */
        private final List<int[]> commentRanges = new ArrayList<>();
        private int stmtStart;

        Lexer(String src) {
            this.src = src;
        }

        List<Literal> stringLiterals() {
            int n = src.length();
            int i = 0;
            while (i < n) {
                char c = src.charAt(i);
                if (c == '/' && i + 1 < n && src.charAt(i + 1) == '/') {
                    int end = skipToLineEnd(i);
                    commentRanges.add(new int[]{i, end});
                    i = end;
                } else if (c == '/' && i + 1 < n && src.charAt(i + 1) == '*') {
                    int end = skipBlockComment(i);
                    commentRanges.add(new int[]{i, end});
                    i = end;
                } else if (c == '"') {
                    i = readString(i);
                } else if (c == '\'') {
                    i = skipCharLiteral(i);
                } else {
                    if (c == ';' || c == '{' || c == '}') {
                        // 分隔符既结束旧语句，也开启新语句
                        stmtStart = i + 1;
                    }
                    i++;
                }
            }
            return out;
        }

        /**
         * 源码的<b>去注释视图</b>：注释整段换成空格，<b>长度与原文完全一致</b>。
         *
         * <p>长度不变是关键：语句区间与字面量位置都能原样映射回同一份偏移，
         * 于是「取语句文本」和「扫 logger 绑定」可以共用一份去注释文本，
         * 不必再维护第二套剥注释正则（那种正则分不清 {@code "//"} 在注释里还是在字符串里）。</p>
         */
        String codeText() {
            char[] chars = src.toCharArray();
            for (int[] range : commentRanges) {
                for (int i = range[0]; i < range[1] && i < chars.length; i++) {
                    chars[i] = ' ';
                }
            }
            return new String(chars);
        }

        /** 读到字符串结束（不含结束引号），登记字面量，并回填所属语句文本。 */
        private int readString(int start) {
            int n = src.length();
            int i = start + 1;
            StringBuilder sb = new StringBuilder();
            while (i < n) {
                char c = src.charAt(i);
                if (c == '\\' && i + 1 < n) {
                    // 转义原样保留曾是个洞：源文件里用 unicode 转义写中文，字面内容是
                    // 纯 ASCII，判据看不见（NetworkOrderPanel 的 66 处转义躲过了六轮）。
                    // 所以 unicode 转义在这里解码成真实字符；其余转义仍原样保留。
                    if (src.charAt(i + 1) == 'u') {
                        int h = i + 2;
                        while (h < n && src.charAt(h) == 'u') {
                            // JLS 允许多个 u：\uuuu0041（注释里写成 \\uuuu0041 防 javac 预解码）
                            h++;
                        }
                        int cp = 0;
                        int k = 0;
                        for (; k < 4 && h + 4 <= n; k++) {
                            int d = Character.digit(src.charAt(h + k), 16);
                            if (d < 0) {
                                break;
                            }
                            cp = (cp << 4) | d;
                        }
                        if (k == 4) {
                            sb.appendCodePoint(cp);
                            i = h + 4;
                            continue;
                        }
                    }
                    sb.append(c).append(src.charAt(i + 1));
                    i += 2;
                    continue;
                }
                if (c == '"') {
                    break;
                }
                sb.append(c);
                i++;
            }
            out.add(new Literal(sb.toString(), stmtStart, i, 1 + countLines(src, start)));
            return i + 1;
        }

        private int skipCharLiteral(int start) {
            int n = src.length();
            int i = start + 1;
            while (i < n) {
                char c = src.charAt(i);
                if (c == '\\') {
                    i += 2;
                    continue;
                }
                if (c == '\'') {
                    return i + 1;
                }
                if (c == '\n') {
                    return i;
                }
                i++;
            }
            return i;
        }

        private int skipToLineEnd(int start) {
            int nl = src.indexOf('\n', start);
            return nl < 0 ? src.length() : nl;
        }

        private int skipBlockComment(int start) {
            int end = src.indexOf("*/", start + 2);
            return end < 0 ? src.length() : end + 2;
        }

        /** 字面量所属语句的文本（去注释、压成一行，违规报告才读得下去）。 */
        String statementOf(Literal lit) {
            return collapse(codeText().substring(lit.stmtStart, lit.stmtEnd));
        }

        private static int countLines(String s, int upTo) {
            int n = 0;
            for (int i = 0; i < upTo; i++) {
                if (s.charAt(i) == '\n') n++;
            }
            return n;
        }

        /** 语句文本压成一行。 */
        private static String collapse(String s) {
            return s.replaceAll("\\s+", " ").trim();
        }
    }

    // ── 小工具 ──────────────────────────────────────────────────────────

    private static List<Path> javaFilesUnder(Path root) throws IOException {
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(p -> p.toString().endsWith(".java")).sorted().toList();
        }
    }

    private static Set<String> keysOf(Path langFile) throws IOException {
        try (Reader reader = Files.newBufferedReader(langFile, StandardCharsets.UTF_8)) {
            JsonObject obj = JsonParser.parseReader(reader).getAsJsonObject();
            return new LinkedHashSet<>(obj.keySet());
        }
    }

    private static String readAllMainCode() throws IOException {
        StringBuilder sb = new StringBuilder();
        for (Path file : javaFilesUnder(MAIN_SRC_DIR)) {
            sb.append(Files.readString(file, StandardCharsets.UTF_8)).append('\n');
        }
        return sb.toString();
    }
}
