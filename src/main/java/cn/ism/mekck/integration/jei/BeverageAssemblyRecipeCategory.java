package cn.ism.mekck.integration.jei;

import cn.ism.mekck.recipe.BeverageAssemblyRecipe;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
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
 * JEI 配方分类（mekck:beverage_assembly），用于饮品调配机：
 * 杯 + 可选小料（物品成分，最多 3×3 网格）+ 饮品流体 → 杯装饮品。
 */
public class BeverageAssemblyRecipeCategory implements IRecipeCategory<BeverageAssemblyRecipe> {

    private static final int SLOT = 18;
    private static final int GRID_X = 4;
    private static final int GRID_Y = 4;
    private static final int COLS = 3;
    private static final int FLUID_X = 4;
    private static final int FLUID_Y = 40;
    private static final int ARROW_X = 66;
    private static final int ARROW_Y = 24;
    private static final int OUTPUT_X = 100;
    private static final int OUTPUT_Y = 18;

    private static final int PANEL_W = 124;
    private static final int PANEL_H = 62;
    private static final int BG_FILL = 0xFF2B2B2B;

    private final RecipeType<BeverageAssemblyRecipe> recipeType;
    private final Component title;
    private final IDrawable icon;

    public BeverageAssemblyRecipeCategory(IGuiHelper helper, RecipeType<BeverageAssemblyRecipe> recipeType, ItemStack iconStack) {
        this.recipeType = recipeType;
        this.title = Component.translatable("block.mekck.beverage_blender");
        this.icon = helper.createDrawableIngredient(VanillaTypes.ITEM_STACK, iconStack);
    }

    @Override
    public RecipeType<BeverageAssemblyRecipe> getRecipeType() {
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
    public void setRecipe(IRecipeLayoutBuilder builder, BeverageAssemblyRecipe recipe, IFocusGroup focuses) {
        List<Ingredient> ings = recipe.getItemIngredients();
        for (int i = 0; i < ings.size() && i < COLS * 2; i++) {
            int col = i % COLS;
            int row = i / COLS;
            List<ItemStack> stacks = new ArrayList<>();
            for (ItemStack s : ings.get(i).getItems()) stacks.add(s.copy());
            if (!stacks.isEmpty()) {
                builder.addSlot(RecipeIngredientRole.INPUT, GRID_X + col * SLOT + 1, GRID_Y + row * SLOT + 1)
                        .addItemStacks(stacks);
            }
        }
        FluidStack fluid = recipe.getFluid();
        if (fluid != null && !fluid.isEmpty()) {
            builder.addSlot(RecipeIngredientRole.INPUT, FLUID_X + 1, FLUID_Y + 1)
                    .addFluidStack(fluid.getFluid(), fluid.getAmount());
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
    public void draw(BeverageAssemblyRecipe recipe, IRecipeSlotsView view, GuiGraphics g, double mouseX, double mouseY) {
        int n = Math.min(recipe.getItemIngredients().size(), COLS * 2);
        for (int i = 0; i < n; i++) {
            int col = i % COLS;
            int row = i / COLS;
            g.fill(GRID_X + col * SLOT, GRID_Y + row * SLOT, GRID_X + col * SLOT + SLOT, GRID_Y + row * SLOT + SLOT, BG_FILL);
        }
        g.fill(FLUID_X, FLUID_Y, FLUID_X + SLOT, FLUID_Y + SLOT, BG_FILL);
        g.fill(OUTPUT_X, OUTPUT_Y, OUTPUT_X + SLOT, OUTPUT_Y + SLOT, BG_FILL);
        g.fill(ARROW_X, ARROW_Y, ARROW_X + 26, ARROW_Y + 12, 0xFF555555);
        g.fill(ARROW_X, ARROW_Y + 14, ARROW_X + 26, ARROW_Y + 15, 0xFF555555);
    }
}
