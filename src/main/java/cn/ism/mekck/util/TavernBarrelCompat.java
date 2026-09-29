package cn.ism.mekck.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.Fluid;
import net.minecraftforge.registries.ForgeRegistries;
import org.slf4j.Logger;

/**
 * 森罗物语系（作者 ysbbbbbb 的两个模组）「按 id 查不到的配方类型」门面：
 * <ul>
 *   <li>森罗物语：酒馆 {@code kaleidoscope_tavern} —— {@code barrel}（酒桶）/{@code pressing_tub}（榨汁盆）
 *       /{@code shaker}（调酒）；</li>
 *   <li>森罗物语：厨房 {@code kaleidoscope_cookery} —— {@code pot}/{@code flex_pot}/{@code chopping_board}
 *       /{@code stockpot}/{@code flex_stockpot}/{@code millstone}（石磨）/{@code steamer}（蒸）/{@code teapot}。</li>
 * </ul>
 * ⚠ 这十一个 {@link RecipeType} **不进注册表**，按 id 查必然拿不到（原因与取证见 {@link #typeById}）。
 * <p>
 * 森罗酒馆的 {@code BarrelRecipe} 是 record，字段语义与原版 {@link Recipe} 的约定**不一致**：
 * <ul>
 *   <li>{@code ingredients()} → 配料，会出现在 {@link Recipe#getIngredients()} 里，可参与槽位准入；</li>
 *   <li>{@code carrier()} → **载具**（如 {@code kaleidoscope_tavern:empty_bottle}），是独立字段，
 *       **不在** {@code getIngredients()} 中——凡按「是否命中配方 ingredient」判定容器槽的实现都会把它挡在门外；</li>
 *   <li>{@code fluid()} → 所需流体，其中含 {@code minecraft:water} / {@code minecraft:lava}
 *       （mother_snow / molotov 等基配方），因此**不能**用「流体名含 juice」当判据。</li>
 * </ul>
 * 本类把载具集、配料集与合法流体集按配方管理器缓存，供陈酿机的槽位准入与流体抽取直接查表。
 * 未安装酒馆时所有方法安全返回空/ false。
 * </p>
 */
public final class TavernBarrelCompat {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final String TAVERN_NAMESPACE = "kaleidoscope_tavern";
    /** 森罗物语：厨房（与酒馆同一作者）——javap 它的 {@code init.ModRecipes} 证实是同一个写法、同一个毛病。 */
    private static final String KITCHEN_NAMESPACE = "kaleidoscope_cookery";

    /** 酒桶配方的类型 id：{@code kaleidoscope_tavern:barrel}（同时也是它的 serializer id）。 */
    public static final ResourceLocation BARREL_TYPE = new ResourceLocation(TAVERN_NAMESPACE, "barrel");
    /** 榨汁盆（鲜果榨汁机的酒馆路径）。 */
    public static final ResourceLocation PRESSING_TUB_TYPE = new ResourceLocation(TAVERN_NAMESPACE, "pressing_tub");
    /** 调酒（调酒机）。 */
    public static final ResourceLocation SHAKER_TYPE = new ResourceLocation(TAVERN_NAMESPACE, "shaker");

    /** 类型实例的持有类（javap 两个模组的 jar 得来；模组自有符号，reobf 不改名）。 */
    private static final String TAVERN_HOLDER_CLASS = "com.github.ysbbbbbb.kaleidoscopetavern.init.ModRecipes";
    private static final String KITCHEN_HOLDER_CLASS = "com.github.ysbbbbbb.kaleidoscopecookery.init.ModRecipes";

