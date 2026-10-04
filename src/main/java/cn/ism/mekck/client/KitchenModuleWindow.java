package cn.ism.mekck.client;

import cn.ism.mekck.kitchen.KitchenFamily;
import cn.ism.mekck.kitchen.KitchenModule;
import cn.ism.mekck.menu.CentralKitchenMenu;
import mekanism.client.gui.IGuiWrapper;
import mekanism.client.gui.element.window.GuiWindow;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.ArrayList;
import java.util.List;

/**
 * 中央厨房的「可安装模块」窗口。
 *
 * <p>左侧为系列列表（滚轮滚动）：每行显示该系列的**基础机器图标**与安装状态；
 * 底部为「可安装模块条」——**每个系列只显示 1 个基础机器图标**（未安装时淡显），
 * 悬停提示「可安装本系列的任意等级机器；处理能力由所安装的模块与工厂等级决定」。</p>
 *
 * <p>模块的放入 / 取出仍在主界面的模块槽完成（与升级界面一致的 20 刻读条语义）。</p>
 */
@OnlyIn(Dist.CLIENT)
public class KitchenModuleWindow extends GuiWindow {

    /** 过滤区布局。 */
    private static final int FILTER_TITLE_Y = 104;
    private static final int FILTER_MODE_Y = 116;
    private static final int FILTER_SLOT_Y = 134;
    private static final int FILTER_SLOT_X = 28;
    private static final int FILTER_SLOT_STEP = 16;

    private static final int ROWS = 7;
    private static final int ROW_H = 11;
    private static final int LIST_X = 6;
    private static final int LIST_Y = 18;

    private final CentralKitchenMenu menu;
    private final List<KitchenFamily> families = new ArrayList<>();
    private int scrollRow = 0;
    private int selectedRow = -1;

    public KitchenModuleWindow(IGuiWrapper gui, CentralKitchenMenu menu) {
        // 窗口身份必须用 UNSPECIFIED（同 NetworkOrderWindow）：UPGRADE 会与升级窗共用
        // 「上次位置」存档，先开升级窗再开本窗会弹到升级窗的旧位置上。
        super(gui, gui.getWidth() / 2 - 100, 10, 200, 196,
                mekanism.common.inventory.container.SelectedWindowData.WindowType.UNSPECIFIED);
        this.menu = menu;
        interactionStrategy = InteractionStrategy.ALL;
        for (KitchenFamily f : KitchenFamily.values()) {
            families.add(f);
        }
        // 请求一次过滤器同步（服务端会把全部系列的过滤设置推给客户端）
        try {
            cn.ism.mekck.network.ModMessages.sendToServer(
                    new cn.ism.mekck.network.KitchenFilterPacket(menu.getMachine().getBlockPos(),
                            0, (byte) 4, 0, net.minecraft.world.item.ItemStack.EMPTY));
        } catch (Throwable ignored) {
        }
    }

    private int maxScroll() {
        return Math.max(0, families.size() - ROWS);
    }

    @Override
    public void renderForeground(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        super.renderForeground(guiGraphics, mouseX, mouseY);
        drawTitleText(guiGraphics, Component.translatable("gui.mekck.ui.modules"), 5);

        int mask = menu.getFamilyMask();
        for (int i = 0; i < ROWS; i++) {
            int idx = scrollRow + i;
            if (idx >= families.size()) break;
            KitchenFamily family = families.get(idx);
            boolean installed = (mask & (1 << family.ordinal())) != 0;
            int y = relativeY + LIST_Y + i * ROW_H;
            int x = relativeX + LIST_X;
            // 选中行高亮
            if (idx == selectedRow) {
                guiGraphics.fill(x - 2, y - 2, x + 188, y + ROW_H - 1, 0x40FFFFFF);
            }
            // 系列图标（基础机器）
            ItemStack icon = family.icon();
            if (!icon.isEmpty()) {
                guiRenderer().renderItem(guiGraphics, icon, x, y, 0.6F);
            }
            String status = installed
                    ? installedStatus(family)
                    : Component.translatable("gui.mekck.ui.module_not_installed").getString();
            int color = installed ? 0xFF3FBF3F : 0xFF888888;
            guiGraphics.drawString(getFont(), family.id + "  " + status, x + 14, y + 1, color, false);
        }

        // 可安装模块条：每系列 1 个基础机器图标
        int stripY = relativeY + 18 + ROWS * ROW_H + 8;
        guiGraphics.fill(relativeX + 5, stripY - 2, relativeX + 195, stripY + 14, 0x60000000);
        guiGraphics.drawString(getFont(), Component.translatable("gui.mekck.ui.modules.desc").getString(),
                relativeX + 7, stripY - 11, 0xFF404040, false);
        for (int i = 0; i < families.size(); i++) {
            KitchenFamily family = families.get(i);
            int sx = relativeX + 7 + (i % 15) * 12;
            int sy = stripY + (i / 15) * 12;
            ItemStack icon = family.icon();
            if (!icon.isEmpty()) {
                guiRenderer().renderItem(guiGraphics, icon, sx, sy, 0.6F);
            }
            boolean installed = (mask & (1 << family.ordinal())) != 0;
            if (!installed) {
                guiGraphics.fill(sx, sy, sx + 11, sy + 11, 0x80AAAAAA);
            }
        }

        renderFilterSection(guiGraphics);
    }

