package cn.ism.mekck.compat;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.core.NonNullList;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 森罗物语：烟火 (kaleidoscope_grilling) 的**运行时可选联动**。
 *
 * 烟火没有原版 RecipeType 处理配方，核心数据在 GrillingDataManager
 * （JSON 重载监听器）里。本类通过反射读取其数据，并提供：
 * - 固定穿串配方（ingredients[][] -> threadingResult）
 * - 自由穿串（材料组合 -> unfinished_skewer + SkewerIngredientStacks NBT）
 * - 烤制配对（raw -> cooked）
 * - 调味瓶判定与烤串调味 NBT 写入
 *
 * 同时提供 {@link Virtual} 虚拟配方：把穿串/烤制包装成暴露
 * tool/ingredient/ingredientCount/side/sideCount 反射字段的 Recipe，
 * 使现有烧烤/穿串工厂的反射管线无需改动即可处理。
 */
public final class KaleidoscopeGrillingCompat {

    private KaleidoscopeGrillingCompat() {
    }

    public static final String MOD_ID = "kaleidoscope_grilling";
    public static final String THREADING_ID_PREFIX = "threading/";
    /** 自由穿串产物 NBT key（与烟火 SkeweringHandler 一致）。 */
    public static final String INGREDIENT_STACKS_TAG = "SkewerIngredientStacks";
    public static final String SEASONING_INGREDIENTS_TAG = "SeasoningIngredients";
    public static final String SEASONING_USES_TAG = "SeasoningUses";
    public static final int SEASONING_MAX_USES = 16;

    /** 固定穿串配方。 */
    public record ThreadingDef(String key, List<Ingredient> groups, ItemStack result) {
    }

    /** 烤制配对（生 -> 熟）。 */
    public record GrillingPair(ItemStack input, ItemStack output) {
    }

    public static boolean isLoaded() {
        return ModList.get().isLoaded(MOD_ID);
    }

    // ==================================================================
    //   固定穿串配方
    // ==================================================================

    // §F29：带失效检测的懒缓存。数据源 skewersForDisplay() 是烟火自己的动态 map（可能晚于本表首次
    // 调用才填充、随 datapack reload 变化），不能无条件缓存；用「轻量代际标记」探测变化：
    // 标记一致⇒直接返回缓存（不再逐条反射/new Gson/展开 Tag）；不一致（含空→非空）⇒全量重建。
    // 调用方高频（AE2 终端列配方、工厂每匹配一次输入都走到），此前每次全表重建且每刷一条 info。
    private static long threadingGen = -1; // -1 = 从未成功探测过；0 = 空表（含反射失败，下次重试）
    private static List<ThreadingDef> threadingCache = List.of();
    private static List<ThreadingDef> threadingVirtualSource; // 派生缓存的源列表引用（同一性比较）
    private static List<VirtualRecipe> threadingVirtualCache = List.of();

    private static long grillingGen = -1;
    private static List<GrillingPair> grillingCache = List.of();
    private static List<GrillingPair> grillingVirtualSource;
    private static List<VirtualRecipe> grillingVirtualCache = List.of();

    /** 数据源 map 的轻量代际标记（keySet 是 String，hashCode 稳定廉价，不遍历 value）。 */
    @SuppressWarnings("unchecked")
    private static long sourceGeneration(Object map) {
        Map<String, ?> m = (Map<String, ?>) map;
        return m.isEmpty() ? 0L : (long) m.size() * 31 + m.keySet().hashCode();
    }

