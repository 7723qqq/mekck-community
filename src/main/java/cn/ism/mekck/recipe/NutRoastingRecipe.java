package cn.ism.mekck.recipe;

import cn.ism.mekck.UniversalCuttingMachine;
import com.google.gson.JsonObject;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.GsonHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.level.Level;
import net.minecraftforge.items.wrapper.RecipeWrapper;
import org.jetbrains.annotations.NotNull;

import javax.annotation.Nullable;
import cn.ism.mekck.registry.MekCkRecipeTypes;

/**
 * 坚果爆炒机专属配方 {@code mekck:nut_roasting}（炒坚果）：1 输入 → 1 输出。
 * <p>
 * 与制冰不同，输入会被消耗（炒熟）。模组自带 榛子 → 炒榛子 配方，可由数据包扩展。
 * </p>
 */
public class NutRoastingRecipe implements Recipe<RecipeWrapper> {

    private final ResourceLocation id;
    private final Ingredient ingredient;
    private final ItemStack result;

    public NutRoastingRecipe(ResourceLocation id, Ingredient ingredient, ItemStack result) {
        this.id = id;
        this.ingredient = ingredient;
        this.result = result;
    }

    public Ingredient getIngredient() {
        return ingredient;
    }

    public ItemStack getResult() {
        return result;
    }

    @Override
    public boolean matches(RecipeWrapper wrapper, @NotNull Level level) {
        return ingredient.test(wrapper.getItem(0));
    }

    @Override
    public @NotNull ItemStack assemble(RecipeWrapper wrapper, RegistryAccess registryAccess) {
        return result.copy();
    }

    @Override
    public boolean canCraftInDimensions(int w, int h) {
        return true;
    }

    @Override
    public @NotNull ItemStack getResultItem(RegistryAccess registryAccess) {
        return result.copy();
    }

    @Override
    public @NotNull ResourceLocation getId() {
        return id;
    }

    @Override
    public @NotNull RecipeSerializer<?> getSerializer() {
        return MekCkRecipeTypes.NUT_ROASTING_RECIPE_SERIALIZER.get();
    }

    @Override
    public @NotNull RecipeType<?> getType() {
        return MekCkRecipeTypes.NUT_ROASTING_RECIPE_TYPE.get();
    }

    @Override
    public @NotNull net.minecraft.core.NonNullList<Ingredient> getIngredients() {
        // 仅用于展示；实际匹配在 matches 中完成
        return net.minecraft.core.NonNullList.create();
    }

    public static class Serializer implements RecipeSerializer<NutRoastingRecipe> {

        @Override
        public @NotNull NutRoastingRecipe fromJson(@NotNull ResourceLocation recipeId, @NotNull JsonObject json) {
            Ingredient ingredient = Ingredient.fromJson(GsonHelper.getAsJsonObject(json, "ingredient"));
            ItemStack result = ShapedRecipe.itemStackFromJson(GsonHelper.getAsJsonObject(json, "result"));
            return new NutRoastingRecipe(recipeId, ingredient, result);
        }

        @Override
        public @Nullable NutRoastingRecipe fromNetwork(@NotNull ResourceLocation recipeId, @NotNull FriendlyByteBuf buffer) {
            Ingredient ingredient = Ingredient.fromNetwork(buffer);
            ItemStack result = buffer.readItem();
            return new NutRoastingRecipe(recipeId, ingredient, result);
        }

        @Override
        public void toNetwork(@NotNull FriendlyByteBuf buffer, @NotNull NutRoastingRecipe recipe) {
            recipe.ingredient.toNetwork(buffer);
            buffer.writeItem(recipe.result);
        }
    }
}
