package cn.ism.mekck.machine;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 工厂 GUI 的<b>数据同步面</b>护栏。
 *
 * <h3>为什么需要它</h3>
 * 这一类缺陷的形态是「编译过、单测过、打包过，界面上却永远是旧值」：
 * 某个读数<b>没有任何同步通道</b>，客户端拿到的是区块加载那一刻的快照。
 * 第三轮审查在「执行器私有状态」上实测到一处：
 * 烧烤的调味料开关与烹饪/穿串的「当前订单 x/y」都属此类
 * （原注释断言「{@code saveAdditional} 会被 {@code getUpdateTag} 复用，随方块更新包同步」——
 * 该断言经 javap 复核<b>不成立</b>）。
 *
 * <p>这类问题之所以能活到第三轮，正是因为<b>没有任何测试会红</b>：
 * 缺一条同步通道不会让任何断言失败。所以本测试把「哪些读数必须有通道」钉成清单。</p>
 *
 * <h3>为什么按源码文本断言</h3>
 * 真 tile 需要 {@code BlockEntityType} 与 Mek 的 {@code Attribute} 注册表，
 * 裸 JVM 里造不出来；而「某个值是否被 {@code addContainerTrackers} 挂上」
 * 恰恰是源码结构问题。判据统一取<b>方法体文本</b>，与本仓库既有的
 * {@code TestMekCkPersistedSlotCoverage} / {@code TestAttackRadiusClamp} 同一手法。
 */
public class TestFactoryGuiSyncSurface {

    private static final String TILE = "src/main/java/cn/ism/mekck/machine/MekCkMachineTile.java";

    private static String read(String path) throws IOException {
        return Files.readString(Path.of(path), StandardCharsets.UTF_8);
    }

    /**
     * 每个 {@code SyncableInt} 的数据源必须在 {@link MekCkMachineTile} 里出现。
     *
     * <p>逐个 getter 核对，而不是只数「挂了几条」——挂够数量但取错数据源同样失效，
     * 而这正是 {@code getWorkProgress} 当年踩过的坑（无条件返回镜像 ⇒ 服务端永远读到 0
     * ⇒ 脏值判定一次都不触发）。</p>
     */
    @Test
    public void everyTrackedValueHasAGetterThatActuallyReadsTheServerSideState()
            throws IOException {
        String src = read(TILE);
        String trackers = methodBody(src, "public void addContainerTrackers(");

        // 进度条：走 trackArray（与 Mek 的 TileEntityFactory 同款）。数组本身就是权威值 ——
        // 服务端读它、客户端由同步 setter 写它，所以不需要按端分流。
        assertTrue("进度条必须仍然挂在容器追踪上", trackers.contains("trackArray(progressArray())"));

        // 执行器展示态：订单三件套 + 家族自定义位。
        for (String getter : new String[]{
                "this::syncOrderActiveFlag", "this::getOrderQuantityForSync",
                "this::getOrderCompletedForSync", "this::syncFamilyExtraBits"}) {
            assertTrue("addContainerTrackers 必须挂上 " + getter
                            + "（执行器私有状态此前完全没有同步通道，界面只拿到区块加载时的快照）",
                    trackers.contains(getter));
        }

        // 每个 getter 都必须做按端分流：有客户端镜像字段，也读服务端权威值。
        // 可见性不统一（getOrderQuantityForSync 是 public、syncFamilyExtraBits 是 protected），
        // 所以两种签名都要试——只试一种的话换个可见性就会静默变成「找不到方法体」而空转。
        for (String getter : new String[]{
                "getOrderQuantityForSync", "getOrderCompletedForSync", "syncFamilyExtraBits"}) {
            String body = methodBody(src, "public int " + getter + "() {");
            if (body.isEmpty()) {
                body = methodBody(src, "protected int " + getter + "() {");
            }
            assertFalse("没找到 " + getter + " 的方法体（签名改了？）", body.isEmpty());
            assertTrue(getter + " 必须有客户端镜像分支", body.contains("clientMirroring()"));
        }
        assertTrue("syncOrderActiveFlag 必须有客户端镜像分支",
                methodBody(src, "protected int syncOrderActiveFlag() {").contains("clientMirroring()"));

        // 反向断言：旧的错误说法不能再留在注释里。
        assertFalse("源码里仍残留「位标志随方块更新包同步」这条已被证伪的说法",
                src.contains("随方块更新包同步到客户端"));
    }

    /**
     * 客户端镜像字段必须真的存在，且与 setter 写的是同一批字段。
     *
     * <p>「getter 读 {@code clientX} 而 setter 写 {@code clientY}」是这类代码最容易犯的错，
     * 而且症状与「完全没有同步」一模一样（永远显示旧值），排查起来毫无线索。</p>
     */
    @Test
    public void everySetterWritesTheFieldItsGetterReads() throws IOException {
        String src = read(TILE);
        String trackers = methodBody(src, "public void addContainerTrackers(");

        // getter 读的字段 ↔ lambda setter 写的字段，必须一一对应。
        assertPair(src, trackers, "syncOrderActiveFlag", "clientOrderActive");
        assertPair(src, trackers, "getOrderQuantityForSync", "clientOrderQuantity");
        assertPair(src, trackers, "getOrderCompletedForSync", "clientOrderCompleted");
        assertPair(src, trackers, "syncFamilyExtraBits", "clientFamilyExtraBits");
    }

