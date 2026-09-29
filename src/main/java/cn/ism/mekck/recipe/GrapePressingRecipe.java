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

/**
 * 鲜果榨汁机专属配方 {@code mekck:grape_pressing}（陈酿机/榨汁机改造简报 需求1）。
 * <p>
 * 语义：{@code N 份输入材料（含各自数量）→ 1 个产物 + 可选加工时长}，忠实折算自 vinery 压榨盆
 * {@code GrapevinePotBlock}——{@code 3 葡萄 + 1 空葡萄酒瓶 → 1 瓶装葡萄汁}。与 {@code PackagingRecipe}
 * 同为「纯物品、无流体」配方，差别在于本类型显式携带 <b>每种成分的数量</b>（{@link #getIngredientCounts()}，
 * 与 {@link #getItemIngredients()} 一一对应），故「3 颗葡萄」无需占 3 个输入槽，可堆叠在单槽内。
 * </p>
 * <p>
 * JSON：{@code { "type":"mekck:grape_pressing",
 * "ingredients":[{"item"|"tag":..., "count":N?}...], "result":{...}, "processingTime":N? }}。
 * {@code count} 缺省为 1；{@code processingTime} 缺省为 0（由机器用基础时长）。真实匹配/消耗在方块实体
 * {@code matchJuicer()} 的葡萄压榨路径中按 count 完成，本类的 {@link #matches} 仅作宽松的物品成分全命中判断。
 * </p>
 */
public class GrapePressingRecipe implements Recipe<RecipeWrapper> {

    private final ResourceLocation id;
    private final List<Ingredient> ingredients;
    private final List<Integer> counts;
    private final ItemStack result;
    private final int processTime;

    public GrapePressingRecipe(ResourceLocation id, List<Ingredient> ingredients, List<Integer> counts,
                               ItemStack result, int processTime) {
        this.id = id;
        this.ingredients = ingredients;
        this.counts = counts;
        this.result = result;
        this.processTime = processTime;
    }

    /** 输入成分清单（与 {@link #getIngredientCounts()} 一一对应）。 */
    public List<Ingredient> getItemIngredients() {
        return ingredients;
    }

    /** 每种成分需消耗的数量（与 {@link #getItemIngredients()} 一一对应；缺省 1）。 */
    public List<Integer> getIngredientCounts() {
        return counts;
    }

    /** 加工时长（tick）；0 表示用机器基础时长。 */
    public int getProcessTime() {
        return processTime;
    }

    /** 第 i 种成分的数量（越界安全返回 1）。 */
    public int countAt(int i) {
        return i >= 0 && i < counts.size() && counts.get(i) != null ? Math.max(1, counts.get(i)) : 1;
    }

    @Override
    public boolean matches(RecipeWrapper wrapper, @NotNull Level level) {
        // 真实匹配（含 count）在方块实体完成；这里仅做「每种成分至少有一个槽命中」的宽松判断。
        for (Ingredient ing : ingredients) {
            if (ing == null || ing.isEmpty()) continue;
            boolean hit = false;
            for (int s = 0; s < wrapper.getContainerSize(); s++) {
                ItemStack st = wrapper.getItem(s);
                if (!st.isEmpty() && ing.test(st)) { hit = true; break; }
            }
            if (!hit) return false;
        }
        return !ingredients.isEmpty();
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
        return UniversalCuttingMachine.GRAPE_PRESSING_RECIPE_SERIALIZER.get();
    }

    @Override
    public @NotNull RecipeType<?> getType() {
        return UniversalCuttingMachine.GRAPE_PRESSING_RECIPE_TYPE.get();
    }

    @Override
    public @NotNull NonNullList<Ingredient> getIngredients() {
        NonNullList<Ingredient> list = NonNullList.create();
        list.addAll(ingredients);
        return list;
    }

    public static class Serializer implements RecipeSerializer<GrapePressingRecipe> {

        @Override
        public @NotNull GrapePressingRecipe fromJson(@NotNull ResourceLocation recipeId, @NotNull JsonObject json) {
            List<Ingredient> ingredients = new ArrayList<>();
            List<Integer> counts = new ArrayList<>();
            JsonArray arr = GsonHelper.getAsJsonArray(json, "ingredients");
            for (int i = 0; i < arr.size(); i++) {
                JsonElement el = arr.get(i);
                int count = 1;
                JsonObject ingObj;
                if (el.isJsonObject()) {
                    JsonObject o = el.getAsJsonObject();
                    // 支持两种写法：嵌套 { "ingredient": {...}, "count": N } 或扁平 { "item"/"tag":..., "count": N }
                    if (o.has("ingredient") && o.get("ingredient").isJsonObject()) {
                        ingObj = o.getAsJsonObject("ingredient");
                    } else {
                        ingObj = o;
                    }
                    count = GsonHelper.getAsInt(o, "count", 1);
                } else {
                    ingObj = el.getAsJsonObject();
                }
                Ingredient ing = Ingredient.fromJson(ingObj);
                if (!ing.isEmpty()) {
                    ingredients.add(ing);
                    counts.add(Math.max(1, count));
                }
            }
            ItemStack result = ShapedRecipe.itemStackFromJson(GsonHelper.getAsJsonObject(json, "result"));
            int time = GsonHelper.getAsInt(json, "processingTime", 0);
            return new GrapePressingRecipe(recipeId, ingredients, counts, result, time);
        }

        @Override
        public @Nullable GrapePressingRecipe fromNetwork(@NotNull ResourceLocation recipeId, @NotNull FriendlyByteBuf buffer) {
            int n = buffer.readVarInt();
            List<Ingredient> ingredients = new ArrayList<>();
            List<Integer> counts = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                ingredients.add(Ingredient.fromNetwork(buffer));
                counts.add(buffer.readVarInt());
            }
            ItemStack result = buffer.readItem();
            int time = buffer.readVarInt();
            return new GrapePressingRecipe(recipeId, ingredients, counts, result, time);
        }

        @Override
        public void toNetwork(@NotNull FriendlyByteBuf buffer, @NotNull GrapePressingRecipe recipe) {
            buffer.writeVarInt(recipe.ingredients.size());
            for (int i = 0; i < recipe.ingredients.size(); i++) {
                recipe.ingredients.get(i).toNetwork(buffer);
                buffer.writeVarInt(recipe.countAt(i));
            }
            buffer.writeItem(recipe.result);
            buffer.writeVarInt(recipe.processTime);
        }
    }
}
