package cn.ism.mekck.integration.jei;

import cn.ism.mekck.UniversalCuttingMachine;
import mekanism.client.SpecialColors;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
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
import cn.ism.mekck.registry.MekCkFluids;

/**
 * JEI 配方分类：展示生物反应堆将各类物品转化为有机物流体（每单位 mb）。
 * <pre>
 *   [输入物品]  →  [有机物流体]
 * </pre>
 */
public class BioreactorRecipeCategory implements IRecipeCategory<BioreactorJeiRecipe> {

    private static final int PANEL_W = 120;
    private static final int PANEL_H = 64;

    private static final int SLOT = 18;

    private static final int INPUT_X = 8;
    private static final int INPUT_Y = 16;

    private static final int OUTPUT_X = 86;
    private static final int OUTPUT_Y = 16;

    private static final int ARROW_X = 36;
    private static final int ARROW_Y = 19;

    private static final int MB_TEXT_X = 70;
    private static final int MB_TEXT_Y = 42;

    private final RecipeType<BioreactorJeiRecipe> recipeType;
    private final Component title;
    private final IDrawable icon;

    public BioreactorRecipeCategory(IGuiHelper helper, RecipeType<BioreactorJeiRecipe> recipeType, ItemStack iconStack) {
        this.recipeType = recipeType;
        this.title = Component.translatable("block.mekck.bioreactor");
        this.icon = helper.createDrawableIngredient(VanillaTypes.ITEM_STACK, iconStack);
    }

    @Override
    public RecipeType<BioreactorJeiRecipe> getRecipeType() {
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
    public void setRecipe(IRecipeLayoutBuilder builder, BioreactorJeiRecipe recipe, IFocusGroup focuses) {
        builder.addSlot(RecipeIngredientRole.INPUT, INPUT_X + 1, INPUT_Y + 1)
                .addItemStack(recipe.getInput());

        FluidStack fluid = new FluidStack(MekCkFluids.ORGANIC_MATTER_SOURCE.get(), recipe.getMbPerUnit());
        builder.addSlot(RecipeIngredientRole.OUTPUT, OUTPUT_X + 1, OUTPUT_Y + 1)
                .addFluidStack(fluid.getFluid(), fluid.getAmount());
    }

    @Override
    public void draw(BioreactorJeiRecipe recipe, IRecipeSlotsView recipeSlotView, GuiGraphics guiGraphics, double mouseX, double mouseY) {
        // 输入/输出槽底框
        guiGraphics.fill(INPUT_X, INPUT_Y, INPUT_X + SLOT, INPUT_Y + SLOT, 0xFF2B2B2B);
        guiGraphics.fill(OUTPUT_X, OUTPUT_Y, OUTPUT_X + SLOT, OUTPUT_Y + SLOT, 0xFF2B2B2B);

        // 箭头
        guiGraphics.fill(ARROW_X, ARROW_Y, ARROW_X + 28, ARROW_Y + 12, 0xFF555555);
        guiGraphics.fill(ARROW_X, ARROW_Y + 14, ARROW_X + 28, ARROW_Y + 15, 0xFF555555);

        // 每单位 mb 文本；「可接受不消耗」食物（eternal_foods 配置）附加绿色标注
        Font font = Minecraft.getInstance().font;
        if (font != null) {
            Component mb = Component.literal(recipe.getMbPerUnit() + " mb / 个");
            guiGraphics.drawString(font, mb, MB_TEXT_X - 20, MB_TEXT_Y, SpecialColors.TEXT_TITLE.argb(), false);
            if (cn.ism.mekck.config.MekckConfig.isBioreactorEternalFood(recipe.getInput().getItem())) {
                Component note = Component.literal("物品不消耗");
                guiGraphics.drawString(font, note, MB_TEXT_X - 20, MB_TEXT_Y + 10, 0xFF8BC34A, false);
            }
        }
    }
}
