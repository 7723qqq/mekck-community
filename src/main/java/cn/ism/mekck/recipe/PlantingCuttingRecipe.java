package cn.ism.mekck.recipe;

import cn.ism.mekck.UniversalCuttingMachine;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.NonNullList;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.GsonHelper;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.level.Level;
import net.minecraftforge.items.wrapper.RecipeWrapper;
import org.jetbrains.annotations.NotNull;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import cn.ism.mekck.registry.MekCkRecipeTypes;

public class PlantingCuttingRecipe implements Recipe<RecipeWrapper> {

    private final ResourceLocation id;
    private final Ingredient seed;
    private final int gasAmount;
    private final NonNullList<ItemStack> results;
    private final NonNullList<ItemStack> secondaryResults;
    private final float secondaryChance;
    /**
     * 「生长方块格」要求（可选）。
     * <p>由 {@code PlantingRecipeGenerator} 在生成 plantcut 配方时算好：把 BotanyPots 里
     * {@code botanypots:soil} 的 categories **⊇** 该种子 crop 的 categories 的土壤物品全部列进来。</p>
     * <p>{@link Ingredient#EMPTY} ⇒ **无要求**（非神秘农业种子 / 该种子没有等级要求）⇒ 生长格放什么都行。</p>
     */
    private final Ingredient growthSoils;
    /** 该种子要求的 categories（如 {@code ["inferium"]}），仅用于 GUI 提示与文档，可为空表。 */
    private final List<String> requiredSoilCategories;

    public PlantingCuttingRecipe(ResourceLocation id, Ingredient seed, int gasAmount,
                                 NonNullList<ItemStack> results,
                                 NonNullList<ItemStack> secondaryResults, float secondaryChance) {
        this(id, seed, gasAmount, results, secondaryResults, secondaryChance, Ingredient.EMPTY, List.of());
    }

    public PlantingCuttingRecipe(ResourceLocation id, Ingredient seed, int gasAmount,
                                 NonNullList<ItemStack> results,
                                 NonNullList<ItemStack> secondaryResults, float secondaryChance,
                                 Ingredient growthSoils, List<String> requiredSoilCategories) {
        this.id = id;
        this.seed = seed;
        this.gasAmount = gasAmount;
        this.results = results;
        this.secondaryResults = secondaryResults;
        this.secondaryChance = secondaryChance;
        this.growthSoils = growthSoils == null ? Ingredient.EMPTY : growthSoils;
        this.requiredSoilCategories = requiredSoilCategories == null ? List.of() : List.copyOf(requiredSoilCategories);
    }

    public Ingredient getSeed() {
        return seed;
    }

    public int getGasAmount() {
        return gasAmount;
    }

    public NonNullList<ItemStack> getResults() {
        return results;
    }

    public NonNullList<ItemStack> getSecondaryResults() {
        return secondaryResults;
    }

    public float getSecondaryChance() {
        return secondaryChance;
    }

    /** 生长方块格允许的物品；{@link Ingredient#EMPTY} 表示这台机器不需要生长方块。 */
    public Ingredient getGrowthSoils() {
        return growthSoils;
    }

    /** 该配方是否要求生长方块（神秘农业种子按 BotanyPots 的 categories 判定）。 */
    public boolean requiresGrowthSoil() {
        return growthSoils.getItems().length > 0;
    }

    /** 要求的 categories（仅供提示；可能为空表而 {@link #requiresGrowthSoil()} 仍为 true）。 */
    public List<String> getRequiredSoilCategories() {
        return requiredSoilCategories;
    }

    @Override
    public boolean matches(RecipeWrapper wrapper, @NotNull Level level) {
        return seed.test(wrapper.getItem(0));
    }

    @Override
    public @NotNull ItemStack assemble(RecipeWrapper wrapper, RegistryAccess registryAccess) {
        return results.isEmpty() ? ItemStack.EMPTY : results.get(0).copy();
    }

    @Override
    public boolean canCraftInDimensions(int w, int h) {
        return true;
    }

    @Override
    public @NotNull ItemStack getResultItem(RegistryAccess registryAccess) {
        return results.isEmpty() ? ItemStack.EMPTY : results.get(0).copy();
    }

    @Override
    public @NotNull ResourceLocation getId() {
        return id;
    }

    @Override
    public @NotNull RecipeSerializer<?> getSerializer() {
        return MekCkRecipeTypes.PLANTING_CUTTING_RECIPE_SERIALIZER.get();
    }

    @Override
    public @NotNull RecipeType<?> getType() {
        return MekCkRecipeTypes.PLANTING_CUTTING_RECIPE_TYPE.get();
    }

    @Override
    public @NotNull NonNullList<Ingredient> getIngredients() {
        // This recipe does not consume the seed — it only matches against it.
        return NonNullList.create();
    }

    // ────────────── Serializer ──────────────

    public static class Serializer implements RecipeSerializer<PlantingCuttingRecipe> {

