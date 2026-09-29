package cn.ism.mekck.kitchen;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;
import net.minecraftforge.items.ItemStackHandler;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.List;

/**
 * 中央厨房的通用配方匹配器：按系列遍历其配方类型，从**存储区**中找出一组满足配方的原料。
 *
 * <p>匹配语义：对配方的 Ingredient 列表做一对一分配（同一存储格可被多个 Ingredient 复用计数），
 * 返回需要消耗的「存储格 + 数量」清单；匹配失败返回 null。</p>
 */
public final class KitchenRecipeMatcher {

    private KitchenRecipeMatcher() {
    }

    /**
     * 一个待执行配方。
     *
     * @param recipeId  配方 id
     * @param family    所属系列
     * @param consumes  需要消耗的 (存储槽索引, 数量) 清单
     * @param outputs   产物（乘以并行份数前的单份产物）
     * @param processTime 单份处理时间（tick）
     */
    public record Match(ResourceLocation recipeId, KitchenFamily family,
                        List<int[]> consumes, List<ItemStack> outputs, int processTime,
                        cn.ism.mekck.util.FluidIngredientHelper.FluidInfo fluidNeed) {
        /** 是否需要消耗流体。 */
        public boolean needsFluid() {
            return fluidNeed != null && !fluidNeed.isEmpty();
        }
    }

    /**
     * 在存储区中查找该系列当前可执行的一个配方。
     *
     * @param level      世界
     * @param family     系列
     * @param items      物品容器（含模块槽 + 存储区 + 输出区）
     * @param storageStart 存储区起始槽
     * @param storageEnd   存储区结束槽
     */
    public static Match find(Level level, KitchenFamily family, ItemStackHandler items,
                             int storageStart, int storageEnd) {
        return find(level, family, items, storageStart, storageEnd, null);
    }

    public static Match find(Level level, KitchenFamily family, ItemStackHandler items,
                             int storageStart, int storageEnd,
                             cn.ism.mekck.util.MultiFluidHandler fluidTank) {
        return find(level, family, items, storageStart, storageEnd, fluidTank, null);
    }

    /**
     * 带**过滤器**的匹配：{@code filter} 非空且启用时，按配方输入材料过滤（只影响自动加工）。
     */
    public static Match find(Level level, KitchenFamily family, ItemStackHandler items,
                             int storageStart, int storageEnd,
                             cn.ism.mekck.util.MultiFluidHandler fluidTank,
                             cn.ism.mekck.kitchen.KitchenFilter filter) {
        if (level == null) return null;
        // ① 烟火（森罗物语）没有原版配方类型，改走兼容层的虚拟配方
        for (Recipe<?> recipe : cn.ism.mekck.util.KaleidoscopeGrillingCompat
                .virtualRecipesForFamily(family.id)) {
            try {
                if (!passesFilter(filter, recipe)) continue;
                Match match = tryMatch(level, family, recipe, items, storageStart, storageEnd, fluidTank);
                if (match != null) return match;
            } catch (Throwable ignored) {
            }
        }
        // ② 常规：按原版配方类型扫描
        for (String typeId : family.recipeTypes) {
            RecipeType<?> type = cn.ism.mekck.util.RecipeCache.type(new ResourceLocation(typeId));
            if (type == null) continue;
            for (Recipe<?> recipe : cn.ism.mekck.util.RecipeCache.all(level, type)) {
                try {
                    if (!passesFilter(filter, recipe)) continue;
                    Match match = tryMatch(level, family, recipe, items, storageStart, storageEnd, fluidTank);
                    if (match != null) return match;
                } catch (Throwable ignored) {
                }
            }
        }
        return null;
    }

