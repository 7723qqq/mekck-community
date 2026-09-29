package cn.ism.mekck.integration.jei;

import cn.ism.mekck.recipe.MekCkSkeweringRecipe;
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
 * JEI 配方分类（{@code mekck:skewering}）：智能穿串机 / 穿串工厂，
 * 签子（载体，不消耗）+ 主料 + 辅料 → 串烧物。
 *
 * <h3>为什么需要它</h3>
 * {@code mekck:skewering} 是本模组自有的配方类型（8 条数据包配方），
 * 但此前<b>没有任何 JEI 分类</b>：{@code JEIPlugin} 只给
 * {@code barbequesdelight:skewering}（外部模组的类型）注册了催化剂，
 * 于是这 8 条配方在 JEI 里完全查不到，而 {@code mekck:skewering} 的催化剂
 * 又指向一个凭空 {@code RecipeType.create} 出来的、连配方类都对不上的类型。
 *
 * <h3>三个输入槽的语义</h3>
 * <ul>
 *   <li><b>签子</b>（{@code tool}）—— 载体，机器不消耗它，所以 JEI 里照常显示为输入；</li>
 *   <li><b>主料</b>（{@code ingredient}）—— 被串上去的食材；</li>
 *   <li><b>辅料</b>（{@code side}）—— 数量由 {@code sideCount} 决定，显示时按该数量出栈。</li>
 * </ul>
 * 布局照 {@link GrindingRecipeCategory} 模板，只是输入从 1 个扩到 3 个。
 */
public class SkeweringRecipeCategory implements IRecipeCategory<MekCkSkeweringRecipe> {

    private static final int PANEL_W = 140;
    private static final int PANEL_H = 54;
    private static final int SLOT = 18;

    private static final int TOOL_X = 8;
    private static final int INGREDIENT_X = 30;
    private static final int SIDE_X = 52;
    private static final int SLOT_Y = 16;
    private static final int ARROW_X = 76;
    private static final int ARROW_Y = 19;
    private static final int OUTPUT_X = 110;
    private static final int OUTPUT_Y = 16;

    private static final int BG_FILL = 0xFF2B2B2B;

    private final RecipeType<MekCkSkeweringRecipe> recipeType;
    private final Component title;
    private final IDrawable background;
    private final IDrawable icon;

    public SkeweringRecipeCategory(IGuiHelper helper, RecipeType<MekCkSkeweringRecipe> recipeType,
                                   ItemStack iconStack) {
        this.recipeType = recipeType;
        this.title = Component.translatable("block.mekck.smart_skewering_machine");
        this.background = helper.createBlankDrawable(PANEL_W, PANEL_H);
        this.icon = helper.createDrawableIngredient(VanillaTypes.ITEM_STACK, iconStack);
    }

    @Override
    public RecipeType<MekCkSkeweringRecipe> getRecipeType() {
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
    public void setRecipe(IRecipeLayoutBuilder builder, MekCkSkeweringRecipe recipe, IFocusGroup focuses) {
        addIngredient(builder, recipe.getTool(), TOOL_X);
        addIngredient(builder, recipe.getIngredient(), INGREDIENT_X);
        // 辅料按 sideCount 出栈：配方里写的是「1 个 tag 里的任意物品 × N」，
        // 显示成 1 个会让玩家以为只要放 1 个。
        addIngredient(builder, recipe.getSide(), SIDE_X, Math.max(1, recipe.getSideCount()));

        builder.addSlot(RecipeIngredientRole.OUTPUT, OUTPUT_X + 1, OUTPUT_Y + 1)
                .addItemStack(recipe.getResult().copy());
    }

    /** 把一个 {@code Ingredient} 铺进指定 x 的输入槽；空配料不建槽（避免出现空框）。 */
    private static void addIngredient(IRecipeLayoutBuilder builder, net.minecraft.world.item.crafting.Ingredient
            ingredient, int x) {
        addIngredient(builder, ingredient, x, 1);
    }

    private static void addIngredient(IRecipeLayoutBuilder builder, net.minecraft.world.item.crafting.Ingredient
            ingredient, int x, int count) {
        if (ingredient == null || ingredient.isEmpty()) {
            return;
        }
        List<ItemStack> stacks = new ArrayList<>();
        for (ItemStack stack : ingredient.getItems()) {
            ItemStack copy = stack.copy();
            copy.setCount(count);
            stacks.add(copy);
        }
        builder.addSlot(RecipeIngredientRole.INPUT, x + 1, SLOT_Y + 1).addItemStacks(stacks);
    }

    @Override
    public void draw(MekCkSkeweringRecipe recipe, IRecipeSlotsView recipeSlotView, GuiGraphics guiGraphics,
                     double mouseX, double mouseY) {
        // 输入/输出槽底框
        guiGraphics.fill(TOOL_X, SLOT_Y, TOOL_X + SLOT, SLOT_Y + SLOT, BG_FILL);
        guiGraphics.fill(INGREDIENT_X, SLOT_Y, INGREDIENT_X + SLOT, SLOT_Y + SLOT, BG_FILL);
        guiGraphics.fill(SIDE_X, SLOT_Y, SIDE_X + SLOT, SLOT_Y + SLOT, BG_FILL);
        guiGraphics.fill(OUTPUT_X, OUTPUT_Y, OUTPUT_X + SLOT, OUTPUT_Y + SLOT, BG_FILL);

        // 箭头
        guiGraphics.fill(ARROW_X, ARROW_Y, ARROW_X + 28, ARROW_Y + 12, 0xFF555555);
        guiGraphics.fill(ARROW_X, ARROW_Y + 14, ARROW_X + 28, ARROW_Y + 15, 0xFF555555);
    }
}
