package cn.ism.mekck.client;

import cn.ism.mekck.menu.CentralKitchenMenu;
import cn.ism.mekck.network.KitchenViewPacket;
import cn.ism.mekck.network.ModMessages;
import mekanism.client.gui.element.slot.GuiVirtualSlot;
import mekanism.client.gui.element.slot.SlotType;
import mekanism.client.gui.element.text.GuiTextField;
import mekanism.client.gui.element.window.GuiWindow;
import mekanism.common.inventory.container.slot.IVirtualSlot;
import mekanism.common.util.MekanismUtils;
import mekanism.common.util.MekanismUtils.ResourceType;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.List;

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
    /** 搜索框尺寸与位置（Mekanism GuiTextField，DIGITAL 黑底边框）。 */
    private static final int SEARCH_W = 120;
    private static final int SEARCH_H = 12;
    private static final int SEARCH_X = 120;
    private static final int SEARCH_Y = 4;
    private static final int SORT_X = 246;
    private static final int SORT_Y = 3;
    private static final int SORT_W = 40;
    private static final int SORT_H = 14;
    /** 侧栏 tab 图标：侧配 / 下单用 Mek 与本模组 tab 图标；模块（可安装）借用 Mek upgrade 图标。 */
    private static final ResourceLocation CONFIG_TEXTURE = MekanismUtils.getResource(ResourceType.GUI, "configuration.png");
    private static final ResourceLocation ORDER_TEXTURE = new ResourceLocation("mekck", "textures/gui/icon_order.png");
    private static final ResourceLocation MODULE_TEXTURE = MekanismUtils.getResource(ResourceType.GUI, "upgrade.png");

    /**
     * 三明治样品槽的界面坐标（与菜单 {@code CentralKitchenMenu} 里
     * {@code SANDWICH_SAMPLE_SLOT} 的 addSlot 坐标 294,78 一致）。
     *
     * <p>槽底改由 {@code GuiVirtualSlot} 绘制，但空位幽灵图标与悬停说明仍留在渲染层，
     * 因此这两个常量<b>仍需保留</b>——重构侧栏 tab 时被一并删掉，会让渲染代码找不到符号。</p>
     */
    private static final int SAMPLE_X = 293;
    private static final int SAMPLE_Y = 78;

    private KitchenModuleWindow moduleWindow;
    private KitchenOrderWindow orderWindow;
    private GuiMekCkSideConfiguration sideWindow;

    private GuiTextField searchField;

    // ===== 下单面板状态 =====
    /** 当前显示的配方列表（由已安装系列收集，客户端本地计算）。 */

    public CentralKitchenScreen(CentralKitchenMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        this.imageWidth = WIDTH;
        this.imageHeight = HEIGHT;
        this.inventoryLabelY = 152 - 10;
    }

    /** 排序三按钮（当前模式的那一个显示——按钮 label 即「排序: 模式名」，取代原 fill 按钮 + 前景文字）。 */
    private mekanism.client.gui.element.button.MekanismButton sortNameBtn;
    private mekanism.client.gui.element.button.MekanismButton sortCountBtn;
    private mekanism.client.gui.element.button.MekanismButton sortDefaultBtn;

    @Override
    protected void addGuiElements() {
        super.addGuiElements();

        // ── 槽位：84 个 GuiVirtualSlot 绑定 menu 真槽（模块 20 + 存储 54 + 输出 9 + 样品 1），
        //    取代 renderBg 的 fill 手绘底（此前是全项目仅剩的手绘槽底之一）。
        //    坐标与 menu 的 addSlot 表达式同源；存储格滚动时 refreshDisplay 只改槽的内容映射，
        //    槽对象与坐标不变 ⇒ 绑定一次即可，内容随原版槽位同步自动刷新。
        //    注意：这些格子的容器是 menu 侧的 SimpleContainer 包装，不是 Mek 的
        //    VirtualInventoryContainerSlot，所以不走 MekCkSlotWindow（那是给主面板装不下的
        //    高档工厂 81+81 格准备的窗口）——直铺主面板正是 Mek 自家机器的标准形态。
        int idx = 0;
        for (int i = 0; i < CentralKitchenMenu.VISIBLE_MODULES; i++) {
            bindSlot(SlotType.NORMAL, idx++, 12 + (i % 5) * 18, 20 + (i / 5) * 18);
        }
        for (int i = 0; i < CentralKitchenMenu.VISIBLE_STORAGE; i++) {
            bindSlot(SlotType.NORMAL, idx++, 120 + (i % CentralKitchenMenu.STORAGE_COLS) * 18,
                    20 + (i / CentralKitchenMenu.STORAGE_COLS) * 18);
        }
        for (int i = 0; i < CentralKitchenMenu.VISIBLE_OUTPUT; i++) {
            bindSlot(SlotType.OUTPUT, idx++, 294 + (i % 3) * 18, 20 + (i / 3) * 18);
        }
        bindSlot(SlotType.INPUT, idx, 294, 78);

        // ── 搜索框：Mekanism GuiTextField（DIGITAL 黑底边框），取代手搓的 fill 输入框 +
        // 自管焦点 + charTyped/keyPressed 键入链。每次文本变化即推送搜索（与旧行为一致）。
        // 原 fill 输入框的占位灰字迁为悬停 tooltip（DIGITAL 风格无占位符）。
        searchField = new GuiTextField(this, SEARCH_X, SEARCH_Y, SEARCH_W, SEARCH_H)
                .configureDigitalBorderInput(this::pushSearch);
        searchField.setMaxLength(32);
        searchField.setResponder(s -> pushSearch());
        // 占位提示走 renderBg 里的悬停分支（Mek 的 GuiElement 没有公开的 hover(...) 设置器，
        // getOnHover 是 protected 且只返回 IHoverable，无法直接挂到本元素上）。
        addRenderableWidget(searchField);

        // ── 排序按钮：三个同位按钮按模式切换显示（MekCkButtons.setShown 的设计用途），点击循环到下一模式。
        sortNameBtn = addRenderableWidget(sortButton("gui.mekck.ui.sort_mode.name"));
        sortCountBtn = addRenderableWidget(sortButton("gui.mekck.ui.sort_mode.count"));
        sortDefaultBtn = addRenderableWidget(sortButton("gui.mekck.ui.sort_mode.default"));

        // ── 三个窗口入口改为侧栏 tab（与其他 9 屏统一；原右下角 fill 按钮删除）。
        //    左列：侧配 6 / 下单 34；右列：模块 6。窗口仍是本屏的三个字段管理（openWindow 单通道）。
        addRenderableWidget(new MekCkTabElement(this, CONFIG_TEXTURE, -26, 6, true,
                MekCkTabElement.OUTER, MekCkTabElement.INNER,
                () -> getWindows().stream().anyMatch(w -> w instanceof GuiMekCkSideConfiguration),
                mekanism.client.SpecialColors.TAB_CONFIGURATION,
                () -> List.of(Component.translatable("tooltip.mekck.side_config")),
                this::openSideConfigWindow, null));
        addRenderableWidget(new MekCkTabElement(this, ORDER_TEXTURE, -26, 34, true,
                MekCkTabElement.OUTER, MekCkTabElement.INNER,
                () -> getWindows().stream().anyMatch(w -> w instanceof KitchenOrderWindow),
                mekanism.client.SpecialColors.TAB_QIO_FREQUENCY,
                () -> List.of(Component.translatable("gui.mekck.ui.order")),
                this::openOrderWindow, null));
        addRenderableWidget(new MekCkTabElement(this, MODULE_TEXTURE, imageWidth, 6, false,
                MekCkTabElement.OUTER, MekCkTabElement.INNER,
                () -> getWindows().stream().anyMatch(w -> w instanceof KitchenModuleWindow),
                mekanism.client.SpecialColors.TAB_UPGRADE,
                () -> List.of(Component.translatable("gui.mekck.ui.modules")),
                this::openModuleWindow, null));
    }

    private mekanism.client.gui.element.button.MekanismButton sortButton(String modeKey) {
        return MekCkButtons.text(this, SORT_X, SORT_Y, SORT_W, SORT_H,
                Component.translatable("gui.mekck.ui.sort", Component.translatable(modeKey)),
                () -> ModMessages.sendToServer(new KitchenViewPacket(menu.getMachine().getBlockPos(), (byte) 1, "",
                        (menu.getSortMode().ordinal() + 1) % CentralKitchenMenu.SortMode.values().length)));
    }

    @Override
    public void containerTick() {
        super.containerTick();
        var mode = menu.getSortMode();
        MekCkButtons.setShown(sortNameBtn, mode == CentralKitchenMenu.SortMode.NAME);
        MekCkButtons.setShown(sortCountBtn, mode == CentralKitchenMenu.SortMode.COUNT);
        MekCkButtons.setShown(sortDefaultBtn, mode != CentralKitchenMenu.SortMode.NAME
                && mode != CentralKitchenMenu.SortMode.COUNT);
    }

    @Override
    protected void renderBg(GuiGraphics guiGraphics, float partialTick, int mouseX, int mouseY) {
        super.renderBg(guiGraphics, partialTick, mouseX, mouseY);
        int x = leftPos;
        int y = topPos;
        // 三组槽位底（模块 / 存储 / 输出）与样品槽底已由 addGuiElements 里的
        // GuiVirtualSlot 元件绘制（贴图自带边框），renderBg 不再 fill 手绘。
        // 搜索框 / 排序按钮 / 三个窗口入口按钮同理（GuiTextField / MekCkButtons / MekCkTabElement）。
        // 滚动位置提示
        String scrollInfo = (menu.getScrollRow() + 1) + "/" + (menu.maxScrollRow() + 1)
                + "  (" + menu.getFilteredCount() + ")";
        guiGraphics.drawString(font, scrollInfo, x + 120, y + 132, 0xFF404040, false);

        // ===== 三明治样品槽（槽底由 GuiVirtualSlot 画；幽灵与悬停说明保留在渲染层）=====
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
        // 搜索框占位提示（DIGITAL 风格无占位符，改用悬停 tooltip 表达「这里可以搜」）。
        if (searchField != null && searchField.isHovered()) {
            guiGraphics.renderTooltip(font,
                    net.minecraft.network.chat.Component.translatable("gui.mekck.ui.search_placeholder"),
                    mouseX, mouseY);
        }
        // 模块窗口按钮 / 侧面配置按钮 / 下单按钮：已迁为侧栏 tab（addGuiElements）。
    }

    /** 绑定一个虚拟槽：坐标 = menu 槽坐标（本模组 GuiVirtualSlot 的惯例，不加 -1）。 */
    private void bindSlot(SlotType type, int menuSlotIndex, int x, int y) {
        GuiVirtualSlot vs = new GuiVirtualSlot(type, this, x, y);
        if (menu.slots.get(menuSlotIndex) instanceof IVirtualSlot ivs) {
            vs.updateVirtualSlot(null, ivs);
        }
        addRenderableWidget(vs);
    }

    @Override
    protected void drawForegroundText(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        renderTitleText(guiGraphics);
        drawString(guiGraphics, playerInventoryTitle, 39, inventoryLabelY, titleTextColor());
        var machine = menu.getMachine();
        guiGraphics.drawString(font, Component.translatable("gui.mekck.ui.heat_side", machine.getHeatTemperature() - 273.15).getString(),
                12, 6, 0xFFFF5555);
        // 排序模式的「键 → 文案」显示已由排序按钮的 label 承担（MekCkButtons.text，按模式切换显示）。
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
     * 打开一扇窗口 —— <b>只走 {@code addWindow} 单通道</b>（与 Mek 全部窗口一致）。
     *
     * <p>绘制不依赖 {@code Screen.renderables}：{@code GuiMekanism.renderLabels} 会反序遍历
     * windows LRU 逐个调 {@code onRenderForeground}，而 {@code GuiElement.onRenderForeground}
     * <b>自含全部绘制</b> —— 先 {@code renderBackgroundOverlay}（{@code GuiWindow} 覆写：
     * 画阴影 + base.png 底图），再 {@code renderForeground}（本模组窗口在这里画标题与内容），
     * 最后递归子元素。事件同理：{@code GuiMekanism.mouseClicked} / {@code keyPressed} /
     * {@code mouseReleased} 都先遍历 windows LRU 分发。</p>
     *
     * <p>旧实现两条注册都做（{@code addRenderableWidget} + {@code addWindow}），结果是
     * <b>同一个窗口被画两遍</b> —— renderables 通道一遍（vanilla {@code Screen.render}）、
     * overlay 通道一遍（renderLabels），两层 z 不同但内容完全重叠。</p>
     */
    private void openWindow(GuiWindow window) {
        addWindow(window);
    }

    /** 关闭一扇窗口：{@code GuiWindow.close()} 自己会从窗口 LRU 出列（gui().removeWindow）。 */
    private void closeWindow(GuiWindow window) {
        window.close();
    }

    /**
     * 对账：窗口若已被它自己的关闭按钮关掉（{@code GuiCloseButton} 走 {@code window.close()}，
     * 出 LRU），把本屏持有的字段引用清掉并返回 {@code null}。
     */
    private <T extends GuiWindow> T reapClosedWindow(T window) {
        if (window != null && !getWindows().contains(window)) {
            return null;
        }
        return window;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // 三个窗口都可能被它们自己的关闭按钮关掉，先对一次账。
        // 侧配 / 下单 / 模块入口（侧栏 tab）、排序按钮（MekCkButtons）、搜索框（GuiTextField）
        // 全部是 renderable widget —— 点击由框架统一派发，本屏不再有任何手算命中矩形。
        sideWindow = reapClosedWindow(sideWindow);
        orderWindow = reapClosedWindow(orderWindow);
        moduleWindow = reapClosedWindow(moduleWindow);
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private void openSideConfigWindow() {
        if (sideWindow == null || !getWindows().contains(sideWindow)) {
            sideWindow = new GuiMekCkSideConfiguration(this, menu, () -> {
                var state = menu.getMachine().getBlockState();
                return state.hasProperty(cn.ism.mekck.block.CentralKitchenBlock.FACING)
                        ? state.getValue(cn.ism.mekck.block.CentralKitchenBlock.FACING)
                        : net.minecraft.core.Direction.NORTH;
            });
            openWindow(sideWindow);
        }
    }

    private void openOrderWindow() {
        if (orderWindow == null || !getWindows().contains(orderWindow)) {
            orderWindow = new KitchenOrderWindow(this, menu);
            openWindow(orderWindow);
        }
    }

    private void openModuleWindow() {
        if (moduleWindow == null || !getWindows().contains(moduleWindow)) {
            moduleWindow = new KitchenModuleWindow(this, menu);
            openWindow(moduleWindow);
        }
    }

    private void pushSearch() {
        ModMessages.sendToServer(new KitchenViewPacket(menu.getMachine().getBlockPos(), (byte) 0,
                searchField == null ? "" : searchField.getText(), 0));
    }
}
