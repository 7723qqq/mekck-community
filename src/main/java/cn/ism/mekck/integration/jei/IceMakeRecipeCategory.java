package cn.ism.mekck.integration.jei;

import cn.ism.mekck.recipe.IceMakeRecipe;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
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
 * JEI 配方分类（mekck:ice_make），用于急冻制冰机与制冰工厂。
 * 输入物品作为催化剂（不消耗），每次配方消耗一定水量，产出单一结果（制冰机无副产物，输出为单格）。
 */
public class IceMakeRecipeCategory implements IRecipeCategory<IceMakeRecipe> {

    private static final ResourceLocation SLOT_INPUT = new ResourceLocation("mekck", "textures/gui/slot/input.png");
    // 制冰机只有单一产物（无副产物），输出槽使用 18×18 单格贴图（与输入槽同款通用槽）
    private static final ResourceLocation SLOT_OUTPUT = new ResourceLocation("mekck", "textures/gui/slot/input.png");
    private static final ResourceLocation PROGRESS_BAR = new ResourceLocation("mekck", "textures/gui/progress/bar.png");

    private static final int PROGRESS_W = 25;
    private static final int PROGRESS_H = 9;
    private static final int PROGRESS_TEX_H = 27;

    private static final int PANEL_W = 160;
    private static final int PANEL_H = 64;
    private static final int SLOT = 18;

    private static final int INPUT_X = 8;
    private static final int INPUT_Y = 8;
    private static final int WATER_X = 28;
    private static final int WATER_Y = 14;
    private static final int WATER_W = 8;
    private static final int WATER_H = 14;
    private static final int PROGRESS_X = 42;
    private static final int PROGRESS_Y = 20;
    private static final int OUTPUT_X = 86;
    private static final int OUTPUT_Y = 10;
    private static final int ENERGY_X = 152;
    private static final int ENERGY_Y = 4;
    private static final int ENERGY_W = 6;
    private static final int ENERGY_H = 54;

    private static final int BG_FILL = 0xFF2B2B2B;
    private static final int BAR_FILL = 0xFF4DE84D;
    private static final int WATER_FILL = 0xFF3399FF;

    private final RecipeType<IceMakeRecipe> recipeType;
    private final Component title;
    private final IDrawable background;
    private final IDrawable icon;

    public IceMakeRecipeCategory(IGuiHelper helper, RecipeType<IceMakeRecipe> recipeType, ItemStack iconStack) {
        this.recipeType = recipeType;
        this.title = Component.translatable("block.mekck.ice_maker");
        this.background = helper.createBlankDrawable(PANEL_W, PANEL_H);
        this.icon = helper.createDrawableIngredient(VanillaTypes.ITEM_STACK, iconStack);
    }

    @Override
    public RecipeType<IceMakeRecipe> getRecipeType() {
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
    public void setRecipe(IRecipeLayoutBuilder builder, IceMakeRecipe recipe, IFocusGroup focuses) {
        List<ItemStack> inputs = new ArrayList<>();
        for (var item : recipe.getIngredient().getItems()) inputs.add(item.copy());
        if (!inputs.isEmpty()) {
            builder.addSlot(RecipeIngredientRole.CATALYST, INPUT_X + 1, INPUT_Y + 1)
                    .addItemStacks(inputs)
                    .setSlotName("input");
        }
        List<ItemStack> results = new ArrayList<>(recipe.getResults());
        if (!results.isEmpty()) {
            builder.addSlot(RecipeIngredientRole.OUTPUT, OUTPUT_X + 1, OUTPUT_Y + 1)
                    .addItemStacks(results)
                    .setSlotName("output");
        }
    }

    @Override
    public void draw(IceMakeRecipe recipe, IRecipeSlotsView recipeSlotView, GuiGraphics guiGraphics, double mouseX, double mouseY) {
        drawTexture(guiGraphics, SLOT_INPUT, INPUT_X, INPUT_Y, SLOT, SLOT);
        drawTexture(guiGraphics, SLOT_OUTPUT, OUTPUT_X, OUTPUT_Y, SLOT, SLOT);

        // 水条（装饰，满格显示）
        drawBar(guiGraphics, WATER_X, WATER_Y, WATER_W, WATER_H, WATER_FILL);

        // 进度条
        guiGraphics.blit(PROGRESS_BAR, PROGRESS_X, PROGRESS_Y, 0, 0, PROGRESS_W, PROGRESS_H, PROGRESS_W, PROGRESS_TEX_H);
        guiGraphics.blit(PROGRESS_BAR, PROGRESS_X + 1, PROGRESS_Y, 1, PROGRESS_H, PROGRESS_W - 2, PROGRESS_H, PROGRESS_W, PROGRESS_TEX_H);

        // 能量条
        drawBar(guiGraphics, ENERGY_X, ENERGY_Y, ENERGY_W, ENERGY_H, BAR_FILL);
    }

    private static void drawTexture(GuiGraphics guiGraphics, ResourceLocation texture, int x, int y, int w, int h) {
        guiGraphics.blit(texture, x, y, 0, 0, w, h, w, h);
    }

    private static void drawBar(GuiGraphics guiGraphics, int x, int y, int w, int h, int fillColor) {
        guiGraphics.fill(x, y, x + w, y + h, BG_FILL);
        guiGraphics.fill(x + 1, y + 1, x + w - 1, y + h - 1, fillColor);
    }

}