    private static Match tryMatch(Level level, KitchenFamily family, Recipe<?> recipe,
                                  ItemStackHandler items, int storageStart, int storageEnd,
                                  cn.ism.mekck.util.MultiFluidHandler fluidTank) {
        List<Ingredient> required = new ArrayList<>();
        for (Ingredient ing : recipe.getIngredients()) {
            if (ing.isEmpty()) continue;
            required.add(ing);
        }
        if (required.isEmpty()) return null;

        // 一对一分配：为每个 Ingredient 找一格存储槽（同格可重复计数）
        List<int[]> consumes = new ArrayList<>();
        List<Integer> usedCount = new ArrayList<>(); // 与 consumes 等长：已占用的该格数量
        for (Ingredient ing : required) {
            boolean matched = false;
            for (int slot = storageStart; slot < storageEnd && !matched; slot++) {
                ItemStack stack = items.getStackInSlot(slot);
                if (stack.isEmpty() || !ing.test(stack)) continue;
                // 该格已分配给其它 Ingredient 的数量
                int already = 0;
                for (int i = 0; i < consumes.size(); i++) {
                    if (consumes.get(i)[0] == slot) already = usedCount.get(i);
                }
                if (stack.getCount() <= already) continue;
                // 复用已有条目或新增
                boolean reused = false;
                for (int i = 0; i < consumes.size(); i++) {
                    if (consumes.get(i)[0] == slot) {
                        consumes.get(i)[1] = already + 1;
                        usedCount.set(i, already + 1);
                        reused = true;
                        break;
                    }
                }
                if (!reused) {
                    consumes.add(new int[]{slot, 1});
                    usedCount.add(1);
                }
                matched = true;
            }
            if (!matched) return null;
        }

        // 流体需求：从配方 Ingredient 中提取水 / 奶（与厨锅同一套分类逻辑）
        cn.ism.mekck.util.FluidIngredientHelper.FluidInfo fluidNeed =
                cn.ism.mekck.util.FluidIngredientHelper.sumFluids(recipe.getIngredients());
        if (!fluidNeed.isEmpty()) {
            if (fluidTank == null) return null;
            if (!fluidTank.hasEnoughOf(true, fluidNeed.waterMb)) return null;
            if (!fluidTank.hasEnoughOf(false, fluidNeed.milkMb)) return null;
        }

        // 产物：收集**全部保证产出**（多产物配方不能只取主产物，否则副产物会丢失）
        List<ItemStack> outputs = collectOutputs(level, recipe);
        if (outputs.isEmpty()) return null;

        return new Match(recipe.getId(), family, consumes, outputs, 200, fluidNeed);
    }

    /**
     * 收集一条配方的**全部保证产出**：
     * <ol>
     *   <li>农夫乐事切割配方（{@code CuttingBoardRecipe}）→ {@code getResults()}（概率 ≥ 100% 的全部产物）；</li>
     *   <li>其它自带 {@code getResults()} 的多产物配方 → 反射取用（兼容各类模组）；</li>
     *   <li>兜底 → {@code getResultItem()}（主产物）。</li>
     * </ol>
     * 只收集**必定产出**，不含概率副产物，以保证订单材料预留与交付数量可预测。
     */
    /** 过滤器判定：未启用过滤时一律放行。 */
    private static boolean passesFilter(cn.ism.mekck.kitchen.KitchenFilter filter, Recipe<?> recipe) {
        if (filter == null || !filter.isFiltering()) return true;
        try {
            return filter.allows(recipe.getIngredients());
        } catch (Throwable t) {
            return true;
        }
    }

    /** 供任务链求解复用的公开入口。 */
    public static List<ItemStack> collectOutputsPublic(Level level, Recipe<?> recipe) {
        return collectOutputs(level, recipe);
    }

