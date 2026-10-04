package cn.ism.mekck.client;

import cn.ism.mekck.menu.CentralKitchenMenu;
import cn.ism.mekck.network.KitchenOrderPacket;
import cn.ism.mekck.network.KitchenOrderResultPacket;
import cn.ism.mekck.network.ModMessages;
import cn.ism.mekck.network.NetworkOrderPacket;
import mekanism.client.gui.IGuiWrapper;
import mekanism.client.gui.element.window.GuiWindow;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.ArrayList;
import java.util.List;

/**
 * 中央厨房的「下单」窗口：配方列表（分页）+ 数量调节 + 预览 / 下单 + 结果文本。
 * 独立弹窗，避免与主界面槽位重叠。
 */
@OnlyIn(Dist.CLIENT)
public class KitchenOrderWindow extends GuiWindow {

    private static final int ROWS = 8;
    private static final int ROW_H = 11;
    private static final int LIST_X = 8;
    private static final int LIST_Y = 22;
    private static final int BTN_W = 46;
    private static final int BTN_H = 14;

    private final CentralKitchenMenu menu;
    /** 「ME 来源」下单面板（AE 终端风格）；窗口里不放搜索框（键盘事件不经此窗口）。 */
    private final NetworkOrderPanel mePanel = new NetworkOrderPanel(false);
    private final List<Recipe<?>> recipeList = new ArrayList<>();
    private int recipePage = 0;
    private int selectedRecipe = -1;
    private int orderCount = 1;
    private int familyMaskCache = -1;

    public KitchenOrderWindow(IGuiWrapper gui, CentralKitchenMenu menu) {
        // 窗口身份必须用 UNSPECIFIED（同 NetworkOrderWindow）：UPGRADE 会与升级窗共用
        // 「上次位置」存档，先开升级窗再开本窗会弹到升级窗的旧位置上。
        super(gui, gui.getWidth() / 2 - 120, 18, 240, 176,
                mekanism.common.inventory.container.SelectedWindowData.WindowType.UNSPECIFIED);
        this.menu = menu;
        interactionStrategy = InteractionStrategy.ALL;
    }

    /** 供 {@code CentralKitchenScreen} / 网络回包读取的 ME 面板。 */
    public NetworkOrderPanel panel() {
        return mePanel;
    }

    /** 面板几何（AE 面板画在窗口内容区里）。 */
    private int panelX() {
        return relativeX + 2;
    }

    private int panelY() {
        return relativeY + 12;
    }

    private static final int PANEL_W = 236;
    private static final int PANEL_H = 160;

