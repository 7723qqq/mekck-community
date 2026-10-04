package cn.ism.mekck.client;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;

/**
 * 下单面板的「终端式」搜索框（AE 终端合成界面顶部就有搜索）。
 *
 * <p>面板下单原先只能滚动翻阅配方列表；这里提供一个可复用的搜索框：
 * 输入关键字后按**产物名称 / 注册名**过滤配方列表，Esc 清空，未输入时显示占位提示。</p>
 *
 * <p>用法（三步）：</p>
 * <pre>
 *   private final OrderSearchBox search = new OrderSearchBox();
 *   // init():            search.init(font, x, y, width)
 *   // 列表赋值后:        recipes = search.filter(recipes)
 *   // keyPressed/charTyped/mouseClicked/render: 转发给 search（见各方法注释）
 * </pre>
 *
 * <p><b>坐标口径</b>：{@code EditBox} 的 x/y 与 {@code mouseClicked}/{@code render} 收到的
 * mouseX/mouseY 必须处在<b>同一个坐标系</b>里（{@code EditBox} 内部就是拿它们直接比 x/y）。
 * {@link NetworkOrderPanel} 传的是 GUI 相对坐标（面板绘制所在的空间），所以调用方必须先把
 * 绝对鼠标换算成 GUI 相对再转发 —— 否则搜索框「画对了、点不到」。</p>
 */
public final class OrderSearchBox {

    private EditBox box;
    private String query = "";

    /** 创建并定位搜索框（在 {@code Screen#init()} 里调用）。 */
    public void init(Font font, int x, int y, int width) {
        EditBox created = new EditBox(font, x, y, width, 14, Component.translatable("gui.mekck.ui.search"));
        created.setMaxLength(48);
        created.setBordered(true);
        created.setHint(Component.translatable("gui.mekck.ui.search.hint"));
        created.setValue(query);
        this.box = created;
    }

    /** 换位置（面板切换模式/尺寸变化时调用）。 */
    public void move(int x, int y, int width) {
        if (box != null) {
            box.setX(x);
            box.setY(y);
            box.setWidth(width);
        }
    }

    public boolean isFocused() {
        return box != null && box.isFocused();
    }

    public String query() {
        return query;
    }

    public void clear() {
        query = "";
        if (box != null) {
            box.setValue("");
            box.setFocused(false);
        }
    }

    /** 渲染搜索框（在屏幕 render 里调用；位置变了就先 move）。 */
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        if (box != null) {
            box.render(guiGraphics, mouseX, mouseY, partialTick);
        }
    }

    /** 点击转发：返回 true 表示搜索框吃掉了这次点击（调用方应直接 return true）。 */
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (box == null) return false;
        boolean wasFocused = box.isFocused();
        box.mouseClicked(mouseX, mouseY, button);
        if (box.isFocused()) {
            // 输入框内点击：不把事件继续传给列表
            return true;
        }
        // 点击别处：失焦并清掉选中态
        if (wasFocused) {
            box.setFocused(false);
        }
        return false;
    }

    /** 键盘转发：返回 true 表示已处理（调用方应直接 return true）。 */
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (box == null || !box.isFocused()) return false;
        if (keyCode == 256) { // Esc：清空并失焦
            query = "";
            box.setValue("");
            box.setFocused(false);
            return true;
        }
        if (box.keyPressed(keyCode, scanCode, modifiers)) {
            query = box.getValue();
            return true;
        }
        return false;
    }

    /** 字符输入转发：返回 true 表示已处理。 */
    public boolean charTyped(char codePoint, int modifiers) {
        if (box == null || !box.isFocused()) return false;
        if (box.charTyped(codePoint, modifiers)) {
            query = box.getValue();
            return true;
        }
        return false;
    }

    /** 按当前关键字过滤配方（空关键字原样返回）。 */
    public List<Recipe<?>> filter(List<Recipe<?>> recipes) {
        if (recipes == null || recipes.isEmpty()) return recipes;
        String q = query == null ? "" : query.trim().toLowerCase(java.util.Locale.ROOT);
        if (q.isEmpty()) return recipes;
        List<Recipe<?>> out = new ArrayList<>();
        for (Recipe<?> r : recipes) {
            if (matches(r, q)) out.add(r);
        }
        return out;
    }

    /** 关键字匹配：产物显示名（含翻译）或注册名任意包含即可。 */
    private boolean matches(Recipe<?> recipe, String q) {
        try {
            var level = net.minecraft.client.Minecraft.getInstance().level;
            if (level == null) return recipe.getId() != null && recipe.getId().toString().contains(q);
            ItemStack result = recipe.getResultItem(level.registryAccess());
            if (!result.isEmpty()) {
                String name = result.getHoverName().getString().toLowerCase(java.util.Locale.ROOT);
                if (name.contains(q)) return true;
                ResourceLocation id = net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(result.getItem());
                if (id != null && id.toString().contains(q)) return true;
            }
        } catch (Throwable ignored) {
        }
        ResourceLocation rid = recipe.getId();
        return rid != null && rid.toString().contains(q);
    }
}