    /** 读取烟火全部固定穿串配方（ingredients 非空且 threadingResult 非空）；§F29 代际懒缓存。 */
    @SuppressWarnings("unchecked")
    public static List<ThreadingDef> getFixedThreading() {
        if (!isLoaded()) return List.of();
        try {
            Class<?> dm = Class.forName("cn.breezeth.kaleidoscope_grilling.data.GrillingDataManager");
            Object map = dm.getMethod("skewersForDisplay").invoke(null);
            long gen = sourceGeneration(map);
            if (gen == threadingGen) {
                return threadingCache; // 命中：不重建、不打日志（旧版「每 2 秒刷两条 count=19」的直接来源）
            }
            List<ThreadingDef> out = new ArrayList<>();
            Gson gson = new Gson();
            for (Map.Entry<String, ?> e : ((Map<String, ?>) map).entrySet()) {
                Object skewer = e.getValue();
                List<List<String>> ingredients = (List<List<String>>) skewer.getClass()
                        .getMethod("ingredients").invoke(skewer);
                // 1.1.0 的 Skewer 无 threadingResult：穿串产物 = 生串本身（key）
                Item resultItem = ForgeRegistries.ITEMS.getValue(ResourceLocation.tryParse(e.getKey()));
                if (resultItem == null) continue;
                List<Ingredient> groups = new ArrayList<>(ingredients.size());
                boolean valid = true;
                for (List<String> group : ingredients) {
                    Ingredient ing = parseGroup(gson, group);
                    if (ing == null) { valid = false; break; }
                    groups.add(ing);
                }
                if (!valid || groups.isEmpty()) continue;
                out.add(new ThreadingDef(e.getKey(), groups, new ItemStack(resultItem)));
            }
            threadingCache = out;
            threadingGen = gen;
            com.mojang.logging.LogUtils.getLogger().debug("[mekck] KG fixed threading rebuilt gen={} count={}", gen, out.size());
            return out;
        } catch (Throwable t) {
            com.mojang.logging.LogUtils.getLogger().warn("[mekck] KG fixed threading load failed", t);
            // 失败：缓存置「空且标记 0」（工单第 5 条），避免脏缓存卡住不重试
            threadingCache = List.of();
            threadingGen = 0;
            return List.of();
        }
    }

    /** 解析一组候选（"item" 或 "#tag"）为 OR ingredient。 */
    private static Ingredient parseGroup(Gson gson, List<String> candidates) {
        List<Item> items = new ArrayList<>();
        for (String c : candidates) {
            if (c == null || c.isEmpty()) continue;
            if (c.startsWith("#")) {
                // 标签候选：延迟到运行时匹配，这里用 TagValue 包装
                ResourceLocation tagId = ResourceLocation.tryParse(c.substring(1));
                if (tagId == null) continue;
                items.addAll(itemsFromTag(tagId));
                continue;
            }
            Item item = ForgeRegistries.ITEMS.getValue(ResourceLocation.tryParse(c));
            if (item != null) items.add(item);
        }
        if (!items.isEmpty()) {
            return Ingredient.of(items.toArray(new Item[0]));
        }
        return null;
    }

    private static List<Item> itemsFromTag(ResourceLocation tagId) {
        List<Item> out = new ArrayList<>();
        try {
            TagKey<Item> key = TagKey.create(net.minecraft.core.registries.Registries.ITEM, tagId);
            ForgeRegistries.ITEMS.getValues().forEach(item -> {
                if (item.builtInRegistryHolder().is(key)) out.add(item);
            });
        } catch (Throwable ignored) {
        }
        return out;
    }

    // ==================================================================
    //   自由穿串（自定义组合 -> 未完成烤串）
    // ==================================================================

    /** 用材料组合生成烟火"未完成烤串"（带 SkewerIngredientStacks NBT）。 */
    public static ItemStack makeCustomSkewer(List<ItemStack> ingredients) {
        Item unfinished = ForgeRegistries.ITEMS.getValue(ResourceLocation.fromNamespaceAndPath(MOD_ID, "unfinished_skewer"));
        ItemStack skewer = unfinished != null ? new ItemStack(unfinished) : new ItemStack(Items.STICK);
        ListTag stacks = new ListTag();
        ListTag legacy = new ListTag();
        for (ItemStack ing : ingredients) {
            if (ing == null || ing.isEmpty()) continue;
            stacks.add(ing.copyWithCount(1).save(new CompoundTag()));
            ResourceLocation id = ForgeRegistries.ITEMS.getKey(ing.getItem());
            if (id != null) legacy.add(StringTag.valueOf(id.toString()));
        }
        CompoundTag tag = skewer.getOrCreateTag();
        tag.put(INGREDIENT_STACKS_TAG, stacks);
        tag.put("SkewerIngredients", legacy);
        return skewer;
    }

