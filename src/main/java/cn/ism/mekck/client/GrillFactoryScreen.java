package cn.ism.mekck.client;

import cn.ism.mekck.machine.grill.GrillFactoryTile;
import cn.ism.mekck.menu.GrillFactoryMenu;
import cn.ism.mekck.network.GrillSeasoningTogglePacket;
import cn.ism.mekck.network.ModMessages;
import mekanism.client.gui.GuiConfigurableTile;
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
public final class GrillFactoryScreen extends MekCkFactoryScreenBase<GrillFactoryTile, GrillFactoryMenu> {

    /**
     * 槽位悬浮窗标签页 —— 只有 &gt;17 并行的高档工厂才有窗口槽，
     * 所以本字段可能恒为 null（见 {@code menu.windowSlots().isEmpty()}）。
     * 关闭窗口后需要用同一实例重新激活，因此必须留引用。
     */
    private MekCkSlotWindowTab slotWindowTab;

    /** 调味料列的横向起点，与 tile 侧 {@code SEASONING_SLOT_X} 同值。 */
    private static final int SEASONING_COL_X = 8;
    /** 单个槽位间距，与 tile 侧 {@code SEASONING_SLOT_STEP} 同值。 */
    private static final int SLOT_STEP = 18;
    /** 调味料槽数。 */
    private static final int SEASONING_SLOTS = GrillFactoryTile.SEASONING_SLOTS;

    public GrillFactoryScreen(GrillFactoryMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        // 面板尺寸由 MekCkFactoryLayout 统一给出（屏幕与菜单共用同一份公式），见该类注释。
        // 3 个调味料槽 + 1 行开关是「额外槽」，方阵分支的高度沿用旧值（不加行）。
        imageWidth = cn.ism.mekck.menu.MekCkFactoryLayout.gridFamilyPanelWidth(tile);
        imageHeight = cn.ism.mekck.menu.MekCkFactoryLayout.gridFamilyPanelHeight(tile, SEASONING_SLOTS, 1, 0);
        inventoryLabelY = cn.ism.mekck.menu.MekCkFactoryLayout.inventoryLabelY(imageHeight);
        dynamicSlots = true;
    }

    @Override
    protected void addGuiElements() {
        // 侧配 / 传输配置 / 升级 / 红石 / 安全 + 全部槽位 widget —— 一句 super 全排好。
        super.addGuiElements();

        // 能量 tab：Mek 固定 (x=-26, y=137, 26, 26)，与侧配/传输配置同处左列不冲突。
        // tooltip 里的存量/上限/每秒耗量由 Mek 自己组装。
        // 第三个参数是「使用量」供给器 —— 上游 GuiFactory 传的是 tile::getLastUsage
        // （上一 tick 的真实扣电量），不是等级的声明值。
        addRenderableWidget(new GuiEnergyTab(this, tile.getEnergyContainer(), tile::getLastUsage));

        // 竖直能源条（旧 GUI 有、迁移时丢的那条），位置与数据源见基类。
        addEnergyBar();

        // 自动分选标签页（上游 GuiFactory 的第一句就是它）。
        addSortingTab();

        // 槽位悬浮窗标签页：只有高档工厂（>17 并行）或烹饪/穿串才有窗口槽，
        // 三组皆空时不加标签页（menu.windowSlots().isEmpty()）。
        if (!menu.windowSlots().isEmpty()) {
            slotWindowTab = addRenderableWidget(new MekCkSlotWindowTab(this, tile,
                    menu.windowSlots(), () -> slotWindowTab));
        }

        // 进度条：一行式档位每并行槽一条（上游 GuiFactory / GuiExtraFactory 的做法），
        // 悬浮窗布局主面板上没有机器槽、居中一条。几何与理由见 MekCkFactoryScreenBase。
        addFactoryProgressBars(menu::getProgressRatio);
    }

    @Override
    protected void renderBg(GuiGraphics guiGraphics, float partialTick, int mouseX, int mouseY) {
        super.renderBg(guiGraphics, partialTick, mouseX, mouseY);

        // 调味料启用按钮（每枚槽正下方一枚）：默认工作模式下这一格是否参与自动调味。
        // 不是槽位，Mek 的自动通路认不出来，只能自己画自己命中。
        int buttonY = topPos + seasoningButtonY();
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

    /**
     * 调味料开关行的 y（面板相对坐标）。
     *
     * <p><b>必须与 tile 侧的槽位几何同源</b>：槽位由
     * {@code MekCkFactoryLayout.extraSlotY(i, oneRow)} 排在
     * {@code extraSlotY0(oneRow)} 起的一列，开关行紧接在最后一枚槽下面。
     * 此前这里写死 {@code 55 + 3*18 = 109}，而一行式布局下槽位其实从 <b>41</b> 起
     * ⇒ 开关行比槽列低 14px，且 109..127 正好压进玩家背包首行（一行式面板 187 的背包在 105）。</p>
     */
    private int seasoningButtonY() {
        return cn.ism.mekck.menu.MekCkFactoryLayout.extraSlotRowBelow(
                SEASONING_SLOTS, 0, tile.usesOneRowLayout());
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            int buttonY = topPos + seasoningButtonY();
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
