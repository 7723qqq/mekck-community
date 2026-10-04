package cn.ism.mekck.client;

import cn.ism.mekck.blockentity.WineCellarBlockEntity;
import cn.ism.mekck.menu.WineCellarMenu;
import cn.ism.mekck.network.ModMessages;
import cn.ism.mekck.network.WineCellarConfigPacket;
import com.mojang.blaze3d.vertex.PoseStack;
import java.util.List;
import mekanism.api.math.FloatingLong;
import mekanism.client.gui.GuiMekanism;
import mekanism.client.gui.GuiUtils;
import mekanism.client.gui.element.GuiInnerScreen;
import mekanism.client.gui.element.bar.GuiBar.IBarInfoHandler;
import mekanism.client.gui.element.bar.GuiDynamicHorizontalRateBar;
import mekanism.client.gui.element.gauge.GaugeType;
import mekanism.client.gui.element.gauge.GuiEnergyGauge;
import mekanism.client.gui.element.slot.GuiVirtualSlot;
import mekanism.client.gui.element.slot.SlotType;
import mekanism.client.gui.element.tab.GuiEnergyTab;
import mekanism.client.gui.element.text.GuiTextField;
import mekanism.client.render.MekanismRenderType;
import mekanism.client.render.MekanismRenderer;
import mekanism.client.render.lib.effect.BoltRenderer;
import mekanism.common.inventory.container.slot.IVirtualSlot;
import mekanism.common.inventory.container.slot.SlotOverlay;
import mekanism.common.inventory.warning.WarningTracker.WarningType;
import mekanism.common.lib.Color;
import mekanism.common.lib.effect.BoltEffect;
import mekanism.common.lib.effect.BoltEffect.BoltRenderInfo;
import mekanism.common.lib.effect.BoltEffect.FadeFunction;
import mekanism.common.lib.effect.BoltEffect.SpawnFunction;
import mekanism.common.util.text.InputValidator;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/**
 * 陈化窖（F20）屏幕：继承 {@link GuiMekanism}（才能用能量条 / 数字输入框 / §F20⑤ 的闪电弧）。
 * 黑屏区 + 3×3 储存格网格 + 右侧粗能量计 + 左上电源槽 + 顶部倍速数字输入框。
 * §F45：闪电/进度条/能量计/黑屏 1:1 照抄反质子核合成器 GuiAntiprotonicNucleosynthesizer
 * （源码见参考示例，含关键修正：构造器第 4 参是 segments 分段数，闪电条数必须走 .count() 链），
 * 仅配色换成我们的酒红→琥珀。§F47：储存格不再被黑屏背板盖没（背板改画在 renderBg，
 * 见 {@link #renderBg}），物品栏标签下移免被进度条压住。
 */
public final class WineCellarScreen extends GuiMekanism<WineCellarMenu> {
    private static final int SPEED_LABEL_X = 44;
    private static final int SPEED_LABEL_Y = 78;
    private static final int SPEED_FIELD_X = 76;
    private static final int SPEED_FIELD_Y = 76;
    private static final int SPEED_FIELD_W = 30;
    private static final int SPEED_FIELD_H = 12;

    // §F45 闪电照抄 SPS：黑屏区内一条水平线 (47,50)→(147,50)，GUI 相对坐标
    //（渲染挂 drawForegroundText，pose 已平移至 GUI 原点，不再加 leftPos/topPos）
    private static final Vec3 ARC_FROM = new Vec3(47, 50, 0);
    private static final Vec3 ARC_TO = new Vec3(147, 50, 0);
    // 酒红 → 琥珀 渐变（闪电本体取琥珀端、进度条用 scale）
    private static final Color WINE_RED = Color.rgbi(140, 26, 48);
    private static final Color AMBER = Color.rgbi(240, 168, 72);
    // SPS 同构：默认 BoltRenderInfo（electricity 基底），只换颜色
    private static final BoltRenderInfo BOLT_RENDER_INFO = new BoltRenderInfo().color(AMBER);

