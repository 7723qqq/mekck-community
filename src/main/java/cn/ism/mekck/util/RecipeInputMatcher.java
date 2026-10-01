package cn.ism.mekck.util;

import cn.ism.mekck.UniversalCuttingMachine;
import cn.ism.mekck.recipe.FerreroRecipe;
import cn.ism.mekck.recipe.IceMakeRecipe;
import cn.ism.mekck.recipe.NutRoastingRecipe;
import cn.ism.mekck.recipe.PlantingCuttingRecipe;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;
import net.minecraftforge.registries.ForgeRegistries;
import vectorwing.farmersdelight.common.crafting.CookingPotRecipe;
import vectorwing.farmersdelight.common.registry.ModRecipeTypes;

import java.util.List;
import cn.ism.mekck.registry.MekCkRecipeTypes;

/**
 * 机器输入 / 额外输入格的配方匹配过滤：
 * 输入格只接受「该机器配方类型的任一配方成分」能匹配的物品（见文档 12.5 / isItemValid 各机器接线）。
 * <p>
 * {@code level == null}（世界加载早期）时放行，避免误拒；配方管理器在客户端同样可用（配方会同步）。
 * BBQ / KC / 无尽乐事等外部配方类型通过注册表或兼容工具类按 id 查询。
 * </p>
 */
public final class RecipeInputMatcher {

    private static final ResourceLocation GRILLING_TYPE_ID = new ResourceLocation("barbequesdelight", "grilling");
    private static final ResourceLocation SKEWERING_TYPE_ID = new ResourceLocation("barbequesdelight", "skewering");
    private static final ResourceLocation EXTREME_COOKING_SHAPED_ID = new ResourceLocation("avaritia_delight", "extreme_cooking_shaped");
    private static final ResourceLocation EXTREME_COOKING_SHAPELESS_ID = new ResourceLocation("avaritia_delight", "extreme_cooking_shapeless");

    private RecipeInputMatcher() {
    }

