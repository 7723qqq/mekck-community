package cn.ism.mekck.integration;

import cn.ism.mekck.UniversalCuttingMachine;
import mekanism.api.recipes.ingredients.ItemStackIngredient;
import mekanism.common.recipe.impl.CombinerIRecipe;
import mekanism.common.recipe.ingredient.creator.ItemStackIngredientCreator;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

/**
 * 装盘配方的**自动生成**。
 *
 * <p>农夫乐事系的「宴席方块」（FeastBlock）在世界中手持容器右键可多次取餐：容器由
 * {@code servingItem.getCraftingRemainingItem()} 决定（如碗装烤鸡 → 碗），份数由
 * {@code getMaxServings()} 决定（默认 4）。</p>
 *
 * <p>本类在服务器启动时扫描全部已注册方块，为每个宴席方块生成一条 Mekanism 融合机
 * （combining）配方：<b>宴席方块 + 容器 ×(份数 − 方块自身合成已含的容器数) → 碗装食物 ×份数</b>，
 * 例如 {@code 烤鸡块 + 3 碗 → 4 碗装烤鸡}（烤鸡块的合成本身已用掉 1 个碗）。</p>
 *
 * <p>兼容两类方块：① 继承自 {@code vectorwing.farmersdelight.common.block.FeastBlock}；
 * ② 任何自行实现 {@code getServingItem(BlockState)} + {@code getMaxServings()} 的方块（反射识别）。
 * 手写配方优先——同 id 或同「方块 → 产物」组合已存在时跳过。</p>
 */
