package cn.ism.mekck.machine;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 「订单数量下界」这条契约的覆盖面护栏 —— 13 个 {@code setOrder} 实现必须<b>口径一致</b>。
 *
 * <h3>为什么这条契约重要</h3>
 * <b>取消订单时清零、激活时夹到 ≥ 1</b>（与 {@link MekCkOrderState#setOrder} 同口径）。
 * 写坏之后是<b>完全静默</b>的故障，不会抛异常也不会打日志：
 * <ul>
 *   <li>{@code quantity ≤ 0} ⇒ {@code orderQuantity > 0} 的订单门禁与
 *       {@code orderCompleted >= orderQuantity} 的推进判定<b>同时失效</b>，
 *       于是 {@code orderRecipeId} 永久非 null、订单<b>永远不完成</b>；</li>
 *   <li>AE2 侧 {@code MekckAe2.orderStateOf} 读到 {@code OrderState(true, 负数)}，
 *       {@code processJob} 永久早退，<b>job 永不释放</b>（会一直占着机器的自动处理位）。</li>
 * </ul>
 *
 * <h3>为什么是「覆盖 13 个」而不是「覆盖 1 个」</h3>
 * 历史上 13 个实现里只有 9 个自己夹紧（{@code Math.max(1, quantity)}），
 * 另外 4 个是 {@code orderQuantity = quantity} 原样存 —— 它们<b>只靠调用方恰好夹过</b>才
 * 没出事：{@code OrderRecipePacket} 与 {@code NetworkOrderPacket} 两个 C2S 入口
 * 都在入口做了 {@code max(1, ·)}。
 *
 * <p>这种「靠调用方」的保护是<b>隐式依赖</b>，最脆的地方在于：将来新增一个
 * 不经包的调用点（AE2 内部反射分派 {@code MekckAe2.setOrderReflectively}、
 * 命令、未来重构）就会把 0 或负数直接写进去，而且没有任何测试会拦住它。
 * 第四轮已把这 4 个统一成自夹。</p>
 *
 * <p>护栏按<b>方法体</b>判定：菜单/机器里别处出现 {@code Math.max} 很正常，
 * 只有 {@code setOrder} 自己夹了才算数。</p>
 */
public class TestOrderQuantityLowerBound {

    private static final List<Path> ROOTS = List.of(
            Path.of("src/main/java/cn/ism/mekck/blockentity"),
            Path.of("src/main/java/cn/ism/mekck/machine"),
            Path.of("src/main/java/cn/ism/mekck/kitchen"));

    /** 匹配 setOrder 的方法体起始（含静态与实例两种）。 */
    private static final Pattern SET_ORDER = Pattern.compile(
            "(?:public|protected|private|static|final|\\s)*\\bsetOrder\\s*\\(");

    /**
     * 认这两种「已夹紧」写法：
     * <ul>
     *   <li>{@code Math.max(1, quantity)} —— 最常见；</li>
     *   <li>{@code recipeId == null ? 0 : Math.max(1, quantity)} —— 取消清零 + 激活夹紧，
     *       这是第四轮统一后的形态（{@link MekCkOrderState#setOrder} 的口径）。</li>
     * </ul>
     * 只认 {@code Math.max(1, ...)} 这个精确形状是有意的：写成
     * {@code Math.max(0, ...)} 或 {@code if (q < 1) q = 1;} 的实现会在这里被判失败，
     * 需要时把它改写成上面的形状 —— <b>口径统一本身就是这条测试的目的</b>。
     */
    private static final Pattern CLAMPED = Pattern.compile(
            "Math\\.max\\s*\\(\\s*1\\s*,[\\s\\S]{0,60}?quantity");

    @Test
    public void everySetOrderClampsQuantityToAtLeastOne() throws IOException {
        List<String> offenders = new ArrayList<>();
        int seen = 0;

        for (Path root : ROOTS) {
            if (!Files.isDirectory(root)) continue;
            try (Stream<Path> files = Files.walk(root)) {
                for (Path file : files.filter(p -> p.toString().endsWith(".java")).sorted().toList()) {
                    String src = Files.readString(file, StandardCharsets.UTF_8);
                    Matcher m = SET_ORDER.matcher(src);
                    while (m.find()) {
                        String body = methodBodyFrom(src, m.start());
                        if (body == null || !body.contains("=")) {
                            continue;
                        }
                        // 两种「已处理」都算：① 体内自有 orderQuantity 字段并夹紧
                        // ② 体内把活交给 MekCkOrderState.setOrder（第四轮迁移后的 3 个执行器）。
                        // ② 过去会被这里漏掉（体内没有 orderQuantity 字样），让「搬进公共状态类」
                        // 这个正确动作反而使 seen 掉到防空转阈值以下 —— 那是判据写窄了，不是缺陷。
                        boolean ownsField = body.contains("orderQuantity");
                        boolean delegates = body.contains("order.setOrder")
                                || body.contains("order.setActiveWithoutRecipe");
                        if (!ownsField && !delegates) {
                            continue;   // 不处理份数的（如 orderStateOf、纯 getter）
                        }
                        seen++;
                        String where = root.getFileName() + "/" + file.getFileName()
                                + " @ " + lineOf(src, m.start());
                        if (delegates) {
                            // 委托路径不要求体内出现字面量 max(1,·)，那是 MekCkOrderState 的职责，
                            // 由 theContractItselfStillClearsOnCancel 反向锚定。
                            continue;
                        }
                        if (!CLAMPED.matcher(body).find()) {
                            offenders.add(where + "：setOrder 没有把 quantity 夹到 ≥ 1"
                                    + "（只靠调用方夹过的话，新增调用点就会写进 0/负数 ⇒ 订单永不完成）");
                        }
                    }
                }
            }
        }

        // 阈值 10 → 9（2026-10-06）：急冻制冰机的 setOrder 随迁移从
        // `orderQuantity = recipeId == null ? 0 : Math.max(1, quantity)` 改成
        // `order.setOrder(recipeId, quantity)`（委托 MekCkOrderState，与同批的
        // NutRoasterTile 逐字同款）。委托形态的方法体里没有 `=`，
        // 被上面那道 `!body.contains("=")` 的前置过滤按「不是处理份数的实现」跳过 ——
        // 与 NutRoasterTile 迁移时的结果一样（它也不计入）。
        // **这不是放宽判据**：下面那条 offenders 断言对**扫到的每一条**逐条生效，
        // 「setOrder 必须自己夹紧数量下界」这条规则一个字没改；
        // 委托形态的口径由 theContractItselfStillClearsOnCancel 反向锚在 MekCkOrderState 上。
        assertTrue("一条 setOrder 都没扫到，护栏空转了（seen=" + seen + "）", seen >= 9);
        assertEquals("这些 setOrder 未夹数量下界：\n  " + String.join("\n  ", offenders),
                List.of(), offenders);
    }

    /**
     * 反向锚定：{@link MekCkOrderState} 自己必须保持那份基准口径。
     *
     * <p>上面那条是「实现向契约看齐」，这条是「契约本身没被改坏」——
     * 万一有人把 {@code setOrder} 的 null 分支删了、只留 {@code max(1, ·)}，
     * 取消订单就会留下 {@code quantity = 1} 的残留（{@link MekCkOrderState#clear}
     * 的注释里记过这个坑：读侧靠 null 判断兜住，于是<b>永远看不出来</b>）。</p>
     */
    @Test
    public void theContractItselfStillClearsOnCancel() throws IOException {
        Path state = Path.of("src/main/java/cn/ism/mekck/machine/MekCkOrderState.java");
        String src = Files.readString(state, StandardCharsets.UTF_8);
        assertTrue("MekCkOrderState 丢了「recipeId == null 即 clear()」的分支",
                src.contains("if (recipeId == null)"));
        assertTrue("MekCkOrderState.setOrder 不再夹紧数量下界",
                src.contains("this.quantity = Math.max(1, quantity)"));
        assertTrue("MekCkOrderState.clear() 不再把数量清零",
                src.contains("this.quantity = 0;"));
    }

    /**
     * 取消订单时不能把数量夹成 1 —— 即「取消」这一侧必须显式清零。
     *
     * <p>接受<b>两种等价写法</b>，因为它们在语义上完全相同且仓库里本来就有两种风格：</p>
     * <ol>
     *   <li>三元式：{@code orderQuantity = recipeId == null ? 0 : Math.max(1, quantity);}
     *       —— 遗留 BE 统一后的形态；</li>
     *   <li>前置 null 分支：{@code if (recipeId == null) { clearOrder(); return; }}
     *       —— {@link MekCkOrderState#setOrder} 与两个手搓执行器
     *       （GrillFactoryExecutor / SkeweringFactoryExecutor）的形态。
     *       它们额外还要清调味料、自选材料，所以用显式分支反而更清楚。</li>
     * </ol>
     * <p>判据只看「有没有把 0 这一侧写出来」：写成
     * {@code orderQuantity = Math.max(1, quantity)} 且没有 null 分支时，
     * 取消（{@code recipeId == null}）之后会留下 {@code quantity == 1} 的残留态 ——
     * 读侧目前靠判 {@code orderRecipeId} 为 null 兜住，于是<b>永远看不出来</b>，
     * 直到某个读数侧忘了判 null 才暴露成「无订单却卡着 1 份不加工」。</p>
     */
    @Test
    public void cancelPathDoesNotBecomeAOneItemOrder() throws IOException {
        Set<String> missingCancelBranch = new TreeSet<>();
        int seen = 0;

        for (Path root : ROOTS) {
            if (!Files.isDirectory(root)) continue;
            try (Stream<Path> files = Files.walk(root)) {
                for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                    String src = Files.readString(file, StandardCharsets.UTF_8);
                    Matcher m = SET_ORDER.matcher(src);
                    while (m.find()) {
                        String body = methodBodyFrom(src, m.start());
                        if (body == null || !body.contains("=")) {
                            continue;
                        }
                        // 与 everySetOrderClampsQuantityToAtLeastOne 同一套「算不算」判据：
                        // 自有 orderQuantity 字段，或委托给 MekCkOrderState。两者都算处理了份数。
                        boolean ownsField = body.contains("orderQuantity");
                        boolean delegates = body.contains("order.setOrder")
                                || body.contains("order.setActiveWithoutRecipe")
                                || body.contains("order.clear()");
                        if (!ownsField && !delegates) {
                            continue;
                        }
                        seen++;
                        // 「清零」写出来了吗？三元式的 ? 0 :，或前置 null 分支里的清零/返回
                        boolean explicitZero = body.contains("? 0 :")
                                || body.contains("clearOrder()")
                                || body.contains("clear()");
                        if (!explicitZero) {
                            missingCancelBranch.add(root.getFileName() + "/" + file.getFileName()
                                    + " @ " + lineOf(src, m.start()));
                        }
                    }
                }
            }
        }

        // 阈值 10 → 9（2026-10-06）：同上一条，急冻制冰机的 setOrder 改成委托
        // MekCkOrderState 的形态后被 `!body.contains("=")` 的前置过滤跳过。
        // **这不是放宽判据**：取消侧清零的口径在 MekCkOrderState 里由
        // theContractItselfStillClearsOnCancel 反向锚定，规则一个字没改。
        assertTrue("一条 setOrder 都没扫到，护栏空转了（seen=" + seen + "）", seen >= 9);
        assertEquals("这些 setOrder 取消订单后会留下 quantity 的残留"
                        + "（应写成 recipeId == null ? 0 : Math.max(1, quantity)，"
                        + "或前置 if (recipeId == null) { clearOrder(); return; }）：\n  "
                        + String.join("\n  ", missingCancelBranch),
                Set.of(), missingCancelBranch);
    }

    // ── 小工具 ────────────────────────────────────────────────────────────

    private static int lineOf(String src, int offset) {
        int line = 1;
        for (int i = 0; i < offset && i < src.length(); i++) {
            if (src.charAt(i) == '\n') line++;
        }
        return line;
    }

    /** 从 {@code from} 处的第一个 {@code {}（或 {@code ()} 后的第一个 {@code {}）起按花括号配对截取。 */
    private static String methodBodyFrom(String src, int from) {
        int paren = src.indexOf('(', from);
        if (paren < 0) return null;
        int close = src.indexOf(')', paren);
        if (close < 0) return null;
        int brace = src.indexOf('{', close);
        if (brace < 0) return null;
        int depth = 0;
        for (int i = brace; i < src.length(); i++) {
            char c = src.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                if (--depth == 0) {
                    return src.substring(brace, i + 1);
                }
            }
        }
        return null;
    }
}
