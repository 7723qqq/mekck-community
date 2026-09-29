package cn.ism.mekck.recipe;

import cn.ism.mekck.UniversalCuttingMachine;
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

/**
 * 烧烤工厂自有配方 {@code mekck:grilling}：单一输入 → 烤制产物。
 *
 * <p>解决的是 §6.5.1 结论 1 的缺口：该工艺原先只读 {@code barbequesdelight:grilling}，
 * 而该 mod 未声明依赖、mekck 侧引用数为 0，导致 12 档全部空转。烟熏炉上位
 * （§6.5.1.1）只是权宜之计——9 条配方撑不住 12 档并行进度。</p>
 *
 * <p><b>为什么产物是新的"烤"变体而不是复用熟肉</b>：原版 {@code SMOKING} 的 9 条
 * 已经产出 {@code cooked_beef} 等；若本类型也产出同一种物品，它与烟熏炉完全重复，
 * 等于什么都没加。烤制变体是刻意与"水煮/烟熏"区分开的产物线。</p>
 *
 * <p><b>字段名与可见性是硬约束</b>：{@code GrillFactoryBlockEntity.matchesInput} 用
 * {@code recipe.getClass().getField("ingredient")} 反射——{@code getField} <b>只找
 * public 字段</b>，私有字段会抛 {@code NoSuchFieldException} 并落到
 * {@code getIngredients().get(0)} 的兜底分支。{@code ingredient} 因此必须 public：
 * 那条兜底是靠抛异常走的，而它在每 tick 每槽的热路径上。改可见性前先读那段代码。</p>
 *
 * <p>本类型<b>不自带调味</b>：调味是 {@code BarbequesDelightCompat} 的 NBT 体系
 * （{@code isSeasonable(result)} → {@code applySeasoning}），另造一套没有依据。
 * {@code currentSeasoningFor} 对本类型的产物会因 {@code isSeasonable} 为 false
 * 而返回 null，行为安全。</p>
 *
 * <p>JSON：
 * <pre>
 * { "type": "mekck:grilling",
 *   "ingredient": &lt;Ingredient&gt;,
 *   "result":     &lt;ItemStack&gt;,
 *   "processingTime": N }        // 缺省 0 = 用机器基础时长
 * </pre>
 * </p>
 */
public class MekCkGrillingRecipe implements Recipe<RecipeWrapper> {

    private final ResourceLocation id;
    /** 主输入。**必须 public**——见类注释的 getField 反射约束。 */
    public final Ingredient ingredient;
    private final ItemStack result;
    private final int processTime;

    public MekCkGrillingRecipe(ResourceLocation id, Ingredient ingredient, ItemStack result, int processTime) {
        this.id = id;
        this.ingredient = ingredient;
        this.result = result;
        this.processTime = processTime;
    }

    public Ingredient getIngredient() {
        return ingredient;
    }

    public ItemStack getResult() {
        return result;
    }

    /** 加工时长（tick）；0 表示用机器基础时长。 */
    public int getProcessTime() {
        return processTime;
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
        return UniversalCuttingMachine.GRILLING_RECIPE_SERIALIZER.get();
    }

    @Override
    public @NotNull RecipeType<?> getType() {
        return UniversalCuttingMachine.GRILLING_RECIPE_TYPE.get();
    }

    @Override
    public @NotNull NonNullList<Ingredient> getIngredients() {
        NonNullList<Ingredient> list = NonNullList.create();
        list.add(ingredient);
        return list;
    }

    public static class Serializer implements RecipeSerializer<MekCkGrillingRecipe> {

        @Override
        public @NotNull MekCkGrillingRecipe fromJson(@NotNull ResourceLocation recipeId, @NotNull JsonObject json) {
            Ingredient ingredient = Ingredient.fromJson(GsonHelper.getAsJsonObject(json, "ingredient"));
            ItemStack result = ShapedRecipe.itemStackFromJson(GsonHelper.getAsJsonObject(json, "result"));
            int time = GsonHelper.getAsInt(json, "processingTime", 0);
            return new MekCkGrillingRecipe(recipeId, ingredient, result, time);
        }

        @Override
        public @Nullable MekCkGrillingRecipe fromNetwork(@NotNull ResourceLocation recipeId, @NotNull FriendlyByteBuf buffer) {
            Ingredient ingredient = Ingredient.fromNetwork(buffer);
            ItemStack result = buffer.readItem();
            int time = buffer.readVarInt();
            return new MekCkGrillingRecipe(recipeId, ingredient, result, time);
        }

        @Override
        public void toNetwork(@NotNull FriendlyByteBuf buffer, @NotNull MekCkGrillingRecipe recipe) {
            recipe.ingredient.toNetwork(buffer);
            buffer.writeItem(recipe.result);
            buffer.writeVarInt(recipe.processTime);
        }
    }
}
