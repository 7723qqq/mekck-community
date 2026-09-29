package cn.ism.mekck.recipe;

import cn.ism.mekck.UniversalCuttingMachine;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.NonNullList;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
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
 * 智能萃取机专属配方 {@code mekck:extracting}（§F19 C+E 半：原创多入、双产物形态类型）。
 * <p>
 * 语义：1..5 种物品输入 + 可选流体输入 → <b>流体产物（进 outputTank）或物品产物（进产物槽 5），二选一</b>。
 * 首两条配方：榛子可可酱（巧克力 tag + 榛子粉 + 糖 → 酱 50 mB，无流体输入）、
 * 本土巧克力（糖 + 可可豆 + {@code #forge:milk} 250 mB → {@code mekck:chocolate_bar} 1）。
 * </p>
 * <p>
 * JSON：{@code { "ingredients": [ ... ], "fluid": { "fluid": "<id 或 #tag>", "amount": N },
 * "result": {物品} | "fluidResult": { "fluid": "<id>", "amount": N }, "processingTime": T }}；
 * {@code fluid} 字段可省略（无流体输入）；{@code result} 与 {@code fluidResult} 必须且只能给出一个。
 * 流体输入支持 {@code #} 前缀的流体 tag（如 {@code #forge:milk}，熊猫奶/模组奶皆可）。
 * 罐校验/消耗由方块实体（{@code SimpleMachineBlockEntity#matchExtracting}）完成，本类只管数据。
 * </p>
 */
public class ExtractingRecipe implements Recipe<RecipeWrapper> {

    private final ResourceLocation id;
    private final List<Ingredient> ingredients;
    /** 输入流体（tag 或精确 id + 数量）；null = 无流体输入。 */
    @Nullable
    private final FluidInput inputFluid;
    private final ItemStack result;
    private final FluidStack fluidResult;
    private final int processTime;

    /** 输入流体需求：精确 id 或 {@code #tag} 二选一 + 数量。 */
    public static final class FluidInput {
        public final String idOrTag;
        public final int amount;

        FluidInput(String idOrTag, int amount) {
            this.idOrTag = idOrTag;
            this.amount = amount;
        }

        /**
         * 给定流体是否满足本需求（tag 走 Forge 注册表 tags()，读不到 tag 时保守不匹配）。
         *
         * <p><b>「读不到 tag」必须真的不匹配</b>，而这正是原实现没做到的：
         * {@code ITagManager#getTag(TagKey)} 标注 {@code @Nullable}（{@code TagManager}
         * 的实现就是 {@code return this.tags.get(key)}），而代码写的是
         * {@code manager != null && manager.getTag(tagId).contains(fluid)} ——
         * {@code manager} 恒非 null（{@code ForgeRegistries.FLUIDS.tags()} 从不返回 null），
         * 所以那道判断<b>完全无效</b>，{@code .contains()} 直接 NPE。</p>
         *
         * <p>危害不是崩服：调用方 {@code SimpleMachineBlockEntity} 用
         * {@code catch (Throwable ignored)} 兜着，于是退化成「带 {@code #tag} 流体输入的
         * {@code mekck:extracting} 配方<b>永远不匹配</b>」—— 静默功能失效。
         * 本类 javadoc 明确宣称支持 {@code #forge:milk} 这类写法，任何数据包一加就中招。</p>
         */
        public boolean matches(@Nullable Fluid fluid) {
            if (fluid == null) {
                return false;
            }
            if (idOrTag.startsWith("#")) {
                TagKey<Fluid> tagId = TagKey.create(Registries.FLUID, new ResourceLocation(idOrTag.substring(1)));
                // tags() 恒非 null，但 getTag(...) 可能是 null —— tag 没被任何注册表绑定时
                // 就是 null。按 javadoc 承诺的「保守不匹配」返回 false。
                var holder = ForgeRegistries.FLUIDS.tags().getTag(tagId);
                return holder != null && holder.contains(fluid);
            }
            ResourceLocation fid = new ResourceLocation(idOrTag);
            return fid.equals(ForgeRegistries.FLUIDS.getKey(fluid));
        }
    }

    public ExtractingRecipe(ResourceLocation id, List<Ingredient> ingredients, @Nullable FluidInput inputFluid,
                            ItemStack result, FluidStack fluidResult, int processTime) {
        this.id = id;
        this.ingredients = ingredients;
        this.inputFluid = inputFluid;
        this.result = result;
        this.fluidResult = fluidResult;
        this.processTime = processTime;
    }

    public List<Ingredient> getItemIngredients() {
        return ingredients;
    }

    @Nullable
    public FluidInput getInputFluid() {
        return inputFluid;
    }

    /** 流体产物（进 outputTank）；物品产物配方时为 EMPTY。与 {@link #hasItemResult()} 互斥。 */
    public FluidStack getFluidResult() {
        return fluidResult;
    }

    public boolean hasItemResult() {
        return !result.isEmpty();
    }

    /** 加工时长（tick）；0 = 用机器基础时长。 */
    public int getProcessTime() {
        return processTime;
    }

    @Override
    public boolean matches(RecipeWrapper wrapper, @NotNull Level level) {
        // 真实匹配（含流体）在方块实体完成；这里仅按物品成分做一次宽松的全命中判断。
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
        return UniversalCuttingMachine.EXTRACTING_RECIPE_SERIALIZER.get();
    }

    @Override
    public @NotNull RecipeType<?> getType() {
        return UniversalCuttingMachine.EXTRACTING_RECIPE_TYPE.get();
    }

    @Override
    public @NotNull NonNullList<Ingredient> getIngredients() {
        NonNullList<Ingredient> list = NonNullList.create();
        list.addAll(ingredients);
        return list;
    }

    public static class Serializer implements RecipeSerializer<ExtractingRecipe> {

        /** 解析流体对象 {@code { "fluid": "<id 或 #tag>", "amount": N }}；amount 缺省 250（对齐 create 曲线）。 */
        private static FluidInput fluidInputFromJson(JsonObject json) {
            String idOrTag = GsonHelper.getAsString(json, "fluid");
            int amount = GsonHelper.getAsInt(json, "amount", 250);
            return new FluidInput(idOrTag, amount);
        }

        @Override
        public @NotNull ExtractingRecipe fromJson(@NotNull ResourceLocation recipeId, @NotNull JsonObject json) {
            List<Ingredient> ingredients = new ArrayList<>();
            JsonArray arr = GsonHelper.getAsJsonArray(json, "ingredients");
            for (int i = 0; i < arr.size(); i++) {
                Ingredient ing = Ingredient.fromJson(arr.get(i));
                if (!ing.isEmpty()) ingredients.add(ing);
            }
            FluidInput input = json.has("fluid") ? fluidInputFromJson(GsonHelper.getAsJsonObject(json, "fluid")) : null;
            boolean hasItem = json.has("result");
            boolean hasFluid = json.has("fluidResult");
            if (hasItem == hasFluid) {
                throw new com.google.gson.JsonSyntaxException(
                        "mekck:extracting 配方必须且只能给出 result（物品）或 fluidResult（流体）之一: " + recipeId);
            }
            ItemStack result = ItemStack.EMPTY;
            FluidStack fluidResult = FluidStack.EMPTY;
            if (hasItem) {
                result = ShapedRecipe.itemStackFromJson(GsonHelper.getAsJsonObject(json, "result"));
            } else {
                FluidInput out = fluidInputFromJson(GsonHelper.getAsJsonObject(json, "fluidResult"));
                Fluid fluid = ForgeRegistries.FLUIDS.getValue(new ResourceLocation(out.idOrTag));
                if (fluid == null) {
                    throw new com.google.gson.JsonSyntaxException("未知产物流体: " + out.idOrTag + " @ " + recipeId);
                }
                fluidResult = new FluidStack(fluid, out.amount);
            }
            int time = GsonHelper.getAsInt(json, "processingTime", 0);
            return new ExtractingRecipe(recipeId, ingredients, input, result, fluidResult, time);
        }

        @Override
        public @Nullable ExtractingRecipe fromNetwork(@NotNull ResourceLocation recipeId, @NotNull FriendlyByteBuf buffer) {
            int n = buffer.readVarInt();
            List<Ingredient> ingredients = new ArrayList<>();
            for (int i = 0; i < n; i++) ingredients.add(Ingredient.fromNetwork(buffer));
            FluidInput input = buffer.readBoolean()
                    ? new FluidInput(buffer.readUtf(), buffer.readVarInt()) : null;
            ItemStack result = buffer.readItem();
            FluidStack fluidResult = buffer.readFluidStack();
            int time = buffer.readVarInt();
            return new ExtractingRecipe(recipeId, ingredients, input, result, fluidResult, time);
        }

        @Override
        public void toNetwork(@NotNull FriendlyByteBuf buffer, @NotNull ExtractingRecipe recipe) {
            buffer.writeVarInt(recipe.ingredients.size());
            for (Ingredient ing : recipe.ingredients) ing.toNetwork(buffer);
            if (recipe.inputFluid != null) {
                buffer.writeBoolean(true);
                buffer.writeUtf(recipe.inputFluid.idOrTag);
                buffer.writeVarInt(recipe.inputFluid.amount);
            } else {
                buffer.writeBoolean(false);
            }
            buffer.writeItem(recipe.result);
            buffer.writeFluidStack(recipe.fluidResult);
            buffer.writeVarInt(recipe.processTime);
        }
    }
}
