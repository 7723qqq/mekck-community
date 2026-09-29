package cn.ism.mekck.integration.jei;

import cn.ism.mekck.recipe.FerreroRecipe;
import mekanism.client.SpecialColors;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;

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
 * JEI 配方分类（mekck:ferrero），用于巧克力大炮：
 * 物品输入 + extra 物品输入 + 2 种流体 → 费列罗巧克力。
 * <pre>
 *   [输入]  [extra]  →  [产物]
 *    流体1 / 流体2 以文本标注
 * </pre>
 */
public class FerreroRecipeCategory implements IRecipeCategory<FerreroRecipe> {

    private static final int PANEL_W = 140;
    private static final int PANEL_H = 70;
    private static final int SLOT = 18;

    private static final int INPUT_X = 8;
    private static final int INPUT_Y = 14;
    private static final int EXTRA_X = 8;
    private static final int EXTRA_Y = 40;
    private static final int ARROW_X = 36;
    private static final int ARROW_Y = 26;
    private static final int OUTPUT_X = 78;
    private static final int OUTPUT_Y = 24;
    private static final int FLUID_TEXT_X = 34;
    private static final int FLUID_TEXT_Y = 56;

    private static final int BG_FILL = 0xFF2B2B2B;

    private final RecipeType<FerreroRecipe> recipeType;
    private final Component title;
    private final IDrawable background;
    private final IDrawable icon;

    public FerreroRecipeCategory(IGuiHelper helper, RecipeType<FerreroRecipe> recipeType, ItemStack iconStack) {
        this.recipeType = recipeType;
        this.title = Component.translatable("block.mekck.chocolate_cannon");
        this.background = helper.createBlankDrawable(PANEL_W, PANEL_H);
        this.icon = helper.createDrawableIngredient(VanillaTypes.ITEM_STACK, iconStack);
    }

    @Override
    public RecipeType<FerreroRecipe> getRecipeType() {
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
    public void setRecipe(IRecipeLayoutBuilder builder, FerreroRecipe recipe, IFocusGroup focuses) {
        builder.addSlot(RecipeIngredientRole.INPUT, INPUT_X + 1, INPUT_Y + 1)
                .addItemStacks(listOf(recipe.getInput()));
        builder.addSlot(RecipeIngredientRole.INPUT, EXTRA_X + 1, EXTRA_Y + 1)
                .addItemStacks(listOf(recipe.getExtra()));
        builder.addSlot(RecipeIngredientRole.OUTPUT, OUTPUT_X + 1, OUTPUT_Y + 1)
                .addItemStack(recipe.getResultItem(Minecraft.getInstance().level.registryAccess()));
    }

    private static List<ItemStack> listOf(net.minecraft.world.item.crafting.Ingredient ingredient) {
        List<ItemStack> list = new ArrayList<>();
        for (ItemStack stack : ingredient.getItems()) {
            list.add(stack.copy());
        }
        return list;
    }

    @Override
    public void draw(FerreroRecipe recipe, IRecipeSlotsView recipeSlotView, GuiGraphics guiGraphics, double mouseX, double mouseY) {
        // 输入/extra/产物槽底框
        guiGraphics.fill(INPUT_X, INPUT_Y, INPUT_X + SLOT, INPUT_Y + SLOT, BG_FILL);
        guiGraphics.fill(EXTRA_X, EXTRA_Y, EXTRA_X + SLOT, EXTRA_Y + SLOT, BG_FILL);
        guiGraphics.fill(OUTPUT_X, OUTPUT_Y, OUTPUT_X + SLOT, OUTPUT_Y + SLOT, BG_FILL);

        // 箭头
        guiGraphics.fill(ARROW_X, ARROW_Y, ARROW_X + 28, ARROW_Y + 12, 0xFF555555);
        guiGraphics.fill(ARROW_X, ARROW_Y + 14, ARROW_X + 28, ARROW_Y + 15, 0xFF555555);

        // 流体输入文本（两种流体的类型与用量）
        Font font = Minecraft.getInstance().font;
        if (font != null) {
            FluidStack f1 = recipe.getFluid1();
            FluidStack f2 = recipe.getFluid2();
            guiGraphics.drawString(font,
                    Component.literal(f1.getAmount() + "mb " + fluidName(f1) + " + " + f2.getAmount() + "mb " + fluidName(f2)),
                    FLUID_TEXT_X, FLUID_TEXT_Y, SpecialColors.TEXT_TITLE.argb(), false);
        }
    }

    private static String fluidName(FluidStack stack) {
        return stack.isEmpty() ? "?" : stack.getDisplayName().getString();
    }
}
