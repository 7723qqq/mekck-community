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
import net.minecraft.world.level.material.Fluid;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.items.wrapper.RecipeWrapper;
import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.NotNull;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * 饮品调配机专属配方 {@code mekck:beverage_assembly}（F11 §四.2 · 原创一站式类型）。
 * <p>
 * 语义：{@code 空杯 + 可选小料(物品) + 饮品流体 → 杯装饮品}。由配方线脚本从原 {@code create:filling}
 * 合格集逐条生成，并把两段式杯内加料折进来（{@code boba_cup → empty_boba_cup + boba}、
 * {@code iced_coffee_cup_ice → iced_coffee_cup + ice}），故本类型是调配机唯一读取的配方源
 * （不再运行时读 {@code create:filling}）。
 * </p>
 * <p>
 * JSON：{@code { "ingredients": [ <杯>, <小料>... ], "fluid": { "fluid": "...", "amount": N }, "result": {...} }}；
 * 流体校验/消耗由方块实体在制作前对 {@code inputTank} 完成，本类只管物品成分与产物。
 * </p>
 */
public class BeverageAssemblyRecipe implements Recipe<RecipeWrapper> {

    private final ResourceLocation id;
    private final List<Ingredient> ingredients;
    private final FluidStack fluid;
    private final ItemStack result;
    private final int processTime;

    public BeverageAssemblyRecipe(ResourceLocation id, List<Ingredient> ingredients,
                                  FluidStack fluid, ItemStack result, int processTime) {
        this.id = id;
        this.ingredients = ingredients;
        this.fluid = fluid;
        this.result = result;
        this.processTime = processTime;
    }

    /** 饮品流体需求（固定流体 + mB）。 */
    public FluidStack getFluid() {
        return fluid;
    }

    /** 物品成分清单（首个必为容器/杯，其后为可选小料），每个各消耗 1。 */
    public List<Ingredient> getItemIngredients() {
        return ingredients;
    }

    /** 加工时长（tick）；0 表示用机器基础时长。 */
    public int getProcessTime() {
        return processTime;
    }

    @Override
    public boolean matches(RecipeWrapper wrapper, @NotNull Level level) {
        // 真实匹配在方块实体（含流体）中完成；这里仅按物品成分做一次宽松的全命中判断。
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
        return UniversalCuttingMachine.BEVERAGE_ASSEMBLY_RECIPE_SERIALIZER.get();
    }

    @Override
    public @NotNull RecipeType<?> getType() {
        return UniversalCuttingMachine.BEVERAGE_ASSEMBLY_RECIPE_TYPE.get();
    }

    @Override
    public @NotNull NonNullList<Ingredient> getIngredients() {
        NonNullList<Ingredient> list = NonNullList.create();
        list.addAll(ingredients);
        return list;
    }

    public static class Serializer implements RecipeSerializer<BeverageAssemblyRecipe> {

        private static FluidStack fluidFromJson(JsonObject json) {
            ResourceLocation fluidId = new ResourceLocation(GsonHelper.getAsString(json, "fluid"));
            Fluid fluid = ForgeRegistries.FLUIDS.getValue(fluidId);
            if (fluid == null) {
                throw new com.google.gson.JsonSyntaxException("未知流体: " + fluidId);
            }
            int amount = GsonHelper.getAsInt(json, "amount", 250);
            return new FluidStack(fluid, amount);
        }

        @Override
        public @NotNull BeverageAssemblyRecipe fromJson(@NotNull ResourceLocation recipeId, @NotNull JsonObject json) {
            List<Ingredient> ingredients = new ArrayList<>();
            JsonArray arr = GsonHelper.getAsJsonArray(json, "ingredients");
            for (int i = 0; i < arr.size(); i++) {
                Ingredient ing = Ingredient.fromJson(arr.get(i));
                if (!ing.isEmpty()) ingredients.add(ing);
            }
            FluidStack fluid = fluidFromJson(GsonHelper.getAsJsonObject(json, "fluid"));
            ItemStack result = ShapedRecipe.itemStackFromJson(GsonHelper.getAsJsonObject(json, "result"));
            int time = GsonHelper.getAsInt(json, "processingTime", 0);
            return new BeverageAssemblyRecipe(recipeId, ingredients, fluid, result, time);
        }

        @Override
        public @Nullable BeverageAssemblyRecipe fromNetwork(@NotNull ResourceLocation recipeId, @NotNull FriendlyByteBuf buffer) {
            int n = buffer.readVarInt();
            List<Ingredient> ingredients = new ArrayList<>();
            for (int i = 0; i < n; i++) ingredients.add(Ingredient.fromNetwork(buffer));
            ResourceLocation fluidId = buffer.readResourceLocation();
            Fluid fluid = ForgeRegistries.FLUIDS.getValue(fluidId);
            int amount = buffer.readVarInt();
            FluidStack fluidStack = fluid == null ? FluidStack.EMPTY : new FluidStack(fluid, amount);
            ItemStack result = buffer.readItem();
            int time = buffer.readVarInt();
            return new BeverageAssemblyRecipe(recipeId, ingredients, fluidStack, result, time);
        }

        @Override
        public void toNetwork(@NotNull FriendlyByteBuf buffer, @NotNull BeverageAssemblyRecipe recipe) {
            buffer.writeVarInt(recipe.ingredients.size());
            for (Ingredient ing : recipe.ingredients) ing.toNetwork(buffer);
            buffer.writeResourceLocation(ForgeRegistries.FLUIDS.getKey(recipe.fluid.getFluid()));
            buffer.writeVarInt(recipe.fluid.getAmount());
            buffer.writeItem(recipe.result);
            buffer.writeVarInt(recipe.processTime);
        }
    }
}
