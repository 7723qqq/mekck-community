package cn.ism.mekck.client;

import cn.ism.mekck.blockentity.SandwichAssemblerBlockEntity;
import cn.ism.mekck.menu.SandwichAssemblerMenu;
import cn.ism.mekck.network.ModMessages;
import cn.ism.mekck.network.SandwichConfigPacket;
import mekanism.client.gui.element.progress.GuiProgress;
import mekanism.client.gui.element.progress.ProgressType;
import mekanism.client.gui.element.slot.GuiVirtualSlot;
import mekanism.client.gui.element.slot.SlotType;
import mekanism.client.gui.element.window.GuiWindow;
import mekanism.common.inventory.container.slot.IVirtualSlot;
import mekanism.common.util.MekanismUtils;
import mekanism.common.util.MekanismUtils.ResourceType;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.List;

/**
 * 三明治组装机界面：有序输入格 32 + 样品槽 + 材料区 27 + 返还槽 + 输出槽 + 模式/数量控件 + 进度条。
 *
 * <p>本屏的槽位 / 进度条 / 模式与数量按钮 / 侧配入口全部走 Mek 元件体系（此前整屏 fill 手绘）：
 * 槽位是 {@link GuiVirtualSlot} 绑定 menu 的 {@code IVirtualSlot} 槽（同 SimpleMachineScreen 等 12 屏），
 * 进度条是 {@link GuiProgress}，按钮走 {@link MekCkButtons}，侧配入口是右侧栏 tab。</p>
 */
@OnlyIn(Dist.CLIENT)
public class SandwichAssemblerScreen extends mekanism.client.gui.GuiMekanism<SandwichAssemblerMenu> {

    private static final int MODE_BTN_X = 240;
    private static final int MODE_BTN_Y = 104;
    private static final int BTN_W = 92;
    private static final int BTN_H = 16;
    private static final int COUNT_Y = 122;
    /** 进度条 y（原 fill 进度条的位置；横向 Mek 箭头 LARGE_RIGHT 48×8）。 */
    private static final int PROGRESS_Y = 144;
    /** 侧配 tab 图标（与其他屏同一张 Mek configuration.png）。 */
    private static final net.minecraft.resources.ResourceLocation CONFIG_TEXTURE =
            MekanismUtils.getResource(ResourceType.GUI, "configuration.png");

    private GuiMekCkSideConfiguration sideWindow;

    /** 模式三按钮（当前模式的那一个显示，其余隐藏——见 containerTick 同步）。 */
    private mekanism.client.gui.element.button.MekanismButton copyBtn;
    private mekanism.client.gui.element.button.MekanismButton customBtn;
    private mekanism.client.gui.element.button.MekanismButton sequencedBtn;

    public SandwichAssemblerScreen(SandwichAssemblerMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        this.imageWidth = SandwichAssemblerMenu.IMAGE_WIDTH;
        this.imageHeight = SandwichAssemblerMenu.IMAGE_HEIGHT;
        this.inventoryLabelY = 164 - 10;
    }