    /**
     * 类型 id → {持有类, 该类型在持有类里的静态字段名}。只列 javap 核实过的；表外 id 一律不答
     * （见 {@link #handles}），免得把「猜字段名」的反射试图留在 tick 路径上。
     */
    private static final Map<String, String[]> UNREGISTERED = Map.ofEntries(
            Map.entry(TAVERN_NAMESPACE + ":barrel", new String[]{TAVERN_HOLDER_CLASS, "BARREL_RECIPE"}),
            Map.entry(TAVERN_NAMESPACE + ":pressing_tub", new String[]{TAVERN_HOLDER_CLASS, "PRESSING_TUB_RECIPE"}),
            Map.entry(TAVERN_NAMESPACE + ":shaker", new String[]{TAVERN_HOLDER_CLASS, "SHAKER_RECIPE"}),
            Map.entry(KITCHEN_NAMESPACE + ":pot", new String[]{KITCHEN_HOLDER_CLASS, "POT_RECIPE"}),
            Map.entry(KITCHEN_NAMESPACE + ":flex_pot", new String[]{KITCHEN_HOLDER_CLASS, "FLEX_POT_RECIPE"}),
            Map.entry(KITCHEN_NAMESPACE + ":chopping_board", new String[]{KITCHEN_HOLDER_CLASS, "CHOPPING_BOARD_RECIPE"}),
            Map.entry(KITCHEN_NAMESPACE + ":stockpot", new String[]{KITCHEN_HOLDER_CLASS, "STOCKPOT_RECIPE"}),
            Map.entry(KITCHEN_NAMESPACE + ":flex_stockpot", new String[]{KITCHEN_HOLDER_CLASS, "FLEX_STOCKPOT_RECIPE"}),
            Map.entry(KITCHEN_NAMESPACE + ":millstone", new String[]{KITCHEN_HOLDER_CLASS, "MILLSTONE_RECIPE"}),
            Map.entry(KITCHEN_NAMESPACE + ":steamer", new String[]{KITCHEN_HOLDER_CLASS, "STEAMER_RECIPE"}),
            Map.entry(KITCHEN_NAMESPACE + ":teapot", new String[]{KITCHEN_HOLDER_CLASS, "TEAPOT_RECIPE"}));

    private static final Map<RecipeManager, Entries> CACHE = Collections.synchronizedMap(new WeakHashMap<>());

    private TavernBarrelCompat() {
    }

    private static final class Entries {
        final List<Ingredient> carriers = new ArrayList<>();
        final List<Ingredient> ingredients = new ArrayList<>();
        final Set<Fluid> fluids = new HashSet<>();

        boolean isEmpty() {
            return carriers.isEmpty() && ingredients.isEmpty() && fluids.isEmpty();
        }
    }

    // ── 类型解析（每个 id 一次性，结果长期有效） ──
    /** id 字符串 → 已解析的类型实例。 */
    private static final Map<String, RecipeType<?>> TYPES = new ConcurrentHashMap<>();
    /** 已确认拿不到（对应模组未安装）：不再重试，免得把反射留在 tick 路径上。 */
    private static final Set<String> ABSENT = ConcurrentHashMap.newKeySet();
    /** 持有类按类名各解析一次；进 {@link #HOLDERS_ABSENT} 表示连类都不存在 = 该模组未安装。 */
    private static final Map<String, Class<?>> HOLDERS = new ConcurrentHashMap<>();
    private static final Set<String> HOLDERS_ABSENT = ConcurrentHashMap.newKeySet();
    /** 首建摘要日志只打一次（部署后一眼能看出类型有没有解析到、表有多宽）。 */
    private static boolean summaryLogged;

    /** 酒桶配方类型（未装酒馆返回 null）；等价于 {@code typeById(BARREL_TYPE)}。 */
    public static RecipeType<?> type() {
        return typeById(BARREL_TYPE);
    }

    /** 榨汁盆类型（鲜果榨汁机的酒馆路径）。 */
    public static RecipeType<?> pressingTubType() {
        return typeById(PRESSING_TUB_TYPE);
    }

    /** 调酒类型（调酒机）。 */
    public static RecipeType<?> shakerType() {
        return typeById(SHAKER_TYPE);
    }