    /** 本 Screen 私有闪电渲染器（owner 传 this），逐帧按在陈化格数重生。 */
    private final BoltRenderer bolt = new BoltRenderer();

    private GuiTextField speedField;

    public WineCellarScreen(WineCellarMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        imageWidth = PANEL_WIDTH;
        imageHeight = PANEL_HEIGHT;
        // §F47：原 -12(=91) 被进度条 (5,88,h≈5) 压住，下移让位。
        // 背包首行与标签同源：菜单覆写 getInventoryYOffset() 返回 WineCellarMenu.INV_TOP，
        // 标签取同一个常量减一行字高（迁移后菜单不再自己摆背包，常量必须由菜单侧提供）。
        inventoryLabelY = WineCellarMenu.INV_TOP - 9;
        dynamicSlots = true;
        // §F45：同 SPS 拓宽 20px，右侧给能量计 (172,18) 让位（GuiMekanism 背景按 imageWidth 自适应铺绘）
        imageWidth += 20;
    }

    // ── 面板布局（屏幕侧唯一出处）──────────────────────────────────────
    //
    // 2026-10-03 迁移：槽位坐标不再在这里——它们只在 MekCkSlots.WineCellar 里定义一次，
    // 由 tile 的 getInitialInventory 写进槽对象，Mek 据此自动渲染。
    // 屏幕现在只剩「面板尺寸」与「背包标签位置」这类纯视觉常量。

    private static final int PANEL_WIDTH = 176;
    private static final int PANEL_HEIGHT = 184;
    /** 黑屏背板（同 SPS GuiInnerScreen 坐标）—— 画在 renderBg 里，见 renderBg 的注释。 */
    private static final int INNER_SCREEN_X = 45;
    private static final int INNER_SCREEN_Y = 18;
    private static final int INNER_SCREEN_W = 104;
    private static final int INNER_SCREEN_H = 68;

    @Override
    protected void addGuiElements() {
        super.addGuiElements();

        // §F45：黑屏背板（同 SPS GuiInnerScreen 坐标）。
        //
        // ⚠️ 迁移后这里不再手画任何槽：Mek 的 GuiMekanism.addSlots() 会遍历容器的
        // InventoryContainerSlot 自动建 GuiSlot，槽的坐标就是 tile 建槽时写进去的
        // （MekCkSlots.WineCellar）。原先手画的 GuiVirtualSlot 与菜单的 Slot 各有一套坐标，
        // 靠 x-1/y-1 凑合对齐——那正是本次重写要消灭的「一个框两套坐标」。
        //
        // 背板仍要画：它是槽位底下的纹理层，与槽位 widget 是两件事 —— 但必须画在
        // renderBg 里（见 renderBg），不能作为 widget 挂进来。

        // §F45：能量显示改 SPS 同款粗能量计 GuiEnergyGauge SMALL_MED (172,18)，替掉旧细 GuiVerticalPowerBar；
        // tooltip 覆写为 FE 口径（不走 Mekanism 默认 kJ 格式化）
        addRenderableWidget(new GuiEnergyGauge(new GuiEnergyGauge.IEnergyInfoHandler() {
            @Override
            public FloatingLong getEnergy() {
                return FloatingLong.create(menu.getEnergy());
            }

            @Override
            public FloatingLong getMaxEnergy() {
                return FloatingLong.create(menu.getEnergyCapacity());
            }
        }, GaugeType.SMALL_MED, WineCellarScreen.this, 172, 18) {
            @Override
            public List<Component> getTooltipText() {
                return List.of(Component.translatable("gui.mekck.energy", menu.getEnergy(), menu.getEnergyCapacity()));
            }
        });
        // §F45 后续：接 Mek 警告系统（WarningTracker）——能量耗尽时左列出现警告 tab；
        // gauge 匿名子类不便链式 .warning，走 GuiMekanism#trackWarning 独立注册（tab tooltip 同源）。
        trackWarning(WarningType.NOT_ENOUGH_ENERGY, () -> menu.getEnergy() <= 0);

        // 能量信息标签（§F43后续 §F44：补「能耗 FE/t」行，与急冻制冰机等同源双行口径）：
        // 客户端按服务端同公式 Σ 62.5×瓶数×倍速（只计可陈化且未封顶的格）实时估算。
        addRenderableWidget(new GuiEnergyTab(this, () -> {
            java.util.List<Component> lines = new java.util.ArrayList<>();
            lines.add(Component.translatable("gui.mekck.energy_stored", menu.getEnergy(), menu.getEnergyCapacity()));
            lines.add(Component.translatable("gui.mekck.energy_per_tick", formatBolt(currentEnergyDraw())));
            return lines;
        }));

        // 倍速数字输入框（1..50，回车提交）
        speedField = new GuiTextField(this, SPEED_FIELD_X, SPEED_FIELD_Y, SPEED_FIELD_W, SPEED_FIELD_H)
                .setInputValidator(InputValidator.DIGIT)
                .configureDigitalBorderInput(this::commitSpeed);
        speedField.setMaxLength(2);
        speedField.setText(String.valueOf(menu.getSpeed()));
        addRenderableWidget(speedField);

        // 整桶一条渐变进度条（酒红→琥珀）；§F45 几何照抄 SPS：(5,88,183) 横贯底缘，level = 各活跃格平均进度
        addRenderableWidget(new GuiDynamicHorizontalRateBar(this, new IBarInfoHandler() {
            @Override
            public Component getTooltip() {
                return Component.translatable("gui.mekck.wine_cellar");
            }

            @Override
            public double getLevel() {
                return overallProgress();
            }
        }, 5, 88, 183, Color.ColorFunction.scale(WINE_RED, AMBER)));
    }