    @Override
    protected void addGuiElements() {
        super.addGuiElements();

        // ── 槽位：坐标与 menu 的 addSlot 表达式同源（两处一旦不一致就是「格在一个地方、物品在另一个地方」）──
        int idx = 0;
        // 有序输入格 8×4（menu 第 0..31 槽）
        for (int i = 0; i < SandwichAssemblerBlockEntity.ORDERED_SLOTS; i++) {
            bindSlot(SlotType.INPUT, idx++, 8 + (i % SandwichAssemblerMenu.ORDERED_COLS) * 18,
                    20 + (i / SandwichAssemblerMenu.ORDERED_COLS) * 18);
        }
        // 材料区 9×3（32..58）
        for (int i = 0; i < SandwichAssemblerBlockEntity.MATERIAL_SLOTS; i++) {
            bindSlot(SlotType.INPUT, idx++, 170 + (i % SandwichAssemblerMenu.MATERIAL_COLS) * 18,
                    20 + (i / SandwichAssemblerMenu.MATERIAL_COLS) * 18);
        }
        // 样品槽 / 输出槽 / 返还槽 3
        bindSlot(SlotType.INPUT, idx++, 170, 80);
        bindSlot(SlotType.OUTPUT, idx++, 200, 80);
        for (int i = 0; i < SandwichAssemblerBlockEntity.RETURN_SLOTS; i++) {
            bindSlot(SlotType.OUTPUT, idx++, 240 + i * 18, 80);
        }
        // 升级槽 3（速度 / 能量 / 创造，直接放取——本 menu 未实现 IUpgradeMenu，不弹升级窗）
        bindSlot(SlotType.NORMAL, idx++, 170, 104);
        bindSlot(SlotType.NORMAL, idx++, 170, 122);
        bindSlot(SlotType.NORMAL, idx++, 170, 140);
        // 能源槽
        bindSlot(SlotType.POWER, idx, 310, 8);

        // ── 进度条：Mek 横向箭头（原 fill 绿条）──
        addRenderableWidget(new GuiProgress(() -> menu.getProgressRatio(),
                ProgressType.LARGE_RIGHT, this, MODE_BTN_X, PROGRESS_Y));

        // ── 模式按钮：三个按需显示的文本按钮（当前模式的那一个显示，见 containerTick 同步）──
        copyBtn = addRenderableWidget(MekCkButtons.text(this, MODE_BTN_X, MODE_BTN_Y, BTN_W, BTN_H,
                Component.translatable("gui.mekck.ui.sandwich_mode.copy"),
                () -> sendMode(SandwichAssemblerBlockEntity.MODE_COPY)));
        customBtn = addRenderableWidget(MekCkButtons.text(this, MODE_BTN_X, MODE_BTN_Y, BTN_W, BTN_H,
                Component.translatable("gui.mekck.ui.sandwich_mode.custom"),
                () -> sendMode(SandwichAssemblerBlockEntity.MODE_CUSTOM)));
        sequencedBtn = addRenderableWidget(MekCkButtons.text(this, MODE_BTN_X, MODE_BTN_Y, BTN_W, BTN_H,
                Component.translatable("gui.mekck.ui.sandwich_mode.sequenced"),
                () -> sendMode(SandwichAssemblerBlockEntity.MODE_SEQUENCED)));

        // 数量 - / +
        addRenderableWidget(MekCkButtons.text(this, MODE_BTN_X, COUNT_Y, 20, BTN_H,
                Component.literal("-"), () -> sendCount(-1)));
        addRenderableWidget(MekCkButtons.text(this, MODE_BTN_X + BTN_W - 20, COUNT_Y, 20, BTN_H,
                Component.literal("+"), () -> sendCount(1)));

        // ── 侧配入口：右侧栏 tab（与其他 9 屏统一），替代原 fill 按钮 ──
        addRenderableWidget(new MekCkTabElement(this, CONFIG_TEXTURE, imageWidth, 6, false,
                MekCkTabElement.OUTER, MekCkTabElement.INNER,
                () -> getWindows().stream().anyMatch(w -> w instanceof GuiMekCkSideConfiguration),
                mekanism.client.SpecialColors.TAB_CONFIGURATION,
                () -> List.of(Component.translatable("tooltip.mekck.side_config")),
                this::openSideConfigWindow, null));
    }

    /** 绑定一个虚拟槽：坐标 = menu 槽坐标（本模组 GuiVirtualSlot 的惯例，不加 -1）。 */
    private void bindSlot(SlotType type, int menuSlotIndex, int x, int y) {
        GuiVirtualSlot vs = new GuiVirtualSlot(type, this, x, y);
        if (menu.slots.get(menuSlotIndex) instanceof IVirtualSlot ivs) {
            vs.updateVirtualSlot(null, ivs);
        }
        addRenderableWidget(vs);
    }

    private void sendMode(int mode) {
        ModMessages.sendToServer(new SandwichConfigPacket(menu.getMachine().getBlockPos(), (byte) 0, mode));
    }

    private void sendCount(int delta) {
        ModMessages.sendToServer(new SandwichConfigPacket(menu.getMachine().getBlockPos(), (byte) 2, delta));
    }

    @Override
    public void containerTick() {
        super.containerTick();
        int mode = menu.getMode();
        MekCkButtons.setShown(copyBtn, mode == SandwichAssemblerBlockEntity.MODE_COPY);
        MekCkButtons.setShown(customBtn, mode == SandwichAssemblerBlockEntity.MODE_CUSTOM);
        MekCkButtons.setShown(sequencedBtn, mode == SandwichAssemblerBlockEntity.MODE_SEQUENCED);
    }

    @Override
    protected void renderBg(GuiGraphics guiGraphics, float partialTick, int mouseX, int mouseY) {
        super.renderBg(guiGraphics, partialTick, mouseX, mouseY);
        // 样品槽空位提示：半透明三明治图标（画在 GuiVirtualSlot 贴图之下，槽贴图中心透明处透出；
        // 有真物品时不画——vanilla 的物品渲染在其后，会盖住幽灵）。幽灵绘制保留在渲染层：
        // 「空槽画半透明预览」在 Mek 元件体系里没有标准行为。
        int x = leftPos;
        int y = topPos;
        var sample = menu.getMachine().items.getStackInSlot(
                SandwichAssemblerBlockEntity.SAMPLE_SLOT);
        if (sample.isEmpty()) {
            var ghost = SandwichAssemblerBlockEntity.sarSandwich();
            if (!ghost.isEmpty()) {
                guiGraphics.renderItem(ghost, x + 170, y + 80);
            }
            guiGraphics.fill(x + 170, y + 80, x + 186, y + 96, 0xC0D0A0A0);
        }
        // 样品槽悬停说明（4 行 lang 键）。GuiVirtualSlot 默认 tooltip 显示槽内物品名，
        // 这里保留渲染层手算命中给出完整说明（Mek GuiSlot.builder 的 hover 通道后续可迁）。
        if (mouseX >= x + 169 && mouseX < x + 169 + 18 && mouseY >= y + 79 && mouseY < y + 79 + 18) {
            guiGraphics.renderTooltip(font, java.util.List.of(
                    net.minecraft.network.chat.Component.translatable("gui.mekck.ui.sandwich_sample_slot"),
                    net.minecraft.network.chat.Component.translatable("gui.mekck.ui.sandwich_sample_slot.desc"),
                    net.minecraft.network.chat.Component.translatable("gui.mekck.ui.sandwich_sample_slot.assembler_desc"),
                    net.minecraft.network.chat.Component.translatable("gui.mekck.ui.sandwich_sample_slot.requires_mod")
            ), java.util.Optional.empty(), mouseX, mouseY);
        }
    }