    private void assertPair(String src, String trackers, String getter, String field) {
        String getterBody = methodBody(src, getter.contains("sync") ? "protected int " + getter + "() {"
                : "public int " + getter + "() {");
        assertTrue(getter + " 必须读 " + field, getterBody.contains(field));

        // 找到该 getter 后面那个 lambda 的 setter。
        // 窗口必须**包含**结尾的 "))" —— 表达式 lambda 写成
        // `value -> this.x = value))`，右括号正是「赋值到此为止」的证据；
        // 早先版本用 indexOf("));") 取子串把它切在外面，于是正则永远匹配不上（假红）。
        int at = trackers.indexOf("this::" + getter);
        assertTrue("addContainerTrackers 里没找到 " + getter, at >= 0);
        int end = trackers.indexOf("));", at);
        assertTrue("找不到 " + getter + " 的 lambda 结尾", end > at);
        String lambda = trackers.substring(at, end + 2);
        // 用正则而不是子串：变异测试实测把 setter 改成 `= value + 1` 时，
        // 子串 `= value` 仍然命中 ⇒ 假绿。必须要求 `value` **后面紧跟 lambda 的右括号**，
        // 即赋值到此为止。（第一版写成 `= value;` 也不行 —— 这里用的是**表达式** lambda
        // `value -> this.x = value`，本来就没有分号，那是第一版的假红。）
        assertTrue(getter + " 的 setter 必须把 value 原样写进 " + field
                        + "（写错字段、或写入的值被加工，症状都与「完全没有同步」完全一样，"
                        + "排查毫无线索）",
                java.util.regex.Pattern
                        .compile("this\\." + java.util.regex.Pattern.quote(field) + "\\s*=\\s*value\\s*\\)")
                        .matcher(lambda).find());
    }

    /**
     * 执行器接口必须声明这三个展示态方法。
     *
     * <p>它们原先只在各执行器上以各自的名字散落着，没有统一入口：菜单只能按具体类型强转，
     * 而新增家族极易漏掉某一个 —— 漏掉的表现是「编译过但界面少一块读数」。</p>
     */
    @Test
    public void executorInterfaceDeclaresTheDisplayState() throws IOException {
        String src = read("src/main/java/cn/ism/mekck/machine/MekCkRecipeExecutor.java");
        for (String sig : new String[]{
                "default boolean hasOrder()", "default int getOrderQuantity()",
                "default int getOrderCompleted()"}) {
            assertTrue("MekCkRecipeExecutor 必须声明 " + sig
                    + "，否则基类的同步通道没有统一数据源",
                    src.contains(sig));
        }
    }

    /**
     * <b>读取侧</b>必须真的走镜像 —— 这是第三轮自己踩的坑留下的护栏。
     *
     * <p>上一轮把 {@code addContainerTrackers} 的 4 条 {@code SyncableInt} 配齐后，
     * 我宣称「订单进度现在会实时刷新」。<b>实际上那 4 条只写不读</b>：</p>
     * <ul>
     *   <li>{@code getClientOrderActive()} 的调用方数量是 <b>0</b>（只有它自己的声明）；</li>
     *   <li>{@code CookingFactoryMenu} / {@code SkeweringFactoryMenu} 读的是
     *       tile 的同名方法，而那两个方法<b>直接问执行器</b>，
     *       客户端拿到的仍是区块加载快照 ⇒ <b>症状与修复前完全一致</b>。</li>
     * </ul>
     *
     * <p>之所以能溜过去：变异测试实测把 {@code CookingFactoryTile.getOrderQuantity()}
     * 改成 {@code return 999999}，<b>两个护栏全绿</b> —— 因为它们只读写入侧
     * （{@code MekCkMachineTile} / {@code GrillFactoryTile} / 执行器接口）的文本，
     * <b>从不去看读取侧</b>（menu / screen / tile）。本条就是补上读取侧。</p>
     */
    @Test
    public void orderReadoutsOnTilesMustGoThroughTheMirroredBase() throws IOException {
        // 家族 tile 的读数方法必须委托给基类，而不是直接问执行器。
        for (String family : new String[]{
                "src/main/java/cn/ism/mekck/machine/cooking/CookingFactoryTile.java",
                "src/main/java/cn/ism/mekck/machine/skewering/SkeweringFactoryTile.java"}) {
            String src = read(family);
            for (String sig : new String[]{
                    "public boolean hasOrder()", "public int getOrderQuantity()",
                    "public int getOrderCompleted()"}) {
                String body = methodBody(src, sig);
                assertFalse(family + " 找不到 " + sig, body.isEmpty());
                assertTrue(family + sig + " 必须委托给基类（super.）而不是直接问执行器："
                                + "直接问执行器在客户端拿到的是区块加载快照，永远不刷新",
                        body.contains("super."));
                assertFalse(family + sig + " 不得绕过镜像直接读执行器："
                                + "那样 4 条 SyncableInt 就成了只写不读的死通道",
                        body.contains("exec.getOrder") || body.contains("exec.hasOrder()"));
            }
        }

        // 基类必须提供这三个读取侧的公共出口。
        String tile = read(TILE);
        for (String sig : new String[]{
                "public boolean hasOrder()", "public int getOrderQuantity()",
                "public int getOrderCompleted()"}) {
            assertTrue("MekCkMachineTile 必须提供 " + sig + " 作为读取侧的唯一出口",
                    tile.contains(sig));
        }
        // getClientOrderActive 至少要被 hasOrder 用上（上一轮它是零调用方）。
        assertTrue("hasOrder() 必须用 getClientOrderActive()，否则那条 SyncableInt 是只写不读",
                methodBody(tile, "public boolean hasOrder() {").contains("executor().hasOrder()")
                        && tile.contains("clientOrderActive = value"));
    }