@Mod.EventBusSubscriber(modid = UniversalCuttingMachine.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class FeastPlatingRecipes {

    private static final org.slf4j.Logger LOGGER =
            org.slf4j.LoggerFactory.getLogger("mekck-plating");

    private static final String FD_MODID = "farmersdelight";
    /** 生成配方的 id 前缀。 */
    private static final String PREFIX = "plating/auto/";

    private FeastPlatingRecipes() {
    }

    /**
     * 挂到数据包重载流程：本监听器排在配方管理器之后，因此执行时配方已加载完毕。
     * 每次 {@code /reload} 也会重新生成，与数据包保持同步。
     */
    /**
     * 在**数据包同步前**注入（玩家加入、/reload 时都会触发）。
     * 该事件正是为「同步前修改数据包内容」设计的，且不属于重载链，
     * 因此不会像自定义重载监听器那样影响世界加载。
     */
    @SubscribeEvent
    public static void onDatapackSync(net.minecraftforge.event.OnDatapackSyncEvent event) {
        try {
            runOnce(event.getPlayerList().getServer().getRecipeManager(),
                    event.getPlayerList().getServer().registryAccess());
        } catch (Throwable t) {
            LOGGER.warn("[MekCK] 自动装盘配方生成失败（已跳过）", t);
        }
    }

    /** 服务器启动时也注入一次，保证没有玩家加入时 JEI / 数据包查询也能看到配方。 */
    @SubscribeEvent
    public static void onServerStarted(net.minecraftforge.event.server.ServerStartedEvent event) {
        try {
            runOnce(event.getServer().getRecipeManager(), event.getServer().registryAccess());
        } catch (Throwable t) {
            LOGGER.warn("[MekCK] 自动装盘配方生成失败（已跳过）", t);
        }
    }

    /** 统一的入口：检查开关与前置，然后注入。 */
    private static void runOnce(RecipeManager manager, RegistryAccess registries) {
        if (manager == null) return;
        if (!ModList.get().isLoaded(FD_MODID)) return;
        if (!cn.ism.mekck.config.MekckConfig.getAutoGeneratePlatingRecipes()) return;
        int added = inject(manager, registries);
        if (added > 0) {
            LOGGER.info("[MekCK] 自动生成 {} 条装盘（融合机）配方", added);
        }
    }

    /**
     * 解析 RecipeManager 的私有字段：优先按给定名字（映射名 → SRG 名），
     * 都找不到时按泛型签名兜底扫描（外层是 Map<RecipeType<?>, Map<...>>，内层是 Map<ResourceLocation, Recipe<?>>）。
     */
    private static Field resolveField(Class<?> owner, String mappedName, String srgName, boolean outer) {
        for (String name : new String[]{mappedName, srgName}) {
            try {
                Field field = owner.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (Throwable ignored) {
            }
        }
        for (Field field : owner.getDeclaredFields()) {
            if (!java.util.Map.class.isAssignableFrom(field.getType())) continue;
            boolean isOuter = field.getGenericType().getTypeName().contains("RecipeType");
            if (isOuter == outer) {
                field.setAccessible(true);
                return field;
            }
        }
        return null;
    }

    /** 扫描并注入；返回新增条数。 */
    private static int inject(RecipeManager manager, RegistryAccess registries) {

        // 运行时字段名是 SRG 名（f_44007_ / f_199900_），开发环境是映射名（recipes / byName），两者都要兼容
        Field recipesField = resolveField(RecipeManager.class, "recipes", "f_44007_", true);
        Field byNameField = resolveField(RecipeManager.class, "byName", "f_199900_", false);
        if (recipesField == null || byNameField == null) {
            LOGGER.warn("[MekCK] 未找到 RecipeManager 的配方表字段，跳过自动装盘配方生成");
            return 0;
        }
        Map<RecipeType<?>, Map<ResourceLocation, Recipe<?>>> byType;
        Map<ResourceLocation, Recipe<?>> byName;
        try {
            @SuppressWarnings("unchecked")
            Map<RecipeType<?>, Map<ResourceLocation, Recipe<?>>> outer =
                    (Map<RecipeType<?>, Map<ResourceLocation, Recipe<?>>>) recipesField.get(manager);
            @SuppressWarnings("unchecked")
            Map<ResourceLocation, Recipe<?>> inner =
                    (Map<ResourceLocation, Recipe<?>>) byNameField.get(manager);
            byType = outer;
            byName = inner;
        } catch (Throwable t) {
            LOGGER.warn("[MekCK] 读取 RecipeManager 配方表失败，跳过自动装盘配方生成", t);
            return 0;
        }
        if (byType == null || byName == null) return 0;

        int added = 0;
        for (Block block : ForgeRegistries.BLOCKS) {
            try {
                ResourceLocation blockId = ForgeRegistries.BLOCKS.getKey(block);
                if (blockId == null) continue;

                BlockState state = block.defaultBlockState();
                ItemStack serving = readServingItem(block, state);
                if (serving.isEmpty()) continue;
                int maxServings = readMaxServings(block);
                if (maxServings <= 1) continue;

                // 容器：碗装食物的“合成剩余物”（如碗装烤鸡 → 碗）；无需容器则跳过
                ItemStack container = serving.getCraftingRemainingItem();
                if (container.isEmpty()) continue;

                // 该方块自身的合成配方里已经含几个容器（通常 1 个碗）
                int bakedIn = countContainerInCrafting(manager, registries, block, container.getItem());
                int needContainers = Math.max(1, maxServings - bakedIn);

                ResourceLocation id = new ResourceLocation(UniversalCuttingMachine.MOD_ID,
                        PREFIX + blockId.getNamespace() + "/" + blockId.getPath());
                if (byName.containsKey(id)) continue;
                if (alreadyConverted(byType, block, serving)) continue;

                ItemStack output = serving.copy();
                output.setCount(maxServings);
                ItemStackIngredient mainInput = ItemStackIngredientCreator.INSTANCE.from(new ItemStack(block));
                ItemStackIngredient extraInput = ItemStackIngredientCreator.INSTANCE.from(
                        new ItemStack(container.getItem()), needContainers);

                CombinerIRecipe recipe = new CombinerIRecipe(id, mainInput, extraInput, output);
                byName.put(id, recipe);
                byType.computeIfAbsent(recipe.getType(), k -> new HashMap<>()).put(id, recipe);
                added++;
            } catch (Throwable ignored) {
            }
        }
        return added;
    }

    /** 读取宴席方块的“份餐物品”：优先 FD FeastBlock，其次反射鸭子类型。 */
    private static ItemStack readServingItem(Block block, BlockState state) {
        // ① FD FeastBlock
        try {
            if (block instanceof vectorwing.farmersdelight.common.block.FeastBlock feast) {
                ItemStack stack = feast.getServingItem(state);
                return stack == null ? ItemStack.EMPTY : stack;
            }
        } catch (Throwable ignored) {
        }
        // ② 反射：getServingItem(BlockState)
        try {
            Method m = block.getClass().getMethod("getServingItem", BlockState.class);
            Object out = m.invoke(block, state);
            return out instanceof ItemStack stack ? stack : ItemStack.EMPTY;
        } catch (Throwable ignored) {
        }
        // ③ 反射：getServingItem()
        try {
            Method m = block.getClass().getMethod("getServingItem");
            Object out = m.invoke(block);
            return out instanceof ItemStack stack ? stack : ItemStack.EMPTY;
        } catch (Throwable ignored) {
        }
        return ItemStack.EMPTY;
    }

    /** 读取最大份数：优先 FD FeastBlock，其次反射。 */
    private static int readMaxServings(Block block) {
        try {
            if (block instanceof vectorwing.farmersdelight.common.block.FeastBlock feast) {
                return feast.getMaxServings();
            }
        } catch (Throwable ignored) {
        }
        try {
            Method m = block.getClass().getMethod("getMaxServings");
            Object out = m.invoke(block);
            if (out instanceof Integer i) return i;
        } catch (Throwable ignored) {
        }
        return 0;
    }

    /** 统计该方块自身的合成配方里含有几个指定容器（用于扣除方块已“自带”的容器）。 */
    private static int countContainerInCrafting(RecipeManager manager, RegistryAccess registries,
                                                Block block, net.minecraft.world.item.Item container) {
        try {
            for (Recipe<?> recipe : manager.getAllRecipesFor(RecipeType.CRAFTING)) {
                ItemStack result;
                try {
                    result = recipe.getResultItem(registries);
                } catch (Throwable t) {
                    continue;
                }
                if (result.isEmpty() || result.getItem() != block.asItem()) continue;
                int count = 0;
                for (Ingredient ing : recipe.getIngredients()) {
                    if (ing.isEmpty()) continue;
                    if (ing.test(new ItemStack(container))) count++;
                }
                if (count > 0) return count;
            }
        } catch (Throwable ignored) {
        }
        return 0;
    }

    /** 是否已存在「同方块 → 同产物」的融合配方（手写配方优先，避免重复）。 */
    private static boolean alreadyConverted(Map<RecipeType<?>, Map<ResourceLocation, Recipe<?>>> byType,
                                            Block block, ItemStack serving) {
        try {
            ItemStack blockStack = new ItemStack(block);
            for (Map<ResourceLocation, Recipe<?>> map : byType.values()) {
                for (Recipe<?> recipe : map.values()) {
                    if (!(recipe instanceof mekanism.api.recipes.CombinerRecipe combiner)) continue;
                    try {
                        if (!combiner.getMainInput().test(blockStack)) continue;
                    } catch (Throwable t) {
                        continue;
                    }
                    for (ItemStack out : combiner.getOutputDefinition()) {
                        if (!out.isEmpty() && out.getItem() == serving.getItem()) return true;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }
}