    @Override
    protected void drawForegroundText(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        renderTitleText(guiGraphics);
        drawString(guiGraphics, playerInventoryTitle, 8, inventoryLabelY, titleTextColor());
        int x = leftPos;
        int y = topPos;
        int mode = menu.getMode();
        String modeLabel = Component.translatable(mode == SandwichAssemblerBlockEntity.MODE_COPY
                ? "gui.mekck.ui.sandwich_mode.copy"
                : mode == SandwichAssemblerBlockEntity.MODE_CUSTOM
                ? "gui.mekck.ui.sandwich_mode.custom"
                : "gui.mekck.ui.sandwich_mode.sequenced").getString();
        guiGraphics.drawString(font, modeLabel,
                x + MODE_BTN_X - leftPos + 6, MODE_BTN_Y + 4, 0xFF101010, false);
        String countText = mode == SandwichAssemblerBlockEntity.MODE_COPY
                ? Component.translatable("gui.mekck.ui.sandwich_count.copy").getString()
                : mode == SandwichAssemblerBlockEntity.MODE_SEQUENCED
                ? Component.translatable("gui.mekck.ui.sandwich_count.sequenced").getString()
                : Component.translatable("gui.mekck.ui.sandwich_count.remaining",
                        menu.getTargetCount()).getString();
        guiGraphics.drawString(font, countText, x + MODE_BTN_X + 24, y + COUNT_Y + 4, 0xFF202020, false);
        if (!SandwichAssemblerBlockEntity.hasSar()) {
            guiGraphics.drawString(font, Component.translatable("gui.mekck.ui.sandwich_sample_slot.requires_sar").getString(), 8, 96, 0xFFFF5555, false);
        }
        guiGraphics.drawString(font, Component.translatable("gui.mekck.ui.layers", menu.getMachine().currentLayers()).getString(),
                8, 106, 0xFF404040, false);
        super.drawForegroundText(guiGraphics, mouseX, mouseY);
    }

    /**
     * 打开一扇窗口 —— <b>只走 {@code addWindow} 单通道</b>（与 Mek 全部窗口一致）。
     *
     * <p>绘制不依赖 {@code Screen.renderables}：{@code GuiMekanism.renderLabels} 反序遍历
     * windows LRU 调 {@code onRenderForeground}，而 {@code GuiElement.onRenderForeground}
     * 自含全部绘制（底图 + 内容 + 子元素）；事件也由 {@code GuiMekanism} 先遍历
     * windows LRU 分发。旧实现两条注册都做 ⇒ 同一窗口被画两遍。</p>
     */
    private void openWindow(GuiWindow window) {
        addWindow(window);
    }

    /** 关闭一扇窗口：{@code GuiWindow.close()} 自己会从窗口 LRU 出列（gui().removeWindow）。 */
    private void closeWindow(GuiWindow window) {
        window.close();
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        int x = leftPos;
        int y = topPos;
        // 窗口可能已被它自己的关闭按钮关掉（close() 会出 LRU），先清一下本屏的字段引用。
        if (sideWindow != null && !getWindows().contains(sideWindow)) {
            sideWindow = null;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private void openSideConfigWindow() {
        if (sideWindow == null || !getWindows().contains(sideWindow)) {
            sideWindow = new GuiMekCkSideConfiguration(this, menu, this::getMachineFacing);
            openWindow(sideWindow);
        }
    }

    private Direction getMachineFacing() {
        if (minecraft != null && minecraft.level != null) {
            BlockState state = minecraft.level.getBlockState(menu.getBlockPos());
            if (state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
                return state.getValue(BlockStateProperties.HORIZONTAL_FACING);
            }
        }
        return Direction.NORTH;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        int x = leftPos;
        int y = topPos;
        // 数量按钮区域滚轮调整（保留原行为；模式按钮区域不响应滚轮）
        if (inRect(mouseX, mouseY, x + MODE_BTN_X, y + COUNT_Y, BTN_W, BTN_H)) {
            sendCount(delta > 0 ? 1 : -1);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    private static boolean inRect(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }
}
