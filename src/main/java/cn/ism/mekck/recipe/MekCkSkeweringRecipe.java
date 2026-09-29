package cn.ism.mekck.recipe;

import cn.ism.mekck.UniversalCuttingMachine;
import com.google.gson.JsonObject;
import net.minecraft.core.NonNullList;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.GsonHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
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
 * 串烧工厂专属配方 {@code mekck:skewering}：签子(载体) + 主料 + 辅料 → 串烧物。
 *
 * <p>字段名<b>必须</b>与 {@code SkeweringFactoryBlockEntity} 的反射契约一致——
 * 该类用 {@code getIngredientField(recipe, "tool"/"ingredient"/"side")} 与
 * {@code getCountField(recipe, "ingredientCount"/"sideCount")} 读取本类型，
 * 字段名或类型不匹配会静默停产（反射失败返回 null，匹配退化为「恒不匹配」）。
 * 改名前先看那段代码。</p>
 *
 * <p>⚠ 命名陷阱：本项目里 {@code ingredientCount} 被
 * {@code consumeIngredients} 读作<b>签子（tool）的消耗数</b>，主料则硬编码为 1。
 * 签子不消耗，所以本类固定写 {@code ingredientCount = 0}；
 * 签子由 {@code completeRecipe} 从输入槽 0 原样取回放进返还槽。</p>
 *
 * <p>JSON：
 * <pre>
 * { "type": "mekck:skewering",
 *   "tool":       &lt;Ingredient&gt;,   // 签子，缺省 minecraft:stick
 *   "ingredient": &lt;Ingredient&gt;,   // 主料，必填，消耗 1
 *   "side":       &lt;Ingredient&gt;,   // 辅料，必填，消耗 sideCount
 *   "sideCount":  N,                 // 缺省 1
 *   "result":     &lt;ItemStack&gt;,
 *   "processingTime": N }            // 缺省 0 = 用机器基础时长
 * </pre>
 * 主料与辅料<b>不区分顺序</b>，避免玩家因放错槽而卡住。</p>
 */
public class MekCkSkeweringRecipe implements Recipe<RecipeWrapper> {

    private final ResourceLocation id;
    private final Ingredient tool;
    private final Ingredient ingredient;
    private final Ingredient side;
    private final int ingredientCount;
    private final int sideCount;
    private final ItemStack result;
    private final int processTime;

    public MekCkSkeweringRecipe(ResourceLocation id, Ingredient tool, Ingredient ingredient,
                                Ingredient side, int ingredientCount, int sideCount,
                                ItemStack result, int processTime) {
        this.id = id;
        this.tool = tool;
        this.ingredient = ingredient;
        this.side = side;
        this.ingredientCount = ingredientCount;
        this.sideCount = sideCount;
        this.result = result;
        this.processTime = processTime;
    }

    /** 签子（载体）：从输入槽 0 读取，产出后原样退回返还槽，不消耗。 */
    public Ingredient getTool() {
        return tool;
    }

    /** 主料，消耗 1。 */
    public Ingredient getIngredient() {
        return ingredient;
    }

    /** 辅料，消耗 {@link #sideCount}。 */
    public Ingredient getSide() {
        return side;
    }

    public int getSideCount() {
        return sideCount;
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
        ItemStack toolStack = wrapper.getItem(0);
        ItemStack a = wrapper.getItem(1);
        ItemStack b = wrapper.getItem(2);
        if (toolStack.isEmpty() || a.isEmpty() || b.isEmpty()) {
            return false;
        }
        if (!tool.test(toolStack)) {
            return false;
        }
        return (ingredient.test(a) && side.test(b)) || (ingredient.test(b) && side.test(a));
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
        return UniversalCuttingMachine.SKEWERING_RECIPE_SERIALIZER.get();
    }

    @Override
    public @NotNull RecipeType<?> getType() {
        return UniversalCuttingMachine.SKEWERING_RECIPE_TYPE.get();
    }

    @Override
    public @NotNull NonNullList<Ingredient> getIngredients() {
        NonNullList<Ingredient> list = NonNullList.create();
        list.add(tool);
        list.add(ingredient);
        list.add(side);
        return list;
    }

    public static class Serializer implements RecipeSerializer<MekCkSkeweringRecipe> {

        @Override
        public @NotNull MekCkSkeweringRecipe fromJson(@NotNull ResourceLocation recipeId, @NotNull JsonObject json) {
            Ingredient tool = json.has("tool")
                    ? Ingredient.fromJson(GsonHelper.getAsJsonObject(json, "tool"))
                    : Ingredient.of(Items.STICK);
            Ingredient ingredient = Ingredient.fromJson(GsonHelper.getAsJsonObject(json, "ingredient"));
            Ingredient side = Ingredient.fromJson(GsonHelper.getAsJsonObject(json, "side"));
            // 签子不消耗，故 toolCount 恒为 0（见类注释的命名陷阱说明）
            int toolCount = 0;
            int sideCount = GsonHelper.getAsInt(json, "sideCount", 1);
            ItemStack result = ShapedRecipe.itemStackFromJson(GsonHelper.getAsJsonObject(json, "result"));
            int time = GsonHelper.getAsInt(json, "processingTime", 0);
            return new MekCkSkeweringRecipe(recipeId, tool, ingredient, side, toolCount, sideCount, result, time);
        }

        @Override
        public @Nullable MekCkSkeweringRecipe fromNetwork(@NotNull ResourceLocation recipeId, @NotNull FriendlyByteBuf buffer) {
            Ingredient tool = Ingredient.fromNetwork(buffer);
            Ingredient ingredient = Ingredient.fromNetwork(buffer);
            Ingredient side = Ingredient.fromNetwork(buffer);
            int toolCount = buffer.readVarInt();
            int sideCount = buffer.readVarInt();
            ItemStack result = buffer.readItem();
            int time = buffer.readVarInt();
            return new MekCkSkeweringRecipe(recipeId, tool, ingredient, side, toolCount, sideCount, result, time);
        }

        @Override
        public void toNetwork(@NotNull FriendlyByteBuf buffer, @NotNull MekCkSkeweringRecipe recipe) {
            recipe.tool.toNetwork(buffer);
            recipe.ingredient.toNetwork(buffer);
            recipe.side.toNetwork(buffer);
            buffer.writeVarInt(recipe.ingredientCount);
            buffer.writeVarInt(recipe.sideCount);
            buffer.writeItem(recipe.result);
            buffer.writeVarInt(recipe.processTime);
        }
    }
}
