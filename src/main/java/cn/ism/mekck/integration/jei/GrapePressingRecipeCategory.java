package cn.ism.mekck.integration.jei;

import cn.ism.mekck.recipe.GrapePressingRecipe;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;

import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.gui.builder.IRecipeLayoutBuilder;
import mezz.jei.api.gui.drawable.IDrawable;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.helpers.IGuiHelper;
import mezz.jei.api.recipe.IFocusGroup;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.recipe.category.IRecipeCategory;

import java.util.ArrayList;
import java.util.List;

/**
 * JEI 配方分类（mekck:grape_pressing），用于鲜果榨汁机的葡萄压榨：N 份葡萄 + 空葡萄酒瓶 → 瓶装葡萄汁。
 * 输入按最多 3×3 网格排列（每种成分一格、叠加其需求量），右侧为产物槽。
 */
public class GrapePressingRecipeCategory implements IRecipeCategory<GrapePressingRecipe> {

    private static final int SLOT = 18;
    private static final int GRID_X = 4;
    private static final int GRID_Y = 6;
    private static final int COLS = 3;
    private static final int ARROW_X = 66;
    private static final int ARROW_Y = 20;
    private static final int OUTPUT_X = 100;
    private static final int OUTPUT_Y = 16;

    private static final int PANEL_W = 124;
    private static final int PANEL_H = 58;
    private static final int BG_FILL = 0xFF2B2B2B;

    private final RecipeType<GrapePressingRecipe> recipeType;
    private final Component title;
    private final IDrawable background;
    private final IDrawable icon;

    public GrapePressingRecipeCategory(IGuiHelper helper, RecipeType<GrapePressingRecipe> recipeType, ItemStack iconStack) {
        this.recipeType = recipeType;
        this.title = Component.translatable("block.mekck.juicer");
        this.background = helper.createBlankDrawable(PANEL_W, PANEL_H);
        this.icon = helper.createDrawableIngredient(VanillaTypes.ITEM_STACK, iconStack);
    }

    @Override
    public RecipeType<GrapePressingRecipe> getRecipeType() {
        return recipeType;
    }

    @Override
    public Component getTitle() {
        return title;
    }

    @Override
    public IDrawable getBackground() {
        return background;
    }

    @Override
    public IDrawable getIcon() {
        return icon;
    }

    @Override
    public int getWidth() {
        return PANEL_W;
    }

    @Override
    public int getHeight() {
        return PANEL_H;
    }

    @Override
    public void setRecipe(IRecipeLayoutBuilder builder, GrapePressingRecipe recipe, IFocusGroup focuses) {
        List<Ingredient> ings = recipe.getItemIngredients();
        for (int i = 0; i < ings.size() && i < COLS * COLS; i++) {
            int col = i % COLS;
            int row = i / COLS;
            int x = GRID_X + col * SLOT;
            int y = GRID_Y + row * SLOT;
            int count = recipe.countAt(i);
            List<ItemStack> stacks = new ArrayList<>();
            for (ItemStack s : ings.get(i).getItems()) {
                ItemStack c = s.copy();
                c.setCount(count);
                stacks.add(c);
            }
            if (!stacks.isEmpty()) {
                builder.addSlot(RecipeIngredientRole.INPUT, x + 1, y + 1).addItemStacks(stacks);
            }
        }
        // 产物 ItemStack 需要 registryAccess：level 为空（标题界面等无世界上下文）时跳过该槽，
        // 不裸解引用 —— 写法对齐 JEIPlugin.registerRecipes 的 mc.level 判空。
        net.minecraft.world.level.Level level = Minecraft.getInstance().level;
        if (level != null) {
            builder.addSlot(RecipeIngredientRole.OUTPUT, OUTPUT_X + 1, OUTPUT_Y + 1)
                    .addItemStack(recipe.getResultItem(level.registryAccess()));
        }
    }

    @Override
    public void draw(GrapePressingRecipe recipe, IRecipeSlotsView view, GuiGraphics g, double mouseX, double mouseY) {
        for (int i = 0; i < recipe.getItemIngredients().size() && i < COLS * COLS; i++) {
            int col = i % COLS;
            int row = i / COLS;
            g.fill(GRID_X + col * SLOT, GRID_Y + row * SLOT, GRID_X + col * SLOT + SLOT, GRID_Y + row * SLOT + SLOT, BG_FILL);
        }
        g.fill(OUTPUT_X, OUTPUT_Y, OUTPUT_X + SLOT, OUTPUT_Y + SLOT, BG_FILL);
        g.fill(ARROW_X, ARROW_Y, ARROW_X + 26, ARROW_Y + 12, 0xFF555555);
        g.fill(ARROW_X, ARROW_Y + 14, ARROW_X + 26, ARROW_Y + 15, 0xFF555555);
    }
}
