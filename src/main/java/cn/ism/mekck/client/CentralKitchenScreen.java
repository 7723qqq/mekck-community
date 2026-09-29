package cn.ism.mekck.client;

import cn.ism.mekck.menu.CentralKitchenMenu;
import cn.ism.mekck.network.KitchenViewPacket;
import cn.ism.mekck.network.ModMessages;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.lwjgl.glfw.GLFW;

/**
 * 中央厨房界面：左侧 20 模块槽、中部 6×9 存储区视图（滚轮滚动 / 搜索 / 排序）、右侧输出区，
 * 顶部显示双温度与线程状态。
 */
@OnlyIn(Dist.CLIENT)
public class CentralKitchenScreen extends mekanism.client.gui.GuiMekanism<CentralKitchenMenu>
        implements NetworkOrderHost {

    private static final int WIDTH = 348;
    private static final int HEIGHT = 236;
    /** 搜索框尺寸与位置。 */
    private static final int SEARCH_W = 120;
    private static final int SEARCH_H = 12;
    private static final int SEARCH_X = 120;
    private static final int SEARCH_Y = 4;
    private static final int SORT_X = 246;
    private static final int SORT_Y = 3;
    /** 模块窗口按钮。 */
    private static final int MODULE_BTN_X = 293;
    private static final int MODULE_BTN_Y = 102;
    private static final int MODULE_BTN_W = 52;
    private static final int MODULE_BTN_H = 12;
    private KitchenModuleWindow moduleWindow;
    /** 侧配窗口按钮。 */
    private static final int SIDE_BTN_X = 293;
    private static final int SIDE_BTN_Y = 118;
    /** 下单窗口按钮。 */
    private static final int ORDER_BTN_X = 293;
    private static final int ORDER_BTN_Y = 134;
    /** 三明治样品槽（界面坐标，与菜单槽位一致）。 */
    private static final int SAMPLE_X = 293;
    private static final int SAMPLE_Y = 78;
    private KitchenOrderWindow orderWindow;
    private static final int SIDE_BTN_W = 52;
    private static final int SIDE_BTN_H = 12;
    private GuiMekCkSideConfiguration sideWindow;

    private boolean searchFocused = false;
    private String searchText = "";

    // ===== 下单面板状态 =====
    /** 当前显示的配方列表（由已安装系列收集，客户端本地计算）。 */

    public CentralKitchenScreen(CentralKitchenMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        this.imageWidth = WIDTH;
        this.imageHeight = HEIGHT;
        this.inventoryLabelY = 152 - 10;
    }

    @Override
    protected void renderBg(GuiGraphics guiGraphics, float partialTick, int mouseX, int mouseY) {
        super.renderBg(guiGraphics, partialTick, mouseX, mouseY);
        int x = leftPos;
        int y = topPos;
        for (int i = 0; i < CentralKitchenMenu.VISIBLE_MODULES; i++) {
            drawSlotBg(guiGraphics, x + 11 + (i % 5) * 18, y + 19 + (i / 5) * 18, 0xFF8B8B8B);
        }
        for (int i = 0; i < CentralKitchenMenu.VISIBLE_STORAGE; i++) {
            drawSlotBg(guiGraphics, x + 119 + (i % 9) * 18, y + 19 + (i / 9) * 18, 0xFF9B9B9B);
        }
        for (int i = 0; i < CentralKitchenMenu.VISIBLE_OUTPUT; i++) {
            drawSlotBg(guiGraphics, x + 293 + (i % 3) * 18, y + 19 + (i / 3) * 18, 0xFF9B9B9B);
        }
        // 搜索框
        int sx = x + SEARCH_X;
        int sy = y + SEARCH_Y;
        guiGraphics.fill(sx, sy, sx + SEARCH_W, sy + SEARCH_H, 0xFF000000);
        guiGraphics.fill(sx + 1, sy + 1, sx + SEARCH_W - 1, sy + SEARCH_H - 1,
                searchFocused ? 0xFFFFFFFF : 0xFFDDDDDD);
        String shown = searchText.isEmpty() ? "搜索..." : searchText;
        guiGraphics.drawString(font, shown, sx + 3, sy + 3,
                searchText.isEmpty() ? 0xFF888888 : 0xFF000000, false);
        // 滚动位置提示
        String scrollInfo = (menu.getScrollRow() + 1) + "/" + (menu.maxScrollRow() + 1)
                + "  (" + menu.getFilteredCount() + ")";
        guiGraphics.drawString(font, scrollInfo, x + 120, y + 132, 0xFF404040, false);

        // ===== 三明治样品槽 =====
        drawSlotBg(guiGraphics, x + SAMPLE_X, y + SAMPLE_Y, 0xFFD0C0A0);
        var sampleStack = menu.getMachine().items.getStackInSlot(
                cn.ism.mekck.blockentity.CentralKitchenBlockEntity.SANDWICH_SAMPLE_SLOT);
        if (sampleStack.isEmpty()) {
            // 空位提示：半透明三明治图标（先画图标，再覆盖半透明底色做“幽灵”淡出效果）
            var ghost = cn.ism.mekck.blockentity.SandwichAssemblerBlockEntity.sarSandwich();
            if (!ghost.isEmpty()) {
                guiGraphics.renderItem(ghost, x + SAMPLE_X + 1, y + SAMPLE_Y + 1);
            }
            guiGraphics.fill(x + SAMPLE_X + 1, y + SAMPLE_Y + 1,
                    x + SAMPLE_X + 17, y + SAMPLE_Y + 17, 0xC0D0C0A0);
        }
        // 悬停说明
        if (mouseX >= x + SAMPLE_X && mouseX < x + SAMPLE_X + 18
                && mouseY >= y + SAMPLE_Y && mouseY < y + SAMPLE_Y + 18) {
            guiGraphics.renderTooltip(font, java.util.List.of(
                    net.minecraft.network.chat.Component.literal("三明治样品槽"),
                    net.minecraft.network.chat.Component.literal("§7放入一个手工做好的三明治"),
                    net.minecraft.network.chat.Component.literal("§7机器会照它的材料清单从存储区量产"),
                    net.minecraft.network.chat.Component.literal("§8需要安装「三明治」模组")
            ), java.util.Optional.empty(), mouseX, mouseY);
        }
        // 模块窗口按钮
        guiGraphics.fill(x + MODULE_BTN_X, y + MODULE_BTN_Y,
                x + MODULE_BTN_X + MODULE_BTN_W, y + MODULE_BTN_Y + MODULE_BTN_H, 0xFF5A5A5A);
        guiGraphics.fill(x + MODULE_BTN_X + 1, y + MODULE_BTN_Y + 1,
                x + MODULE_BTN_X + MODULE_BTN_W - 1, y + MODULE_BTN_Y + MODULE_BTN_H - 1, 0xFF9A9A9A);
        guiGraphics.drawString(font, "可安装模块", x + MODULE_BTN_X + 2, y + MODULE_BTN_Y + 2, 0xFF101010, false);
        // 侧面配置按钮
        guiGraphics.fill(x + SIDE_BTN_X, y + SIDE_BTN_Y,
                x + SIDE_BTN_X + SIDE_BTN_W, y + SIDE_BTN_Y + SIDE_BTN_H, 0xFF5A5A5A);
        guiGraphics.fill(x + SIDE_BTN_X + 1, y + SIDE_BTN_Y + 1,
                x + SIDE_BTN_X + SIDE_BTN_W - 1, y + SIDE_BTN_Y + SIDE_BTN_H - 1, 0xFF9A9A9A);
        guiGraphics.drawString(font, "侧面配置", x + SIDE_BTN_X + 5, y + SIDE_BTN_Y + 2, 0xFF101010, false);
        // 下单按钮
        guiGraphics.fill(x + ORDER_BTN_X, y + ORDER_BTN_Y,
                x + ORDER_BTN_X + MODULE_BTN_W, y + ORDER_BTN_Y + MODULE_BTN_H, 0xFF5A5A5A);
        guiGraphics.fill(x + ORDER_BTN_X + 1, y + ORDER_BTN_Y + 1,
                x + ORDER_BTN_X + MODULE_BTN_W - 1, y + ORDER_BTN_Y + MODULE_BTN_H - 1, 0xFFD0B080);
        guiGraphics.drawString(font, "下单", x + ORDER_BTN_X + 18, y + ORDER_BTN_Y + 2, 0xFF101010, false);
    }

    private void drawSlotBg(GuiGraphics guiGraphics, int sx, int sy, int inner) {
        guiGraphics.fill(sx, sy, sx + 18, sy + 18, 0xFF373737);
        guiGraphics.fill(sx + 1, sy + 1, sx + 17, sy + 17, inner);
    }

    protected void drawForegroundText(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        renderTitleText(guiGraphics);
        drawString(guiGraphics, playerInventoryTitle, 39, inventoryLabelY, titleTextColor());
        var machine = menu.getMachine();
        guiGraphics.drawString(font, String.format("发热侧 %.1f℃", machine.getHeatTemperature() - 273.15),
                12, 6, 0xFFFF5555);
        String sortName = switch (menu.getSortMode()) {
            case NAME -> "名称";
            case COUNT -> "数量";
            default -> "默认";
        };
        guiGraphics.drawString(font, "排序:" + sortName, leftPos + SORT_X - leftPos + 2, 6, 0xFF204080);
        guiGraphics.drawString(font, "线程 " + machine.runningThreads() + "/" + machine.totalThreads(),
                12, 84, 0xFF006000);
        int milli = menu.getOrderProgressMilli();
        if (milli > 0) {
            guiGraphics.drawString(font, "订单进度 " + (milli / 10) + "%", 12, 138, 0xFF604000, false);
        }
        super.drawForegroundText(guiGraphics, mouseX, mouseY);
    }

    /** ME 来源面板：由下单窗口持有（窗口打开时才非 null）。 */
    @Override
    public NetworkOrderPanel networkOrderPanel() {
        return orderWindow == null ? null : orderWindow.panel();
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        int x = leftPos;
        int y = topPos;
        // 仅在存储区范围内滚动
        if (mouseX >= x + 119 && mouseX < x + 119 + 9 * 18 && mouseY >= y + 19 && mouseY < y + 19 + 6 * 18) {
            ModMessages.sendToServer(new KitchenViewPacket(menu.getMachine().getBlockPos(), (byte) 2,
                    "", delta > 0 ? -1 : 1));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        int x = leftPos;
        int y = topPos;
        // 侧配按钮
        if (mouseX >= x + SIDE_BTN_X && mouseX < x + SIDE_BTN_X + SIDE_BTN_W
                && mouseY >= y + SIDE_BTN_Y && mouseY < y + SIDE_BTN_Y + SIDE_BTN_H) {
            if (sideWindow == null) {
                sideWindow = new GuiMekCkSideConfiguration(this, menu, () -> {
                    var state = menu.getMachine().getBlockState();
                    return state.hasProperty(cn.ism.mekck.block.CentralKitchenBlock.FACING)
                            ? state.getValue(cn.ism.mekck.block.CentralKitchenBlock.FACING)
                            : net.minecraft.core.Direction.NORTH;
                });
                addRenderableWidget(sideWindow);
            } else {
                sideWindow = null;
            }
            return true;
        }
        // 下单窗口按钮
        if (mouseX >= x + ORDER_BTN_X && mouseX < x + ORDER_BTN_X + MODULE_BTN_W
                && mouseY >= y + ORDER_BTN_Y && mouseY < y + ORDER_BTN_Y + MODULE_BTN_H) {
            if (orderWindow == null) {
                orderWindow = new KitchenOrderWindow(this, menu);
                addRenderableWidget(orderWindow);
            } else {
                orderWindow = null;
            }
            return true;
        }
        // 模块窗口按钮
        if (mouseX >= x + MODULE_BTN_X && mouseX < x + MODULE_BTN_X + MODULE_BTN_W
                && mouseY >= y + MODULE_BTN_Y && mouseY < y + MODULE_BTN_Y + MODULE_BTN_H) {
            if (moduleWindow == null) {
                moduleWindow = new KitchenModuleWindow(this, menu);
                addRenderableWidget(moduleWindow);
            } else {
                moduleWindow = null;
            }
            return true;
        }
        // 搜索框
        if (mouseX >= x + SEARCH_X && mouseX < x + SEARCH_X + SEARCH_W
                && mouseY >= y + SEARCH_Y && mouseY < y + SEARCH_Y + SEARCH_H) {
            searchFocused = true;
            return true;
        }
        // 排序按钮
        if (mouseX >= x + SORT_X && mouseX < x + SORT_X + 40
                && mouseY >= y + SORT_Y && mouseY < y + SORT_Y + 14) {
            int next = (menu.getSortMode().ordinal() + 1) % CentralKitchenMenu.SortMode.values().length;
            ModMessages.sendToServer(new KitchenViewPacket(menu.getMachine().getBlockPos(), (byte) 1, "", next));
            return true;
        }
        searchFocused = false;
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        if (searchFocused) {
            if (codePoint == '\b') {
                if (!searchText.isEmpty()) {
                    searchText = searchText.substring(0, searchText.length() - 1);
                    pushSearch();
                }
                return true;
            }
            if (searchText.length() < 32 && !Character.isISOControl(codePoint)) {
                searchText += codePoint;
                pushSearch();
            }
            return true;
        }
        return super.charTyped(codePoint, modifiers);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (searchFocused) {
            if (keyCode == GLFW.GLFW_KEY_BACKSPACE) {
                if (!searchText.isEmpty()) {
                    searchText = searchText.substring(0, searchText.length() - 1);
                    pushSearch();
                }
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_ESCAPE) {
                searchFocused = false;
                return true;
            }
            return false;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }


    private void pushSearch() {
        ModMessages.sendToServer(new KitchenViewPacket(menu.getMachine().getBlockPos(), (byte) 0, searchText, 0));
    }
}
