package cn.ism.mekck.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;
import net.minecraftforge.items.ItemStackHandler;
import net.minecraftforge.items.wrapper.RecipeWrapper;
import org.jetbrains.annotations.NotNull;

/**
 * 配方表缓存：把 {@code RecipeManager.getAllRecipesFor(type)} 的结果按「配方管理器实例」缓存。
 * <p>
 * 原版实现每次调用都会 stream+collect 出一个新 List，而模组里有 90+ 处调用点、
 * 其中不少在机器每 tick 的热路径上。这里按管理器实例做缓存：
 * <ul>
 *   <li>数据包重载会创建**新的 RecipeManager 实例** → 键不同即自动失效（弱键，重载后旧表可回收）；</li>
 *   <li>服务端与客户端各有管理器，各缓存一份，互不干扰。</li>
 * </ul>
 * 返回的列表是不可变副本，调用方不得修改（就地过滤请自行复制）。
 * </p>
 */
public final class RecipeCache {

    private static final Map<RecipeManager, Map<RecipeType<?>, List<Recipe<?>>>> CACHE =
            Collections.synchronizedMap(new WeakHashMap<>());

    private RecipeCache() {
    }

    /** 取该类型全部配方（缓存；level 为空返回空表）。 */
    @SuppressWarnings("unchecked")
    public static List<Recipe<?>> all(Level level, RecipeType<?> type) {
        if (level == null || type == null) return List.of();
        RecipeManager manager = level.getRecipeManager();
        synchronized (CACHE) {
            Map<RecipeType<?>, List<Recipe<?>>> byType = CACHE.get(manager);
            if (byType == null) {
                byType = new HashMap<>();
                CACHE.put(manager, byType);
            }
            List<Recipe<?>> cached = byType.get(type);
            if (cached != null) return cached;
            List<Recipe<?>> list = new ArrayList<>((List<Recipe<?>>) (List<?>) manager.getAllRecipesFor((RecipeType) type));
            List<Recipe<?>> immutable = Collections.unmodifiableList(list);
            byType.put(type, immutable);
            return immutable;
        }
    }

    /** 按 "namespace:path" 取该类型全部配方；类型不存在返回空表。 */
    public static List<Recipe<?>> all(Level level, String namespace, String path) {
        // 走 type(...) 而不是直查注册表：否则绕过 {@link TavernBarrelCompat} 那道「未注册类型」兜底。
        RecipeType<?> type = type(namespace, path);
        return type == null ? List.of() : all(level, type);
    }

    // ── 配方类型查找缓存 ──
    // 全模组有 45 处 ForgeRegistries.RECIPE_TYPES.getValue(new ResourceLocation(ns, path))，其中不少在
    // tick 路径上（每次都要新建 ResourceLocation 并做字符串校验 + 注册表查询）。类型注册表在运行期是稳定的，
    // 因此按 id 字符串缓存查找结果，未安装的模组也会缓存"不存在"，避免每 tick 重复构造与查询。
    private static final Map<String, RecipeType<?>> TYPE_CACHE = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Set<String> MISSING_TYPES = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** 查不到的 id 只告警一次（包含门面负责的 id：那些会重试，不加门会每 tick 刷屏）。 */
    private static final java.util.Set<String> MISSED_LOGGED = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();

    /** 按命名空间 + 路径取配方类型（带缓存；不存在返回 null）。 */
    public static RecipeType<?> type(String namespace, String path) {
        return type(namespace + ":" + path);
    }

    /** 按 "namespace:path" 取配方类型（带缓存；不存在返回 null）。 */
    public static RecipeType<?> type(String id) {
        if (id == null) return null;
        RecipeType<?> cached = TYPE_CACHE.get(id);
        if (cached != null) return cached;
        if (MISSING_TYPES.contains(id)) return null;
        int idx = id.indexOf(':');
        if (idx <= 0 || idx == id.length() - 1) return null;
        ResourceLocation rl = new ResourceLocation(id.substring(0, idx), id.substring(idx + 1));
        RecipeType<?> found = net.minecraftforge.registries.ForgeRegistries.RECIPE_TYPES.getValue(rl);
        if (found == null) {
            // 「类型存在但从不进注册表」的第三方模组：按 id 查恒为 null，会让整条配方路径静默当成未安装。
            // 森罗酒馆（kaleidoscope_tavern）的三个类型就是这样（javap 其 init.ModRecipes 证实，见
            // {@link TavernBarrelCompat#typeById}）⇒ 由那个门面反射拿模组自己使用的实例。
            // 非酒馆 id 会立刻返回 null（门面内部先核对命名空间），其余调用点行为不变。
            found = TavernBarrelCompat.typeById(rl);
        }
        if (found == null) {
            // 门面负责的 id 不进「不存在」黑名单：它可能只是还太早（字段在 RegisterEvent 里才赋值），
            // 一旦误缓存，那个类型在本次运行里就永久查不到了。
            if (!TavernBarrelCompat.handles(rl)) {
                MISSING_TYPES.add(id);
            }
            // 一次性告警：「静默当成未安装」是这类病最难查的地方，留一条可证伪的线索。
            if (MISSED_LOGGED.add(id)) {
                LOGGER.info("[mekck] 配方类型按 id 查不到：{}（未安装该模组，或它像森罗物语那样把类型造好了却不注册）", id);
            }
            return null;
        }
        TYPE_CACHE.put(id, found);
        return found;
    }

