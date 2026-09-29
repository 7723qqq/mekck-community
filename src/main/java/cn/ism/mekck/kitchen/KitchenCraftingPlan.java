package cn.ism.mekck.kitchen;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 中央厨房的订单合成链求解器。
 *
 * <p>给定目标配方与数量，先在**已安装系列**的配方中建立「产物 → 配方」反向索引，
 * 然后递归展开依赖：存储区已有的材料直接用，缺少的材料尝试由其它系列现场合成
 * （例如"牛肉馅"缺失但已装切菜工厂可把"牛肉"切成牛肉馅）。</p>
 *
 * <p>安全约束：递归深度上限（默认 3）、循环依赖检测、全部叶子必须能由存储区满足，
 * 否则整个订单被拒绝（不做部分执行）。</p>
 */
public final class KitchenCraftingPlan {

    /** 任务链中的一个步骤。 */
    public static final class Step {
        public final KitchenFamily family;
        public final ResourceLocation recipeId;
        /** 该步骤需要的材料（每份）与份数。 */
        public final List<ItemStack> inputs;
        public final ItemStack output;
        /** 需要执行的份数（批次数）。 */
        public int batches;
        /** 流体需求（水 / 奶；null 表示不需要流体）。 */
        public final cn.ism.mekck.util.FluidIngredientHelper.FluidInfo fluidNeed;
        /** 同一次加工的**额外保证产出**（副产物，如切割配方的多产物）；不含主产物。 */
        public final List<ItemStack> extraOutputs = new ArrayList<>();

        Step(KitchenFamily family, ResourceLocation recipeId, List<ItemStack> inputs,
             ItemStack output, int batches) {
            this(family, recipeId, inputs, output, batches, null);
        }

        Step(KitchenFamily family, ResourceLocation recipeId, List<ItemStack> inputs,
             ItemStack output, int batches, cn.ism.mekck.util.FluidIngredientHelper.FluidInfo fluidNeed) {
            this.family = family;
            this.recipeId = recipeId;
            this.inputs = inputs;
            this.output = output;
            this.batches = batches;
            this.fluidNeed = fluidNeed;
        }
    }

    /** 求解结果。 */
    public static final class Result {
        /** 任务链（按执行顺序：先中间产物，后最终产物）。 */
        public final List<Step> steps;
        /** 需要从存储区直接取用的叶子材料（物品 → 总数量）。 */
        public final Map<Item, Integer> leaves;
        /** 失败原因（成功时为 null）。 */
        public final String failure;

        Result(List<Step> steps, Map<Item, Integer> leaves, String failure) {
            this.steps = steps;
            this.leaves = leaves;
            this.failure = failure;
        }

        public boolean ok() {
            return failure == null;
        }
    }

    private KitchenCraftingPlan() {
    }