    /** 过滤区：选中系列的模式按钮 + 9 个过滤材料幽灵槽。 */
    private void renderFilterSection(GuiGraphics guiGraphics) {
        int px = relativeX + LIST_X;
        int py = relativeY + FILTER_TITLE_Y;
        guiGraphics.drawString(getFont(), Component.translatable("gui.mekck.ui.filter_section").getString(), px, py, 0xFF404040, false);
        if (selectedRow < 0 || selectedRow >= families.size()) {
            guiGraphics.drawString(getFont(), Component.translatable("gui.mekck.ui.filter.pick_series_first").getString(), relativeX + LIST_X, relativeY + FILTER_MODE_Y,
                    0xFF808080, false);
            return;
        }
        KitchenFamily family = families.get(selectedRow);
        var cached = cn.ism.mekck.network.KitchenFilterSyncPacket.CLIENT_CACHE.get(family.ordinal());
        int mode = cached == null ? 0 : cached.mode;
        String modeKey = switch (mode) {
            case 1 -> "gui.mekck.ui.filter_mode.whitelist";
            case 2 -> "gui.mekck.ui.filter_mode.blacklist";
            default -> "gui.mekck.ui.off";
        };
        String modeName = Component.translatable(modeKey).getString();
        int modeColor = switch (mode) {
            case 1 -> 0xFF80C0FF;
            case 2 -> 0xFFFF9090;
            default -> 0xFF808080;
        };
        // 模式按钮
        int bx = relativeX + LIST_X;
        int by = relativeY + FILTER_MODE_Y;
        guiGraphics.fill(bx, by, bx + 70, by + 12, 0xFF5A5A5A);
        guiGraphics.fill(bx + 1, by + 1, bx + 69, by + 11, 0xFFB0B0B0);
        guiGraphics.drawString(getFont(), Component.translatable("gui.mekck.ui.filter.label", modeName).getString(), bx + 4, by + 2, modeColor, false);
        // 清空按钮
        int cbx = bx + 74;
        guiGraphics.fill(cbx, by, cbx + 34, by + 12, 0xFF5A5A5A);
        guiGraphics.fill(cbx + 1, by + 1, cbx + 33, by + 11, 0xFFB0B0B0);
        guiGraphics.drawString(getFont(), Component.translatable("gui.mekck.ui.clear").getString(), cbx + 6, by + 2, 0xFF303030, false);

        // 自动加工开关（第三轮补：此前整个自动加工引擎没有任何入口）
        //
        // 放在过滤模式右边同一行 —— 它和过滤器是同一粒度（按系列）的设置，
        // 而自动模式**正是按过滤器的结果挑配方**的（见 CentralKitchenBlockEntity.startThread），
        // 拆到别处反而割裂。
        //
        // 只对**已装模块**的系列画：没有模块时服务端会忽略这个开关（case 5 里的
        // abilityOf 判空），画一个点了没反应的按钮就是「把不可达换个形式复现」。
        boolean installed = (menu.getFamilyMask() & (1 << family.ordinal())) != 0;
        if (installed) {
            boolean auto = cached != null && cached.autoMode;
            int abx = bx + 112;
            guiGraphics.fill(abx, by, abx + 76, by + 12, auto ? 0xFF2E6B2E : 0xFF5A5A5A);
            guiGraphics.fill(abx + 1, by + 1, abx + 75, by + 11, auto ? 0xFF9CE09C : 0xFFB0B0B0);
            guiGraphics.drawString(getFont(), Component.translatable("gui.mekck.ui.auto",
                    Component.translatable(auto ? "gui.mekck.ui.on" : "gui.mekck.ui.off")).getString(), abx + 6, by + 2,
                    auto ? 0xFF103010 : 0xFF303030, false);
        }
        // 幽灵槽
        for (int i = 0; i < cn.ism.mekck.kitchen.KitchenFilter.MAX_ITEMS; i++) {
            int sx = relativeX + FILTER_SLOT_X + i * FILTER_SLOT_STEP;
            int sy = relativeY + FILTER_SLOT_Y;
            guiGraphics.fill(sx - 1, sy - 1, sx + 15, sy + 15, 0xFF606060);
            guiGraphics.fill(sx, sy, sx + 14, sy + 14, 0xFF9A9A9A);
            if (cached != null && i < cached.items.size()) {
                var stack = cached.items.get(i);
                if (!stack.isEmpty()) guiRenderer().renderItem(guiGraphics, stack, sx - 1, sy - 1, 1.0F);
            }
        }
        // 说明
        // 说明：模式 0=关闭（整系列全做）、1=白名单、其余按黑名单处理。
        // 三选一刻意照抄原来的三元链而不是 switch：服务端若送来 >2 的 mode，
        // 原行为是落到黑名单分支，switch 版的 default 会悄悄改成「关闭」。
        String hintKey = mode == 0 ? "gui.mekck.ui.filter.hint_off"
                : mode == 1 ? "gui.mekck.ui.filter.hint_whitelist"
                : "gui.mekck.ui.filter.hint_blacklist";
        String hint = Component.translatable(hintKey).getString();
        guiGraphics.drawString(getFont(), hint, relativeX + LIST_X, relativeY + FILTER_SLOT_Y + 20, 0xFF606060, false);
        guiGraphics.drawString(getFont(), Component.translatable("gui.mekck.ui.modules.hint").getString(),
                relativeX + LIST_X, relativeY + FILTER_SLOT_Y + 31, 0xFF808080, false);
    }