    /** 按 ResourceLocation 取配方类型（带缓存；不存在返回 null）。 */
    public static RecipeType<?> type(ResourceLocation id) {
        return id == null ? null : type(id.toString());
    }

    /** 按当前已装模组的类型 id 字符串取（不存在返回空表）。 */
    public static List<Recipe<?>> all(Level level, String typeId) {
        int idx = typeId.indexOf(':');
        if (idx <= 0) return List.of();
        return all(level, typeId.substring(0, idx), typeId.substring(idx + 1));
    }

    /**
     * 单槽配方查找：把一颗物品当成 1 格容器去 {@code getRecipeFor}（阶段 3 引入）。
     *
     * <p><b>为什么不在调用方造包装器</b>：单槽查询出现在每 tick × 每输入槽的热路径上
     * （奇点档 81 槽），而 {@code RecipeManager.getRecipeFor} 要的是一个
     * {@code Inventory} 实现。旧种植切配工厂每次调用都 {@code new} 一对匿名
     * {@code ItemStackHandler} + {@code RecipeWrapper}；这里复用一对静态包装器。
     *
     * <p><b>不是线程安全的</b>：包装器只有一个可变槽位，两个线程同时调会互相串味。
     * 配方查找只在服务端 tick 线程发生，所以现状安全——但若将来有并行世界 tick，
     * 必须改成 {@link ThreadLocal}。
     *
    /**
     * @param level 发起查询的世界，null 时返回空
     * @param type  配方类型，null 时返回空
     * @param stack 单颗待匹配物品
     * @param <T>   配方类型参数。边界写 {@code Recipe<RecipeWrapper>} 而不是
     *              {@code Recipe<?>}：本方法固定用 {@link #SINGLE_WRAPPER} 当容器，
     *              而 {@code RecipeManager.getRecipeFor} 的两个类型参数是
     *              {@code <C extends Container, T extends Recipe<C>>} 一起推断的——
     *              边界松到 {@code Recipe<?>} 会让推断失败，边界写成
     *              {@code Recipe<Container>} 则因泛型不变而不匹配
     *              （{@code Recipe<RecipeWrapper>} 不是 {@code Recipe<Container>}）。
     */
    public static <T extends Recipe<RecipeWrapper>> Optional<T> singleSlotQuery(
            Level level, RecipeType<T> type, ItemStack stack) {
        if (level == null || type == null || stack == null || stack.isEmpty()) {
            return Optional.empty();
        }
        SINGLE[0] = stack;
        try {
            return level.getRecipeManager().getRecipeFor(type, SINGLE_WRAPPER, level);
        } finally {
            SINGLE[0] = ItemStack.EMPTY;
        }
    }

    private static final ItemStack[] SINGLE = {ItemStack.EMPTY};

    /** 复用型单槽容器。只被 {@link #singleSlotQuery} 使用，不对外暴露。 */
    private static final RecipeWrapper SINGLE_WRAPPER = new RecipeWrapper(new ItemStackHandler(1) {
        @Override
        public int getSlots() {
            return 1;
        }

        @NotNull
        @Override
        public ItemStack getStackInSlot(int slot) {
            return SINGLE[0];
        }

        @Override
        public void setStackInSlot(int slot, @NotNull ItemStack stack) {
            SINGLE[0] = stack;
        }

        @NotNull
        @Override
        public ItemStack insertItem(int slot, @NotNull ItemStack stack, boolean simulate) {
            return stack;
        }

        @NotNull
        @Override
        public ItemStack extractItem(int slot, int amount, boolean simulate) {
            return ItemStack.EMPTY;
        }

        @Override
        public int getSlotLimit(int slot) {
            return SINGLE[0].isEmpty() ? 64 : SINGLE[0].getMaxStackSize();
        }
    });

    /** 当前缓存的管理器数量（诊断用）。 */
    public static int cachedManagers() {
        synchronized (CACHE) {
            return CACHE.size();
        }
    }
}