    /**
     * 黑屏背板 —— 画在 {@code renderBg}（槽位 widget 与物品之前）。
     *
     * <p>迁移后自动 {@code GuiSlot} 不调 {@code setRenderAboveSlots}：槽贴图在
     * {@code super.render} 阶段画、物品在 {@code renderSlot} 阶段画，而 widget 的
     * {@code drawBackground}（{@code GuiInnerScreen} 背板原来挂在这里）要到
     * {@code renderLabels} 阶段才跑 —— 背板会把 3×3 存储格整片盖没
     * （{@code inner_screen.png} alpha 全 255）。画进 renderBg 后不再依赖元素顺序。</p>
     */
    @Override
    protected void renderBg(GuiGraphics guiGraphics, float partialTick, int mouseX, int mouseY) {
        super.renderBg(guiGraphics, partialTick, mouseX, mouseY);
        // renderBg 的 pose 在屏幕原点 ⇒ 这里用绝对屏幕坐标（leftPos/topPos + 面板相对）。
        GuiUtils.renderBackgroundTexture(guiGraphics, GuiInnerScreen.SCREEN,
                GuiInnerScreen.SCREEN_SIZE, GuiInnerScreen.SCREEN_SIZE,
                leftPos + INNER_SCREEN_X, topPos + INNER_SCREEN_Y,
                INNER_SCREEN_W, INNER_SCREEN_H, 256, 256);
    }

    /** 各活跃格进度百分比的平均值（0..1）；非酒/空格自动跳过。 */
    private double overallProgress() {
        int sum = 0;
        int n = 0;
        for (int i = 0; i < WineCellarBlockEntity.SLOT_COUNT; i++) {
            int p = menu.getSlotProgress(i);
            if (p >= 0) {
                sum += p;
                n++;
            }
        }
        return n == 0 ? 0 : Math.min(1.0, sum / (double) (n * 100));
    }

