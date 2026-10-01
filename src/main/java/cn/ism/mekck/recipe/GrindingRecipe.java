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
 * 电力研磨机专属配方 {@code mekck:grinding}（§F19 D 半：磨粉工序）：1 输入 → 1 输出。
 * <p>
 * 照抄 {@link NutRoastingRecipe} 单入单出模板（用户 2026-09-25 定向）。首条配方：
 * 炒榛子 → 榛子粉（{@code data/mekck/recipes/grinding/hazelnut_powder.json}），可由数据包扩展。
 * 研磨机执行侧与筛粉/绞碎同口径：匹配 {@code ingredients[0]}、产物为固定单物品。
 * </p>
 */
public class GrindingRecipe implements Recipe<RecipeWrapper> {

    private final ResourceLocation id;
    private final Ingredient ingredient;
    private final ItemStack result;

    public GrindingRecipe(ResourceLocation id, Ingredient ingredient, ItemStack result) {
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
        return MekCkRecipeTypes.GRINDING_RECIPE_SERIALIZER.get();
    }

    @Override
    public @NotNull RecipeType<?> getType() {
        return MekCkRecipeTypes.GRINDING_RECIPE_TYPE.get();
    }

    @Override
    public @NotNull net.minecraft.core.NonNullList<Ingredient> getIngredients() {
        // 研磨机执行侧（matchesInput/isItemValid/AE2 样板）统一走 getIngredients() 通用通道，
        // 须返回真实列表（不同于 NutRoastingRecipe 的展示用空表——爆炒机自带 wrapper 查找路径）。
        return net.minecraft.core.NonNullList.of(ingredient);
    }

    public static class Serializer implements RecipeSerializer<GrindingRecipe> {

        @Override
        public @NotNull GrindingRecipe fromJson(@NotNull ResourceLocation recipeId, @NotNull JsonObject json) {
            Ingredient ingredient = Ingredient.fromJson(GsonHelper.getAsJsonObject(json, "ingredient"));
            ItemStack result = ShapedRecipe.itemStackFromJson(GsonHelper.getAsJsonObject(json, "result"));
            return new GrindingRecipe(recipeId, ingredient, result);
        }

        @Override
        public @Nullable GrindingRecipe fromNetwork(@NotNull ResourceLocation recipeId, @NotNull FriendlyByteBuf buffer) {
            Ingredient ingredient = Ingredient.fromNetwork(buffer);
            ItemStack result = buffer.readItem();
            return new GrindingRecipe(recipeId, ingredient, result);
        }

        @Override
        public void toNetwork(@NotNull FriendlyByteBuf buffer, @NotNull GrindingRecipe recipe) {
            recipe.ingredient.toNetwork(buffer);
            buffer.writeItem(recipe.result);
        }
    }
}
