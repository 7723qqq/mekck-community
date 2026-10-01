package cn.ism.mekck.client;

import cn.ism.mekck.menu.CentralKitchenMenu;
import cn.ism.mekck.network.KitchenViewPacket;
import cn.ism.mekck.network.ModMessages;
import mekanism.client.gui.element.window.GuiWindow;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.lwjgl.glfw.GLFW;
import com.mojang.blaze3d.vertex.PoseStack;

/**
 * 中央厨房界面：左侧 20 模块槽、中部 6×9 存储区视图（滚轮滚动 / 搜索 / 排序）、右侧输出区，
 * 顶部显示双温度与线程状态。
 */
@OnlyIn(Dist.CLIENT)
public class CentralKitchenScreen extends mekanism.client.gui.GuiMekanism<CentralKitchenMenu>
        implements NetworkOrderHost {

    /**
     * 存储浏览器自动重建的间隔（tick）。
     *
     * <p><b>为什么需要它（第三轮补）</b>：{@code CentralKitchenMenu.refreshDisplay()} 是
     * 54 个可见存储格映射的<b>唯一</b>数据来源，而它此前只在构造器、搜索/排序变更、
     * quickMoveStack 成功时被调 —— 也就是说存储区在<b>开界面之后</b>发生的一切变化
     * （AutoIO 拉入、订单交付、AE2 补料、弹出）都反映不到界面上：
     * 新到的物品永远不出现，被消耗掉的格子还占着一个可见位置。
     * 搜索框能绕过（重新输入会触发 refresh），但浏览器语义本身已失效。</p>
     *
     * <p><b>为什么放客户端而不是服务端菜单</b>：菜单的 {@code tick} 侧对<b>每个</b>观察者
     * 都会跑，而重建要扫 300 格存储区 + 每格算一次 hoverName（不便宜）。
     * 只有真正看着界面的玩家需要它，所以按屏幕节流。</p>
     *
     * <p>1 秒一次是权衡：够跟上肉眼可辨的变化，又不至于每帧重扫。
     * 玩家主动操作（搜索/排序/滚动）仍然立即重建，不受这个节流影响。</p>
     */
    private static final int BROWSER_REFRESH_INTERVAL = 20;

    /** 距离下一次自动重建还有几次渲染。 */
    private int browserRefreshCountdown = BROWSER_REFRESH_INTERVAL;

    /**
     * 挂在 {@code render} 而不是 {@code tick} 上。
     *
     * <p>原因：{@code AbstractContainerScreen#tick()} 是 <b>final</b>（覆写会编译失败），
     * 而 {@code tickClient} 钩子要走到 {@code AbstractContainerScreen#tick} 才会执行。
     * {@code GuiMekanism#render(GuiGraphics, int, int, float)}（Mek 覆写后的签名，
     * 不是原版的 {@code render(PoseStack, …)}）每帧都跑，用计数器节流到 1 秒一次，
     * 效果与「每秒重建一次」等价，而且在 GUI 打开期间必然被调用。</p>
     */
    @Override
    public void render(net.minecraft.client.gui.GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        if (--browserRefreshCountdown <= 0) {
            browserRefreshCountdown = BROWSER_REFRESH_INTERVAL;
            // 读全在客户端侧：容器槽的 ItemStack 由方块更新包 / 槽位同步持续刷新，
            // 这里读到的就是当前状态，不需要额外向服务端查询。
            menu.refreshDisplay();
        }
        super.render(guiGraphics, mouseX, mouseY, partialTick);
    }

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
        String shown = searchText.isEmpty()
                ? Component.translatable("gui.mekck.ui.search_placeholder").getString() : searchText;
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
                    net.minecraft.network.chat.Component.translatable("gui.mekck.ui.sandwich_sample_slot"),
                    net.minecraft.network.chat.Component.translatable("gui.mekck.ui.sandwich_sample_slot.desc"),
                    net.minecraft.network.chat.Component.translatable("gui.mekck.ui.sandwich_sample_slot.central_desc"),
                    net.minecraft.network.chat.Component.translatable("gui.mekck.ui.sandwich_sample_slot.requires_mod")
            ), java.util.Optional.empty(), mouseX, mouseY);
        }
        // 模块窗口按钮
        guiGraphics.fill(x + MODULE_BTN_X, y + MODULE_BTN_Y,
                x + MODULE_BTN_X + MODULE_BTN_W, y + MODULE_BTN_Y + MODULE_BTN_H, 0xFF5A5A5A);
        guiGraphics.fill(x + MODULE_BTN_X + 1, y + MODULE_BTN_Y + 1,
                x + MODULE_BTN_X + MODULE_BTN_W - 1, y + MODULE_BTN_Y + MODULE_BTN_H - 1, 0xFF9A9A9A);
        guiGraphics.drawString(font, Component.translatable("gui.mekck.ui.modules").getString(), x + MODULE_BTN_X + 2, y + MODULE_BTN_Y + 2, 0xFF101010, false);
        // 侧面配置按钮
        guiGraphics.fill(x + SIDE_BTN_X, y + SIDE_BTN_Y,
                x + SIDE_BTN_X + SIDE_BTN_W, y + SIDE_BTN_Y + SIDE_BTN_H, 0xFF5A5A5A);
        guiGraphics.fill(x + SIDE_BTN_X + 1, y + SIDE_BTN_Y + 1,
                x + SIDE_BTN_X + SIDE_BTN_W - 1, y + SIDE_BTN_Y + SIDE_BTN_H - 1, 0xFF9A9A9A);
        guiGraphics.drawString(font, Component.translatable("gui.mekck.ui.side_config").getString(), x + SIDE_BTN_X + 5, y + SIDE_BTN_Y + 2, 0xFF101010, false);
        // 下单按钮
        guiGraphics.fill(x + ORDER_BTN_X, y + ORDER_BTN_Y,
                x + ORDER_BTN_X + MODULE_BTN_W, y + ORDER_BTN_Y + MODULE_BTN_H, 0xFF5A5A5A);
        guiGraphics.fill(x + ORDER_BTN_X + 1, y + ORDER_BTN_Y + 1,
                x + ORDER_BTN_X + MODULE_BTN_W - 1, y + ORDER_BTN_Y + MODULE_BTN_H - 1, 0xFFD0B080);
        guiGraphics.drawString(font, Component.translatable("gui.mekck.ui.order").getString(), x + ORDER_BTN_X + 18, y + ORDER_BTN_Y + 2, 0xFF101010, false);
    }

    private void drawSlotBg(GuiGraphics guiGraphics, int sx, int sy, int inner) {
        guiGraphics.fill(sx, sy, sx + 18, sy + 18, 0xFF373737);
        guiGraphics.fill(sx + 1, sy + 1, sx + 17, sy + 17, inner);
    }

    protected void drawForegroundText(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        renderTitleText(guiGraphics);
        drawString(guiGraphics, playerInventoryTitle, 39, inventoryLabelY, titleTextColor());
        var machine = menu.getMachine();
        guiGraphics.drawString(font, Component.translatable("gui.mekck.ui.heat_side", machine.getHeatTemperature() - 273.15).getString(),
                12, 6, 0xFFFF5555);
        // switch 出**键**而不是文案：Component 不是常量、不能当 switch 表达式的分支值，
        // 而且「键 → 文案」这一步放在 switch 外面，排序模式也只有一处翻译点。
        String sortNameKey = switch (menu.getSortMode()) {
            case NAME -> "gui.mekck.ui.sort_mode.name";
            case COUNT -> "gui.mekck.ui.sort_mode.count";
            default -> "gui.mekck.ui.sort_mode.default";
        };
        guiGraphics.drawString(font,
                Component.translatable("gui.mekck.ui.sort", Component.translatable(sortNameKey)).getString(),
                leftPos + SORT_X - leftPos + 2, 6, 0xFF204080);
        guiGraphics.drawString(font, Component.translatable("gui.mekck.ui.threads", machine.runningThreads(), machine.totalThreads()).getString(),
                12, 84, 0xFF006000);
        int milli = menu.getOrderProgressMilli();
        if (milli > 0) {
            guiGraphics.drawString(font, Component.translatable("gui.mekck.ui.order_progress", milli / 10).getString(), 12, 138, 0xFF604000, false);
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

    /**
     * 打开一扇窗口 —— <b>两条注册都要做</b>。
     *
     * <ul>
     *   <li>{@code addRenderableWidget} 把它放进 {@code Screen.renderables}，
     *       而 {@code Screen.render} 正是遍历那份列表画的（实测 SRG 字节码
     *       {@code Screen.m_88315_} 偏移 0-45）；</li>
     *   <li>{@code addWindow} 把它放进 {@code GuiMekanism.windows}，
     *       而 {@code GuiMekanism.mouseClicked} / {@code keyPressed} / {@code mouseReleased}
     *       都是遍历那份 LRU 的。</li>
     * </ul>
     * 只做前者 ⇒ 窗口画得出来但<b>点不动</b>，连它自己的关闭按钮都按不了；
     * 只做后者 ⇒ 进得了 LRU 但画不出来。原先这里只做了前者，
     * 正是「关不掉、再点一次还会叠一个」的根因。
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

    /**
     * 对账：窗口若已被它自己的关闭按钮关掉，把残留的 widget 也摘掉并返回 {@code null}。
     *
     * <p>{@code GuiWindow.close()} 只把自己移出窗口 LRU，<b>不会</b>移出
     * {@code Screen.renderables}，所以那条路径会留下一个「画得出来但点不动」的残影。</p>
     */
    private <T extends GuiWindow> T reapClosedWindow(T window) {
        if (window != null && !getWindows().contains(window)) {
            removeWidget(window);
            return null;
        }
        return window;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        int x = leftPos;
        int y = topPos;
        // 三个窗口都可能被它们自己的关闭按钮关掉，先对一次账。
        sideWindow = reapClosedWindow(sideWindow);
        orderWindow = reapClosedWindow(orderWindow);
        moduleWindow = reapClosedWindow(moduleWindow);
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
                openWindow(sideWindow);
            } else {
                closeWindow(sideWindow);
                sideWindow = null;
            }
            return true;
        }
        // 下单窗口按钮
        if (mouseX >= x + ORDER_BTN_X && mouseX < x + ORDER_BTN_X + MODULE_BTN_W
                && mouseY >= y + ORDER_BTN_Y && mouseY < y + ORDER_BTN_Y + MODULE_BTN_H) {
            if (orderWindow == null) {
                orderWindow = new KitchenOrderWindow(this, menu);
                openWindow(orderWindow);
            } else {
                closeWindow(orderWindow);
                orderWindow = null;
            }
            return true;
        }
        // 模块窗口按钮
        if (mouseX >= x + MODULE_BTN_X && mouseX < x + MODULE_BTN_X + MODULE_BTN_W
                && mouseY >= y + MODULE_BTN_Y && mouseY < y + MODULE_BTN_Y + MODULE_BTN_H) {
            if (moduleWindow == null) {
                moduleWindow = new KitchenModuleWindow(this, menu);
                openWindow(moduleWindow);
            } else {
                closeWindow(moduleWindow);
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