    /** 当前实时能耗估算（FE/t）：与服务端 tickAging 同公式 Σ 62.5×数量×倍速（仅可陈化格）。 */
    private double currentEnergyDraw() {
        int s = Math.max(1, Math.min(50, menu.getSpeed()));
        net.minecraft.world.level.Level lvl = minecraft == null ? null : minecraft.level;
        double sum = 0;
        for (int i = 0; i < WineCellarBlockEntity.SLOT_COUNT; i++) {
            // 菜单槽顺序是「升级 2 → 存储 9 → 电源 1 → 背包」：直接 slots.get(i) 会读到
            // 升级槽与存储 0..6，漏掉存储 7、8（M6-M2）。走菜单的存储下标换算。
            ItemStack st = menu.slots.get(menu.getStorageSlotIndex(i)).getItem();
            if (st.isEmpty() || !cn.ism.mekck.compat.WineAgeCompat.hasWineAge(st)) continue;
            if (cn.ism.mekck.compat.WineAgeCompat.isAgedOut(st, lvl)) continue;
            sum += 62.5D * st.getCount() * s;
        }
        return sum;
    }

    /** 能耗读数：整数值不带小数（64瓶@1×=4000），非整保留一位（@1.6×类中间态）。 */
    private static String formatBolt(double v) {
        return v == Math.floor(v) ? String.valueOf((long) v) : String.format("%.1f", v);
    }

    /** 回车提交倍速：解析 → 钳制 [1,50] → 发 packet；不同则同步回显。 */
    private void commitSpeed() {
        String text = speedField.getText();
        if (text.isEmpty()) {
            speedField.setText(String.valueOf(menu.getSpeed()));
            return;
        }
        try {
            int s = Math.max(1, Math.min(50, Integer.parseInt(text)));
            if (s != menu.getSpeed()) {
                ModMessages.sendToServer(new WineCellarConfigPacket(menu.getBlockPos(), s));
            }
            speedField.setText(String.valueOf(s));
        } catch (NumberFormatException ignored) {
            speedField.setText(String.valueOf(menu.getSpeed()));
        }
    }

    @Override
    protected void drawForegroundText(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        renderTitleText(guiGraphics);
        drawString(guiGraphics, playerInventoryTitle, (imageWidth - 162) / 2, inventoryLabelY, titleTextColor());
        drawString(guiGraphics, Component.translatable("gui.mekck.wine_cellar_speed"), SPEED_LABEL_X, SPEED_LABEL_Y, 0xFFFFFFFF);
        super.drawForegroundText(guiGraphics, mouseX, mouseY);
        // §F45：闪电渲染挂点照抄 SPS（drawForegroundText 尾部，此处 pose 已为 GUI 原点）
        if (menu.getActiveCount() > 0) {
            PoseStack pose = guiGraphics.pose();
            pose.pushPose();
            pose.translate(0, 0, 100);
            MultiBufferSource.BufferSource bufferSource = guiGraphics.bufferSource();
            bolt.update(this, agingBoltEffect(), MekanismRenderer.getPartialTick());
            bolt.render(MekanismRenderer.getPartialTick(), pose, bufferSource);
            bufferSource.endBatch(MekanismRenderType.MEK_LIGHTNING);
            pose.popPose();
        }
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        // §F44：倍速框实时回显——旧版只在首帧 setText 一次，彼时 ContainerData 尚未同步
        // （stored 数组全 0）⇒ 永远显示 0（实际服务端是 1）；未聚焦时每帧跟同步值。
        if (speedField != null && !speedField.isTextFieldFocused()) {
            speedField.setText(String.valueOf(menu.getSpeed()));
        }
        super.render(guiGraphics, mouseX, mouseY, partialTick);
    }

    /**
     * §F45：闪电参数照抄 SPS——修正旧版关键误用：BoltEffect 构造器第 4 参是 <b>segments 分段数</b>
     *（官方固定 15），闪电<b>条数</b>必须走 .count() 链（默认 1）⇒ 旧版只画 1 条、观感成细直线。
     * 条数闸门用我们的在陈化格数（active×2.2 封顶 20，与 SPS rate/8 同构），配色留琥珀。
     */
    private BoltEffect agingBoltEffect() {
        int active = menu.getActiveCount();
        return new BoltEffect(BOLT_RENDER_INFO, ARC_FROM, ARC_TO, 15)
                .count(Math.min(20, (int) Math.ceil(active * 2.2)))
                .size(1)
                .lifespan(1)
                .spawn(SpawnFunction.CONSECUTIVE)
                .fade(FadeFunction.NONE);
    }
}
