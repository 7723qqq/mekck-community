package cn.ism.mekck.client;

import mekanism.client.gui.IGuiWrapper;
import mekanism.client.gui.element.slot.GuiVirtualSlot;
import mekanism.client.gui.element.slot.SlotType;
import mekanism.client.gui.element.window.GuiWindow;
import mekanism.common.inventory.container.SelectedWindowData;
import mekanism.common.inventory.container.slot.VirtualInventoryContainerSlot;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * 槽位悬浮窗 —— 把机器的大片槽位收进一扇可拖动的窗口，<b>不翻页</b>。
 *
 * <h3>为什么是「输入/输出」而不是「存储」</h3>
 * 高档工厂（烈焰炽焱 25 / 晶钛矩阵 36 / 星云塑造 49 / 奇点创世 81 并行）的
 * 输入与输出槽各有 {@code processes} 个，奇点创世就是 <b>81 + 81 = 162 格</b>，
 * 全塞进主面板会让面板撑到 412×310。放进悬浮窗后主面板可以保持紧凑。
 *
 * <h3>布局：每区块至少 9 列，多区块并排，一页显示完</h3>
 * <ul>
 *   <li>每个区块<b>至少</b> {@value #COLS} 列 —— 81 格正好 <b>9×9</b>，一页装下。</li>
 *   <li>两个区块（输入 / 输出）并排 ⇒ 18 列 × 9 行 = 162 格，仍是<b>一页</b>。</li>
 *   <li><b>没有翻页</b>：窗口高度按内容算，有多少行就多高。这比翻页少一套状态
 *       （页码、按钮、滚轮、重建格子），也就少一类 bug。</li>
 *   <li>行数封顶 {@value #MAX_ROWS}：超过就<b>加宽列数</b>而不是继续加高。
 *       烹饪工厂的存储区是 144 格，按 9 列排是 16 行 = 322px 高，
 *       比整个游戏窗口还高（窗口 y 从 15 起算），底部的槽位永远点不到。
 *       改成 16 列 × 9 行 = 196px 后落回屏幕内，且仍是「一页」。</li>
 * </ul>
 *
 * <h3>1.20.1 的 API 差异（踩过的坑）</h3>
 * <ul>
 *   <li>{@code mouseClicked} 是 {@code final}，要处理点击只能覆写
 *       {@code onClick(double,double,int)}。</li>
 *   <li>{@code mouseScrolled} 只有<b>一个</b> delta 参数（1.21 是两个）。</li>
 *   <li>取字体是 {@code getFont()}，不是 1.21 的 {@code font()}；
 *       鼠标位置也不在 {@code IGuiWrapper} 上，悬停判定要用
 *       {@code renderForeground} 的入参。</li>
 * </ul>
 */
public class MekCkSlotWindow extends GuiWindow {

    /** 每个区块的<b>最小</b>列数。9 是刻意选的：81 格 = 9×9，一页装满一个档位。 */
    public static final int COLS = 9;
    /**
     * 每个区块的行数上限。超过就加宽列数，见类注释。
     *
     * <p>取 9 与 {@link #COLS} 对称：81 格（奇点创世的输入/输出、穿串工厂的存储）
     * 正好排成 9×9，是「一页装下」这个设计目标的上界。</p>
     */
    public static final int MAX_ROWS = 9;
    /** 标准槽距。 */
    private static final int SLOT = 18;
    /** 窗口内边距。 */
    private static final int PAD = 6;
    /** 标题栏高度。 */
    private static final int TITLE_H = 18;
    /** 区块标题行高度。 */
    private static final int GROUP_TITLE_H = 10;
    /** 两个区块之间的水平间隔。 */
    private static final int GROUP_GAP = 8;

    /** 一页区块：标题 + 槽位列表。 */
    public record SlotGroup(Component title, List<VirtualInventoryContainerSlot> slots) {
    }

    private final List<SlotGroup> groups;

    public MekCkSlotWindow(IGuiWrapper gui, int x, int y, List<SlotGroup> groups,
                           SelectedWindowData windowData) {
        super(gui, x, y, computeWidth(groups), computeHeight(groups), windowData);
        this.groups = groups == null ? List.of() : groups;
        // 窗口内要能正常点击槽位。
        this.interactionStrategy = InteractionStrategy.ALL;
        buildSlots();
    }

    /** 单个区块的列数：至少 {@link #COLS}，格数多到超过 {@link #MAX_ROWS} 行时加宽。 */
    private static int colsOf(int count) {
        int c = Math.max(1, count);
        return Math.max(COLS, (c + MAX_ROWS - 1) / MAX_ROWS);
    }

    /** 单个区块的行数（按 {@link #colsOf} 算出的列数向上取整）。 */
    private static int rowsOf(int count) {
        int cols = colsOf(count);
        return Math.max(1, (Math.max(1, count) + cols - 1) / cols);
    }

    /** 单个区块的宽度。 */
    private static int groupWidth(SlotGroup group) {
        return colsOf(group.slots().size()) * SLOT;
    }

    /** 窗口宽度：并排的区块 + 间隔 + 边距。 */
    public static int computeWidth(List<SlotGroup> groups) {
        int n = groups == null ? 0 : groups.size();
        if (n == 0) {
            return PAD * 2 + COLS * SLOT;
        }
        int content = 0;
        for (SlotGroup group : groups) {
            content += groupWidth(group);
        }
        return PAD * 2 + content + (n - 1) * GROUP_GAP;
    }

    /** 窗口高度：标题栏 + 区块标题 + 最高区块 + 底部边距。 */
    public static int computeHeight(List<SlotGroup> groups) {
        int rows = 1;
        if (groups != null) {
            for (SlotGroup group : groups) {
                rows = Math.max(rows, rowsOf(group.slots().size()));
            }
        }
        return TITLE_H + GROUP_TITLE_H + rows * SLOT + PAD;
    }

    /** 第 {@code index} 个区块的左上角 x（相对窗口）。各区块宽度可能不同，必须累加。 */
    private int groupX(int index) {
        int x = PAD;
        for (int i = 0; i < index && i < groups.size(); i++) {
            x += groupWidth(groups.get(i)) + GROUP_GAP;
        }
        return x;
    }

    /**
     * 建出全部格子 —— <b>不做分页，因此只需建一次</b>。
     *
     * <p>每个格子用 {@link GuiVirtualSlot} 绑定容器给的
     * {@link VirtualInventoryContainerSlot}：容器槽坐标恒为 (0,0)，
     * 实际渲染位置由这里写进去（见 Mek 的 {@code GuiVirtualSlot#updateVirtualSlot}）。</p>
     */
    private void buildSlots() {
        for (int g = 0; g < groups.size(); g++) {
            SlotGroup group = groups.get(g);
            List<VirtualInventoryContainerSlot> slots = group.slots();
            int cols = colsOf(slots.size());
            for (int i = 0; i < slots.size(); i++) {
                int col = i % cols;
                int row = i / cols;
                int sx = relativeX + groupX(g) + col * SLOT;
                int sy = relativeY + TITLE_H + GROUP_TITLE_H + row * SLOT;
                addChild(new GuiVirtualSlot(this, SlotType.NORMAL, gui(), sx, sy, slots.get(i)));
            }
        }
    }

    @Override
    protected int getTitlePadEnd() {
        return 6;
    }

    /** 鼠标滚轮不再用于翻页；保留覆写只是为了明确语义（1.20.1 的签名只有一个 delta）。 */
    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    @Override
    public void renderForeground(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        super.renderForeground(guiGraphics, mouseX, mouseY);
        drawTitleText(guiGraphics, Component.translatable("gui.mekck.slot_window.title"), 5);
        // 每个区块的标题（输入 / 输出 / 存储），左边与自己的第一列对齐
        for (int g = 0; g < groups.size(); g++) {
            Component title = groups.get(g).title();
            guiGraphics.drawString(getFont(), title,
                    relativeX + groupX(g), relativeY + TITLE_H, 0xFFB0B0B0, false);
        }
    }
}
