package cn.ism.mekck.client;

import net.minecraft.client.gui.GuiGraphics;

/**
 * 陈酿机的果汁液位条。
 * <p>
 * 优先**反射复用葡园酒香的绘制方法** {@code FermentationBarrelGui.drawJuiceBar(GuiGraphics, String, int, int, int)}
 * （参数：类型串、液位、x、y；内部读 {@code PlatformHelper.getMaxFluidLevel()} 并按类型选取贴图行），
 * 装了 vinery 时显示与原版发酵桶完全一致的果汁条；
 * 未安装 vinery 或反射失败时回退为自绘（同口径：宽 20px × 高 4px，宽度随液位/上限变化，颜色按红/白/苹果三类）。
 * </p>
 */
public final class VineryJuiceBar {

    /** 液位条尺寸（与葡园酒香的绘制方法同口径：最长 20px、高 4px）。 */
    public static final int WIDTH = 20;
    public static final int HEIGHT = 4;
    /** 液位上限（vinery 配置 maxFluidLevel 默认值）。 */
    public static final int MAX_LEVEL = 100;

    private static java.lang.reflect.Method nativeMethod;
    private static boolean nativeFailed = false;

    private VineryJuiceBar() {
    }

    /** 尝试使用葡园酒香的绘制方法；不可用返回 false（调用方转自绘）。 */
    public static boolean drawNative(GuiGraphics guiGraphics, String juiceType, int level, int x, int y) {
        if (nativeFailed) return false;
        try {
            if (nativeMethod == null) {
                Class<?> c = Class.forName("net.satisfy.vinery.client.gui.FermentationBarrelGui");
                nativeMethod = c.getMethod("drawJuiceBar", GuiGraphics.class, String.class,
                        int.class, int.class, int.class);
            }
            nativeMethod.invoke(null, guiGraphics, juiceType == null ? "" : juiceType, level, x, y);
            return true;
        } catch (Throwable t) {
            nativeFailed = true; // 未装 vinery 或签名变化：此后一律自绘
            return false;
        }
    }

    /** 自绘果汁条（外框 + 底槽 + 按液位的填充）。 */
    public static void drawFallback(GuiGraphics guiGraphics, String juiceType, int level, int x, int y) {
        guiGraphics.fill(x - 1, y - 1, x + WIDTH + 1, y + HEIGHT + 1, 0xFF1B1B1B);
        guiGraphics.fill(x, y, x + WIDTH, y + HEIGHT, 0xFF3A3A3A);
        int filled = (int) Math.max(0, Math.min(WIDTH, (long) Math.max(0, level) * WIDTH / MAX_LEVEL));
        if (filled > 0) {
            guiGraphics.fill(x, y, x + filled, y + HEIGHT, colorFor(juiceType));
        }
    }

    /** 按果汁类型取颜色（与外框/原版行的三类划分一致）。 */
    private static int colorFor(String juiceType) {
        if (juiceType == null || juiceType.isEmpty()) return 0xFF8A8A8A;
        if (juiceType.startsWith("red")) return 0xFFB02A3C;
        if (juiceType.startsWith("white")) return 0xFFE6DFC0;
        if (juiceType.equals("apple")) return 0xFFE0A63C;
        return 0xFF8A8A8A;
    }
}