    // ==================================================================
    //   烤制配对（生 -> 熟，仅取显式 cookedResult）
    // ==================================================================

    /** 烤制配对（生→熟）；§F29 同款代际懒缓存。 */
    @SuppressWarnings("unchecked")
    public static List<GrillingPair> getGrillingPairs() {
        if (!isLoaded()) return List.of();
        try {
            Class<?> dm = Class.forName("cn.breezeth.kaleidoscope_grilling.data.GrillingDataManager");
            Object map = dm.getMethod("skewersForDisplay").invoke(null);
            long gen = sourceGeneration(map);
            if (gen == grillingGen) {
                return grillingCache; // 命中：不重建
            }
            List<GrillingPair> out = new ArrayList<>();
            for (Map.Entry<String, ?> e : ((Map<String, ?>) map).entrySet()) {
                String cooked = (String) e.getValue().getClass().getMethod("cookedResult").invoke(e.getValue());
                if (cooked == null || cooked.isEmpty()) continue;
                ResourceLocation rawId = ResourceLocation.tryParse(e.getKey());
                ResourceLocation cookedId = ResourceLocation.tryParse(cooked);
                if (rawId == null || cookedId == null) continue;
                Item raw = ForgeRegistries.ITEMS.getValue(rawId);
                Item cookedItem = ForgeRegistries.ITEMS.getValue(cookedId);
                if (raw == null || cookedItem == null) continue;
                out.add(new GrillingPair(new ItemStack(raw), new ItemStack(cookedItem)));
            }
            grillingCache = out;
            grillingGen = gen;
            com.mojang.logging.LogUtils.getLogger().debug("[mekck] KG grilling pairs rebuilt gen={} count={}", gen, out.size());
            return out;
        } catch (Throwable ignored) {
            grillingCache = List.of();
            grillingGen = 0;
            return List.of();
        }
    }

    // ==================================================================
    //   调味（烟火调味瓶：多调味 NBT 列表，上限 16 次）
    // ==================================================================

    /** 是否为烟火调味瓶（SeasoningItem，BlockItem 子类）。 */
    public static boolean isSeasoningBottle(ItemStack stack) {
        if (stack.isEmpty() || !isLoaded()) return false;
        try {
            Class<?> cls = Class.forName("cn.breezeth.kaleidoscope_grilling.seasoning.SeasoningItem");
            return cls.isInstance(stack.getItem());
        } catch (Throwable t) {
            return false;
        }
    }

    /** 调味瓶的调味标识（取瓶上 SeasoningIngredients 第一个 id；无则用物品 id）。 */
    public static String getSeasoningId(ItemStack bottle) {
        ListTag list = bottle.getTag() != null ? bottle.getTag().getList(SEASONING_INGREDIENTS_TAG, 8) : new ListTag();
        if (!list.isEmpty()) return list.getString(0);
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(bottle.getItem());
        return id != null ? id.toString() : "kg_seasoning";
    }

    /** 调味瓶剩余可用次数（新瓶无 NBT 视为满）。 */
    public static int getSeasoningUses(ItemStack bottle) {
        if (bottle.getTag() == null || !bottle.getTag().contains(SEASONING_USES_TAG)) return SEASONING_MAX_USES;
        return SEASONING_MAX_USES - Math.max(0, Math.min(SEASONING_MAX_USES, bottle.getTag().getInt(SEASONING_USES_TAG)));
    }