    /**
     * 取森罗物语某个配方类型**实际在用的那一个实例**（对应模组未安装 / 表外 id 返回 null）。
     * <p>
     * ⚠ **不能按 id 查注册表**。javap 两个模组的 {@code init.ModRecipes} 各自证实：它们只有一个
     * {@code DeferredRegister<RecipeSerializer>}，RecipeType 全是在 {@code register(RegisterEvent)} 里用
     * {@code RecipeType.simple(modLoc(...))} 造的**匿名对象**，那个分支里只有几条 putstatic、**没有任何**
     * {@code event.register(...)} ⇒ 类型从不进注册表 ⇒ 按 id 查恒为 null。
     * （Forge 的 {@code GameData} 里 {@code makeRegistry(Keys.RECIPE_TYPES)} 没给 default 项，所以落空就是 null、
     * 不会返回 CRAFTING：失效方式是**静默走「未安装」分支**，各调用点连报错都不会有。）
     * <p>
     * 又因 {@code RecipeManager} 是按**实例**给配方分组的，必须拿到 {@code Recipe#getType()} 实际返回的那同一个
     * 对象才查得到配方 ⇒ 直接读模组自己的静态字段（javap 它们的 {@code getType()} 都是 getstatic 该字段）。
     * </p>
     */
    public static RecipeType<?> typeById(ResourceLocation id) {
        if (id == null) return null;
        String key = id.toString();
        String[] spec = UNREGISTERED.get(key);
        if (spec == null) return null; // 表外：不是已知的「未注册类型」，别乱答
        RecipeType<?> known = TYPES.get(key);
        if (known != null) return known;
        if (ABSENT.contains(key)) return null;
        synchronized (TYPES) {
            known = TYPES.get(key);
            if (known != null) return known;
            if (ABSENT.contains(key)) return null;
            RecipeType<?> found = fromModField(spec[0], spec[1]);
            if (found == null) found = fromRegistry(id);
            if (found != null) {
                TYPES.put(key, found);
                LOGGER.info("[mekck] 未注册配方类型：{} 解析为 {}@{}（按 id 查注册表拿不到，取的是模组自己的静态字段）",
                        key, found.getClass().getSimpleName(), System.identityHashCode(found));
            } else if (HOLDERS_ABSENT.contains(spec[0])) {
                ABSENT.add(key); // 连持有类都没有 = 该模组未安装，重试也不会变
            }
            return found;
        }
    }

    /** 该 id 是否由本门面负责（已知「类型存在但从不进注册表」的第三方 id）。 */
    public static boolean handles(ResourceLocation id) {
        return id != null && UNREGISTERED.containsKey(id.toString());
    }

