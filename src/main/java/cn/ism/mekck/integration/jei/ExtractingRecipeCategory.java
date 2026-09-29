package cn.ism.mekck.integration.jei;

import cn.ism.mekck.recipe.ExtractingRecipe;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.material.Fluid;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.registries.ForgeRegistries;

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
import java.util.Collections;
import java.util.List;

/**
 * JEI 配方分类（mekck:extracting，§F19 C+E 半），用于智能萃取机：
 * 1..5 物品输入（3×2 网格）+ 可选流体输入（精确 id 或 {@code #tag}，tag 展开为候选）
 * → 流体产物（outputTank）或物品产物（产物槽），二选一。
 */
public class ExtractingRecipeCategory implements IRecipeCategory<ExtractingRecipe> {

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

    private final RecipeType<ExtractingRecipe> recipeType;
    private final Component title;
    private final IDrawable background;
    private final IDrawable icon;

    public ExtractingRecipeCategory(IGuiHelper helper, RecipeType<ExtractingRecipe> recipeType, ItemStack iconStack) {
        this.recipeType = recipeType;
        this.title = Component.translatable("block.mekck.smart_extractor");
        this.background = helper.createBlankDrawable(PANEL_W, PANEL_H);
        this.icon = helper.createDrawableIngredient(VanillaTypes.ITEM_STACK, iconStack);
    }

    @Override
    public RecipeType<ExtractingRecipe> getRecipeType() {
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
    public void setRecipe(IRecipeLayoutBuilder builder, ExtractingRecipe recipe, IFocusGroup focuses) {
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
        ExtractingRecipe.FluidInput in = recipe.getInputFluid();
        if (in != null) {
            var slot = builder.addSlot(RecipeIngredientRole.INPUT, FLUID_X + 1, FLUID_Y + 1);
            if (in.idOrTag.startsWith("#")) {
                // 流体 tag：展开为候选（封顶 10 个，避免超宽 tag 拖垮页面）
                int shown = 0;
                for (Fluid f : fluidsOfTag(in.idOrTag.substring(1))) {
                    if (shown++ >= 10) break;
                    slot.addFluidStack(f, in.amount);
                }
            } else {
                Fluid f = ForgeRegistries.FLUIDS.getValue(new ResourceLocation(in.idOrTag));
                if (f != null) slot.addFluidStack(f, in.amount);
            }
        }
        if (!recipe.getFluidResult().isEmpty()) {
            FluidStack out = recipe.getFluidResult();
            builder.addSlot(RecipeIngredientRole.OUTPUT, OUTPUT_X + 1, OUTPUT_Y + 1)
                    .addFluidStack(out.getFluid(), out.getAmount());
        } else {
            builder.addSlot(RecipeIngredientRole.OUTPUT, OUTPUT_X + 1, OUTPUT_Y + 1)
                    .addItemStack(recipe.getResultItem(Minecraft.getInstance().level.registryAccess()));
        }
    }

    /** 把 {@code #} 后的流体 tag 名展开为成员流体列表（Forge 注册表 tags()，拿不到时空表）。 */
    private static List<Fluid> fluidsOfTag(String tagName) {
        var manager = ForgeRegistries.FLUIDS.tags();
        if (manager == null) return Collections.emptyList();
        var tags = manager.getTag(net.minecraft.tags.TagKey.create(
                net.minecraft.core.registries.Registries.FLUID, new ResourceLocation(tagName)));
        if (tags == null) return Collections.emptyList();
        List<Fluid> list = new ArrayList<>();
        for (Fluid f : tags) list.add(f);
        return list;
    }

    @Override
    public void draw(ExtractingRecipe recipe, IRecipeSlotsView view, GuiGraphics g, double mouseX, double mouseY) {
        int n = Math.min(recipe.getItemIngredients().size(), COLS * 2);
        for (int i = 0; i < n; i++) {
            int col = i % COLS;
            int row = i / COLS;
            g.fill(GRID_X + col * SLOT, GRID_Y + row * SLOT, GRID_X + col * SLOT + SLOT, GRID_Y + row * SLOT + SLOT, BG_FILL);
        }
        if (recipe.getInputFluid() != null) {
            g.fill(FLUID_X, FLUID_Y, FLUID_X + SLOT, FLUID_Y + SLOT, BG_FILL);
        }
        g.fill(OUTPUT_X, OUTPUT_Y, OUTPUT_X + SLOT, OUTPUT_Y + SLOT, BG_FILL);
        g.fill(ARROW_X, ARROW_Y, ARROW_X + 26, ARROW_Y + 12, 0xFF555555);
        g.fill(ARROW_X, ARROW_Y + 14, ARROW_X + 26, ARROW_Y + 15, 0xFF555555);
    }
}
