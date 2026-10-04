package cn.ism.mekck.integration.jei;

import cn.ism.mekck.recipe.PlantingCuttingRecipe;
import mekanism.client.SpecialColors;
import mekanism.common.util.text.TextUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
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
 * JEI recipe category for mekck:plantcut recipes, displayed for the Planting & Cutting Station and Factory.
 * <p>
 * All textures are bundled with MekCK itself ({@code assets/mekck/textures/gui/...}) so the category has
 * no runtime dependency on Mekanism's internal GUI textures (the old combined {@code mekanism:gui/gui.png}
 * was split into separate files in Mekanism 10.4+, which previously produced pink/missing-texture blocks).
 * <p>
 * Layout (18px grid, everything inside the 160x64 panel):
 * <pre>
 *   [power] [input] [gas bar] [progress]   [output wide: main|second]  [energy]
 *           [extra]
 * </pre>
 */
public class PlantingCuttingRecipeCategory implements IRecipeCategory<PlantingCuttingRecipe> {

    // ── Bundled textures (mekck) ──────────────────────────────────────────────
    private static final ResourceLocation SLOT_INPUT = new ResourceLocation("mekck", "textures/gui/slot/input.png");
    private static final ResourceLocation SLOT_EXTRA = new ResourceLocation("mekck", "textures/gui/slot/extra.png");
    private static final ResourceLocation SLOT_POWER = new ResourceLocation("mekck", "textures/gui/slot/power.png");
    private static final ResourceLocation SLOT_POWER_OVERLAY = new ResourceLocation("mekck", "textures/gui/slot/overlay_power.png");
    private static final ResourceLocation SLOT_OUTPUT_WIDE = new ResourceLocation("mekck", "textures/gui/slot/output_wide.png");
    private static final ResourceLocation PROGRESS_BAR = new ResourceLocation("mekck", "textures/gui/progress/bar.png");

    private static final int PROGRESS_W = 25;
    private static final int PROGRESS_H = 9;
    private static final int PROGRESS_TEX_H = 27; // base(0..9) / overlay(9..18) / warning(18..27)

    // ── Panel ─────────────────────────────────────────────────────────────────
    // 布局对齐 mekmm 种植站（通用机械 ItemStackGasToItemStackRecipeCategory）：
    // 输入/营养液槽在左中，电力槽与进度条居中，产物在右，能量条为右侧竖直条。
    private static final int PANEL_W = 176;
    private static final int PANEL_H = 74;

    private static final int SLOT = 18;

    // Seed input slot (catalyst, not consumed)
    private static final int INPUT_X = 64;
    private static final int INPUT_Y = 17;

    // Nutrient provider slot (catalyst)
    private static final int EXTRA_X = 64;
    private static final int EXTRA_Y = 53;

    // 生长方块格（catalyst，**只有神秘农业种子才有**）：放在种子槽右侧，
    // 位置不能压在进度条(68,36~93,45)上 —— 所以走 (88,17) 这一格。
    private static final int GROWTH_X = 88;
    private static final int GROWTH_Y = 17;

    // Power decoration slot
    private static final int POWER_X = 39;
    private static final int POWER_Y = 35;

    // 产物槽：主产物 + 副产物（与 mekmm 种植分类一致，均为普通槽）
    private static final int MAIN_OUTPUT_X = 116;
    private static final int MAIN_OUTPUT_Y = 35;
    private static final int SECONDARY_OUTPUT_X = 134;
    private static final int SECONDARY_OUTPUT_Y = 35;

    // Progress bar (25x9)：位于电力槽与产物槽之间
    private static final int PROGRESS_X = 68;
    private static final int PROGRESS_Y = 36;

    // Energy bar —— 右侧竖直条（与 mekmm 种植站一致）
    private static final int ENERGY_X = 164;
    private static final int ENERGY_Y = 15;
    private static final int ENERGY_W = 6;
    private static final int ENERGY_H = 54;

    // Secondary chance text（副产物槽下方）
    private static final int CHANCE_X = 116;
    private static final int CHANCE_Y = 56;

    private static final int BG_FILL = 0xFF2B2B2B;
    private static final int BAR_FILL = 0xFF4DE84D;

    private final RecipeType<PlantingCuttingRecipe> recipeType;
    private final Component title;
    private final IDrawable background;
    private final IDrawable icon;

