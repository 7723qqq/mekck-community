package cn.ism.mekck.menu;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 中央厨房<b>存储浏览器</b>的同步面护栏（修 I-N4 的回归守卫）。
 *
 * <h3>这个缺陷当初为什么 441 个测试全绿</h3>
 * {@code CentralKitchenMenu} 的搜索 / 排序 / 滚动<b>一直是在服务端算对的</b>，
 * 编译通过、单机看不出来（单机下服务端与客户端是同一个对象），
 * 只有<b>联机</b>时客户端那侧才是「空 BE + 空 filtered」——
 * 于是 54 个格子永远画成空的，整个浏览器是死的。
 *
 * <p>这与 {@code TestClientValueSync} 抓到的那类缺陷同族：
 * <b>「客户端读了一个服务端才有的值」在单机下无法暴露</b>。
 * 所以本测试钉的全是<b>源码形态</b>而不是运行时行为。</p>
 *
 * <h3>为什么不能只靠「有 S2C 包」这一条</h3>
 * 补了包不等于修好了：包如果没接进渲染路径，界面照样是空的，而且没有任何日志。
 * 所以下面五条分别守住链路的<b>每一环</b>，缺一环就是死的：
 * <ol>
 *   <li>客户端<b>不</b>自行过滤（否则它会用空桩算出空列表）；</li>
 *   <li>槽位渲染<b>分端</b>（客户端读镜像，不读 {@code machine.items}）；</li>
 *   <li>快照真的被应用（包处理器调到了 menu 的落地方法）；</li>
 *   <li>包已注册且方向是 S2C（否则包根本到不了客户端）；</li>
 *   <li>页码指示读的值也下行了（否则会显示「共 0 条」「1/0 页」）。</li>
 * </ol>
 *
 * <h3>判据不许空转</h3>
 * 每条断言都要求在源码里<b>真的匹配到东西</b>；正则若因重构改名而失配，
 * 下面的 {@link #criteriaStillMatchSomething} 会先红，而不是让本文件变成永远绿的摆设。
 */
public class TestKitchenStorageBrowserSync {

    private static final Path MENU = Path.of("src/main/java/cn/ism/mekck/menu/CentralKitchenMenu.java");
    private static final Path PACKET =
            Path.of("src/main/java/cn/ism/mekck/network/KitchenStorageSyncPacket.java");
    private static final Path MOD_MESSAGES =
            Path.of("src/main/java/cn/ism/mekck/network/ModMessages.java");

    private static String read(Path path) throws IOException {
        assertTrue("找不到源文件：" + path + "（源码测试需要在仓库根目录跑）", Files.isRegularFile(path));
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    /** 截出 {@code name} 之后第一个 {@code {} 块}，用于只看方法体而不受同名的调用点干扰。 */
    private static String blockOf(String source, String signature) {
        int at = source.indexOf(signature);
        if (at < 0) {
            return "";
        }
        int open = source.indexOf('{', at);
        if (open < 0) {
            return "";
        }
        int depth = 0;
        for (int i = open; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return source.substring(open, i + 1);
                }
            }
        }
        return "";
    }

    // ── 1. 客户端不自行过滤 ────────────────────────────────────────────

    /**
     * {@code refreshDisplay()} 必须<b>在客户端直接返回</b>。
     *
     * <p>客户端手里的 {@code CentralKitchenBlockEntity} 是一个 items 全空的桩，
     * 让它跑一遍 300 格扫描只会稳定地产出空列表；更糟的是它会顺手把
     * {@code displayOrder} 刷成全 -1，把服务端推来的有效映射覆盖掉。</p>
     */
    @Test
    public void clientDoesNotRecomputeTheFilterItself() throws IOException {
        String body = blockOf(read(MENU), "public void refreshDisplay()");
        assertTrue("refreshDisplay() 没找到，判据可能失配", !body.isEmpty());
        assertTrue("refreshDisplay() 必须在客户端提前返回（客户端的 machine.items 是空桩，"
                        + "自行过滤会产出空列表并覆盖服务端下行的 displayOrder）",
                body.contains("isClientSide()"));
    }

    // ── 2. 槽位渲染分端 ────────────────────────────────────────────────

    /**
     * {@code StorageSlot.getItem()} 必须分端：客户端读<b>按显示位置索引的镜像</b>，
     * 服务端才读 {@code machine.items}。
     *
     * <p>这是整个修复的核心一环。旧写法只有
     * {@code machineIndex < 0 ? EMPTY : machine.items.getStackInSlot(machineIndex)}，
     * 而客户端 {@code machineIndex} 恒 -1 ⇒ 恒返回 EMPTY。</p>
     */
    @Test
    public void storageSlotRenderIsSideAware() throws IOException {
        String body = blockOf(read(MENU), "public ItemStack getItem()");
        assertTrue("StorageSlot.getItem() 没找到，判据可能失配", !body.isEmpty());
        assertTrue("getItem() 必须按端分支", body.contains("isClientSide()"));
        assertTrue("客户端分支必须读镜像数组 clientVisible", body.contains("clientVisible"));
        assertTrue("客户端分支不得直接读 machine.items —— 那是全空的桩，"
                + "读了界面仍然是死的", !clientBranchOf(body).contains("machine.items"));
    }

    /**
     * 客户端分支必须<b>只</b>由 {@code isClientSide()} 把关。
     *
     * <h3>为什么这条不能省（变异测试实测）</h3>
     * 只断言「body 里有 {@code isClientSide()}、客户端分支里没有 {@code machine.items}」
     * 是不够的。把条件改成 {@code isClientSide() && false} —— 客户端分支<b>整个失效</b>、
     * 静默回落到读空桩 {@code machine.items}，**缺陷完整复现**，
     * 而上面那些断言<b>全部照旧为真</b>。实测这一条加上之后才把该变异抓住。
     *
     * <p>换句话说：前一条守的是「写了对的东西」，这一条守的是「对的东西真的在跑」。
     * 缺了它的护栏会在最需要的时候变成摆设。</p>
     */
    @Test
    public void clientBranchIsGatedBySideAlone() throws IOException {
        String body = blockOf(read(MENU), "public ItemStack getItem()");
        int at = body.indexOf("isClientSide()");
        assertTrue("getItem() 里找不到 isClientSide() 分支，判据可能失配", at >= 0);
        int open = body.lastIndexOf('(', at);
        assertTrue("找不到 if 条件左括号，判据可能失配", open >= 0);

        // ⚠️ 必须**按括号配对**找 if 条件的右括号，不能用 indexOf(')')。
        // 第一个 ')' 是 isClientSide() 自己的右括号 —— 停在那里的话
        // 条件被截成 "isClientSide()"，`isClientSide() && false` 这类后缀
        // 完全看不见，断言会绿。（这个 bug 在本条断言上犯过两次，
        //  两次都是靠变异测试发现的：第一次是闭区间 off-by-one，第二次是这里的配对。）
        int depth = 0;
        int close = -1;
        for (int i = open; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    close = i;
                    break;
                }
            }
        }
        assertTrue("找不到 if 条件的配对右括号，判据可能失配", close > open);
        String condition = body.substring(open + 1, close).trim();
        assertEquals("客户端分支必须只由 isClientSide() 把关。"
                        + "多挂任何条件（哪怕 isClientSide() && false）都会让客户端静默回落到"
                        + "读全空桩 machine.items，缺陷完整复现而护栏却不会响。实际条件："
                        + condition,
                "isClientSide()", condition);
    }

    /** 取出 getItem() 里 {@code if (isClientSide())} 那个分支的正文。 */
    private static String clientBranchOf(String getItemBody) {
        int at = getItemBody.indexOf("isClientSide()");
        if (at < 0) {
            return "";
        }
        int open = getItemBody.indexOf('{', at);
        if (open < 0) {
            return "";
        }
        int depth = 0;
        for (int i = open; i < getItemBody.length(); i++) {
            char c = getItemBody.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return getItemBody.substring(open + 1, i);
                }
            }
        }
        return "";
    }

    // ── 3. 快照真的被应用 ──────────────────────────────────────────────

    /**
     * 包的客户端处理器必须真的调 menu 的落地方法。
     *
     * <p>「加了包但没接上」是这类修复最常见的半成品：包能收到、能解码，
     * 却只写了个静态缓存表，界面照样空白，而且没有任何日志。</p>
     */
    @Test
    public void packetHandlerActuallyAppliesTheSnapshot() throws IOException {
        String packet = read(PACKET);
        assertTrue("包处理器没有按 pos 校验「玩家正开着这个界面」", packet.contains("containerMenu"));
        assertTrue("包处理器必须调用 menu 的快照落地方法", packet.contains("applyStorageSnapshot"));
        // 反向：menu 必须真的有这个方法，否则上面那条是空断言
        assertTrue("menu 上找不到 applyStorageSnapshot 定义（包在调一个不存在的方法）",
                read(MENU).contains("public void applyStorageSnapshot("));
    }

    // ── 4. 包已注册且方向正确 ──────────────────────────────────────────

    @Test
    public void packetIsRegisteredAndSentToPlayer() throws IOException {
        String modMessages = read(MOD_MESSAGES);
        assertTrue("KitchenStorageSyncPacket 没有在 ModMessages 注册 —— 包根本到不了客户端",
                modMessages.contains("registerMessage(29, KitchenStorageSyncPacket.class")
                        || modMessages.matches("(?s).*registerMessage\\(\\d+,\\s*KitchenStorageSyncPacket\\.class.*"));
        assertTrue("没有 sendToPlayer 重载 —— 方向反了（服务端收不到该包的判定会把它弹掉）",
                modMessages.contains("sendToPlayer(KitchenStorageSyncPacket message"));
        assertTrue("S2C 包必须校验方向，否则客户端能伪造它发到服务端",
                read(PACKET).contains("PacketGuard.fromServer"));
    }

    // ── 5. 页码指示读的值也下行了 ──────────────────────────────────────

    /**
     * {@code getFilteredCount()} 在客户端必须读快照带来的值。
     *
     * <p>这一条最容易被当成「细节」跳过，但少了它界面的「共 N 条」恒为 0、
     * 「1/0 页」的分母为 0 —— 格子有货了，页码却是空的，玩家仍然会以为没同步。</p>
     */
    @Test
    public void pageIndicatorReadsTheSyncedCount() throws IOException {
        String body = blockOf(read(MENU), "public int getFilteredCount()");
        assertTrue("getFilteredCount() 没找到，判据可能失配", !body.isEmpty());
        assertTrue("getFilteredCount() 必须在客户端读 clientFilteredCount，"
                + "否则界面显示「共 0 条」", body.contains("clientFilteredCount"));
    }

    // ── 判据不许空转 ───────────────────────────────────────────────────

    /**
     * 确认上面五条的判据在本仓<b>确实还能匹配到东西</b>。
     *
     * <p>代价：不这么做的话，某天有人把 {@code isClientSide()} 改名成
     * {@code onClient()}，五条断言会同时因为「方法体为空 / 找不到」而误报，
     * 或者更糟——被改成 {@code assertTrue(contains(...))} 之外的形态后<b>静默恒真</b>。
     * 这与 {@code TestNoHardcodedUiText#theDetectionPatternsStillMatchSomething} 同一个道理。</p>
     */
    @Test
    public void criteriaStillMatchSomething() throws IOException {
        String menu = read(MENU);
        assertTrue("判据失效：menu 里已找不到 isClientSide() 分支", menu.contains("isClientSide()"));
        assertTrue("判据失效：menu 里已找不到 clientVisible 镜像", menu.contains("clientVisible"));
        assertTrue("判据失效：menu 里已找不到 clientFilteredCount", menu.contains("clientFilteredCount"));
        assertTrue("判据失效：包类里已找不到 fromServer 方向校验",
                read(PACKET).contains("PacketGuard.fromServer"));
    }
}
