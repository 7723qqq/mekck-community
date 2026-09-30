package cn.ism.mekck.client;

import mekanism.client.SpecialColors;
import mekanism.client.gui.IGuiWrapper;
import mekanism.client.gui.element.tab.window.GuiWindowCreatorTab;
import mekanism.client.gui.element.window.GuiWindow;
import mekanism.client.render.MekanismRenderer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

import javax.annotation.Nullable;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 「下单」标签页 —— 点开 {@link NetworkOrderWindow}。
 *
 * <h3>照 Mek 的 {@code GuiUpgradeWindowTab} 写的</h3>
 * 同一个基类 {@link GuiWindowCreatorTab}，同样的「点击 → {@code createWindow()}
 * → {@code gui().addWindow(...)}」生命周期，所以关闭 / 重挂 / 再次激活都由 Mek 处理好
 * （{@code onClick} 里 {@code setTabListeners(getCloseListener(), getReAttachListener())}
 * 之后 {@code disableTab()}，关闭时 closeListener 再把 {@code active} 置回 true）。
 *
 * <h3>几何与旧 {@code MekCkTabElement} 逐像素一致</h3>
 * {@code GuiWindowCreatorTab(icon, gui, dataSource, x, y, 26, 18, left, supplier)} 里
 * 后两个尺寸参数经 {@code GuiInsetElement} 落到 {@code GuiSideHolder}：holder 恒 26 宽、
 * 高度取第 6 参（26），图标取第 7 参（18），border = (26-18)/2 = 4。
 * 与 {@code MekCkTabElement.OUTER=26 / INNER=18} 同值，所以各屏的 {@code TAB_X=-26} /
 * {@code ORDER_TAB_Y=34} 一个数都不用改。
 *
 * <h3>染色沿用 TAB_CONTAINER_EDIT_MODE</h3>
 * 这 11 枚 tab 迁移前就是 {@code SpecialColors.TAB_CONTAINER_EDIT_MODE}（Mek 的
 * 「容器编辑模式」色，{@code ColorAtlas.register(0xFF366BD0)}）。本任务只换容器不换观感，
 * 所以不改成 {@link MekCkSlotWindowTab} 的硬编码 {@code 0xFF2E7D8F}：
 * 那是「槽位视图」自己的青蓝，改过来会让 11 个屏的 tab 颜色发生可见变化。
 * 另外走 {@code ColorRegistryObject} 而不是裸 int，颜色会进 Mek 的 color atlas，
 * 与 Mek 自己的 tab 同一条渲染路径。
 */
public class NetworkOrderTab extends GuiWindowCreatorTab<BlockPos, NetworkOrderTab> {

    /** 本机数据源；null = 本屏没有本机下单列表（制冰工厂 / 种植切割站），见 {@link NetworkOrderWindow}。 */
    private final NetworkOrderPanel.LocalSource localSource;
    /**
     * 当前打开的窗口；关闭后置回 null。
     *
     * <p>必须留引用：{@code GuiWindowCreatorTab} 每次点击都新建窗口，而宿主屏幕要靠
     * {@link #panel()} 把 {@code NetworkRecipeListPacket} / {@code NetworkMissingPacket}
     * 投给「正在显示的那一个」面板。窗口关掉后若还留着旧引用，回包会灌进一个已经不在
     * 渲染树里的面板（表现为「重新打开面板时列表是上一次的」）。</p>
     */
    private NetworkOrderWindow window;

    public NetworkOrderTab(IGuiWrapper gui, BlockPos pos, int x, int y, boolean left,
                           NetworkOrderPanel.LocalSource localSource,
                           Supplier<NetworkOrderTab> elementSupplier) {
        // 图标是本模组自绘的「清单 + 向下箭头」（18×18，与 Mek tab 图标同尺寸），
        // 不借用 Mekanism 的 sorting.png —— 那张画的是整理/排序语义，与下单无关。
        super(MachineTabIcons.ORDER, gui, pos, x, y, 26, 18, left, elementSupplier);
        this.localSource = localSource;
    }

    @Override
    protected GuiWindow createWindow() {
        window = new NetworkOrderWindow(gui(), dataSource, localSource);
        return window;
    }

    /**
     * 在 Mek 的关闭回调上再挂一层：先把窗口引用清掉，再交回父类去重新激活本 tab。
     *
     * <p>{@code GuiWindow#close()} 的顺序是 {@code gui().removeWindow(this)} →
     * {@code closeListener.accept(this)}（实测字节码），所以回调里清引用时窗口已经出 LRU，
     * 不存在「清完又被渲染一帧」的窗口期。</p>
     */
    @Override
    protected Consumer<GuiWindow> getCloseListener() {
        Consumer<GuiWindow> parent = super.getCloseListener();
        return closed -> {
            window = null;
            parent.accept(closed);
        };
    }

    /** 窗口开着时返回窗口里的面板，关着返回 null —— {@code NetworkOrderHost} 的投递口径。 */
    @Nullable
    public NetworkOrderPanel panel() {
        return window == null ? null : window.panel();
    }

    @Override
    protected void colorTab(GuiGraphics guiGraphics) {
        MekanismRenderer.color(guiGraphics, SpecialColors.TAB_CONTAINER_EDIT_MODE);
    }

    @Override
    public void renderToolTip(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        super.renderToolTip(guiGraphics, mouseX, mouseY);
        displayTooltips(guiGraphics, mouseX, mouseY, Component.translatable("tooltip.mekck.order_panel"));
    }
}
