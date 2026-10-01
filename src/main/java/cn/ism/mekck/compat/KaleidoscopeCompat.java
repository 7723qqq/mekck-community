package cn.ism.mekck.compat;

import net.minecraft.core.NonNullList;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;
import net.minecraftforge.fml.ModList;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;
import java.util.ArrayList;
import java.util.List;

/**
 * 森罗物语厨房 (kaleidoscope_cookery) 的**运行时可选联动**。
 *
 * 关键实现：所有对森罗类的直接引用都被隔离在 {@link Bridge}
 * 私有静态内部类里。由于 Java 的"首次使用才加载内部类"规则，外层的
 * {@link #isLoaded()} 等方法不会触发 {@link Bridge} 的类加载，
 * 因此当森罗物语未安装时，调用方只要先 {@link #isLoaded()} 守卫，
 * 就**不会**触发 NoClassDefFoundError。
 *
 * 外层对森罗类的签名一律使用 Recipe<?> / Ingredient / Object，
 * 从不直接 import 森罗类。编译时需要森罗 jar（compileOnly fg.deobf）
 * 只是为了 {@link Bridge} 在编译期间能引用其字段。
 */
public final class KaleidoscopeCompat {

    private KaleidoscopeCompat() {
    }

    public static final String MOD_ID = "kaleidoscope_cookery";

    public static boolean isLoaded() {
        return ModList.get().isLoaded(MOD_ID);
    }

    // ================================================================
    //   外部 API（全部先 isLoaded 检查再进入 Bridge）
    // ================================================================

    /** 收集全部 4 类森罗配方（汤锅+炒锅，各自 normal/flex）。未安装返回空列表。 */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static List<Recipe<?>> getAllKaleidoscopeRecipes(Level level) {
        if (!isLoaded()) return List.of();
        List<Recipe<?>> all = new ArrayList<>();
        RecipeType stockpot = Bridge.STOCKPOT;
        RecipeType flexStockpot = Bridge.FLEX_STOCKPOT;
        RecipeType pot = Bridge.POT;
        RecipeType flexPot = Bridge.FLEX_POT;
        all.addAll(cn.ism.mekck.util.RecipeCache.all(level, stockpot));
        all.addAll(cn.ism.mekck.util.RecipeCache.all(level, flexStockpot));
        all.addAll(cn.ism.mekck.util.RecipeCache.all(level, pot));
        all.addAll(cn.ism.mekck.util.RecipeCache.all(level, flexPot));
        return all;
    }

