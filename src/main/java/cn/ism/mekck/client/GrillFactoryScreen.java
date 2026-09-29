package cn.ism.mekck.client;

import cn.ism.mekck.CuttingMachineFactoryTier;
import cn.ism.mekck.machine.grill.GrillFactoryTile;
import cn.ism.mekck.menu.GrillFactoryMenu;
import cn.ism.mekck.network.GrillSeasoningTogglePacket;
import cn.ism.mekck.network.ModMessages;
import mekanism.api.math.FloatingLong;
import mekanism.client.gui.GuiConfigurableTile;
import mekanism.client.gui.element.progress.GuiProgress;
import mekanism.client.gui.element.progress.IProgressInfoHandler;
import mekanism.client.gui.element.progress.ProgressType;
import mekanism.client.gui.element.tab.GuiEnergyTab;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

/**
 * 烧烤工厂 GUI（Mek 体系版）—— 阶段 3 Task 3。
 *
 * <h3>从 1368 行缩到一百来行：省下的是什么</h3>
 * 与切菜、研磨、种植切配同款：继承 {@link GuiConfigurableTile} 后
 * {@code super.addGuiElements()} 一句就把 侧配 / 传输配置 / 升级 / 红石 / 安全 排好，
 * 坐标由 Mek 的常量决定。<b>这正是本项目换掉旧体系的目的</b>：旧自研 tab 与 Mek 的 tab
 * 分属两套坐标系，运行时只能靠 {@code avoidEnergyTabY} 互相避让，表现为侧栏元素重叠。
 *
 * <p>槽位 widget 也不用手写：{@code dynamicSlots = true} 让 {@code GuiMekanism.addSlots()}
 * 遍历 {@code menu.slots}，对每个 {@code InventoryContainerSlot} 按其 {@code ContainerSlotType}
 * 自动建 {@code GuiSlot}，坐标直接取容器槽的 x/y——而那两个值又是 tile 侧
 * {@code getInitialInventory} / {@code appendExtraSlots} 里排方阵与调味料列时写进去的。
 * 旧界面手摆那 2N 个 {@code GuiVirtualSlot} 的原因也在这里：旧菜单的槽是原版
 * {@code Slot}，不是 {@code InventoryContainerSlot}，自动通路根本认不出来。</p>
 *
 * <h3>本界面仍然手绘的唯一一块：3 个调味料启用按钮</h3>
 * 它不是槽位、没有 Mek 等价物（{@code IInventorySlot} 表达不了「这一格是否参与自动调味」），
 * 所以只能留在 widget 层之外自己画自己命中。坐标与 tile 侧调味料列的
 * {@code (SEASONING_SLOT_X, SEASONING_SLOT_Y + i * STEP)} 同源，一枚槽配一枚开关，
 * 按钮排在槽列正下方。
 *
 * <h3>本界面没有的面板（不是取舍，是 tile 换人的必然结果）</h3>
 * <ul>
 *   <li><b>45 格存储区 + 单列纵向滚动</b>：旧 BE 有独立的 {@code STORAGE_SLOTS} 存储缓冲，
 *       新 tile 的 {@code MekCkMachineTile} 不再提供这层缓冲（订单引擎已经能直接读方阵）。
 *       穿串/烹饪两个「固定输入 + 存储缓冲」家族仍需要它，届时由基类的
 *       「槽位布局可覆写」扩展点提供，而不是在这里画一列虚拟槽。</li>
 *   <li><b>自动分配 / ME 自动处理 / 工作模式 / 下单面板</b>：与研磨、切菜同款，
 *       GUI 侧的这几块手绘面板等 {@code GuiConfigurableTile} 的 tab 布局定稿后统一接。
 *       订单<b>引擎</b>没丢——{@code GrillFactoryExecutor} 仍持有订单状态、
 *       仍按订单门禁配方匹配、仍推进完成计数。</li>
 *   <li><b>温度显示</b>：旧 BE 实现了 {@code IMekanismHeatHandler}。grilling 的「热」
 *       是被加热的串而不是机器发热，且新 tile 没接 {@code ITileHeatHandler}，
 *       显示一个没有数据源的读数只会误导玩家。真要迁时见 P3-T3 的遗留项。</li>
 * </ul>
 */
public final class GrillFactoryScreen extends GuiConfigurableTile<GrillFactoryTile, GrillFactoryMenu> {