    /** 建立「产物物品 → 可产出它的配方」反向索引（仅限已安装系列）。 */
    public static Map<Item, List<Recipe<?>>> buildReverseIndex(Level level,
                                                              List<KitchenModule.Ability> abilities) {
        Map<Item, List<Recipe<?>>> index = new HashMap<>();
        if (level == null) return index;
        for (KitchenModule.Ability ability : abilities) {
            // 烟火虚拟配方（无原版配方类型）
            for (Recipe<?> recipe : cn.ism.mekck.util.KaleidoscopeGrillingCompat
                    .virtualRecipesForFamily(ability.family().id)) {
                try {
                    ItemStack out = recipe.getResultItem(level.registryAccess());
                    if (out.isEmpty()) continue;
                    index.computeIfAbsent(out.getItem(), k -> new ArrayList<>()).add(recipe);
                } catch (Throwable ignored) {
                }
            }
            for (String typeId : ability.family().recipeTypes) {
                RecipeType<?> type = cn.ism.mekck.util.RecipeCache.type(new ResourceLocation(typeId));
                if (type == null) continue;
                for (Recipe<?> recipe : cn.ism.mekck.util.RecipeCache.all(level, type)) {
                    try {
                        ItemStack out = recipe.getResultItem(level.registryAccess());
                        if (out.isEmpty()) continue;
                        index.computeIfAbsent(out.getItem(), k -> new ArrayList<>()).add(recipe);
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
        return index;
    }

    /**
     * 求解订单：目标配方 × 数量。
     *
     * @param level        世界
     * @param abilities    已安装模块能力
     * @param storageOf    存储区内容快照（物品 → 可用数量）
     * @param target       目标配方
     * @param count        目标份数
     * @param maxDepth     递归深度上限
     */
    public static Result solve(Level level, List<KitchenModule.Ability> abilities,
                               Map<Item, Integer> storageOf, Recipe<?> target, int count, int maxDepth) {
        Map<Item, List<Recipe<?>>> index = buildReverseIndex(level, abilities);
        Map<Item, Integer> available = new HashMap<>(storageOf);
        Map<Item, Integer> leaves = new LinkedHashMap<>();
        List<Step> steps = new ArrayList<>();
        List<Item> path = new ArrayList<>();

        String failure = expand(level, abilities, index, available, leaves, steps, path,
                target, Math.max(1, count), 0, maxDepth);
        if (failure != null) return new Result(List.of(), Map.of(), failure);
        return new Result(steps, leaves, null);
    }

    /**
     * 递归展开一个配方。返回失败原因（null = 成功）。
     *
     * @param available 可用的中间产物/库存（展开过程中会被虚拟消耗）
     */
    private static String expand(Level level, List<KitchenModule.Ability> abilities,
                                 Map<Item, List<Recipe<?>>> index,
                                 Map<Item, Integer> available, Map<Item, Integer> leaves,
                                 List<Step> steps, List<Item> path,
                                 Recipe<?> recipe, int batches, int depth, int maxDepth) {
        ItemStack out;
        try {
            out = recipe.getResultItem(level.registryAccess());
        } catch (Throwable t) {
            return "配方 " + recipe.getId() + " 无法取得产物";
        }
        if (out.isEmpty()) return "配方 " + recipe.getId() + " 无产物";

        KitchenFamily family = familyOf(abilities, recipe);
        if (family == null) return "配方 " + recipe.getId() + " 没有对应的已安装模块";

        List<ItemStack> inputs = new ArrayList<>();
        int perCraft = Math.max(1, out.getCount());
        int need = (int) Math.ceil((double) batches / perCraft);

        for (Ingredient ing : recipe.getIngredients()) {
            if (ing.isEmpty()) continue;
            ItemStack[] matches = ing.getItems();
            if (matches.length == 0) continue;
            ItemStack sample = matches[0].copy();
            sample.setCount(need);
            inputs.add(sample);

            // 优先用可用库存（含已展开出的中间产物）
            int covered = 0;
            for (Map.Entry<Item, Integer> e : available.entrySet()) {
                if (covered >= need) break;
                if (e.getValue() <= 0) continue;
                if (!ing.test(new ItemStack(e.getKey()))) continue;
                int take = Math.min(e.getValue(), need - covered);
                e.setValue(e.getValue() - take);
                covered += take;
            }
            if (covered >= need) continue;

            int missing = need - covered;
            // 缺料：尝试由其它系列合成
            Item missItem = null;
            for (ItemStack m : matches) {
                if (index.containsKey(m.getItem())) { missItem = m.getItem(); break; }
            }
            if (missItem == null) {
                return "缺少材料：" + sample.getHoverName().getString() + " ×" + missing;
            }
            if (depth + 1 > maxDepth) {
                return "合成链超过 " + maxDepth + " 层：" + sample.getHoverName().getString();
            }
            if (path.contains(missItem)) {
                return "检测到循环依赖：" + sample.getHoverName().getString();
            }
            Recipe<?> sub = index.get(missItem).get(0);
            path.add(missItem);
            String err = expand(level, abilities, index, available, leaves, steps, path,
                    sub, missing, depth + 1, maxDepth);
            path.remove(path.size() - 1);
            if (err != null) return err;
            // 展开后该中间产物已进入 available（见下方记录），再取用
            int got = Math.min(available.getOrDefault(missItem, 0), missing);
            if (got < missing) {
                return "缺少材料：" + sample.getHoverName().getString() + " ×" + (missing - got);
            }
            available.put(missItem, available.get(missItem) - missing);
        }

        // 流体需求（水 / 奶）——无法合成，必须由流体罐提供
        cn.ism.mekck.util.FluidIngredientHelper.FluidInfo fluidNeed =
                cn.ism.mekck.util.FluidIngredientHelper.sumFluids(recipe.getIngredients());
        // 记录本步骤（后序：子步骤已在前面入链）
        Step newStep = new Step(family, recipe.getId(), inputs, out.copy(), need,
                fluidNeed.isEmpty() ? null : fluidNeed);
        // 副产物：多产物配方里除主产物外的其余保证产出（按物品去重汇总）
        try {
            for (ItemStack candidate : cn.ism.mekck.kitchen.KitchenRecipeMatcher
                    .collectOutputsPublic(level, recipe)) {
                if (candidate.isEmpty()) continue;
                if (ItemStack.isSameItemSameTags(candidate, out)) continue;
                newStep.extraOutputs.add(candidate.copy());
            }
        } catch (Throwable ignored) {
        }
        steps.add(newStep);
        available.merge(out.getItem(), out.getCount() * need, Integer::sum);
        leaves.merge(out.getItem(), 0, Integer::sum);
        return null;
    }

    /**
     * 配方的类型 id：先查 Forge 的 {@code RECIPE_TYPES}，拿不到再问森罗酒馆门面。
     * <p>
     * 后者必需：该模组的三个 RecipeType 是用 {@code RecipeType.simple()} 造的**匿名对象**、从不进注册表
     * （javap 其 {@code init.ModRecipes} 证实）⇒ {@code getKey(...)} 对它们恒返回 null，按 id 归类的
     * 中央厨房会把酒馆配方当成「无人能处理」（与陈酿机 / 鲜果榨汁机 / 调酒机同一根因）。
     */
    private static ResourceLocation typeId(RecipeType<?> raw) {
        ResourceLocation id = ForgeRegistries.RECIPE_TYPES.getKey(raw);
        return id != null ? id : cn.ism.mekck.util.TavernBarrelCompat.idOf(raw);
    }

    /** 找出能执行该配方的已安装系列。 */
    private static KitchenFamily familyOf(List<KitchenModule.Ability> abilities, Recipe<?> recipe) {
        // 烟火虚拟配方的 getType() 为 null，改用 id 前缀判定归属
        RecipeType<?> rawType = recipe.getType();
        ResourceLocation type = rawType == null ? null : typeId(rawType);
        ResourceLocation recipeId = recipe.getId();
        for (KitchenModule.Ability a : abilities) {
            if (a.family().handles(type)) return a.family();
            if (a.family().handlesVirtualId(recipeId)) return a.family();
        }
        return null;
    }

    /** 从配方重建一个步骤（供订单持久化读取）。 */
    public static Step rebuildStep(Level level, ResourceLocation recipeId, int batches, Recipe<?> recipe) {
        try {
            List<ItemStack> inputs = new ArrayList<>();
            int perCraft = Math.max(1, recipe.getResultItem(level.registryAccess()).getCount());
            int need = (int) Math.ceil((double) Math.max(1, batches) / perCraft);
            for (Ingredient ing : recipe.getIngredients()) {
                if (ing.isEmpty()) continue;
                ItemStack[] matches = ing.getItems();
                if (matches.length == 0) continue;
                ItemStack sample = matches[0].copy();
                sample.setCount(need);
                inputs.add(sample);
            }
            ItemStack output = recipe.getResultItem(level.registryAccess()).copy();
            if (output.isEmpty()) return null;
            KitchenFamily family = null;
            RecipeType<?> rawType = recipe.getType();
            ResourceLocation type = rawType == null ? null : typeId(rawType);
            for (KitchenFamily f : KitchenFamily.values()) {
                if (f.handles(type) || f.handlesVirtualId(recipeId)) { family = f; break; }
            }
            if (family == null) return null;
            cn.ism.mekck.util.FluidIngredientHelper.FluidInfo fluidNeed =
                    cn.ism.mekck.util.FluidIngredientHelper.sumFluids(recipe.getIngredients());
            Step rebuilt = new Step(family, recipeId, inputs, output, need,
                    fluidNeed.isEmpty() ? null : fluidNeed);
            try {
                for (ItemStack candidate : cn.ism.mekck.kitchen.KitchenRecipeMatcher
                        .collectOutputsPublic(level, recipe)) {
                    if (candidate.isEmpty()) continue;
                    if (ItemStack.isSameItemSameTags(candidate, output)) continue;
                    rebuilt.extraOutputs.add(candidate.copy());
                }
            } catch (Throwable ignored) {
            }
            return rebuilt;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 统计存储区内容（物品 → 数量）。 */
    public static Map<Item, Integer> snapshotStorage(net.minecraftforge.items.ItemStackHandler items,
                                                     int start, int end) {
        Map<Item, Integer> map = new LinkedHashMap<>();
        for (int i = start; i < end; i++) {
            ItemStack stack = items.getStackInSlot(i);
            if (stack.isEmpty()) continue;
            map.merge(stack.getItem(), stack.getCount(), cn.ism.mekck.util.CountMath::addClamp);
        }
        return map;
    }
}
