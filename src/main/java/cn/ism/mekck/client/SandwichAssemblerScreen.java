package cn.ism.mekck.client;

import cn.ism.mekck.blockentity.SandwichAssemblerBlockEntity;
import cn.ism.mekck.menu.SandwichAssemblerMenu;
import cn.ism.mekck.network.ModMessages;
import cn.ism.mekck.network.SandwichConfigPacket;
import mekanism.client.gui.element.window.GuiWindow;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * 三明治组装机界面：有序输入格 32 + 样品槽 + 材料区 27 + 返还槽 + 输出槽 + 模式/数量控件 + 进度条。
 */
@OnlyIn(Dist.CLIENT)
public class SandwichAssemblerScreen extends mekanism.client.gui.GuiMekanism<SandwichAssemblerMenu> {

    private static final int MODE_BTN_X = 240;
    private static final int MODE_BTN_Y = 104;
    private static final int BTN_W = 92;
    private static final int BTN_H = 16;
    private static final int COUNT_Y = 122;
    private static final int SLOT_BG = 0xFF8B8B8B;
    private static final int SIDE_BTN_X = 240;
    private static final int SIDE_BTN_Y = 140;
    private GuiMekCkSideConfiguration sideWindow;

    public SandwichAssemblerScreen(SandwichAssemblerMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        this.imageWidth = SandwichAssemblerMenu.IMAGE_WIDTH;
        this.imageHeight = SandwichAssemblerMenu.IMAGE_HEIGHT;
        this.inventoryLabelY = 164 - 10;
    }

    @Override
    protected void renderBg(GuiGraphics guiGraphics, float partialTick, int mouseX, int mouseY) {
        super.renderBg(guiGraphics, partialTick, mouseX, mouseY);
        int x = leftPos;
        int y = topPos;
        // 有序输入格（自定义模式）
        for (int i = 0; i < SandwichAssemblerBlockEntity.ORDERED_SLOTS; i++) {
            drawSlot(guiGraphics, x + 7 + (i % 8) * 18, y + 19 + (i / 8) * 18, 0xFF9B9B9B);
        }
        // 材料区（复制模式）
        for (int i = 0; i < SandwichAssemblerBlockEntity.MATERIAL_SLOTS; i++) {
            drawSlot(guiGraphics, x + 169 + (i % 9) * 18, y + 19 + (i / 9) * 18, 0xFF9B9B9B);
        }
        // 样品 / 输出 / 返还 / 升级 / 能源
        drawSlot(guiGraphics, x + 169, y + 79, 0xFFD0A0A0);
        // 样品槽空位提示：半透明三明治图标（先画图标，再覆盖半透明底色做“幽灵”淡出）
        var sample = menu.getMachine().items.getStackInSlot(
                SandwichAssemblerBlockEntity.SAMPLE_SLOT);
        if (sample.isEmpty()) {
            var ghost = SandwichAssemblerBlockEntity.sarSandwich();
            if (!ghost.isEmpty()) {
                guiGraphics.renderItem(ghost, x + 170, y + 80);
            }
            guiGraphics.fill(x + 170, y + 80, x + 186, y + 96, 0xC0D0A0A0);
        }
        // 样品槽悬停说明
        if (mouseX >= x + 169 && mouseX < x + 169 + 18 && mouseY >= y + 79 && mouseY < y + 79 + 18) {
            guiGraphics.renderTooltip(font, java.util.List.of(
                    net.minecraft.network.chat.Component.literal("三明治样品槽"),
                    net.minecraft.network.chat.Component.literal("§7放入一个手工做好的三明治"),
                    net.minecraft.network.chat.Component.literal("§7机器会照它的材料清单自动量产同款"),
                    net.minecraft.network.chat.Component.literal("§8需要安装「三明治」模组")
            ), java.util.Optional.empty(), mouseX, mouseY);
        }
        drawSlot(guiGraphics, x + 199, y + 79, 0xFFA0D0A0);
        for (int i = 0; i < SandwichAssemblerBlockEntity.RETURN_SLOTS; i++) {
            drawSlot(guiGraphics, x + 239 + i * 18, y + 79, 0xFFB0B0B0);
        }
        drawSlot(guiGraphics, x + 169, y + 103, 0xFFB0B0C0);
        drawSlot(guiGraphics, x + 169, y + 121, 0xFFB0B0C0);
        drawSlot(guiGraphics, x + 169, y + 139, 0xFFB0B0C0);
        drawSlot(guiGraphics, x + 309, y + 7, 0xFFB0B0B0);

        // 模式按钮
        int mode = menu.getMode();
        guiGraphics.fill(x + MODE_BTN_X, y + MODE_BTN_Y, x + MODE_BTN_X + BTN_W, y + MODE_BTN_Y + BTN_H, 0xFF5A5A5A);
        int modeInner = mode == SandwichAssemblerBlockEntity.MODE_COPY ? 0xFF7FA0D0
                : mode == SandwichAssemblerBlockEntity.MODE_CUSTOM ? 0xFFD0A070 : 0xFFA0D0A0;
        guiGraphics.fill(x + MODE_BTN_X + 1, y + MODE_BTN_Y + 1, x + MODE_BTN_X + BTN_W - 1,
                y + MODE_BTN_Y + BTN_H - 1, modeInner);
        // 数量 - / +
        drawButton(guiGraphics, x + MODE_BTN_X, y + COUNT_Y, 20, BTN_H, "-");
        drawButton(guiGraphics, x + MODE_BTN_X + BTN_W - 20, y + COUNT_Y, 20, BTN_H, "+");
        // 侧面配置按钮
        drawButton(guiGraphics, x + SIDE_BTN_X, y + SIDE_BTN_Y, 40, BTN_H, "侧配");
        // 进度条
        int pw = BTN_W;
        int ph = 8;
        int px = x + MODE_BTN_X;
        int py = y + 144;
        guiGraphics.fill(px, py, px + pw, py + ph, 0xFF303030);
        int fill = (int) (pw * menu.getProgressRatio());
        if (fill > 0) guiGraphics.fill(px, py, px + fill, py + ph, 0xFF55D055);
    }

