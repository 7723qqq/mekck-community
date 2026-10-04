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
import net.minecraft.world.level.material.Fluid;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.items.wrapper.RecipeWrapper;
import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.NotNull;

import javax.annotation.Nullable;
import cn.ism.mekck.registry.MekCkRecipeTypes;

/**
 * 巧克力大炮使用的 {@code mekck:ferrero} 配方。
 * <p>
 * 输入：物品输入格 {@link #input} + extra 物品输入格 {@link #extra}（每次制作各消耗 1 个），
 * 流体输入 1 {@link #fluid1} 与流体输入 2 {@link #fluid2}（各消耗配方声明的 mB），
 * 产出 {@link #result}。支持数据包扩展。
 * </p>
 * <p>
 * 物品匹配在本类完成（{@link RecipeWrapper} 第 0 格 = 物品输入，第 1 格 = extra 输入）；
 * 流体类型/数量由方块实体在制作前自行校验两个流体罐。
 * </p>
 */
public class FerreroRecipe implements Recipe<RecipeWrapper> {

    private final ResourceLocation id;
    private final Ingredient input;
    private final Ingredient extra;
    private final FluidStack fluid1;
    private final FluidStack fluid2;
    private final ItemStack result;

    public FerreroRecipe(ResourceLocation id, Ingredient input, Ingredient extra,
                         FluidStack fluid1, FluidStack fluid2, ItemStack result) {
        this.id = id;
        this.input = input;
        this.extra = extra;
        this.fluid1 = fluid1;
        this.fluid2 = fluid2;
        this.result = result;
    }

    public Ingredient getInput() {
        return input;
    }

    public Ingredient getExtra() {
        return extra;
    }

    /** 流体输入 1（默认配方为牛奶）。 */
    public FluidStack getFluid1() {
        return fluid1;
    }

    /** 流体输入 2（默认配方为榛子可可酱）。 */
    public FluidStack getFluid2() {
        return fluid2;
    }

    @Override
    public boolean matches(RecipeWrapper wrapper, @NotNull Level level) {
        return input.test(wrapper.getItem(0)) && extra.test(wrapper.getItem(1));
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
        return MekCkRecipeTypes.FERRERO_RECIPE_SERIALIZER.get();
    }

    @Override
    public @NotNull RecipeType<?> getType() {
        return MekCkRecipeTypes.FERRERO_RECIPE_TYPE.get();
    }

    public static class Serializer implements RecipeSerializer<FerreroRecipe> {

        private static FluidStack fluidFromJson(JsonObject json) {
            String rawId = GsonHelper.getAsString(json, "fluid");
            // tryParse 对非法 id 返回 null（不抛异常）——自己抛出，保住「数据包写错了」这条可读信息。
            ResourceLocation fluidId = ResourceLocation.tryParse(rawId);
            if (fluidId == null) {
                throw new com.google.gson.JsonSyntaxException("非法流体 id: " + rawId);
            }
            Fluid fluid = ForgeRegistries.FLUIDS.getValue(fluidId);
            if (fluid == null) {
                throw new com.google.gson.JsonSyntaxException("未知流体: " + fluidId);
            }
            int amount = GsonHelper.getAsInt(json, "amount", 1000);
            return new FluidStack(fluid, amount);
        }

        private static FluidStack fluidFromNetwork(FriendlyByteBuf buffer) {
            ResourceLocation fluidId = buffer.readResourceLocation();
            Fluid fluid = ForgeRegistries.FLUIDS.getValue(fluidId);
            int amount = buffer.readVarInt();
            return fluid == null ? FluidStack.EMPTY : new FluidStack(fluid, amount);
        }

        private static void fluidToNetwork(FriendlyByteBuf buffer, FluidStack stack) {
            buffer.writeResourceLocation(ForgeRegistries.FLUIDS.getKey(stack.getFluid()));
            buffer.writeVarInt(stack.getAmount());
        }

        @Override
        public @NotNull FerreroRecipe fromJson(@NotNull ResourceLocation recipeId, @NotNull JsonObject json) {
            Ingredient input = Ingredient.fromJson(GsonHelper.getAsJsonObject(json, "input"));
            Ingredient extra = Ingredient.fromJson(GsonHelper.getAsJsonObject(json, "extra"));
            FluidStack fluid1 = fluidFromJson(GsonHelper.getAsJsonObject(json, "fluid1"));
            FluidStack fluid2 = fluidFromJson(GsonHelper.getAsJsonObject(json, "fluid2"));
            ItemStack result = ShapedRecipe.itemStackFromJson(GsonHelper.getAsJsonObject(json, "result"));
            return new FerreroRecipe(recipeId, input, extra, fluid1, fluid2, result);
        }

        @Override
        public @Nullable FerreroRecipe fromNetwork(@NotNull ResourceLocation recipeId, @NotNull FriendlyByteBuf buffer) {
            Ingredient input = Ingredient.fromNetwork(buffer);
            Ingredient extra = Ingredient.fromNetwork(buffer);
            FluidStack fluid1 = fluidFromNetwork(buffer);
            FluidStack fluid2 = fluidFromNetwork(buffer);
            ItemStack result = buffer.readItem();
            return new FerreroRecipe(recipeId, input, extra, fluid1, fluid2, result);
        }

        @Override
        public void toNetwork(@NotNull FriendlyByteBuf buffer, @NotNull FerreroRecipe recipe) {
            recipe.input.toNetwork(buffer);
            recipe.extra.toNetwork(buffer);
            fluidToNetwork(buffer, recipe.fluid1);
            fluidToNetwork(buffer, recipe.fluid2);
            buffer.writeItem(recipe.result);
        }
    }
}
