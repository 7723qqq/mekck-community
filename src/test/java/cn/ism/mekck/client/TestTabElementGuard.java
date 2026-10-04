package cn.ism.mekck.client;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * M22 的<b>源码形态</b>护栏（M4-7 / M4-8 / M4-9）。
 *
 * <p>任务书把本轮护栏文件名钉死为 {@code TestUpgradeSlotHitTest}（纯逻辑）与
 * {@code TestTabElementGuard}（源码形态）；M4-7/M4-9 的护栏按「源码形态」归入本文件。</p>
 *
 * <ul>
 *   <li><b>M4-8</b>：{@code MekCkTabElement.isMouseOver} 覆写丢了父类
 *       {@code GuiInsetElement.isMouseOver} 的 active/visible 守卫（javap 实测父类先查两者）；
 *       置灰 tab 会重新参与命中/tooltip。</li>
 *   <li><b>M4-7</b>：{@code renderGrowthSlotHint} 跑在 {@code drawForegroundText}（GUI 相对 pose）里，
 *       命中却拿绝对 mouseX 比 GUI 相对常量；tooltip 也因同一 pose 偏移 (leftPos, topPos)。</li>
 *   <li><b>M4-9</b>：vinery 皮肤下「Inventory」标签（x=20）压住流体条（x 10..28）下缘 6px。</li>
 * </ul>
 *
 * <p>这些缺陷编译通过、测试全绿、只在打开界面时可见，故只能钉源码形态；
 * 每条断言都要求真的匹配到东西（找不到方法/常量即红），避免判据空转。</p>
 */
public class TestTabElementGuard {

    private static final Path TAB_ELEMENT =
            Path.of("src/main/java/cn/ism/mekck/client/MekCkTabElement.java");
    private static final Path PLANTING_SCREEN =
            Path.of("src/main/java/cn/ism/mekck/client/PlantingCuttingStationScreen.java");
    private static final Path SIMPLE_MACHINE_SCREEN =
            Path.of("src/main/java/cn/ism/mekck/client/SimpleMachineScreen.java");

    /** STANDARD 流体条尺寸（javap 实测：GaugeOverlay.STANDARD 16×58 + GaugeInfo 边框 2）。 */
    private static final int GAUGE_W = 18;
    private static final int GAUGE_H = 60;
    /** MC 默认字体一行高。 */
    private static final int LABEL_H = 9;

    private static String read(Path path) throws IOException {
        assertTrue("找不到 " + path + "（源码测试需在仓库根目录运行）", Files.isRegularFile(path));
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    /** 截出方法体：从签名后的第一个 '{' 到配对 '}'（只看方法体，不受同名调用点/注释干扰）。 */
    private static String methodBody(String source, String signature) {
        int at = source.indexOf(signature);
        assertTrue("源码里找不到方法：" + signature, at >= 0);
        int open = source.indexOf('{', at);
        assertTrue("方法签名后找不到 '{'：" + signature, open >= 0);
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
        fail("方法体括号不配对：" + signature);
        return "";
    }

    private static int intConstant(String source, String name) {
        Matcher m = Pattern.compile("\\b" + Pattern.quote(name) + "\\s*=\\s*(\\d+)").matcher(source);
        assertTrue("源码里找不到常量 " + name + " 的字面量定义，判据可能失配", m.find());
        return Integer.parseInt(m.group(1));
    }

    // ── M4-8 ──────────────────────────────────────────────────────────

    @Test
    public void tabElementIsMouseOverKeepsActiveAndVisibleGuards() throws IOException {
        String body = methodBody(read(TAB_ELEMENT),
                "public boolean isMouseOver(double mouseX, double mouseY)");
        assertTrue("MekCkTabElement.isMouseOver 必须保留父类的 active 守卫（!active ⇒ false）",
                Pattern.compile("!\\s*active\\b").matcher(body).find());
        assertTrue("MekCkTabElement.isMouseOver 必须保留父类的 visible 守卫（!visible ⇒ false）",
                Pattern.compile("!\\s*visible\\b").matcher(body).find());
    }

    // ── M4-7 ──────────────────────────────────────────────────────────

    @Test
    public void growthSlotHintHitTestConvertsMouseToGuiRelative() throws IOException {
        String body = methodBody(read(PLANTING_SCREEN),
                "private void renderGrowthSlotHint(GuiGraphics guiGraphics, int mouseX, int mouseY)");
        assertTrue("命中必须把绝对 mouseX 换算成 GUI 相对（mouseX - leftPos）",
                body.contains("mouseX - leftPos"));
        assertTrue("命中必须把绝对 mouseY 换算成 GUI 相对（mouseY - topPos）",
                body.contains("mouseY - topPos"));
        assertFalse("不得再用绝对 mouseX 直接比 GUI 相对常量 GROWTH_SLOT_X",
                body.contains("mouseX >= GROWTH_SLOT_X"));
        assertFalse("不得再用绝对 mouseY 直接比 GUI 相对常量 GROWTH_SLOT_Y",
                body.contains("mouseY >= GROWTH_SLOT_Y"));
        assertTrue("tooltip 必须在抵消 GUI 平移的 pose 下渲染（translate(-leftPos, -topPos)），"
                        + "否则整体偏移 (leftPos, topPos)",
                body.contains("translate(-leftPos, -topPos"));
    }

    // ── M4-9 ──────────────────────────────────────────────────────────

    @Test
    public void vineryInventoryLabelClearsTheFluidGauge() throws IOException {
        String source = read(SIMPLE_MACHINE_SCREEN);
        int gaugeX = intConstant(source, "WV_GAUGE_X");
        int gaugeY = intConstant(source, "WV_GAUGE_Y");
        int labelX = intConstant(source, "VINERY_LABEL_X");
        int labelY = vineryLabelY(source);

        assertTrue("vinery 标签 x 必须让开流体条右缘（条 " + gaugeX + ".." + (gaugeX + GAUGE_W)
                        + "，标签 x=" + labelX + "）",
                labelX >= gaugeX + GAUGE_W);
        // 判据不许空转：标签 y 确实落在条的高度范围内，否则这条断言守的是一个不存在的重叠
        assertTrue("判据失效：标签 y=" + labelY + " 已不在流体条 y 范围 "
                        + gaugeY + ".." + (gaugeY + GAUGE_H) + " 内",
                labelY < gaugeY + GAUGE_H && labelY + LABEL_H > gaugeY);
    }

    /** vinery 分支的 inventoryLabelY 字面量（构造器 if (menu.vineryLayout()) 块内）。 */
    private static int vineryLabelY(String source) {
        int at = source.indexOf("if (menu.vineryLayout()) {");
        assertTrue("找不到 vineryLayout 分支，判据失配", at >= 0);
        Matcher m = Pattern.compile("inventoryLabelY\\s*=\\s*(\\d+)\\s*;").matcher(source.substring(at));
        assertTrue("vinery 分支里找不到 inventoryLabelY 字面量赋值", m.find());
        return Integer.parseInt(m.group(1));
    }
}
