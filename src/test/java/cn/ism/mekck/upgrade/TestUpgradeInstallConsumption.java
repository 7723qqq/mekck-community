package cn.ism.mekck.upgrade;

import cn.ism.mekck.TestSourceText;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * C1 回归护栏：潜行右键安装升级必须恰好消耗一次手持物品。
 *
 * <h3>缺陷形态</h3>
 * 潜行安装的<b>唯一活路径</b>是 {@code UpgradeInstallHandler.onRightClickBlock}
 * （Forge 的 {@code RightClickBlock} 事件在原版潜行跳过逻辑之前触发）。它调用
 * {@code addUpgradesFromHand} 把升级写进机器槽位后直接取消事件，却从不 shrink 手持物品；
 * 而 9 个方块类 {@code use()} 里的同款 {@code held.shrink(added)} 是死代码
 * （原版 {@code ServerPlayerGameMode.useItemOn}：潜行 + 非 {@code doesSneakBypassUse}
 * 物品时跳过 {@code blockstate.use}）。结果：升级物品进机器、玩家手持堆叠不减，可无限复制。
 *
 * <h3>覆盖边界</h3>
 * <ul>
 *   <li><b>覆盖</b>：handler 的 {@code added > 0} 分支必须出现 {@code held.shrink(added)}
 *       与 {@code setItemInHand}，且整个 handler 只允许一处 shrink（防双倍扣除）。</li>
 *   <li><b>不覆盖</b>：Forge 事件时序与「方块类分支是死代码」这一运行时事实 ——
 *       它需要真实游戏环境，只能由实机/GameTest 兜底；本测试用源码形态钉住修复本身。</li>
 * </ul>
 */
public class TestUpgradeInstallConsumption {

    private static final String HANDLER =
            "src/main/java/cn/ism/mekck/upgrade/UpgradeInstallHandler.java";

    @Test
    public void handlerConsumesHeldStackWhenInstallSucceeds() throws Exception {
        String src = TestSourceText.read(HANDLER);
        String method = TestSourceText.methodBody(src,
                "public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {");
        assertFalse("找不到 onRightClickBlock 方法体（改名了就同步更新本测试）", method.isEmpty());

        String branch = TestSourceText.methodBody(method, "if (added > 0) {");
        assertFalse("找不到 added > 0 分支（改名了就同步更新本测试）", branch.isEmpty());
        assertTrue("added > 0 分支必须消耗手持物品：held.shrink(added)。"
                        + "漏掉它 = 升级物品（含创造/冷萃/费列罗）可无限复制",
                branch.contains("held.shrink(added)"));
        assertTrue("消耗后必须写回手持槽（与 9 个方块类同款）：setItemInHand",
                branch.contains("setItemInHand"));
    }

    @Test
    public void handlerShrinksExactlyOnce() throws Exception {
        String src = TestSourceText.read(HANDLER);
        assertEquals("UpgradeInstallHandler 只允许一处 shrink（双倍扣除 = 玩家白丢物品）",
                1, countOccurrences(src, ".shrink("));
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int i = 0;
        while ((i = haystack.indexOf(needle, i)) >= 0) {
            count++;
            i += needle.length();
        }
        return count;
    }
}