    private static List<ItemStack> collectOutputs(Level level, Recipe<?> recipe) {
        List<ItemStack> outputs = new ArrayList<>();
        // ① 农夫乐事切割配方
        try {
            if (recipe instanceof vectorwing.farmersdelight.common.crafting.CuttingBoardRecipe cutting) {
                for (ItemStack stack : cutting.getResults()) {
                    if (stack != null && !stack.isEmpty()) outputs.add(stack.copy());
                }
            }
        } catch (Throwable ignored) {
        }
        // ② 其它模组的多产物配方：反射 getResults()
        if (outputs.isEmpty()) {
            try {
                Object results = cn.ism.mekck.util.Reflect.call(recipe, "getResults");
                if (results instanceof java.util.List<?> list) {
                    for (Object entry : list) {
                        if (entry instanceof ItemStack stack && !stack.isEmpty()) outputs.add(stack.copy());
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        // ③ 兜底主产物
        if (outputs.isEmpty()) {
            try {
                ItemStack main = recipe.getResultItem(level.registryAccess());
                if (!main.isEmpty()) outputs.add(main.copy());
            } catch (Throwable ignored) {
            }
        }
        return outputs;
    }

    /**
     * 从存储区扣除一份配方所需的原料；调用前必须已通过 {@link #find} 校验。
     */
    public static void consume(ItemStackHandler items, List<int[]> consumes) {
        for (int[] c : consumes) {
            items.extractItem(c[0], c[1], false);
        }
    }

    /**
     * 三明治系列专用匹配：从**样品槽**解码有序材料清单，检查存储区是否齐备。
     * 三明治没有配方类型，因此不走配方管理器，直接按样品清单匹配。
     */
    public static Match findSandwich(ItemStackHandler items, int storageStart, int storageEnd,
                                     int sampleSlot) {
        var layers = cn.ism.mekck.blockentity.SandwichAssemblerBlockEntity
                .decodeSample(items.getStackInSlot(sampleSlot));
        if (layers.isEmpty()) return null;

        // 逐层在存储区找精确匹配（isSameItemSameTags）
        List<int[]> consumes = new ArrayList<>();
        List<Boolean> used = new ArrayList<>();
        for (int i = storageStart; i < storageEnd; i++) used.add(false);
        for (ItemStack layer : layers) {
            boolean found = false;
            for (int i = storageStart; i < storageEnd; i++) {
                if (used.get(i - storageStart)) continue;
                ItemStack stack = items.getStackInSlot(i);
                if (stack.isEmpty() || !ItemStack.isSameItemSameTags(stack, layer)) continue;
                used.set(i - storageStart, true);
                consumes.add(new int[]{i, 1});
                found = true;
                break;
            }
            if (!found) return null;
        }
        ItemStack out = cn.ism.mekck.blockentity.SandwichAssemblerBlockEntity.buildSandwich(layers);
        if (out.isEmpty()) return null;
        return new Match(new ResourceLocation("mekck", "sandwich/auto"),
                KitchenFamily.SANDWICH, consumes, List.of(out),
                Math.max(1, layers.size() * cn.ism.mekck.blockentity.SandwichAssemblerBlockEntity.TICKS_PER_LAYER),
                null);
    }

    /** 消耗配方所需的流体（水 / 奶）。 */
    public static void consumeFluid(cn.ism.mekck.util.MultiFluidHandler fluidTank,
                                    cn.ism.mekck.util.FluidIngredientHelper.FluidInfo info) {
        if (fluidTank == null || info == null || info.isEmpty()) return;
        if (info.waterMb > 0) fluidTank.drainOf(true, info.waterMb);
        if (info.milkMb > 0) fluidTank.drainOf(false, info.milkMb);
    }

    /**
     * 产物写入输出区（超大堆叠，按类型归并）。返回未能放入的产物。
     *
     * <h3>⚠️ 归并必须逐格夹紧（本轮修掉的静默销毁整堆物品）</h3>
     * 原实现是
     * <pre>{@code long total = (long) out.getCount() * Math.max(1, multiplier);
     * stack.setCount((int) Math.min(Integer.MAX_VALUE - 1, total));   // 夹了
     * ...
     * existing.grow(stack.getCount());                                  // 没夹 ← 就是这里}</pre>
     * {@code ItemStack.grow(n)} 就是 {@code setCount(getCount() + n)}，而 1.20.1 的
     * {@code ItemStack.setCount} <b>不做任何夹紧</b>。本模组的槽位上限默认就是
     * {@code Integer.MAX_VALUE}（{@code MekckConfig.factorySlotLimit}），
     * 所以 {@code existing.getCount()} 可达 2.1e9，加上 {@code stack.getCount()}
     * （最高 {@code MAX_VALUE - 1}）⇒ <b>必然溢出为负</b>。
     *
     * <p>后果链：{@code count <= 0} ⇒ {@code ItemStack.isEmpty()} 变 true ⇒ 该格被当成空格；
     * 落盘前被读到时 {@code BigStackItemHandler.readStack} 走
     * {@code if (count <= 0) return ItemStack.EMPTY;} ⇒ <b>整堆永久消失</b>。
     * 且 {@code grow()} 不触发 {@code onContentsChanged()}，方块实体可能连
     * {@code setChanged()} 都不会被调到。</p>
     *
     * <p>修法照抄同仓 {@code util/StorageMerger.merge}（那份是本轮复核确认<b>彻底正确</b>的
     * 参照实现）：逐格算剩余空间、只搬得动的量、搬完把剩余量留在参数里交给下一段处理，
     * <b>任何一步都不允许「造出一个新物品」或「吃掉一个已存在的物品」</b>。</p>
     */
    public static List<ItemStack> insertOutputs(ItemStackHandler items, int outputStart, int outputEnd,
                                                List<ItemStack> outputs, int multiplier) {
        List<ItemStack> leftover = new ArrayList<>();
        for (ItemStack out : outputs) {
            long total = (long) out.getCount() * Math.max(1, multiplier);
            ItemStack stack = out.copy();
            stack.setCount((int) Math.min(Integer.MAX_VALUE - 1, total));

            // 第一段：归并到同类槽。**只搬得动的量**，剩余量留在 stack 里。
            for (int slot = outputStart; slot < outputEnd && !stack.isEmpty(); slot++) {
                ItemStack existing = items.getStackInSlot(slot);
                if (existing.isEmpty() || !ItemStack.isSameItemSameTags(existing, stack)) {
                    continue;
                }
                int space = Math.min(items.getSlotLimit(slot), Integer.MAX_VALUE) - existing.getCount();
                if (space <= 0) {
                    continue;
                }
                int moved = Math.min(space, stack.getCount());
                existing.grow(moved);
                items.setStackInSlot(slot, existing);
                stack.shrink(moved);
            }
            // 第二段：剩余量找空格。仍放不下就进 leftover 交还调用方（本方法不吞）。
            for (int slot = outputStart; slot < outputEnd && !stack.isEmpty(); slot++) {
                if (items.getStackInSlot(slot).isEmpty()) {
                    items.setStackInSlot(slot, stack);
                    stack = ItemStack.EMPTY;
                }
            }
            if (!stack.isEmpty()) {
                leftover.add(stack);
            }
        }
        return leftover;
    }
}