    /**
     * 返回森罗 4 类配方的 {@link ResourceLocation}（顺序与 {@link Bridge}
     * 字段一致：stockpot、flex_stockpot、pot、flex_pot）。未安装返回空列表。
     * 供 JEI 集成构造 RecipeType.catalyst 使用，不引入 JEI 依赖。
     */
    @SuppressWarnings("rawtypes")
    public static List<ResourceLocation> getKaleidoscopeRecipeTypeIds() {
        if (!isLoaded()) return List.of();
        List<ResourceLocation> ids = new ArrayList<>(4);
        ids.add(registryId(Bridge.STOCKPOT));
        ids.add(registryId(Bridge.FLEX_STOCKPOT));
        ids.add(registryId(Bridge.POT));
        ids.add(registryId(Bridge.FLEX_POT));
        return ids;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ResourceLocation registryId(RecipeType type) {
        ResourceLocation id = net.minecraftforge.registries.ForgeRegistries.RECIPE_TYPES.getKey(type);
        return id != null ? id : new ResourceLocation(MOD_ID, "unknown");
    }


    // ================================================================
    //   石磨 (Millstone) 配方支持 —— 电力研磨机/研磨工厂
    // ================================================================

    /** 石磨配方的单个随机产出（stack + 独立判定概率）。 */
    public record MillstoneOutput(ItemStack stack, float chance) {}

    /** 是否为石磨配方。 */
    public static boolean isMillstoneRecipe(Recipe<?> recipe) {
        return isLoaded() && Bridge.isMillstone(recipe);
    }

    /** 收集全部石磨配方。未安装返回空列表。 */
    @SuppressWarnings("rawtypes")
    public static List<Recipe<?>> getAllMillstoneRecipes(Level level) {
        if (!isLoaded()) return List.of();
        return new ArrayList<>(cn.ism.mekck.util.RecipeCache.all(level, Bridge.MILLSTONE));
    }

    /** 查找匹配输入物品的石磨配方（无则 Optional.empty）。 */
    public static Optional<Recipe<?>> findMillstoneRecipe(Level level, ItemStack stack) {
        if (!isLoaded()) return Optional.empty();
        return Bridge.findMillstone(level, stack).map(r -> (Recipe<?>) r);
    }

    /**
     * 取出石磨配方的随机产出列表（已过滤空产出）。
     * 每个产出独立按 chance 判定，与原版石磨行为一致。
     */
    public static List<MillstoneOutput> getMillstoneOutputs(Recipe<?> recipe) {
        if (!isLoaded() || !isMillstoneRecipe(recipe)) return List.of();
        return Bridge.millstoneOutputs(recipe);
    }

    public static boolean isStockpotRecipe(Recipe<?> recipe) {
        return isLoaded() && Bridge.isStockpot(recipe);
    }
    public static boolean isPotRecipe(Recipe<?> recipe) {
        return isLoaded() && Bridge.isPot(recipe);
    }
    public static int getCookTime(Recipe<?> recipe) {
        return isLoaded() ? Bridge.cookTime(recipe) : -1;
    }

    /** 取出配方的非空食材 Ingredient。 */
    public static List<Ingredient> getSolidIngredients(Recipe<?> recipe) {
        if (!isLoaded()) return List.of();
        NonNullList<Ingredient> ings = recipe.getIngredients();
        List<Ingredient> out = new ArrayList<>(ings.size());
        for (Ingredient ing : ings) if (ing != null && !ing.isEmpty()) out.add(ing);
        return out;
    }

    /** 取 carrier（容器）Ingredient。未安装或非森罗配方返回 Ingredient.EMPTY。 */
    public static Ingredient getCarrier(Recipe<?> recipe) {
        return isLoaded() ? Bridge.carrier(recipe) : Ingredient.EMPTY;
    }

    /** carrier 中是否已显式列出 minecraft:bowl（此时"不用额外增加"一份）。 */
    public static boolean carrierIncludesBowl(Ingredient carrier) {
        if (carrier == null || carrier.isEmpty()) return false;
        for (ItemStack stack : carrier.getItems()) {
            if (stack.is(net.minecraft.world.item.Items.BOWL)) return true;
        }
        return false;
    }

    /** 炒锅 (pot / flex_pot) 需要额外消耗一份 oil。 */
    public static boolean needsOil(Recipe<?> recipe) {
        return isLoaded() && Bridge.isPot(recipe);
    }

    /** 返回 kaleidoscope_cookery:oil 对应 tag 或 fallback 物品的 ingredient。 */
    public static Ingredient getOilIngredient() {
        if (!isLoaded()) return Ingredient.EMPTY;
        return Bridge.oilIngredient();
    }

    /**
     * 根据用户说明，返回"需要**额外**再消耗的材料列表"：
     *   - 森罗 Stockpot：carrier（若不是 EMPTY 且不包含 bowl）——
     *     即若 carrier 已直接把 bowl 写入材料则不重复附加（但是要消耗）。
     *   - 森罗 Pot：carrier 同上；且无条件再附加 1 份 oil。
     */
    public static List<Ingredient> getExtraConsumables(Recipe<?> recipe) {
        List<Ingredient> extras = new ArrayList<>(2);
        if (!isLoaded()) return extras;
        Ingredient carrier = getCarrier(recipe);
        if (!carrier.isEmpty() && !carrierIncludesBowl(carrier)) {
            extras.add(carrier);
        }
        if (needsOil(recipe)) {
            Ingredient oil = getOilIngredient();
            if (!oil.isEmpty()) extras.add(oil);
        }
        return extras;
    }

    /** 判定配方是森罗（汤锅/炒锅任意一种） */
    public static boolean isKaleidoscopeRecipe(Recipe<?> r) {
        return isStockpotRecipe(r) || isPotRecipe(r);
    }

    // ================================================================
    //   Bridge：真实引用森罗类的所有代码都在这里，延迟到首次访问才加载
    //   这里的 RecipeType 字段使用 raw type，规避外层泛型擦除导致的
    //   RecipeManager.getAllRecipesFor(通配符泛型) 签名冲突编译错误。
    // ================================================================
    @SuppressWarnings({"rawtypes"})
    private static final class Bridge {
        static final RecipeType MILLSTONE;

        static final RecipeType STOCKPOT;
        static final RecipeType FLEX_STOCKPOT;
        static final RecipeType POT;
        static final RecipeType FLEX_POT;

        static {
            try {
                Class<?> mr = Class.forName("com.github.ysbbbbbb.kaleidoscopecookery.init.ModRecipes");
                STOCKPOT       = (RecipeType) mr.getField("STOCKPOT_RECIPE").get(null);
                MILLSTONE       = (RecipeType) mr.getField("MILLSTONE_RECIPE").get(null);
                FLEX_STOCKPOT  = (RecipeType) mr.getField("FLEX_STOCKPOT_RECIPE").get(null);
                POT            = (RecipeType) mr.getField("POT_RECIPE").get(null);
                FLEX_POT       = (RecipeType) mr.getField("FLEX_POT_RECIPE").get(null);
            } catch (Exception e) {
                throw new RuntimeException("KaleidoscopeCookery is loaded but cannot access ModRecipes", e);
            }
        }

        static boolean isStockpot(Recipe<?> r) {
            String n = r.getClass().getName();
            return "com.github.ysbbbbbb.kaleidoscopecookery.crafting.recipe.StockpotRecipe".equals(n)
                    || "com.github.ysbbbbbb.kaleidoscopecookery.crafting.recipe.FlexStockpotRecipe".equals(n);
        }

        static boolean isPot(Recipe<?> r) {
            String n = r.getClass().getName();
            return "com.github.ysbbbbbb.kaleidoscopecookery.crafting.recipe.PotRecipe".equals(n)
                    || "com.github.ysbbbbbb.kaleidoscopecookery.crafting.recipe.FlexPotRecipe".equals(n);
        }

        static int cookTime(Recipe<?> r) {
            try {
                return (int) cn.ism.mekck.util.Reflect.call(r, "time");
            } catch (Exception e) { return -1; }
        }

        static Ingredient carrier(Recipe<?> r) {
            try {
                Object c = cn.ism.mekck.util.Reflect.call(r, "carrier");
                if (c instanceof Ingredient ing) return ing;
            } catch (Exception ignored) {}
            return Ingredient.EMPTY;
        }

        /**
         * 解析"食用油" ingredient：
         * 1) 优先 #kaleidoscope_cookery:oil 标签（TagMod 位于 init.tag 包，
         *    与原版炒锅 PotBlockEntity 的 stack.is(TagMod.OIL) 判定一致）；
         * 2) 回退 ModItems.OIL（食用油物品本身）；
         * 3) 最后回退 ModItems.OIL_POT（油壶）。
         */
        @SuppressWarnings("unchecked")
        static Ingredient oilIngredient() {
            try {
                Class<?> tagMod = Class.forName("com.github.ysbbbbbb.kaleidoscopecookery.init.tag.TagMod");
                var tagKey = (net.minecraft.tags.TagKey<Item>) tagMod.getField("OIL").get(null);
                return Ingredient.of(tagKey);
            } catch (Throwable ignored) {}
            List<Item> fallbacks = new ArrayList<>(2);
            for (String field : new String[]{"OIL", "OIL_POT"}) {
                try {
                    Class<?> mi = Class.forName("com.github.ysbbbbbb.kaleidoscopecookery.init.ModItems");
                    Object o = mi.getField(field).get(null);
                    if (o instanceof net.minecraftforge.registries.RegistryObject ro) {
                        Item item = (Item) ro.get();
                        if (item != null) fallbacks.add(item);
                    }
                } catch (Throwable ignored) {}
            }
            if (fallbacks.isEmpty()) return Ingredient.EMPTY;
            return Ingredient.of(fallbacks.toArray(new Item[0]));
        }

        static boolean isMillstone(Recipe<?> r) {
            return "com.github.ysbbbbbb.kaleidoscopecookery.crafting.recipe.MillstoneRecipe".equals(r.getClass().getName());
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        static Optional<Recipe<?>> findMillstone(Level level, ItemStack stack) {
            net.minecraft.world.SimpleContainer container = new net.minecraft.world.SimpleContainer(stack);
            Optional<Recipe> found = (Optional) level.getRecipeManager().getRecipeFor(MILLSTONE, container, level);
            return found.map(r -> (Recipe<?>) r);
        }

        /** 反射读取 MillstoneRecipe.results() -> List<RandomOutput(stack, chance)>。 */
        static List<MillstoneOutput> millstoneOutputs(Recipe<?> r) {
            try {
                Object results = cn.ism.mekck.util.Reflect.call(r, "results");
                if (results instanceof List<?> list) {
                    List<MillstoneOutput> out = new ArrayList<>(list.size());
                    for (Object o : list) {
                        if (o == null) continue;
                        Object stack = cn.ism.mekck.util.Reflect.call(o, "stack");
                        Object chance = cn.ism.mekck.util.Reflect.call(o, "chance");
                        if (stack instanceof ItemStack is && chance instanceof Float f && !is.isEmpty()) {
                            out.add(new MillstoneOutput(is.copy(), f));
                        }
                    }
                    return out;
                }
            } catch (Exception ignored) {}
            return List.of();
        }
    }
}