    /** 从模组自己的静态字段取它实际在用的 RecipeType 实例；取不到返回 null（可能是极早期，下次再试）。 */
    private static RecipeType<?> fromModField(String holderClassName, String field) {
        Class<?> owner = holder(holderClassName);
        if (owner == null) return null;
        try {
            java.lang.reflect.Field f = Reflect.field(owner, field);
            if (f == null) return null;
            return f.get(null) instanceof RecipeType<?> t ? t : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 持有类按类名解析一次并缓存；连类都找不到（该模组未安装）即记住，此后不再重试。 */
    private static Class<?> holder(String holderClassName) {
        Class<?> cached = HOLDERS.get(holderClassName);
        if (cached != null) return cached;
        if (HOLDERS_ABSENT.contains(holderClassName)) return null;
        try {
            Class<?> loaded = Class.forName(holderClassName);
            HOLDERS.put(holderClassName, loaded);
            return loaded;
        } catch (Throwable ignored) {
            HOLDERS_ABSENT.add(holderClassName);
            return null;
        }
    }

    /**
     * 兜底：万一哪天它改成注册进注册表，按 id 取——但要回同表核身份，防拿回一个不相干的类型。
     * <p>
     * ⚠ 这里**必须直查注册表**，不能走 {@code RecipeCache.type(...)}：那个方法在注册表落空时会回头
     * 调 {@link #typeById}（未注册类型的中心兜底），两者会构成无界递归。
     * </p>
     */
    private static RecipeType<?> fromRegistry(ResourceLocation id) {
        RecipeType<?> t = ForgeRegistries.RECIPE_TYPES.getValue(id);
        if (t == null) return null;
        return id.equals(ForgeRegistries.RECIPE_TYPES.getKey(t)) ? t : null;
    }

    /**
     * 反查：给定 RecipeType 实例，若它是森罗物语那两个模组的类型之一就返回它的 id，否则 null。
     * <p>
     * 供「只能拿到 {@code Recipe#getType()} 、需要按 id 归类」的调用点使用（如中央厨房的系列归属判定）：
     * 这些类型不在注册表里，{@code ForgeRegistries.RECIPE_TYPES.getKey(...)} 对它们恒返回 null。
     * </p>
     */
    public static ResourceLocation idOf(RecipeType<?> type) {
        if (type == null) return null;
        for (String key : UNREGISTERED.keySet()) {
            ResourceLocation id = ResourceLocation.tryParse(key);
            if (id != null && type.equals(typeById(id))) return id;
        }
        return null;
    }

    /** 取某条酒桶配方的载具（record 访问器 {@code carrier()}）；读不到返回 null。 */
    public static Ingredient carrierOf(Recipe<?> r) {
        return Reflect.call(r, "carrier") instanceof Ingredient ing ? ing : null;
    }

    /** 取某条酒桶配方所需的流体（record 访问器 {@code fluid()}）；读不到返回 null。 */
    public static Fluid fluidOf(Recipe<?> r) {
        return Reflect.call(r, "fluid") instanceof Fluid f ? f : null;
    }

    /** 该流体是否为某条酒桶配方的合法输入（含 water / lava 基配方）；判定不了返回 false。 */
    public static boolean isBarrelFluid(Level level, Fluid fluid) {
        if (level == null || fluid == null) return false;
        return entries(level).fluids.contains(fluid);
    }

    /** 该物品是否命中某条酒桶配方的**配料**（{@code getIngredients()} 那份，可进输入槽）；判定不了返回 false。 */
    public static boolean hasIngredient(Level level, ItemStack stack) {
        if (level == null || stack == null || stack.isEmpty()) return false;
        for (Ingredient ing : entries(level).ingredients) {
            try {
                if (ing != null && !ing.isEmpty() && ing.test(stack)) return true;
            } catch (Throwable ignored) {
                // 单条配料判定异常不影响其余
            }
        }
        return false;
    }

    /** 该物品是否为某条酒桶配方要求的载具（空酒瓶等）；判定不了返回 false。 */
    public static boolean isCarrier(Level level, ItemStack stack) {
        if (level == null || stack == null || stack.isEmpty()) return false;
        for (Ingredient carrier : entries(level).carriers) {
            try {
                if (carrier != null && !carrier.isEmpty() && carrier.test(stack)) return true;
            } catch (Throwable ignored) {
                // 单个载具判定异常不影响其余
            }
        }
        return false;
    }

    /** 扫描配方表建载具集 / 配料集 / 合法流体集。 */
    private static Entries entries(Level level) {
        RecipeManager manager = level.getRecipeManager();
        synchronized (CACHE) {
            Entries e = CACHE.get(manager);
            if (e != null) return e;
            e = new Entries();
            RecipeType<?> t = type();
            List<Recipe<?>> recipes = t == null ? List.of() : RecipeCache.all(level, t);
            for (Recipe<?> r : recipes) {
                try {
                    Ingredient carrier = carrierOf(r);
                    if (carrier != null && !carrier.isEmpty()) e.carriers.add(carrier);
                    for (Ingredient ing : r.getIngredients()) {
                        if (ing != null && !ing.isEmpty()) e.ingredients.add(ing);
                    }
                    Fluid f = fluidOf(r);
                    if (f == null) continue;
                    ResourceLocation fid = ForgeRegistries.FLUIDS.getKey(f);
                    // 空流体（minecraft:empty）不是合法输入，挡掉避免"任意容器都能抽"
                    if (fid != null && !"empty".equals(fid.getPath())) e.fluids.add(f);
                } catch (Throwable ignored) {
                    // 读不动的配方跳过：判定面只会收窄，不会误放行
                }
            }
            if (!summaryLogged) {
                summaryLogged = true;
                LOGGER.info("[mekck] 森罗酒馆酒桶配方解析：type={} 配方={} 载具={} 配料={} 流体={}",
                        t, recipes.size(), e.carriers.size(), e.ingredients.size(), e.fluids.size());
            }
            if (e.isEmpty()) {
                // **空表不缓存**：1.20.1 的 RecipeManager 实例是跟着重载复用的（apply 原地换内部 byType），
                // 弱键因此根本不会失效；万一在配方加载完成前建起一张空表，它会永久驻留，把这台机器锁死在
                // 「什么都不认」的状态。留空只让下一次查询重扫（正常一轮就命中）。
                return new Entries();
            }
            CACHE.put(manager, e);
            return e;
        }
    }
}
