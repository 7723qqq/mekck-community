package cn.ism.mekck.recipe;

import cn.ism.mekck.config.MekckConfig;
import mekanism.api.recipes.ItemStackToItemStackRecipe;
import mekanism.common.recipe.MekanismRecipeType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.Level;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.HashMap;
import java.util.Map;

/**
 * 生物反应堆燃料换算：单个物品可转化为多少有机物流体（mb）。
 * 规则优先级：C（配置文件） &gt; A（Mekanism 粉碎配方 → 生物燃料） &gt; B（食物营养）。
 * <p>
 * 同时被生物反应堆方块实体（运行时）与 JEI 配方展示使用，避免逻辑重复。
 */
public final class BioreactorFuels {

    /** 「可接受不消耗」食物（eternal_foods）未自定义 =mb 时的默认转换量：10000 mb/个。 */
    public static final int ETERNAL_MB = 10_000;

    private BioreactorFuels() {
    }

    // ── 规则 C 的缓存：配置条目解析后的「物品注册名 → 每单位 mb」─────────────
    //
    // getMBPerUnit 是每 tick 路径（BioreactorBlockEntity 对每个输入槽各调一次，最多 16 次/tick）；
    // 而 MekckConfig.getBioreactorFuels() 每次都 new ArrayList<>(...) 拷贝整份配置，
    // 再对每条做 substring + trim + 字符串比较 —— 每槽每 tick 一次全表拷贝与线性扫描。
    // 配置只在重载时变，因此解析一次缓存起来即可。
    private static Map<String, Integer> fuelCache;

    private static synchronized Map<String, Integer> fuels() {
        if (fuelCache != null) {
            return fuelCache;
        }
        Map<String, Integer> map = new HashMap<>();
        for (String entry : MekckConfig.getBioreactorFuels()) {
            int eq = entry.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String id = entry.substring(0, eq).trim();
            try {
                map.put(id, Math.max(1, Integer.parseInt(entry.substring(eq + 1).trim())));
            } catch (NumberFormatException ignored) {
                map.put(id, 0); // 与原逻辑一致：解析失败即 0（不可转化）
            }
        }
        fuelCache = map;
        return map;
    }

    /**
     * 配置重载后调用：丢掉规则 C 的解析缓存。
     *
     * <p>与规则 A 的 {@code cachedLevel} 按实例判断不同，配置列表变了但引用可能仍是
     * 同一个对象，因此必须显式失效。{@code MekckConfig} 的重载入口应调用本方法。</p>
     */
    public static synchronized void invalidateFuelCache() {
        fuelCache = null;
    }

    // 规则 A 的缓存：物品注册名 → 每单位 mb。按 Level 实例构建一次。
    private static Map<ResourceLocation, Integer> crushingCache;
    private static Level cachedLevel;

    private static synchronized void buildCrushingCache(Level level) {
        if (crushingCache != null && cachedLevel == level) {
            return;
        }
        Map<ResourceLocation, Integer> map = new HashMap<>();
        ItemStack bioFuel = new ItemStack(ForgeRegistries.ITEMS.getValue(new ResourceLocation("mekanism", "bio_fuel")));
        if (!bioFuel.isEmpty()) {
            for (ItemStackToItemStackRecipe recipe : MekanismRecipeType.CRUSHING.getRecipes(level)) {
                if (recipe.isIncomplete()) {
                    continue;
                }
                ItemStack out = recipe.getResultItem(level.registryAccess());
                if (out.isEmpty() || !out.is(bioFuel.getItem())) {
                    continue;
                }
                int mb = Math.max(1, out.getCount() * 200);
                for (Ingredient ingredient : recipe.getIngredients()) {
                    for (ItemStack match : ingredient.getItems()) {
                        ResourceLocation id = ForgeRegistries.ITEMS.getKey(match.getItem());
                        if (id != null) {
                            map.put(id, Math.max(map.getOrDefault(id, 0), mb));
                        }
                    }
                }
            }
        }
        crushingCache = map;
        cachedLevel = level;
    }

    /**
     * 返回单个物品可转换的有机物 mb 数；0 表示不可转化。
     *
     * @param level 用于查询 Mekanism 粉碎配方；可为 null（离线/无配方时退化到规则 C、B）
     */
    public static int getMBPerUnit(ItemStack stack, Level level) {
        if (stack.isEmpty()) {
            return 0;
        }
        ResourceLocation itemId = ForgeRegistries.ITEMS.getKey(stack.getItem());
        if (itemId == null) {
            return 0;
        }

        // 「可接受不消耗」食物（eternal_foods 配置）：优先级最高，不受 fuels 自定义 /
        // Mekanism 粉碎 / 食物营养规则影响；条目带 =mb 时按自定义转换量（如
        // relics:infinity_ham=1600 与牛排相同），否则固定 ETERNAL_MB
        if (MekckConfig.isBioreactorEternalFood(stack.getItem())) {
            Integer customMb = MekckConfig.getBioreactorEternalFoodMb(stack.getItem());
            return customMb != null ? customMb : ETERNAL_MB;
        }

        // 规则 C：mekck.toml 中 [bioreactor] fuels 条目 "注册名=每单位mb"（已解析缓存，见 fuels()）
        Integer custom = fuels().get(itemId.toString());
        if (custom != null) {
            return custom;
        }

        // 规则 A：Mekanism 粉碎配方产出生物燃料 → X 生物燃料 → X*200 mb
        if (level != null) {
            buildCrushingCache(level);
            Integer mb = crushingCache.get(itemId);
            if (mb != null) {
                return mb;
            }
        }

        // 规则 B：食物营养值（1 饥饿点 → 200 mb）
        FoodProperties food = stack.getItem().getFoodProperties(stack, null);
        if (food != null && food.getNutrition() > 0) {
            return food.getNutrition() * 200;
        }

        return 0;
    }
}
