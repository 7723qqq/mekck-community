package cn.ism.mekck.integration.jei;

import cn.ism.mekck.recipe.NutRoastingRecipe;
import mekanism.client.SpecialColors;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

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
 * JEI 配方分类（mekck:nut_roasting），用于坚果爆炒机：1 输入（炒熟消耗）→ 1 输出。
 */
public class NutRoastingRecipeCategory implements IRecipeCategory<NutRoastingRecipe> {

    private static final int PANEL_W = 120;
    private static final int PANEL_H = 54;
    private static final int SLOT = 18;

    private static final int INPUT_X = 8;
    private static final int INPUT_Y = 16;
    private static final int ARROW_X = 36;
    private static final int ARROW_Y = 19;
    private static final int OUTPUT_X = 86;
    private static final int OUTPUT_Y = 16;

    private static final int BG_FILL = 0xFF2B2B2B;

    private final RecipeType<NutRoastingRecipe> recipeType;
    private final Component title;
    private final IDrawable icon;

    public NutRoastingRecipeCategory(IGuiHelper helper, RecipeType<NutRoastingRecipe> recipeType, ItemStack iconStack) {
        this.recipeType = recipeType;
        this.title = Component.translatable("block.mekck.nut_roaster");
        this.icon = helper.createDrawableIngredient(VanillaTypes.ITEM_STACK, iconStack);
    }

    @Override
    public RecipeType<NutRoastingRecipe> getRecipeType() {
        return recipeType;
    }

    @Override
    public Component getTitle() {
        return title;
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
    public void setRecipe(IRecipeLayoutBuilder builder, NutRoastingRecipe recipe, IFocusGroup focuses) {
        List<ItemStack> inputs = new ArrayList<>();
        for (ItemStack stack : recipe.getIngredient().getItems()) {
            inputs.add(stack.copy());
        }
        if (!inputs.isEmpty()) {
            builder.addSlot(RecipeIngredientRole.INPUT, INPUT_X + 1, INPUT_Y + 1)
                    .addItemStacks(inputs);
        }
        builder.addSlot(RecipeIngredientRole.OUTPUT, OUTPUT_X + 1, OUTPUT_Y + 1)
                .addItemStack(recipe.getResult().copy());
    }

    @Override
    public void draw(NutRoastingRecipe recipe, IRecipeSlotsView recipeSlotView, GuiGraphics guiGraphics, double mouseX, double mouseY) {
        // 输入/输出槽底框
        guiGraphics.fill(INPUT_X, INPUT_Y, INPUT_X + SLOT, INPUT_Y + SLOT, BG_FILL);
        guiGraphics.fill(OUTPUT_X, OUTPUT_Y, OUTPUT_X + SLOT, OUTPUT_Y + SLOT, BG_FILL);

        // 箭头
        guiGraphics.fill(ARROW_X, ARROW_Y, ARROW_X + 28, ARROW_Y + 12, 0xFF555555);
        guiGraphics.fill(ARROW_X, ARROW_Y + 14, ARROW_X + 28, ARROW_Y + 15, 0xFF555555);
    }
}