    /** 输入方阵与输出方阵的水平间隔，与 tile 侧 {@code GRID_GAP} 同值。 */
    private static final int GAP_BETWEEN = 30;
    /** 调味料列的横向起点，与 tile 侧 {@code SEASONING_SLOT_X} 同值。 */
    private static final int SEASONING_COL_X = 8;
    /** 调味料列的纵向起点，与 tile 侧 {@code SEASONING_SLOT_Y} 同值。 */
    private static final int SEASONING_COL_Y = 55;
    /** 单个槽位间距，与 tile 侧 {@code SEASONING_SLOT_STEP} 同值。 */
    private static final int SLOT_STEP = 18;
    /** 调味料槽数。 */
    private static final int SEASONING_SLOTS = GrillFactoryTile.SEASONING_SLOTS;
    /** 调味料槽列与开关行合计多占的高度（3 个槽 + 1 行开关）。 */
    private static final int SEASONING_BLOCK_HEIGHT = (SEASONING_SLOTS + 1) * SLOT_STEP;

    public GrillFactoryScreen(GrillFactoryMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        CuttingMachineFactoryTier tier = tile.getTier();
        int columns = tier == null ? 1 : (int) Math.ceil(Math.sqrt(tier.processes));
        int rows = tier == null ? 1 : (int) Math.ceil((double) tier.processes / columns);
        int extraHeight = Math.max(0, (rows - 2) * SLOT_STEP) + SEASONING_BLOCK_HEIGHT;
        // 面板尺寸必须随并行数长，否则高等级工厂的槽位会画到面板外面。
        // 底图不用自己画：GuiMekanism.renderBg 直接把 base.png 拉到 (imageWidth, imageHeight)。
        imageWidth = 38 + columns * SLOT_STEP + GAP_BETWEEN + columns * SLOT_STEP + 20;
        imageHeight = 184 + extraHeight;
        inventoryLabelY = 89 + extraHeight;
        dynamicSlots = true;
    }

    @Override
    protected void addGuiElements() {
        // 侧配 / 传输配置 / 升级 / 红石 / 安全 + 全部槽位 widget —— 一句 super 全排好。
        super.addGuiElements();

        // 能量 tab：Mek 固定 (x=-26, y=137, 26, 26)，与侧配/传输配置同处左列不冲突。
        // tooltip 里的存量/上限/每秒耗量由 Mek 自己组装。
        CuttingMachineFactoryTier tier = tile.getTier();
        addRenderableWidget(new GuiEnergyTab(this, tile.getEnergyContainer(),
                () -> FloatingLong.create(tier == null ? 0 : tier.energyPerTick)));

        // 进度条：SMALL_RIGHT 箭头，横在输入方阵与输出方阵之间。
        int columns = tier == null ? 1 : (int) Math.ceil(Math.sqrt(tier.processes));
        int rows = tier == null ? 1 : (int) Math.ceil((double) tier.processes / columns);
        int progressX = 38 + columns * SLOT_STEP + (GAP_BETWEEN - 28) / 2;
        int progressY = 41 + rows * SLOT_STEP / 2 - 4;
        addRenderableWidget(new GuiProgress(new IProgressInfoHandler() {
            @Override
            public double getProgress() {
                return menu.getProgressRatio();
            }

            @Override
            public boolean isActive() {
                return menu.isBusy();
            }
        }, ProgressType.SMALL_RIGHT, this, progressX, progressY));
    }

    @Override
    protected void renderBg(GuiGraphics guiGraphics, float partialTick, int mouseX, int mouseY) {
        super.renderBg(guiGraphics, partialTick, mouseX, mouseY);

        // 调味料启用按钮（每枚槽正下方一枚）：默认工作模式下这一格是否参与自动调味。
        // 不是槽位，Mek 的自动通路认不出来，只能自己画自己命中。
        int buttonY = topPos + SEASONING_COL_Y + SEASONING_SLOTS * SLOT_STEP;
        for (int i = 0; i < SEASONING_SLOTS; i++) {
            int bx = leftPos + SEASONING_COL_X + i * SLOT_STEP;
            boolean enabled = menu.isSeasoningEnabled(i);
            boolean hovered = mouseX >= bx && mouseX < bx + SLOT_STEP
                    && mouseY >= buttonY && mouseY < buttonY + SLOT_STEP;
            int color = enabled
                    ? (hovered ? 0xFF7CE25F : 0xFF2E8B2E)
                    : (hovered ? 0xFF666666 : 0xFF333333);
            guiGraphics.fill(bx, buttonY, bx + SLOT_STEP, buttonY + SLOT_STEP, color);
            String label = enabled ? "开" : "关";
            guiGraphics.drawString(font, label,
                    bx + (SLOT_STEP - font.width(label)) / 2, buttonY + 5, 0xFFFFFFFF);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            int buttonY = topPos + SEASONING_COL_Y + SEASONING_SLOTS * SLOT_STEP;
            for (int i = 0; i < SEASONING_SLOTS; i++) {
                int bx = leftPos + SEASONING_COL_X + i * SLOT_STEP;
                if (mouseX >= bx && mouseX < bx + SLOT_STEP
                        && mouseY >= buttonY && mouseY < buttonY + SLOT_STEP) {
                    ModMessages.sendToServer(new GrillSeasoningTogglePacket(tile.getBlockPos(), i));
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }
}
