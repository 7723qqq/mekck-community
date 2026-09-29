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

        // 进度条：双向分流的样板，注释里写明了这个坑。
        assertTrue("进度条必须仍然挂在容器追踪上", trackers.contains("this::getWorkProgress"));

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

        // 找到该 getter 后面那个 lambda 的 setter
        int at = trackers.indexOf("this::" + getter);
        assertTrue("addContainerTrackers 里没找到 " + getter, at >= 0);
        int end = trackers.indexOf("));", at);
        assertTrue("找不到 " + getter + " 的 lambda 结尾", end > at);
        String lambda = trackers.substring(at, end);
        assertTrue(getter + " 的 setter 必须写 " + field
                        + "（写错字段的症状与「完全没有同步」完全一样，排查毫无线索）",
                lambda.contains("this." + field + " = value"));
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
     * 烧烤的调味料位必须走 {@code syncFamilyExtraBits} 这条同步通道。
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
        int i = src.indexOf(signature);
        if (i < 0) {
            return "";
        }
        int start = src.indexOf('{', i);
        if (start < 0) {
            return "";
        }
        int depth = 0;
        for (int j = start; j < src.length(); j++) {
            char c = src.charAt(j);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return src.substring(i, j + 1);
                }
            }
        }
        return src.substring(i);
    }
}