    /** 消耗调味瓶 1 次使用。 */
    public static void consumeSeasoningUse(ItemStack bottle) {
        int used = SEASONING_MAX_USES - getSeasoningUses(bottle) + 1;
        bottle.getOrCreateTag().putInt(SEASONING_USES_TAG, Math.min(SEASONING_MAX_USES, used));
    }

    /** 给烤串追加烟火调味（多调味列表，上限 16）。 */
    public static void applySeasoningToSkewer(ItemStack skewer, String seasoningId) {
        CompoundTag tag = skewer.getOrCreateTag();
        ListTag list = tag.getList(SEASONING_INGREDIENTS_TAG, 8);
        list.add(StringTag.valueOf(seasoningId));
        tag.put(SEASONING_INGREDIENTS_TAG, list);
        int uses = tag.contains(SEASONING_USES_TAG) ? tag.getInt(SEASONING_USES_TAG) : 0;
        tag.putInt(SEASONING_USES_TAG, Math.min(SEASONING_MAX_USES, uses + 1));
    }

    // ==================================================================
    //   虚拟配方：把穿串/烤制包装成带反射字段的 Recipe
    // ==================================================================

    /**
     * 暴露 public 字段 tool / ingredient / ingredientCount / side / sideCount / ingredient 字段，
     * 与烧烤乐事 SimpleSkeweringRecipe / SimpleGrillingRecipe 的反射访问完全兼容，
     * 使现有工厂管线（matchesSkewering / consumeIngredients / matchesInput）零改动复用。
     */
    /**
     * 按中央厨房系列 id 取该系列的烟火虚拟配方：
     * {@code grilling} → 烤制配对；{@code skewering} → 固定穿串。其它系列返回空列表。
     */
    public static List<VirtualRecipe> virtualRecipesForFamily(String familyId) {
        if (!isLoaded()) return List.of();
        try {
            return switch (familyId) {
                case "grilling" -> getGrillingVirtualRecipes();
                case "skewering" -> getThreadingVirtualRecipes();
                default -> List.of();
            };
        } catch (Throwable t) {
            return List.of();
        }
    }