    /** 按已安装系列收集可下单配方（客户端本地遍历配方管理器）。 */
    private void refresh() {
        int mask = menu.getFamilyMask();
        if (mask == familyMaskCache) return;
        familyMaskCache = mask;
        recipeList.clear();
        var level = menu.getMachine().getLevel();
        if (level == null) return;
        for (var family : cn.ism.mekck.kitchen.KitchenFamily.values()) {
            if ((mask & (1 << family.ordinal())) == 0) continue;
            for (String typeId : family.recipeTypes) {
                var rl = net.minecraft.resources.ResourceLocation.tryParse(typeId);
                if (rl == null) continue;
                var type = cn.ism.mekck.util.RecipeCache.type(rl);
                if (type == null) continue;
                try {
                    for (var recipe : cn.ism.mekck.util.RecipeCache.all(level, type)) {
                        recipeList.add(recipe);
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        recipePage = 0;
        selectedRecipe = -1;
    }

    private int maxPage() {
        return Math.max(0, (recipeList.size() - 1) / ROWS);
    }

    private String labelOf(Recipe<?> recipe) {
        try {
            var level = menu.getMachine().getLevel();
            if (level == null) return recipe.getId().toString();
            return recipe.getResultItem(level.registryAccess()).getHoverName().getString();
        } catch (Throwable t) {
            return recipe.getId().toString();
        }
    }

    @Override
    public void renderForeground(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        super.renderForeground(guiGraphics, mouseX, mouseY);
        // ME 来源：整块换成 AE 终端风格面板（本机"下单"列表原样保留在下面）
        mePanel.bind(menu.getMachine().getBlockPos());
        if (mePanel.isMe()) {
            mePanel.render(guiGraphics, getFont(), panelX(), panelY(), PANEL_W, PANEL_H, mouseX, mouseY, 0f);
            return;
        }
        refresh();
        drawTitleText(guiGraphics, Component.translatable("gui.mekck.ui.order"), 5);

        int px = relativeX + LIST_X;
        int py = relativeY + LIST_Y;
        int mask = menu.getFamilyMask();

        // 右侧留给「本机 / ME」切换按钮，避免长清单信息串到按钮上
        String status = Component.translatable("gui.mekck.ui.order_status",
                Integer.bitCount(mask), menu.getOrderCount(),
                recipePage + 1, maxPage() + 1, recipeList.size()).getString();
        guiGraphics.drawString(getFont(), getFont().plainSubstrByWidth(status, 138),
                px, relativeY + 12, 0xFF404040, false);

        int start = recipePage * ROWS;
        for (int i = 0; i < ROWS; i++) {
            int idx = start + i;
            if (idx >= recipeList.size()) break;
            boolean sel = idx == selectedRecipe;
            int rowY = py + i * ROW_H;
            if (sel) guiGraphics.fill(px - 3, rowY - 1, px + 220, rowY + ROW_H - 2, 0x4000FF00);
            guiGraphics.drawString(getFont(), (sel ? "▶ " : "  ") + labelOf(recipeList.get(idx)),
                    px, rowY, sel ? 0xFF106010 : 0xFF202020, false);
        }

        // 数量与按钮行
        int by = relativeY + LIST_Y + ROWS * ROW_H + 4;
        drawSmallButton(guiGraphics, px, by, 16, BTN_H, "−");
        drawSmallButton(guiGraphics, px + 18, by, 40, BTN_H, "×" + orderCount);
        drawSmallButton(guiGraphics, px + 60, by, 16, BTN_H, "+");
        drawSmallButton(guiGraphics, px + 84, by, BTN_W, BTN_H, Component.translatable("gui.mekck.ui.prev_page").getString());
        drawSmallButton(guiGraphics, px + 84 + BTN_W + 2, by, BTN_W, BTN_H, Component.translatable("gui.mekck.ui.next_page").getString());
        drawSmallButton(guiGraphics, px + 84 + (BTN_W + 2) * 2, by, BTN_W - 6, BTN_H, Component.translatable("gui.mekck.ui.preview").getString());
        drawSmallButton(guiGraphics, px + 84 + (BTN_W + 2) * 2 + BTN_W - 4, by, BTN_W - 6, BTN_H,
                Component.translatable("gui.mekck.ui.order").getString());

        // 结果文本
        String result = KitchenOrderResultPacket.lastPreview;
        int ry = by + BTN_H + 4;
        if (result != null && !result.isEmpty()) {
            String[] lines = result.split("\n");
            for (int i = 0; i < Math.min(4, lines.length); i++) {
                guiGraphics.drawString(getFont(), lines[i], px, ry + i * 10, 0xFF106010, false);
            }
        }
        String last = KitchenOrderResultPacket.lastResult;
        if (last != null && !last.isEmpty()) {
            guiGraphics.drawString(getFont(), last.replace("§a", "").replace("§c", ""),
                    px, ry + 4 * 10, last.contains("§c") ? 0xFFA02020 : 0xFF106010, false);
        }
        // 来源切换按钮**最后画**（本机列表会盖住它，点击判定仍在前面）
        mePanel.renderModeButtons(guiGraphics, getFont(), panelX(), panelY(), PANEL_W, mouseX, mouseY);
    }

    private void drawSmallButton(GuiGraphics guiGraphics, int bx, int by, int w, int h, String label) {
        guiGraphics.fill(bx, by, bx + w, by + h, 0xFF5A5A5A);
        guiGraphics.fill(bx + 1, by + 1, bx + w - 1, by + h - 1, 0xFFB0B0B0);
        guiGraphics.drawString(getFont(), label, bx + (w - getFont().width(label)) / 2, by + 3,
                0xFF101010, false);
    }

    private void sendOrder(byte mode) {
        if (selectedRecipe < 0 || selectedRecipe >= recipeList.size()) return;
        ModMessages.sendToServer(new KitchenOrderPacket(menu.getMachine().getBlockPos(), mode,
                recipeList.get(selectedRecipe).getId().toString(), orderCount));
    }

    @Override
    public mekanism.client.gui.element.GuiElement mouseClickedNested(double mouseX, double mouseY, int button) {
        // ME 来源：整块交给共用面板（几何与渲染共用）
        mePanel.bind(menu.getMachine().getBlockPos());
        if (mePanel.isMe()) {
            if (mouseX >= panelX() && mouseX <= panelX() + PANEL_W
                    && mouseY >= panelY() && mouseY <= panelY() + PANEL_H) {
                mePanel.mouseClicked(mouseX, mouseY, button, panelX(), panelY(), PANEL_W, PANEL_H,
                        (recipeId, qty) -> ModMessages.sendToServer(new NetworkOrderPacket(
                                menu.getMachine().getBlockPos(), recipeId.toString(), qty)));
                return this;
            }
            return super.mouseClickedNested(mouseX, mouseY, button);
        }
        if (mePanel.handleModeClick(mouseX, mouseY, panelX(), panelY(), PANEL_W)) {
            return this;
        }

        int px = relativeX + LIST_X;
        int py = relativeY + LIST_Y;
        // 配方行
        for (int i = 0; i < ROWS; i++) {
            int idx = recipePage * ROWS + i;
            if (idx >= recipeList.size()) break;
            int rowY = py + i * ROW_H;
            if (mouseX >= px - 3 && mouseX <= px + 220 && mouseY >= rowY - 1 && mouseY < rowY + ROW_H - 2) {
                selectedRecipe = (selectedRecipe == idx) ? -1 : idx;
                return this;
            }
        }
        // 按钮行
        int by = relativeY + LIST_Y + ROWS * ROW_H + 4;
        if (mouseY >= by && mouseY < by + BTN_H) {
            int bx = px;
            if (hit(mouseX, bx, 16)) { orderCount = Math.max(1, orderCount - 1); return this; }
            bx += 18;
            if (hit(mouseX, bx, 40)) { orderCount = Math.min(9999, orderCount * 2); return this; }
            bx += 60;
            if (hit(mouseX, bx, 16)) { orderCount = Math.min(9999, orderCount + 1); return this; }
            bx += 84;
            if (hit(mouseX, bx, BTN_W)) { recipePage = Math.max(0, recipePage - 1); return this; }
            bx += BTN_W + 2;
            if (hit(mouseX, bx, BTN_W)) { recipePage = Math.min(maxPage(), recipePage + 1); return this; }
            bx += BTN_W + 2;
            if (hit(mouseX, bx, BTN_W - 6)) { sendOrder((byte) 0); return this; }
            bx += BTN_W - 4;
            if (hit(mouseX, bx, BTN_W - 6)) { sendOrder((byte) 1); return this; }
        }
        return super.mouseClickedNested(mouseX, mouseY, button);
    }

    private static boolean hit(double mouseX, int bx, int w) {
        return mouseX >= bx && mouseX < bx + w;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (mePanel.isMe() && mePanel.keyPressed(keyCode, scanCode, modifiers)) return true;
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (mePanel.isMe()) {
            return mePanel.mouseScrolled(delta);
        }
        if (selectedRecipe >= 0 || !recipeList.isEmpty()) {
            int next = Math.max(0, Math.min(maxPage(), recipePage + (delta > 0 ? -1 : 1)));
            if (next != recipePage) {
                recipePage = next;
                return true;
            }
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }
}
