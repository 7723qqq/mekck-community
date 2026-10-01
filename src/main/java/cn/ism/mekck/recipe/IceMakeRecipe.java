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
import java.util.ArrayList;
import java.util.List;
import cn.ism.mekck.registry.MekCkRecipeTypes;

/**
 * 急冻制冰机 / 制冰工厂使用的 {@code mekck:ice_make} 配方。
 * <p>
 * 输入物品作为催化剂（不消耗），每完成一次配方消耗 {@link #fluidAmount} mB 水，产出 results。
 * 模组自带 ice/packed_ice/blue_ice（1000 mB）与 youkaishomecoming:ice_cube（125 mB）配方，也可由数据包扩展。
 */
public class IceMakeRecipe implements Recipe<RecipeWrapper> {

    private final ResourceLocation id;
    private final Ingredient ingredient;
    private final int fluidAmount;
    private final NonNullList<ItemStack> results;

    public IceMakeRecipe(ResourceLocation id, Ingredient ingredient, int fluidAmount, NonNullList<ItemStack> results) {
        this.id = id;
        this.ingredient = ingredient;
        this.fluidAmount = fluidAmount;
        this.results = results;
    }

    public Ingredient getIngredient() {
        return ingredient;
    }

    /** 每次配方完成所需消耗的水量（mB）。 */
    public int getFluidAmount() {
        return fluidAmount;
    }

    public NonNullList<ItemStack> getResults() {
        return results;
    }

    @Override
    public boolean matches(RecipeWrapper wrapper, @NotNull Level level) {
        return ingredient.test(wrapper.getItem(0));
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
        return MekCkRecipeTypes.ICE_MAKE_RECIPE_SERIALIZER.get();
    }

    @Override
    public @NotNull RecipeType<?> getType() {
        return MekCkRecipeTypes.ICE_MAKE_RECIPE_TYPE.get();
    }

    @Override
    public @NotNull NonNullList<Ingredient> getIngredients() {
        // 输入作为催化剂，不消耗。
        return NonNullList.create();
    }

    public static class Serializer implements RecipeSerializer<IceMakeRecipe> {

        @Override
        public @NotNull IceMakeRecipe fromJson(@NotNull ResourceLocation recipeId, @NotNull JsonObject json) {
            Ingredient ingredient = Ingredient.fromJson(GsonHelper.getAsJsonObject(json, "ingredient"));

            int fluidAmount = GsonHelper.getAsInt(json, "fluidAmount", 250);

            NonNullList<ItemStack> results = NonNullList.create();
            JsonArray resultsArray = GsonHelper.getAsJsonArray(json, "results");
            for (JsonElement elem : resultsArray) {
                JsonObject obj = elem.getAsJsonObject();
                ItemStack stack = ShapedRecipe.itemStackFromJson(obj);
                if (!stack.isEmpty()) {
                    results.add(stack);
                }
            }

            return new IceMakeRecipe(recipeId, ingredient, fluidAmount, results);
        }

        @Override
        public @Nullable IceMakeRecipe fromNetwork(@NotNull ResourceLocation recipeId, @NotNull FriendlyByteBuf buffer) {
            Ingredient ingredient = Ingredient.fromNetwork(buffer);
            int fluidAmount = buffer.readVarInt();
            int resultCount = buffer.readVarInt();
            NonNullList<ItemStack> results = NonNullList.create();
            for (int i = 0; i < resultCount; i++) {
                results.add(buffer.readItem());
            }
            return new IceMakeRecipe(recipeId, ingredient, fluidAmount, results);
        }

        @Override
        public void toNetwork(@NotNull FriendlyByteBuf buffer, @NotNull IceMakeRecipe recipe) {
            recipe.ingredient.toNetwork(buffer);
            buffer.writeVarInt(recipe.fluidAmount);
            buffer.writeVarInt(recipe.results.size());
            for (ItemStack stack : recipe.results) {
                buffer.writeItem(stack);
            }
        }
    }
}