    /** 按配方 id 找回烟火虚拟配方（供订单/任务链持久化后重建）。 */
    public static VirtualRecipe findVirtualById(ResourceLocation id) {
        if (!isLoaded() || id == null || !MOD_ID.equals(id.getNamespace())) return null;
        try {
            for (VirtualRecipe vr : getGrillingVirtualRecipes()) {
                if (vr.getId().equals(id)) return vr;
            }
            for (VirtualRecipe vr : getThreadingVirtualRecipes()) {
                if (vr.getId().equals(id)) return vr;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    public static final class VirtualRecipe implements Recipe<SimpleContainer> {
        public Ingredient tool;
        public Ingredient ingredient;
        public int ingredientCount;
        public Ingredient side;
        public int sideCount;
        private final ResourceLocation id;
        private final ItemStack result;
        private final NonNullList<Ingredient> ingredients;

        public VirtualRecipe(ResourceLocation id, Ingredient tool, Ingredient ingredient, int ingredientCount,
                             Ingredient side, int sideCount, ItemStack result) {
            this.id = id;
            this.tool = tool;
            this.ingredient = ingredient;
            this.ingredientCount = ingredientCount;
            this.side = side;
            this.sideCount = sideCount;
            this.result = result;
            NonNullList<Ingredient> list = NonNullList.create();
            if (tool != null && !tool.isEmpty()) list.add(tool);
            if (ingredient != null && !ingredient.isEmpty()) list.add(ingredient);
            if (side != null && !side.isEmpty()) list.add(side);
            this.ingredients = list;
        }

        @Override public boolean matches(SimpleContainer c, net.minecraft.world.level.Level l) { return false; }
        @Override public ItemStack assemble(SimpleContainer c, RegistryAccess r) { return result.copy(); }
        @Override public boolean canCraftInDimensions(int w, int h) { return true; }
        @Override public ItemStack getResultItem(RegistryAccess r) { return result.copy(); }
        @Override public NonNullList<Ingredient> getIngredients() { return ingredients; }
        @Override public ResourceLocation getId() { return id; }
        @Override public RecipeSerializer<?> getSerializer() { return null; }
        @Override public RecipeType<?> getType() { return null; }
        @Override public boolean isSpecial() { return true; }
    }

    /** 把固定穿串配方包装为虚拟配方（签子作为 tool，组1=ingredient，其余组合并为 side）；
     *  §F29：以源列表引用为键的派生缓存（代际未变时不重新 new ResourceLocation/合并 Ingredient）。 */
    public static List<VirtualRecipe> getThreadingVirtualRecipes() {
        List<ThreadingDef> defs = getFixedThreading();
        if (defs == threadingVirtualSource) {
            return threadingVirtualCache; // 源命中同一引用 ⇒ 包装层直接复用
        }
        List<VirtualRecipe> out = new ArrayList<>();
        Ingredient stick = Ingredient.of(Items.STICK);
        for (ThreadingDef def : defs) {
            ResourceLocation id = ResourceLocation.fromNamespaceAndPath(MOD_ID, THREADING_ID_PREFIX + def.key().replace(':', '_').replace('/', '_'));
            Ingredient first = def.groups().get(0);
            Ingredient rest = null;
            if (def.groups().size() > 1) {
                List<Item> merged = new ArrayList<>();
                for (int i = 1; i < def.groups().size(); i++) {
                    for (ItemStack s : def.groups().get(i).getItems()) merged.add(s.getItem());
                }
                if (!merged.isEmpty()) rest = Ingredient.of(merged.toArray(new Item[0]));
            }
            out.add(new VirtualRecipe(id, stick, first, 1, rest, 1, def.result()));
        }
        threadingVirtualSource = defs;
        threadingVirtualCache = out;
        return out;
    }

    /** 按自定义材料组合构造虚拟穿串配方（产物为烟火未完成烤串）。 */
    public static VirtualRecipe makeCustomThreadingRecipe(List<ItemStack> ingredients, int quantity) {
        Ingredient stick = Ingredient.of(Items.STICK);
        Ingredient first = ingredients.get(0).isEmpty() ? Ingredient.EMPTY : Ingredient.of(ingredients.get(0));
        Ingredient rest = null;
        if (ingredients.size() > 1) {
            List<Item> merged = new ArrayList<>();
            for (int i = 1; i < ingredients.size(); i++) merged.add(ingredients.get(i).getItem());
            rest = Ingredient.of(merged.toArray(new Item[0]));
        }
        ItemStack result = makeCustomSkewer(ingredients);
        result.setCount(Math.max(1, quantity));
        return new VirtualRecipe(ResourceLocation.fromNamespaceAndPath(MOD_ID, "threading/custom"), stick, first, 1, rest, 1, result);
    }

    /** 把烟火烤制配对包装为虚拟烧烤配方（ingredient=生串，产物=熟串）；§F29 同款派生缓存。 */
    public static List<VirtualRecipe> getGrillingVirtualRecipes() {
        List<GrillingPair> pairs = getGrillingPairs();
        if (pairs == grillingVirtualSource) {
            return grillingVirtualCache;
        }
        List<VirtualRecipe> out = new ArrayList<>();
        for (GrillingPair pair : pairs) {
            ResourceLocation rawId = ForgeRegistries.ITEMS.getKey(pair.input().getItem());
            if (rawId == null) continue;
            ResourceLocation id = ResourceLocation.fromNamespaceAndPath(MOD_ID, "grilling/" + rawId.getPath());
            out.add(new VirtualRecipe(id, null, Ingredient.of(pair.input()), 1, null, 0, pair.output()));
        }
        grillingVirtualSource = pairs;
        grillingVirtualCache = out;
        return out;
    }
}
