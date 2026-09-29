package cn.ism.mekck.integration.jei;

import cn.ism.mekck.recipe.MekCkGrillingRecipe;
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
 * JEI 配方分类（{@code mekck:grilling}）：电力烧烤架 / 烧烤工厂，1 输入 → 1 输出。
 *
 * <h3>为什么需要它</h3>
 * {@code mekck:grilling} 是本模组自有的配方类型（8 条数据包配方），
 * 但此前<b>没有任何 JEI 分类</b>：{@code JEIPlugin} 只给
 * {@code barbequesdelight:grilling}（外部模组的类型）注册了催化剂，
 * 于是这 8 条配方在 JEI 里完全查不到，而 {@code mekck:grilling} 的催化剂
 * 又指向一个凭空 {@code RecipeType.create} 出来的、连配方类都对不上的类型。
 * 布局照 {@link GrindingRecipeCategory} 模板。
 */
public class GrillingRecipeCategory implements IRecipeCategory<MekCkGrillingRecipe> {

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

    private final RecipeType<MekCkGrillingRecipe> recipeType;
    private final Component title;
    private final IDrawable background;
    private final IDrawable icon;

    public GrillingRecipeCategory(IGuiHelper helper, RecipeType<MekCkGrillingRecipe> recipeType,
                                  ItemStack iconStack) {
        this.recipeType = recipeType;
        this.title = Component.translatable("block.mekck.electric_grill");
        this.background = helper.createBlankDrawable(PANEL_W, PANEL_H);
        this.icon = helper.createDrawableIngredient(VanillaTypes.ITEM_STACK, iconStack);
    }

    @Override
    public RecipeType<MekCkGrillingRecipe> getRecipeType() {
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
    public void setRecipe(IRecipeLayoutBuilder builder, MekCkGrillingRecipe recipe, IFocusGroup focuses) {
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
    public void draw(MekCkGrillingRecipe recipe, IRecipeSlotsView recipeSlotView, GuiGraphics guiGraphics,
                     double mouseX, double mouseY) {
        // 输入/输出槽底框
        guiGraphics.fill(INPUT_X, INPUT_Y, INPUT_X + SLOT, INPUT_Y + SLOT, BG_FILL);
        guiGraphics.fill(OUTPUT_X, OUTPUT_Y, OUTPUT_X + SLOT, OUTPUT_Y + SLOT, BG_FILL);

        // 箭头
        guiGraphics.fill(ARROW_X, ARROW_Y, ARROW_X + 28, ARROW_Y + 12, 0xFF555555);
        guiGraphics.fill(ARROW_X, ARROW_Y + 14, ARROW_X + 28, ARROW_Y + 15, 0xFF555555);
    }
}