    /**
     * 烤炉取消订单必须<b>连调味料一起清</b>。
     *
     * <p>旧 {@code GrillFactoryBlockEntity.clearOrder} 明确写着「订单完成，清空（含调味料）」。
     * 迁到执行器时只清了那三个订单字段 ⇒ 一次订单跑完，调味料永久赖在机器上：
     * {@code currentSeasoningFor()} 在无订单时也会拿它强制调味，
     * 而 {@code consumeSeasoningUses} 的 {@code enabledOnly} 会因此变成 false，
     * 去扣<b>未启用</b>槽的次数；{@code save()} 又把它写进 NBT 持久化。</p>
     */
    @Test
    public void grillClearOrderAlsoClearsTheSeasoning() throws IOException {
        String src = read("src/main/java/cn/ism/mekck/machine/grill/GrillFactoryExecutor.java");
        String body = methodBody(src, "public void clearOrder() {");
        assertFalse("找不到 GrillFactoryExecutor.clearOrder", body.isEmpty());
        assertTrue("clearOrder() 必须清 orderSeasoning，否则订单跑完后调味料会永久残留"
                        + "（旧实现是清的，迁移时漏了 —— 第三轮的行为回归）",
                body.contains("orderSeasoning = null"));
        // 取消路径必须走 clearOrder，而不是「只清 id」。
        String setBody = methodBody(src, "public void setOrder(ResourceLocation recipeId, int quantity, String seasoningId) {");
        assertTrue("setOrder(null, …) 必须走 clearOrder()，否则取消按钮只清 id 而留下调味料",
                setBody.contains("clearOrder()"));
        assertTrue("setOrder 的份数下界必须是 max(1,·)（0 份订单会让批量夹成 0 而永久惰性）",
                setBody.contains("Math.max(1, quantity)"));
    }

    /**
     * 烤炉的调味料位必须走 {@code syncFamilyExtraBits} 这条同步通道。
     *
     * <p>逐条钉住「打包位图」这一段：位图是三次调用的打包结果，
     * 一旦有人把它缓存成字段，Mek 的脏值判定就永远读到同一个值 ⇒ 永不推送。</p>
     */
    @Test
    public void grillSeasoningBitsGoThroughTheSharedChannel() throws IOException {
        String tile = read("src/main/java/cn/ism/mekck/machine/grill/GrillFactoryTile.java");
        String exec = read("src/main/java/cn/ism/mekck/machine/grill/GrillFactoryExecutor.java");

        assertTrue("GrillFactoryExecutor 必须提供位图打包方法",
                exec.contains("public int seasoningEnabledBits()"));
        String bitsBody = methodBody(exec, "public int seasoningEnabledBits() {");
        assertTrue("seasoningEnabledBits 必须每次重新打包，不能缓存成字段"
                        + "（缓存后脏值判定永远读到同一个值 ⇒ 永不推送客户端）",
                bitsBody.contains("for (int i = 0"));
        assertFalse("seasoningEnabledBits 里不得出现对打包结果的字段缓存",
                bitsBody.contains("cachedBits") || bitsBody.contains("lastBits"));

        assertTrue("GrillFactoryTile 必须覆写 syncFamilyExtraBits 把调味料位送进同步通道",
                tile.contains("protected int syncFamilyExtraBits()"));
        assertTrue("isSeasoningEnabled 必须读位图而不是直接问执行器"
                        + "（直接问执行器在客户端拿到的是永不更新的字段）",
                methodBody(tile, "public boolean isSeasoningEnabled(int index) {")
                        .contains("getSeasoningBits()"));
        assertFalse("旧的错误断言「随方块更新包同步到客户端」必须从 GrillFactoryTile 移除",
                tile.contains("执行器的位标志随方块更新包同步到客户端"));
    }

    private static String methodBody(String src, String signature) {
        return cn.ism.mekck.TestSourceText.methodBody(src, signature);
    }
}