    public PlantingCuttingRecipeCategory(IGuiHelper helper, RecipeType<PlantingCuttingRecipe> recipeType, ItemStack iconStack) {
        this.recipeType = recipeType;
        this.title = Component.translatable("block.mekck.planting_cutting_station");
        this.background = helper.createBlankDrawable(PANEL_W, PANEL_H);
        this.icon = helper.createDrawableIngredient(VanillaTypes.ITEM_STACK, iconStack);
    }

    @Override
    public RecipeType<PlantingCuttingRecipe> getRecipeType() {
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
    public void setRecipe(IRecipeLayoutBuilder builder, PlantingCuttingRecipe recipe, IFocusGroup focuses) {
        // Seed: catalyst (never consumed)
        List<ItemStack> seedItems = getSeedItems(recipe);
        if (!seedItems.isEmpty()) {
            builder.addSlot(RecipeIngredientRole.CATALYST, INPUT_X + 1, INPUT_Y + 1)
                    .addItemStacks(seedItems)
                    .setSlotName("seed");
        }

        // Nutrient provider: catalyst —— 种植切配实际消耗 mekmm 营养液（气体 mekmm:nutrient_solution）。
        // 优先以 Mekanism JEI 气体原料展示（与 mekmm 官方种植分类一致，不依赖桶物品、无缺失纹理）；
        // 气体不可用时退回桶物品槽。
        boolean gasAdded = false;
        try {
            mekanism.api.chemical.gas.Gas gas = mekanism.api.MekanismAPI.gasRegistry()
                    .getValue(new net.minecraft.resources.ResourceLocation("mekmm", "nutrient_solution"));
            if (gas != null) {
                builder.addSlot(RecipeIngredientRole.CATALYST, EXTRA_X + 1, EXTRA_Y + 1)
                        .addIngredient(mekanism.client.jei.MekanismJEI.TYPE_GAS,
                                new mekanism.api.chemical.gas.GasStack(gas, 1000))
                        .setSlotName("nutrient");
                gasAdded = true;
            }
        } catch (Throwable ignored) {
        }
        if (!gasAdded) {
            List<ItemStack> nutrientProviders = getNutrientProviders();
            if (!nutrientProviders.isEmpty()) {
                builder.addSlot(RecipeIngredientRole.CATALYST, EXTRA_X + 1, EXTRA_Y + 1)
                        .addItemStacks(nutrientProviders)
                        .setSlotName("nutrient");
            }
        }

        // 生长方块格：只有带 soils 白名单的配方（神秘农业种子）才显示这一槽，
        // 候选物品就是配方里生成好的那串（= soil.categories ⊇ seed.categories 的土壤），
        // 与站点判定**同一份数据**，不做第二遍收集。
        if (recipe.requiresGrowthSoil()) {
            List<ItemStack> growthSoils = List.of(recipe.getGrowthSoils().getItems());
            if (!growthSoils.isEmpty()) {
                builder.addSlot(RecipeIngredientRole.CATALYST, GROWTH_X + 1, GROWTH_Y + 1)
                        .addItemStacks(growthSoils)
                        .setSlotName("growthSoil");
            }
        }

        // Main results
        List<ItemStack> results = new ArrayList<>(recipe.getResults());
        if (!results.isEmpty()) {
            builder.addSlot(RecipeIngredientRole.OUTPUT, MAIN_OUTPUT_X + 1, MAIN_OUTPUT_Y + 1)
                    .addItemStacks(results)
                    .setSlotName("mainOutput");
        }

        // Secondary results (with chance)
        List<ItemStack> secondaryResults = recipe.getSecondaryResults();
        if (!secondaryResults.isEmpty()) {
            builder.addSlot(RecipeIngredientRole.OUTPUT, SECONDARY_OUTPUT_X + 1, SECONDARY_OUTPUT_Y + 1)
                    .addItemStacks(secondaryResults)
                    .setSlotName("secondaryOutput");
        }
    }

    @Override
    public void draw(PlantingCuttingRecipe recipe, IRecipeSlotsView recipeSlotView, GuiGraphics guiGraphics, double mouseX, double mouseY) {
        // Slot backgrounds（与 mekmm 种植分类一致的槽位布局）
        drawTexture(guiGraphics, SLOT_POWER, POWER_X, POWER_Y, SLOT, SLOT);
        drawTexture(guiGraphics, SLOT_POWER_OVERLAY, POWER_X, POWER_Y, SLOT, SLOT);
        drawTexture(guiGraphics, SLOT_INPUT, INPUT_X, INPUT_Y, SLOT, SLOT);
        drawTexture(guiGraphics, SLOT_EXTRA, EXTRA_X, EXTRA_Y, SLOT, SLOT);
        // 产物槽底图：output_wide.png 是 42×26 的「两格宽」底图（3px 边距 + 18×18 + 18×18 + 3px 边距），
        // 一次覆盖主/副产物两格。旧实现把它按 18×18 画两次 ⇒ 整张 42×26 被压进 18×18、描边压扁。
        // blit 的 textureWidth/Height 必须等于贴图真实尺寸（护栏 TestJeiTextureSizes 钉住）。
        guiGraphics.blit(SLOT_OUTPUT_WIDE, MAIN_OUTPUT_X - 3, MAIN_OUTPUT_Y - 4, 0, 0, 42, 26, 42, 26);
        // 生长方块格底图：只在配方需要时画（与 setRecipe 的加槽条件一致）
        if (recipe.requiresGrowthSoil()) {
            drawTexture(guiGraphics, SLOT_EXTRA, GROWTH_X, GROWTH_Y, SLOT, SLOT);
        }

        // Progress bar: base row + full overlay row
        guiGraphics.blit(PROGRESS_BAR, PROGRESS_X, PROGRESS_Y, 0, 0, PROGRESS_W, PROGRESS_H, PROGRESS_W, PROGRESS_TEX_H);
        guiGraphics.blit(PROGRESS_BAR, PROGRESS_X + 1, PROGRESS_Y, 1, PROGRESS_H, PROGRESS_W - 2, PROGRESS_H, PROGRESS_W, PROGRESS_TEX_H);

        // Energy bar（右侧竖直条，与 mekmm 种植站一致）
        drawBar(guiGraphics, ENERGY_X, ENERGY_Y, ENERGY_W, ENERGY_H, BAR_FILL);

        // Secondary chance percentage
        float secondaryChance = recipe.getSecondaryChance();
        if (secondaryChance > 0 && secondaryChance < 1) {
            Font font = getFont();
            if (font != null) {
                Component chanceText = TextUtils.getPercent(secondaryChance);
                guiGraphics.drawString(font, chanceText.getString(), CHANCE_X, CHANCE_Y,
                        SpecialColors.TEXT_TITLE.argb(), false);
            }
        }
    }

    // ────────────── Drawing helpers ──────────────

    private static void drawTexture(GuiGraphics guiGraphics, ResourceLocation texture, int x, int y, int w, int h) {
        guiGraphics.blit(texture, x, y, 0, 0, w, h, w, h);
    }

    /** Draws a simple vertical bar: dark background + colored fill. */
    private static void drawBar(GuiGraphics guiGraphics, int x, int y, int w, int h, int fillColor) {
        guiGraphics.fill(x, y, x + w, y + h, BG_FILL);
        // Full fill for decorative display
        guiGraphics.fill(x + 1, y + 1, x + w - 1, y + h - 1, fillColor);
    }

    private Font getFont() {
        return Minecraft.getInstance().font;
    }

    // ────────────── Data helpers ──────────────

    private static List<ItemStack> getSeedItems(PlantingCuttingRecipe recipe) {
        ItemStack[] items = recipe.getSeed().getItems();
        if (items == null || items.length == 0) {
            return Collections.emptyList();
        }
        return List.of(items);
    }

    private static List<ItemStack> getNutrientProviders() {
        // 兜底物品：mekmm 营养液桶（若未来注册）→ mekmm 营养糊剂桶 → 空。
        // mekmm 只是可选依赖：未安装时 getValue 返回 null，而 new ItemStack(null, 1) 的第一步
        // 就是 ItemLike.asItem() 解引用 ⇒ 直接 NPE（本分类无条件注册），所以必须先判空再构造；
        // 语义保持「优先气体、退化到桶物品、都没有就空」，写法对齐 JEIPlugin.makeWineCellarInfoRecipe。
        Item bucketItem = ForgeRegistries.ITEMS.getValue(
                new ResourceLocation("mekmm", "nutrient_solution_bucket"));
        if (bucketItem != null && bucketItem != Items.AIR) {
            return Collections.singletonList(new ItemStack(bucketItem, 1));
        }
        Item pasteItem = ForgeRegistries.ITEMS.getValue(
                new ResourceLocation("mekmm", "nutritional_paste_bucket"));
        if (pasteItem != null && pasteItem != Items.AIR) {
            return Collections.singletonList(new ItemStack(pasteItem, 1));
        }
        return Collections.emptyList();
    }
}
