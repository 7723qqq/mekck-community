package cn.ism.mekck.recipe;

import cn.ism.mekck.UniversalCuttingMachine;
import com.google.gson.JsonArray;
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

/**
 * 包材组装机专属配方 {@code mekck:packaging}（F7 / F11 §四.4）。
 * <p>
 * 语义：{@code 若干输入材料 → 1 个空容器（包材）}。产物限定为「包材类」（杯 / 包装盒 / 碗等），
 * 不做任何食品/饮料灌装——该约束由「包材机只读本类型 + 配方线只往里放包材配方」天然保证。
 * 无流体，纯物品加工；可数据包扩展。
 * </p>
 * <p>
 * JSON：{@code { "type": "mekck:packaging", "ingredients": [ <ingredient>, ... ], "result": {...}, "processingTime": N? }}。
 * 由 {@code matchIngredients} 把每个成分分配到不同输入槽，各消耗 1。
 * </p>
 */
public class PackagingRecipe implements Recipe<RecipeWrapper> {

    private final ResourceLocation id;
    private final List<Ingredient> ingredients;
    private final ItemStack result;
    private final int processTime;

    public PackagingRecipe(ResourceLocation id, List<Ingredient> ingredients, ItemStack result, int processTime) {
        this.id = id;
        this.ingredients = ingredients;
        this.result = result;
        this.processTime = processTime;
    }

    /** 输入成分清单（每个各消耗 1）。 */
    public List<Ingredient> getItemIngredients() {
        return ingredients;
    }

    /** 加工时长（tick）；0 表示用机器基础时长。 */
    public int getProcessTime() {
        return processTime;
    }

    @Override
    public boolean matches(RecipeWrapper wrapper, @NotNull Level level) {
        int matched = 0;
        for (int s = 0; s < wrapper.getContainerSize(); s++) {
            ItemStack st = wrapper.getItem(s);
            if (st.isEmpty()) continue;
            for (Ingredient ing : ingredients) {
                if (ing.test(st)) { matched++; break; }
            }
        }
        return matched == ingredients.size();
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
        return UniversalCuttingMachine.PACKAGING_RECIPE_SERIALIZER.get();
    }

    @Override
    public @NotNull RecipeType<?> getType() {
        return UniversalCuttingMachine.PACKAGING_RECIPE_TYPE.get();
    }

    @Override
    public @NotNull NonNullList<Ingredient> getIngredients() {
        NonNullList<Ingredient> list = NonNullList.create();
        list.addAll(ingredients);
        return list;
    }

    public static class Serializer implements RecipeSerializer<PackagingRecipe> {

        @Override
        public @NotNull PackagingRecipe fromJson(@NotNull ResourceLocation recipeId, @NotNull JsonObject json) {
            List<Ingredient> ingredients = new ArrayList<>();
            JsonArray arr = GsonHelper.getAsJsonArray(json, "ingredients");
            for (int i = 0; i < arr.size(); i++) {
                Ingredient ing = Ingredient.fromJson(arr.get(i));
                if (!ing.isEmpty()) ingredients.add(ing);
            }
            ItemStack result = ShapedRecipe.itemStackFromJson(GsonHelper.getAsJsonObject(json, "result"));
            int time = GsonHelper.getAsInt(json, "processingTime", 0);
            return new PackagingRecipe(recipeId, ingredients, result, time);
        }

        @Override
        public @Nullable PackagingRecipe fromNetwork(@NotNull ResourceLocation recipeId, @NotNull FriendlyByteBuf buffer) {
            int n = buffer.readVarInt();
            List<Ingredient> ingredients = new ArrayList<>();
            for (int i = 0; i < n; i++) ingredients.add(Ingredient.fromNetwork(buffer));
            ItemStack result = buffer.readItem();
            int time = buffer.readVarInt();
            return new PackagingRecipe(recipeId, ingredients, result, time);
        }

        @Override
        public void toNetwork(@NotNull FriendlyByteBuf buffer, @NotNull PackagingRecipe recipe) {
            buffer.writeVarInt(recipe.ingredients.size());
            for (Ingredient ing : recipe.ingredients) ing.toNetwork(buffer);
            buffer.writeItem(recipe.result);
            buffer.writeVarInt(recipe.processTime);
        }
    }
}