        @Override
        public @NotNull PlantingCuttingRecipe fromJson(@NotNull ResourceLocation recipeId, @NotNull JsonObject json) {
            Ingredient seed = Ingredient.fromJson(GsonHelper.getAsJsonObject(json, "seed"));

            int gasAmount = GsonHelper.getAsInt(json, "gasAmount", 1);

            // Parse main results
            NonNullList<ItemStack> results = NonNullList.create();
            JsonArray resultsArray = GsonHelper.getAsJsonArray(json, "results");
            for (JsonElement elem : resultsArray) {
                JsonObject obj = elem.getAsJsonObject();
                ItemStack stack = ShapedRecipe.itemStackFromJson(obj);
                if (!stack.isEmpty()) {
                    results.add(stack);
                }
            }

            // Parse secondary results
            NonNullList<ItemStack> secondaryResults = NonNullList.create();
            float secondaryChance = 0.0f;
            if (json.has("secondaryResults")) {
                JsonArray secondaryArray = GsonHelper.getAsJsonArray(json, "secondaryResults");
                for (JsonElement elem : secondaryArray) {
                    JsonObject obj = elem.getAsJsonObject();
                    ItemStack stack = ShapedRecipe.itemStackFromJson(obj);
                    if (!stack.isEmpty()) {
                        secondaryResults.add(stack);
                    }
                }
                secondaryChance = GsonHelper.getAsFloat(json, "secondaryChance", 0.0f);
            }

            // 生长方块要求（可选；由 PlantingRecipeGenerator 依据 BotanyPots soil categories 生成）
            // 形如 "soils": ["mysticalagriculture:inferium_farmland", ...] —— 纯字符串数组，自己解析
            Ingredient growthSoils = Ingredient.EMPTY;
            if (json.has("soils")) {
                List<ItemStack> soilStacks = new ArrayList<>();
                for (JsonElement elem : GsonHelper.getAsJsonArray(json, "soils")) {
                    Item soilItem = ForgeRegistries.ITEMS.getValue(new ResourceLocation(elem.getAsString()));
                    if (soilItem != null) {
                        soilStacks.add(new ItemStack(soilItem));
                    }
                }
                if (!soilStacks.isEmpty()) {
                    growthSoils = Ingredient.of(soilStacks.stream());
                }
            }
            List<String> requiredSoilCategories = new ArrayList<>();
            if (json.has("requiredSoilCategories")) {
                for (JsonElement elem : GsonHelper.getAsJsonArray(json, "requiredSoilCategories")) {
                    requiredSoilCategories.add(elem.getAsString());
                }
            }

            return new PlantingCuttingRecipe(recipeId, seed, gasAmount, results, secondaryResults, secondaryChance,
                    growthSoils, requiredSoilCategories);
        }

        @Override
        public @Nullable PlantingCuttingRecipe fromNetwork(@NotNull ResourceLocation recipeId, @NotNull FriendlyByteBuf buffer) {
            Ingredient seed = Ingredient.fromNetwork(buffer);
            int gasAmount = buffer.readVarInt();

            int resultCount = buffer.readVarInt();
            NonNullList<ItemStack> results = NonNullList.create();
            for (int i = 0; i < resultCount; i++) {
                results.add(buffer.readItem());
            }

            int secondaryCount = buffer.readVarInt();
            NonNullList<ItemStack> secondaryResults = NonNullList.create();
            for (int i = 0; i < secondaryCount; i++) {
                secondaryResults.add(buffer.readItem());
            }
            float secondaryChance = buffer.readFloat();

            Ingredient growthSoils = Ingredient.fromNetwork(buffer);
            int categoryCount = buffer.readVarInt();
            List<String> requiredSoilCategories = new ArrayList<>(categoryCount);
            for (int i = 0; i < categoryCount; i++) {
                requiredSoilCategories.add(buffer.readUtf());
            }

            return new PlantingCuttingRecipe(recipeId, seed, gasAmount, results, secondaryResults, secondaryChance,
                    growthSoils, requiredSoilCategories);
        }

        @Override
        public void toNetwork(@NotNull FriendlyByteBuf buffer, @NotNull PlantingCuttingRecipe recipe) {
            recipe.seed.toNetwork(buffer);
            buffer.writeVarInt(recipe.gasAmount);

            buffer.writeVarInt(recipe.results.size());
            for (ItemStack stack : recipe.results) {
                buffer.writeItem(stack);
            }

            buffer.writeVarInt(recipe.secondaryResults.size());
            for (ItemStack stack : recipe.secondaryResults) {
                buffer.writeItem(stack);
            }
            buffer.writeFloat(recipe.secondaryChance);

            recipe.growthSoils.toNetwork(buffer);
            buffer.writeVarInt(recipe.requiredSoilCategories.size());
            for (String category : recipe.requiredSoilCategories) {
                buffer.writeUtf(category);
            }
        }
    }
}