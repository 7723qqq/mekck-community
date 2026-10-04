package cn.ism.mekck.world;

import cn.ism.mekck.config.MekckConfig;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraftforge.registries.ForgeRegistries;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 创造升级「49 种随机食物」——<b>候选池构建</b>。
 *
 * <p>候选池口径（施工单第五节 / 依据文档第十一节）：</p>
 * <ol>
 *   <li><b>可食用</b>：{@code new ItemStack(item).isEdible()}（= 有饥饿值）。这是<b>唯一精确</b>的判定，
 *       名字启发式（"像食物"）与 {@code forge:foods} 标签都<b>不能</b>当过滤器 ——
 *       前者会混入 {@code bread_knife} / {@code wine_rack} 这类非食用物，后者只覆盖 9 个模组、重开多样性极差；</li>
 *   <li><b>可合成</b>：{@link RecipeManager} 中<b>至少有一条配方产出它</b>（不限配方类型/工作台）。</li>
 * </ol>
 *
 * <p>已知局限：部分模组配方类型取不到静态产物（需要机器上下文的、产物由代码计算的）会被<b>误判为不可合成</b>。
 * 对策是双轨制 —— 自动判定为主，{@link #MANUAL_SUPPLEMENT} 人工补齐。</p>
 */
public final class CreativeUpgradeFoodPool {

    public static final Logger LOGGER = LoggerFactory.getLogger(CreativeUpgradeFoodPool.class);

    /** tag 数量上限（= 配方里的 ingredient 数 = 终末合成台 9×9 要摆的格数）。 */
    public static final int TAG_COUNT = 49;

    /** 黑名单：创造专属 / 无正常来源 / 会自指的物品。 */
    private static final Set<ResourceLocation> BLACKLIST = Set.of(
            // 本配方自己的产物：绝不能出现在自己的材料里
            ResourceLocation.fromNamespaceAndPath("mekanism_extras", "upgrade_creative")
    );

    /**
     * 人工补充清单：对"自动判定取不到产物、但玩家确实能拿到"的食物，在这里按物品 id 手工补齐。
     * <p>默认留空 —— 当前实测候选池远超需求（见 {@link #build} 的日志）。</p>
     */
    private static final List<ResourceLocation> MANUAL_SUPPLEMENT = List.of();

    private CreativeUpgradeFoodPool() {
    }

    /** 候选池 + 统计信息。 */
    public record Pool(List<Item> items, Map<String, Integer> byNamespace) {

        public int size() {
            return items.size();
        }
    }

    /**
     * 构建本局候选池。**必须在服务器资源（配方）已加载后调用。**
     *
     * @return 候选池；永不返回 null（池可能为空，由调用方判定）
     */
    public static Pool build(MinecraftServer server) {
        Set<ResourceLocation> craftable = collectCraftableOutputs(server);
        RegistryAccess access = server.registryAccess();

        List<Item> pool = new ArrayList<>();
        Set<ResourceLocation> seen = new HashSet<>();

        for (Item item : ForgeRegistries.ITEMS) {
            ResourceLocation id = ForgeRegistries.ITEMS.getKey(item);
            if (id == null || BLACKLIST.contains(id)) {
                continue;
            }
            // I5：必须能被合成
            if (!craftable.contains(id)) {
                continue;
            }
            // I4：必须有饥饿值
            if (!isFood(item)) {
                continue;
            }
            if (seen.add(id)) {
                pool.add(item);
            }
        }

        // 双轨制的人工补充
        for (ResourceLocation id : MANUAL_SUPPLEMENT) {
            if (BLACKLIST.contains(id) || !seen.add(id)) {
                continue;
            }
            Item item = ForgeRegistries.ITEMS.getValue(id);
            if (item != null && isFood(item)) {
                pool.add(item);
            } else {
                LOGGER.warn("[创造升级] 人工清单条目无效（物品不存在或不可食用）：{}", id);
            }
        }

        // 按 id 排序：让"抽签结果"只取决于随机数，不取决于注册顺序
        pool.sort((a, b) -> {
            ResourceLocation ka = ForgeRegistries.ITEMS.getKey(a);
            ResourceLocation kb = ForgeRegistries.ITEMS.getKey(b);
            return String.valueOf(ka).compareTo(String.valueOf(kb));
        });

        Map<String, Integer> byNs = new TreeMap<>();
        for (Item item : pool) {
            ResourceLocation id = ForgeRegistries.ITEMS.getKey(item);
            if (id != null) {
                byNs.merge(id.getNamespace(), 1, Integer::sum);
            }
        }
        return new Pool(List.copyOf(pool), new LinkedHashMap<>(byNs));
    }

    /** 可食用判定：{@code ItemStack.isEdible()}（等价于 {@code Item#getFoodProperties() != null}）。 */
    public static boolean isFood(Item item) {
        try {
            return !item.getDefaultInstance().isEmpty() && item.getDefaultInstance().isEdible();
        } catch (Throwable t) {
            // 极少数模组物品在构造默认栈时会炸；当作不可食用处理，避免整池构建失败
            return false;
        }
    }

    /** 收集"有配方产出"的物品 id（遍历全部已加载配方，取每条配方的产物）。 */
    private static Set<ResourceLocation> collectCraftableOutputs(MinecraftServer server) {
        Set<ResourceLocation> out = new HashSet<>();
        RecipeManager rm = server.getRecipeManager();
        RegistryAccess access = server.registryAccess();
        int scanned = 0;
        int failed = 0;

        for (Recipe<?> recipe : rm.getRecipes()) {
            scanned++;
            try {
                ItemStack result = recipe.getResultItem(access);
                if (!result.isEmpty()) {
                    ResourceLocation id = ForgeRegistries.ITEMS.getKey(result.getItem());
                    if (id != null) {
                        out.add(id);
                    }
                }
            } catch (Throwable t) {
                // 部分模组配方在无上下文时取产物会抛异常 ⇒ 计入 failed，不影响其它配方
                failed++;
            }
        }
        LOGGER.info("[创造升级] 扫描配方 {} 条（取产物失败 {} 条），有产出的物品 {} 种",
                scanned, failed, out.size());
        return out;
    }

    /** 供 Rotator 落盘 candidates.json 用。 */
    public static String describe(Item item) {
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(item);
        return id == null ? "unknown:unknown" : id.toString();
    }

    /** 校验：该物品仍满足 I4 + I5（写盘前的复核，防人工清单写错）。 */
    public static boolean verify(Item item, Set<ResourceLocation> craftable) {
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(item);
        return id != null && !BLACKLIST.contains(id) && craftable.contains(id) && isFood(item);
    }

    /**
     * 供外部在"写盘前复核"时复用：重新收集一次可合成集合。
     * <p>为避免重复扫描，{@link CreativeUpgradeFoodRotator} 会在一次流程里只调一次。</p>
     */
    public static Set<ResourceLocation> craftableFor(MinecraftServer server) {
        return collectCraftableOutputs(server);
    }

    /**
     * 本功能是否启用。
     *
     * <p>旧配置项 {@code rotate_foods_on_startup} 已由 {@code food_rotation_mode}（per_world / per_restart）取代，
     * 两种模式都**会**生成 49 种食物（per_world 只是"每个存档只生成一次"）⇒ 不再有"完全关闭"的总开关，
     * 这里恒为 {@code true}；是否重新随机由 {@link MekckConfig#isCreativeUpgradePerRestart()} 决定。</p>
     */
    public static boolean enabled() {
        return true;
    }
}