    private void drawButton(GuiGraphics guiGraphics, int bx, int by, int w, int h, String label) {
        guiGraphics.fill(bx, by, bx + w, by + h, 0xFF5A5A5A);
        guiGraphics.fill(bx + 1, by + 1, bx + w - 1, by + h - 1, 0xFF9A9A9A);
        guiGraphics.drawString(font, label, bx + (w - font.width(label)) / 2, by + 4, 0xFF101010, false);
    }

    private void drawSlot(GuiGraphics guiGraphics, int sx, int sy, int inner) {
        guiGraphics.fill(sx, sy, sx + 18, sy + 18, 0xFF373737);
        guiGraphics.fill(sx + 1, sy + 1, sx + 17, sy + 17, inner);
    }

    @Override
    protected void drawForegroundText(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        renderTitleText(guiGraphics);
        drawString(guiGraphics, playerInventoryTitle, 8, inventoryLabelY, titleTextColor());
        int x = leftPos;
        int y = topPos;
        int mode = menu.getMode();
        String modeLabel = mode == SandwichAssemblerBlockEntity.MODE_COPY ? "复制样品模式"
                : mode == SandwichAssemblerBlockEntity.MODE_CUSTOM ? "自定义组装模式" : "序列组模式";
        guiGraphics.drawString(font, modeLabel,
                x + MODE_BTN_X - leftPos + 6, MODE_BTN_Y + 4, 0xFF101010, false);
        String countText = mode == SandwichAssemblerBlockEntity.MODE_COPY ? "自动（材料够就产）"
                : mode == SandwichAssemblerBlockEntity.MODE_SEQUENCED ? "有序格+流体罐"
                : ("剩余: " + menu.getTargetCount());
        guiGraphics.drawString(font, countText, x + MODE_BTN_X + 24, y + COUNT_Y + 4, 0xFF202020, false);
        if (!SandwichAssemblerBlockEntity.hasSar()) {
            guiGraphics.drawString(font, "§c需要安装 Some Assembly Required", 8, 96, 0xFFFF5555, false);
        }
        guiGraphics.drawString(font, "层数: " + menu.getMachine().currentLayers(),
                8, 106, 0xFF404040, false);
        super.drawForegroundText(guiGraphics, mouseX, mouseY);
    }

