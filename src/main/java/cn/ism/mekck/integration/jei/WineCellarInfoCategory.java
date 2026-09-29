package cn.ism.mekck.integration.jei;

import mekanism.client.SpecialColors;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.gui.builder.IRecipeLayoutBuilder;
import mezz.jei.api.gui.drawable.IDrawable;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.helpers.IGuiHelper;
import mezz.jei.api.recipe.IFocusGroup;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.recipe.category.IRecipeCategory;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/**
 * JEI 介绍页分类（§F20⑥）：陈化窖是纯容器、无配方，此页用「示例瓶：普通酒 → 更陈的酒」+ 若干行说明
 * 演示它「把瓶中酒的年份往过去推、加速陈化」的用途。未装 vinery 时示例槽自动隐藏、只留文字。
 */
public class WineCellarInfoCategory implements IRecipeCategory<WineCellarInfoRecipe> {

    private static final int PANEL_W = 150;
    private static final int PANEL_H = 76;

    private static final int SLOT = 18;
    private static final int INPUT_X = 12;
    private static final int INPUT_Y = 8;
    private static final int OUTPUT_X = 114;
    private static final int OUTPUT_Y = 8;
    private static final int ARROW_X = 46;
    private static final int ARROW_Y = 12;
    private static final int TEXT_X = 8;
    private static final int TEXT_Y = 34;

    private final RecipeType<WineCellarInfoRecipe> recipeType;
    private final Component title;
    private final IDrawable background;
    private final IDrawable icon;

    public WineCellarInfoCategory(IGuiHelper helper, RecipeType<WineCellarInfoRecipe> recipeType, ItemStack iconStack) {
        this.recipeType = recipeType;
        this.title = Component.translatable("block.mekck.wine_cellar");
        this.background = helper.createBlankDrawable(PANEL_W, PANEL_H);
        this.icon = helper.createDrawableIngredient(VanillaTypes.ITEM_STACK, iconStack);
    }

    @Override
    public RecipeType<WineCellarInfoRecipe> getRecipeType() {
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
    public void setRecipe(IRecipeLayoutBuilder builder, WineCellarInfoRecipe recipe, IFocusGroup focuses) {
        if (!recipe.hasSample()) {
            return;
        }
        builder.addSlot(RecipeIngredientRole.INPUT, INPUT_X + 1, INPUT_Y + 1).addItemStack(recipe.getInput());
        builder.addSlot(RecipeIngredientRole.OUTPUT, OUTPUT_X + 1, OUTPUT_Y + 1).addItemStack(recipe.getOutput());
    }

    @Override
    public void draw(WineCellarInfoRecipe recipe, IRecipeSlotsView recipeSlotView, GuiGraphics guiGraphics, double mouseX, double mouseY) {
        if (recipe.hasSample()) {
            guiGraphics.fill(INPUT_X, INPUT_Y, INPUT_X + SLOT, INPUT_Y + SLOT, 0xFF2B2B2B);
            guiGraphics.fill(OUTPUT_X, OUTPUT_Y, OUTPUT_X + SLOT, OUTPUT_Y + SLOT, 0xFF2B2B2B);
            guiGraphics.fill(ARROW_X, ARROW_Y, ARROW_X + 62, ARROW_Y + 3, 0xFF8C5A2B);
            guiGraphics.fill(ARROW_X + 56, ARROW_Y - 3, ARROW_X + 62, ARROW_Y + 9, 0xFF8C5A2B);
        }
        Font font = Minecraft.getInstance().font;
        if (font == null) {
            return;
        }
        int y = recipe.hasSample() ? TEXT_Y : 8;
        guiGraphics.drawString(font, Component.translatable("jei.mekck.wine_cellar.1"), TEXT_X, y,
                SpecialColors.TEXT_TITLE.argb(), false);
        guiGraphics.drawString(font, Component.translatable("jei.mekck.wine_cellar.2"), TEXT_X, y + 11, 0xFFB0B0B0, false);
        guiGraphics.drawString(font, Component.translatable("jei.mekck.wine_cellar.3"), TEXT_X, y + 22, 0xFFB0B0B0, false);
    }
}