    /** 通用：遍历某配方类型的全部配方，任一成分匹配该物品即通过。 */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static boolean matchesAnyIngredient(Level level, RecipeType<?> type, ItemStack stack) {
        if (type == null) {
            return false;
        }
        for (Recipe<?> recipe : cn.ism.mekck.util.RecipeCache.all(level, type)) {
            for (Ingredient ingredient : recipe.getIngredients()) {
                if (ingredient.test(stack)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 网络拉料辅助：某配方类型可处理的所有首个成分的并集 Ingredient（用于空输入槽拉料）。 */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static Ingredient unionFirstIngredients(Level level, ResourceLocation typeId) {
        RecipeType<?> type = cn.ism.mekck.util.RecipeCache.type(typeId);
        if (type == null) return Ingredient.EMPTY;
        java.util.List<Ingredient> all = new java.util.ArrayList<>();
        for (Recipe<?> recipe : cn.ism.mekck.util.RecipeCache.all(level, type)) {
            List<Ingredient> ings = recipe.getIngredients();
            if (!ings.isEmpty() && !ings.get(0).isEmpty()) {
                all.add(ings.get(0));
            }
        }
        return all.isEmpty() ? Ingredient.EMPTY : Ingredient.merge(all);
    }

    /** 农夫乐事切割（通用切菜机 / 切菜工厂）。 */
    public static boolean matchesCutting(Level level, ItemStack stack) {
        if (level == null) return true;
        return matchesAnyIngredient(level, ModRecipeTypes.CUTTING.get(), stack);
    }

    /** 烹饪（智能厨锅 / 烹饪工厂）：农夫乐事 cooking + 森罗物语 pot/stockpot（若装）。 */
    public static boolean matchesCooking(Level level, ItemStack stack) {
        if (level == null) return true;
        for (CookingPotRecipe recipe : (java.util.List<CookingPotRecipe>) (java.util.List<?>) cn.ism.mekck.util.RecipeCache.all(level, ModRecipeTypes.COOKING.get())) {
            for (Ingredient ingredient : recipe.getIngredients()) {
                if (ingredient.test(stack)) {
                    return true;
                }
            }
        }
        if (KaleidoscopeCompat.isLoaded()) {
            for (Recipe<?> recipe : KaleidoscopeCompat.getAllKaleidoscopeRecipes(level)) {
                if (!(KaleidoscopeCompat.isStockpotRecipe(recipe) || KaleidoscopeCompat.isPotRecipe(recipe))) {
                    continue;
                }
                for (Ingredient ingredient : KaleidoscopeCompat.getSolidIngredients(recipe)) {
                    if (ingredient.test(stack)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** 无尽乐事终焉烹饪（仅奇点创世烹饪工厂调用）。 */
    public static boolean matchesExtremeCooking(Level level, ItemStack stack) {
        if (level == null) return true;
        return matchesAnyIngredient(level, cn.ism.mekck.util.RecipeCache.type(EXTREME_COOKING_SHAPED_ID), stack)
                || matchesAnyIngredient(level, cn.ism.mekck.util.RecipeCache.type(EXTREME_COOKING_SHAPELESS_ID), stack);
    }

    /** 烧烤（电力烧烤架 / 烧烤工厂）：mekck:grilling 优先，回落 barbequesdelight:grilling。 */
    public static boolean matchesGrilling(Level level, ItemStack stack) {
        if (level == null) return true;
        if (matchesAnyIngredient(level, MekCkRecipeTypes.GRILLING_RECIPE_TYPE.get(), stack)) {
            return true;
        }
        return matchesAnyIngredient(level, cn.ism.mekck.util.RecipeCache.type(GRILLING_TYPE_ID), stack);
    }

    /** 原版食物类烹饪配方（烟熏炉 / 篝火）——烧烤工厂全档位可处理。 */
    private static final RecipeType<?>[] FOOD_COOKING_TYPES = {
            RecipeType.SMOKING, RecipeType.CAMPFIRE_COOKING
    };

    /** 原版熔炼类配方（熔炉 / 高炉）——仅晶钛矩阵以上烧烤工厂额外可处理。 */
    private static final RecipeType<?>[] FURNACE_FAMILY_TYPES = {
            RecipeType.SMELTING, RecipeType.BLASTING
    };

    /** 烟熏炉 / 篝火烹饪配方是否可处理该物品。全档位有效，不受档位或配置门禁约束。 */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static boolean matchesFoodCooking(Level level, ItemStack stack) {
        if (level == null) return true;
        for (RecipeType<?> type : FOOD_COOKING_TYPES) {
            for (Recipe<?> recipe : cn.ism.mekck.util.RecipeCache.all(level, type)) {
                List<Ingredient> ingredients = recipe.getIngredients();
                if (!ingredients.isEmpty() && ingredients.get(0).test(stack)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 熔炉 / 高炉 两类原版熔炼配方是否可处理该物品（不含烟熏炉 / 篝火）。 */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static boolean matchesFurnaceFamily(Level level, ItemStack stack) {
        if (level == null) return true;
        for (RecipeType<?> type : FURNACE_FAMILY_TYPES) {
            for (Recipe<?> recipe : cn.ism.mekck.util.RecipeCache.all(level, type)) {
                List<Ingredient> ingredients = recipe.getIngredients();
                if (!ingredients.isEmpty() && ingredients.get(0).test(stack)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 沉浸农艺厨锅（farm_and_charm:pot_cooking）。 */
    private static final ResourceLocation FARM_CHARM_POT_COOKING_ID = new ResourceLocation("farm_and_charm", "pot_cooking");

    /** 沉浸农艺 pot_cooking 配方（厨锅/烹饪工厂扩展支持）。 */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static List<Recipe<?>> getPotCookingRecipes(Level level) {
        RecipeType<?> type = cn.ism.mekck.util.RecipeCache.type(FARM_CHARM_POT_COOKING_ID);
        if (type == null) return List.of();
        return cn.ism.mekck.util.RecipeCache.all(level, type);
    }

    /** 是否沉浸农艺 pot_cooking 配方。 */
    public static boolean isPotCookingRecipe(Recipe<?> recipe) {
        return recipe != null && ForgeRegistries.RECIPE_TYPES.getKey(recipe.getType()) != null
                && FARM_CHARM_POT_COOKING_ID.equals(ForgeRegistries.RECIPE_TYPES.getKey(recipe.getType()));
    }

    /** 输入格判定：沉浸农艺厨锅配方的任一成分。 */
    public static boolean matchesPotCooking(Level level, ItemStack stack) {
        if (level == null) return true;
        RecipeType<?> type = cn.ism.mekck.util.RecipeCache.type(FARM_CHARM_POT_COOKING_ID);
        return type != null && matchesAnyIngredient(level, type, stack);
    }

    /** 穿串（智能穿串机 / 穿串工厂）：mekck:skewering 优先，回落 barbequesdelight:skewering。 */
    public static boolean matchesSkewering(Level level, ItemStack stack) {
        if (level == null) return true;
        if (matchesAnyIngredient(level, MekCkRecipeTypes.SKEWERING_RECIPE_TYPE.get(), stack)) {
            return true;
        }
        return matchesAnyIngredient(level, cn.ism.mekck.util.RecipeCache.type(SKEWERING_TYPE_ID), stack);
    }

    /** 石磨研磨（电力研磨机 / 研磨工厂）：kaleidoscope_cookery:millstone（未装森罗时无配方，全部拒绝）。 */
    public static boolean matchesMillstone(Level level, ItemStack stack) {
        if (level == null) return true;
        if (!KaleidoscopeCompat.isLoaded()) {
            return false;
        }
        return KaleidoscopeCompat.findMillstoneRecipe(level, stack).isPresent();
    }

    /** 制冰（急冻制冰机 / 制冰工厂）。 */
    public static boolean matchesIceMake(Level level, ItemStack stack) {
        if (level == null) return true;
        for (IceMakeRecipe recipe : (java.util.List<IceMakeRecipe>) (java.util.List<?>) cn.ism.mekck.util.RecipeCache.all(level, MekCkRecipeTypes.ICE_MAKE_RECIPE_TYPE.get())) {
            if (recipe.getIngredient().test(stack)) {
                return true;
            }
        }
        return false;
    }

    /** 炒坚果（坚果爆炒机）。 */
    public static boolean matchesNutRoasting(Level level, ItemStack stack) {
        if (level == null) return true;
        for (NutRoastingRecipe recipe : (java.util.List<NutRoastingRecipe>) (java.util.List<?>) cn.ism.mekck.util.RecipeCache.all(level, MekCkRecipeTypes.NUT_ROASTING_RECIPE_TYPE.get())) {
            if (recipe.getIngredient().test(stack)) {
                return true;
            }
        }
        return false;
    }

    /** 费列罗配方物品输入（巧克力大炮输入格）。 */
    public static boolean matchesFerreroInput(Level level, ItemStack stack) {
        if (level == null) return true;
        for (FerreroRecipe recipe : (java.util.List<FerreroRecipe>) (java.util.List<?>) cn.ism.mekck.util.RecipeCache.all(level, MekCkRecipeTypes.FERRERO_RECIPE_TYPE.get())) {
            if (recipe.getInput().test(stack)) {
                return true;
            }
        }
        return false;
    }

    /** 费列罗配方 extra 输入（巧克力大炮 extra 格）。 */
    public static boolean matchesFerreroExtra(Level level, ItemStack stack) {
        if (level == null) return true;
        for (FerreroRecipe recipe : (java.util.List<FerreroRecipe>) (java.util.List<?>) cn.ism.mekck.util.RecipeCache.all(level, MekCkRecipeTypes.FERRERO_RECIPE_TYPE.get())) {
            if (recipe.getExtra().test(stack)) {
                return true;
            }
        }
        return false;
    }

    /** 种植切配种子（种植切配站 / 种植切配工厂种子格）。 */
    public static boolean matchesPlantingSeed(Level level, ItemStack stack) {
        if (level == null) return true;
        for (PlantingCuttingRecipe recipe : (java.util.List<PlantingCuttingRecipe>) (java.util.List<?>) cn.ism.mekck.util.RecipeCache.all(level, MekCkRecipeTypes.PLANTING_CUTTING_RECIPE_TYPE.get())) {
            if (recipe.getSeed().test(stack)) {
                return true;
            }
        }
        return false;
    }

    /** 生物反应堆输入：任何可转化为有机物流体的物品（规则 C &gt; A &gt; B）。 */
    public static boolean matchesBioreactorFuel(Level level, ItemStack stack) {
        if (level == null) return true;
        return BioreactorFuels.getMBPerUnit(stack, level) > 0;
    }
}