    /**
     * 打开一扇窗口 —— <b>两条注册都要做</b>。
     *
     * <ul>
     *   <li>{@code addRenderableWidget} 把它放进 {@code Screen.renderables}，
     *       而 {@code Screen.render} 正是遍历那份列表画的；</li>
     *   <li>{@code addWindow} 把它放进 {@code GuiMekanism.windows}，
     *       而 {@code GuiMekanism.mouseClicked} / {@code keyPressed} 都是遍历那份 LRU 的。</li>
     * </ul>
     * 只做前者 ⇒ 窗口画得出来但点不动，连它自己的关闭按钮都按不了。
     */
    private void openWindow(GuiWindow window) {
        addRenderableWidget(window);
        addWindow(window);
    }

    /** 关闭一扇窗口 —— 与 {@link #openWindow} 对称，两条注册都要撤。 */
    private void closeWindow(GuiWindow window) {
        window.close();       // 第一句就是 gui().removeWindow(this)，出窗口 LRU
        removeWidget(window); // 出 renderables / children
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        int x = leftPos;
        int y = topPos;
        // 窗口可能已被它自己的关闭按钮关掉（close() 只出 LRU，不出 renderables），先对一次账。
        if (sideWindow != null && !getWindows().contains(sideWindow)) {
            removeWidget(sideWindow);
            sideWindow = null;
        }
        // 侧面配置
        if (inRect(mouseX, mouseY, x + SIDE_BTN_X, y + SIDE_BTN_Y, 40, BTN_H)) {
            if (sideWindow == null) {
                sideWindow = new GuiMekCkSideConfiguration(this, menu, () -> {
                    var state = menu.getMachine().getBlockState();
                    return state.hasProperty(cn.ism.mekck.block.SandwichAssemblerBlock.FACING)
                            ? state.getValue(cn.ism.mekck.block.SandwichAssemblerBlock.FACING)
                            : net.minecraft.core.Direction.NORTH;
                });
                openWindow(sideWindow);
            } else {
                closeWindow(sideWindow);
                sideWindow = null;
            }
            return true;
        }
        // 模式按钮（三态循环：复制→自定义→序列组→复制）
        if (inRect(mouseX, mouseY, x + MODE_BTN_X, y + MODE_BTN_Y, BTN_W, BTN_H)) {
            ModMessages.sendToServer(new SandwichConfigPacket(menu.getMachine().getBlockPos(), (byte) 0,
                    (menu.getMode() + 1) % 3));
            return true;
        }
        // 数量 -
        if (inRect(mouseX, mouseY, x + MODE_BTN_X, y + COUNT_Y, 20, BTN_H)) {
            ModMessages.sendToServer(new SandwichConfigPacket(menu.getMachine().getBlockPos(), (byte) 2, -1));
            return true;
        }
        // 数量 +
        if (inRect(mouseX, mouseY, x + MODE_BTN_X + BTN_W - 20, y + COUNT_Y, 20, BTN_H)) {
            ModMessages.sendToServer(new SandwichConfigPacket(menu.getMachine().getBlockPos(), (byte) 2, 1));
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        int x = leftPos;
        int y = topPos;
        if (inRect(mouseX, mouseY, x + MODE_BTN_X, y + COUNT_Y, BTN_W, BTN_H)) {
            ModMessages.sendToServer(new SandwichConfigPacket(menu.getMachine().getBlockPos(), (byte) 2,
                    delta > 0 ? 1 : -1));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    private static boolean inRect(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }
}