    /** 已安装模块的能力描述（线程 × 并行）。 */
    private String installedStatus(KitchenFamily family) {
        for (KitchenModule.Ability a : menu.getMachine().installedAbilities()) {
            if (a.family() == family) {
                return a.displayName() + "  " + Component.translatable("gui.mekck.ui.module_power",
                        a.threads(), a.parallel()).getString();
            }
        }
        return Component.translatable("gui.mekck.ui.module_installed").getString();
    }

    private mekanism.client.gui.IGuiWrapper guiRenderer() {
        return gui();
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        int next = Math.max(0, Math.min(maxScroll(), scrollRow + (delta > 0 ? -1 : 1)));
        if (next != scrollRow) {
            scrollRow = next;
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    @Override
    public mekanism.client.gui.element.GuiElement mouseClickedNested(double mouseX, double mouseY, int button) {
        int x = relativeX + LIST_X;
        for (int i = 0; i < ROWS; i++) {
            int idx = scrollRow + i;
            if (idx >= families.size()) break;
            int y = relativeY + LIST_Y + i * ROW_H;
            if (mouseX >= x - 2 && mouseX <= x + 188 && mouseY >= y - 2 && mouseY < y + ROW_H - 1) {
                selectedRow = (selectedRow == idx) ? -1 : idx;
                return this;
            }
        }

        // ===== 过滤区交互 =====
        if (selectedRow >= 0 && selectedRow < families.size()) {
            KitchenFamily family = families.get(selectedRow);
            int bx = relativeX + LIST_X;
            int by = relativeY + FILTER_MODE_Y;
            // 模式按钮
            if (mouseX >= bx && mouseX < bx + 70 && mouseY >= by && mouseY < by + 12) {
                sendFilter((byte) 0, family, 0, net.minecraft.world.item.ItemStack.EMPTY);
                return this;
            }
            // 清空按钮
            if (mouseX >= bx + 74 && mouseX < bx + 108 && mouseY >= by && mouseY < by + 12) {
                sendFilter((byte) 3, family, 0, net.minecraft.world.item.ItemStack.EMPTY);
                return this;
            }
            // 自动加工开关：与服务端一样只对已装模块的系列响应，
            // 否则点了会被服务端忽略（本地却像生效了）。
            if ((menu.getFamilyMask() & (1 << family.ordinal())) != 0
                    && mouseX >= bx + 112 && mouseX < bx + 188 && mouseY >= by && mouseY < by + 12) {
                sendFilter((byte) 5, family, 0, net.minecraft.world.item.ItemStack.EMPTY);
                return this;
            }
            // 幽灵槽：手上拿物品 = 添加；空手点已有 = 移除
            var cached = cn.ism.mekck.network.KitchenFilterSyncPacket.CLIENT_CACHE.get(family.ordinal());
            for (int i = 0; i < cn.ism.mekck.kitchen.KitchenFilter.MAX_ITEMS; i++) {
                int sx = relativeX + FILTER_SLOT_X + i * FILTER_SLOT_STEP;
                int sy = relativeY + FILTER_SLOT_Y;
                if (mouseX < sx - 1 || mouseX >= sx + 15 || mouseY < sy - 1 || mouseY >= sy + 15) continue;
                var player = net.minecraft.client.Minecraft.getInstance().player;
                if (player == null) return this;
                net.minecraft.world.item.ItemStack carried = player.containerMenu.getCarried();
                if (!carried.isEmpty()) {
                    sendFilter((byte) 1, family, 0, carried.copy());
                } else if (cached != null && i < cached.items.size()) {
                    sendFilter((byte) 2, family, i, net.minecraft.world.item.ItemStack.EMPTY);
                }
                return this;
            }
        }
        return super.mouseClickedNested(mouseX, mouseY, button);
    }

    /** 发送一条过滤设置包。 */
    private void sendFilter(byte action, KitchenFamily family, int index,
                            net.minecraft.world.item.ItemStack stack) {
        cn.ism.mekck.network.ModMessages.sendToServer(
                new cn.ism.mekck.network.KitchenFilterPacket(menu.getMachine().getBlockPos(),
                        family.ordinal(), action, index, stack));
    }
}
