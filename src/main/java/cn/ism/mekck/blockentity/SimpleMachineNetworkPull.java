package cn.ism.mekck.blockentity;

import cn.ism.mekck.ae2.AE2InputSpec;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.List;

/**
 * 遗留单机（14 台联动机器）的 <b>AE2 持续补料输入规格</b> 子系统：为每台机器算出
 * 「ME 网络该往本机补什么料」（{@code getNetworkPullInputs()} 的每个 case 一个构造器）。
 *
 * <h3>为什么要从 {@code SimpleMachineBlockEntity} 里独立出来</h3>
 * 这块约 230 行，是 BE 里仅次于「配方适配器族」与「流体子系统」的第三个职责面。
 * 它与 BE 的其余部分关注点不同：BE 管「这台机器当前做什么」，
 * 这里管「ME 端要按什么规格往这台机器里补料」。混在一起时，
 * 想读机器逻辑就得先跳过一大段 AE2 补料细节。
 *
 * <h3>搬运规则（与 {@link SimpleMachineRecipes} 同口径）</h3>
 * 本类的每个方法都是 {@code getNetworkPullInputs()} 的分支实现；该方法是
 * {@code INetworkPullable} 的 {@code @Override}，必须留在 BE 上（覆写不搬），
 * 由它按 {@code kind} 分派到本类。辅助方法（如 {@code collectCompactingInputs}）
 * <b>只有全部调用点都在本类内</b>时才跟着搬；否则留在 BE，这里用 {@code be.xxx()} 调。
 *
 * <p>本类<b>不持有任何自己的状态</b>：所有机器状态都从 {@code be} 取，
 * 因此搬运不改变任何读写时序 —— 与 {@link SimpleMachineFluids} / {@link SimpleMachineRecipes}
 * 一致，也是这个拆分敢在只有源码断言、没有实机环境的情况下做的原因。</p>
 */
public final class SimpleMachineNetworkPull {

    private final SimpleMachineBlockEntity be;

    public SimpleMachineNetworkPull(SimpleMachineBlockEntity be) {
        this.be = be;
    }

    /** 简单单输入：槽 0 已放料则取该配方第一个成分；空槽则取所有可处理配方的并集（一次拉任一）。 */
    List<AE2InputSpec> simpleSingleInput(String ns, String path, java.util.function.Predicate<Recipe<?>> filter) {
        RecipeType<?> rt = be.recipeTypeOf(ResourceLocation.fromNamespaceAndPath(ns, path));
        if (rt == null) return List.of();
        List<Recipe<?>> recipes = cn.ism.mekck.recipe.RecipeCache.all(be.getLevel(), rt);
        ItemStack slot0 = be.items.getStackInSlot(0);
        if (!slot0.isEmpty()) {
            for (Recipe<?> r : recipes) {
                if (!filter.test(r)) continue;
                List<Ingredient> ings = r.getIngredients();
                if (!ings.isEmpty() && !ings.get(0).isEmpty() && ings.get(0).test(slot0)) {
                    return List.of(new AE2InputSpec(ings.get(0)));
                }
            }
            return List.of();
        }
        // 空槽：并集成分（可拉任一可处理材料）
        Ingredient union = Ingredient.EMPTY;
        List<Ingredient> all = new ArrayList<>();
        for (Recipe<?> r : recipes) {
            if (!filter.test(r)) continue;
            List<Ingredient> ings = r.getIngredients();
            if (!ings.isEmpty() && !ings.get(0).isEmpty()) {
                all.add(ings.get(0));
            }
        }
        if (all.isEmpty()) return List.of();
        return List.of(new AE2InputSpec(Ingredient.merge(all)));
    }

    List<AE2InputSpec> multiIngredient(String ns, String path, java.util.function.Predicate<Recipe<?>> filter) {
        RecipeType<?> rt = be.recipeTypeOf(ResourceLocation.fromNamespaceAndPath(ns, path));
        if (rt == null) return List.of();
        List<Recipe<?>> recipes = cn.ism.mekck.recipe.RecipeCache.all(be.getLevel(), rt);
        ItemStack slot0 = be.items.getStackInSlot(0);
        if (slot0.isEmpty()) return List.of();
        for (Recipe<?> r : recipes) {
            if (!filter.test(r)) continue;
            List<Ingredient> ings = r.getIngredients();
            if (!ings.isEmpty() && !ings.get(0).isEmpty() && ings.get(0).test(slot0)) {
                List<AE2InputSpec> specs = new ArrayList<>();
                for (Ingredient ing : ings) {
                    if (!ing.isEmpty()) specs.add(new AE2InputSpec(ing));
                }
                return specs;
            }
        }
        return List.of();
    }

    List<AE2InputSpec> curdPullInputs() {
        ItemStack slot0 = be.items.getStackInSlot(0);
        if (!slot0.isEmpty()) {
            // 槽 0 已有料（含 F9 的 oreo_dough / tapioca_flour）：按其物品类型续料，天然覆盖 compacting 补料。
            return List.of(new AE2InputSpec(Ingredient.of(slot0.getItem())));
        }
        // 空槽：并集候选（凝乳块 + F9 createcafe compacting 输入），ME 可拉任一以起批。
        java.util.List<Ingredient> candidates = new java.util.ArrayList<>();
        candidates.add(Ingredient.of(ForgeRegistries.ITEMS.getValue(ResourceLocation.fromNamespaceAndPath("trailandtales_delight", "curd_block"))));
        candidates.add(Ingredient.of(ForgeRegistries.ITEMS.getValue(ResourceLocation.fromNamespaceAndPath("trailandtales_delight", "cherry_curd_block"))));
        collectCompactingInputs(candidates);
        return List.of(new AE2InputSpec(Ingredient.merge(candidates)));
    }

    /** F9：收集 {@code createcafe:} 的 {@code create:compacting} 配方输入（空槽时并入 ME 拉料候选）。 */
    private void collectCompactingInputs(java.util.List<Ingredient> out) {
        if (be.getLevel() == null) return;
        RecipeType<?> rt = cn.ism.mekck.recipe.RecipeCache.type(ResourceLocation.fromNamespaceAndPath("create", "compacting"));
        if (rt == null) return;
        for (Recipe<?> r : cn.ism.mekck.recipe.RecipeCache.all(be.getLevel(), rt)) {
            try {
                ResourceLocation rid = r.getId();
                if (rid == null || !"createcafe".equals(rid.getNamespace())) continue;
                for (Ingredient ing : r.getIngredients()) {
                    if (!ing.isEmpty()) out.add(ing);
                }
            } catch (Throwable ignored) {
            }
        }
    }

    List<AE2InputSpec> juicerPullInputs() {
        ItemStack slot0 = be.items.getStackInSlot(0);
        if (!slot0.isEmpty()) {
            List<AE2InputSpec> specs = new ArrayList<>();
            specs.add(new AE2InputSpec(Ingredient.of(slot0.getItem())));
            // 简报 §六③：酒瓶是载具、不在 ingredients 里 → 通用规格列不出来，
            // 不补这一条的话 AE2 自动补料永远缺瓶子、批次空转（槽 0 已占，瓶子自然进下一个空槽）。
            if (be.findVineryBottleSlot(1) < 0 && be.juicerHasBottleRequiringRecipe()) {
                Ingredient bottle = SimpleMachineBlockEntity.itemIng("vinery", "wine_bottle");
                if (bottle != null) specs.add(new AE2InputSpec(bottle));
            }
            return specs;
        }
        // 空槽：苹果 / 苹果浆 都可
        return List.of(new AE2InputSpec(Ingredient.merge(java.util.List.of(
                Ingredient.of(net.minecraft.world.item.Items.APPLE),
                Ingredient.of(ForgeRegistries.ITEMS.getValue(ResourceLocation.fromNamespaceAndPath("vinery", "apple_mash")))))));
    }

    List<AE2InputSpec> ricePullInputs() {
        RecipeType<?> rt = cn.ism.mekck.recipe.RecipeCache.type(ResourceLocation.fromNamespaceAndPath("farmersdelight", "cooking"));
        if (rt == null) return List.of();
        List<Recipe<?>> recipes = cn.ism.mekck.recipe.RecipeCache.all(be.getLevel(), rt);
        ItemStack slot0 = be.items.getStackInSlot(0);
        for (Recipe<?> r : recipes) {
            net.minecraft.resources.ResourceLocation rid = ForgeRegistries.ITEMS.getKey(r.getResultItem(be.getLevel().registryAccess()).getItem());
            if (rid == null) continue;
            boolean isRice = "farmersdelight:cooked_rice".equals(rid.toString()) || rid.getPath().contains("rice_ball");
            if (!isRice) continue;
            List<Ingredient> ings = r.getIngredients();
            if (ings.isEmpty() || ings.get(0).isEmpty()) continue;
            if (slot0.isEmpty()) {
                // 返回第一个可做米饭配方的全部材料（做米饭 = 大米）
                List<AE2InputSpec> specs = new ArrayList<>();
                for (Ingredient ing : ings) specs.add(new AE2InputSpec(ing));
                return specs;
            }
            if (ings.get(0).test(slot0)) {
                List<AE2InputSpec> specs = new ArrayList<>();
                for (Ingredient ing : ings) specs.add(new AE2InputSpec(ing));
                return specs;
            }
        }
        return List.of();
    }

    List<AE2InputSpec> sushiPullInputs() {
        ItemStack slot0 = be.items.getStackInSlot(0);
        if (slot0.isEmpty()) {
            // 空槽：熟米饭（底材）
            return List.of(new AE2InputSpec(SimpleMachineBlockEntity.riceIngredient()));
        }
        // 已放底材/米饭：返回该配方全部部件
        String[] types = {"cuisine_ordered", "cuisine_mixed", "cuisine_fixed"};
        for (String t : types) {
            RecipeType<?> rt = cn.ism.mekck.recipe.RecipeCache.type(ResourceLocation.fromNamespaceAndPath("youkaishomecoming", t));
            if (rt == null) continue;
            for (Recipe<?> r : cn.ism.mekck.recipe.RecipeCache.all(be.getLevel(), rt)) {
                try {
                    net.minecraft.resources.ResourceLocation base = (ResourceLocation) cn.ism.mekck.util.Reflect.call(r, "base");
                    if (base == null) continue;
                    net.minecraft.resources.ResourceLocation slot0Id = ForgeRegistries.ITEMS.getKey(slot0.getItem());
                    boolean baseDirect = slot0Id != null && slot0Id.equals(base);
                    boolean riceBase = !baseDirect && (slot0Id != null && "farmersdelight:cooked_rice".equals(slot0Id.toString()));
                    if (!baseDirect && !riceBase) continue;
                    @SuppressWarnings("unchecked")
                    List<Ingredient> parts = (List<Ingredient>) cn.ism.mekck.util.Reflect.call(r, "getCustomIngredients");
                    List<AE2InputSpec> specs = new ArrayList<>();
                    if (!baseDirect) specs.add(new AE2InputSpec(SimpleMachineBlockEntity.riceIngredient()));
                    if (parts != null) {
                        for (Ingredient ing : parts) {
                            if (!ing.isEmpty()) specs.add(new AE2InputSpec(ing));
                        }
                    }
                    return specs;
                } catch (Exception ignored) {
                }
            }
        }
        return List.of();
    }

    List<AE2InputSpec> wineryPullInputs() {
        // WINERY Tavern 模式：批次活跃时禁止 AE2 拉料混入输入区
        if (!be.getTavernBatch().isIdle()) return List.of();
        ItemStack slot0 = be.items.getStackInSlot(0);
        if (slot0.isEmpty()) {
            // 空槽：拉任意果汁瓶（红/白葡萄汁等）
            List<Ingredient> juices = new ArrayList<>();
            for (String id : new String[]{"red_grapejuice", "white_grapejuice", "red_jungle_grapejuice",
                    "red_savanna_grapejuice", "red_taiga_grapejuice", "white_jungle_grapejuice",
                    "white_savanna_grapejuice", "white_taiga_grapejuice", "apple_juice"}) {
                net.minecraft.world.item.Item item = ForgeRegistries.ITEMS.getValue(ResourceLocation.fromNamespaceAndPath("vinery", id));
                if (item != null && item != net.minecraft.world.item.Items.AIR) {
                    juices.add(Ingredient.of(item));
                }
            }
            if (juices.isEmpty()) return List.of();
            return List.of(new AE2InputSpec(Ingredient.merge(juices)));
        }
        // 已放果汁：取该果汁对应配方的配料
        RecipeType<?> rt = cn.ism.mekck.recipe.RecipeCache.type(ResourceLocation.fromNamespaceAndPath("vinery", "wine_fermentation"));
        if (rt == null) return List.of();
        for (Recipe<?> r : cn.ism.mekck.recipe.RecipeCache.all(be.getLevel(), rt)) {
            try {
                String type = cn.ism.mekck.util.VineryJuice.recipeJuiceType(r);
                if (type == null) continue;
                String juiceId = cn.ism.mekck.util.VineryJuice.itemIdForType(type);
                ResourceLocation juiceLoc = ResourceLocation.tryParse(juiceId);
                if (juiceLoc == null) continue;
                net.minecraft.world.item.Item juiceItem = ForgeRegistries.ITEMS.getValue(juiceLoc);
                if (juiceItem != null && juiceItem != net.minecraft.world.item.Items.AIR && slot0.getItem() == juiceItem) {
                    List<Ingredient> ings = r.getIngredients();
                    List<AE2InputSpec> specs = new ArrayList<>();
                    for (Ingredient ing : ings) {
                        if (!ing.isEmpty()) specs.add(new AE2InputSpec(ing));
                    }
                    return specs;
                }
            } catch (Exception ignored) {
            }
        }
        return List.of();
    }

    List<AE2InputSpec> fermenterPullInputs() {
        // 发酵机：返回原料 + 输入流体提示（流体拉取暂不支持，仅物品）
        ItemStack slot0 = be.items.getStackInSlot(0);
        if (slot0.isEmpty()) return List.of();
        RecipeType<?> rt = cn.ism.mekck.recipe.RecipeCache.type(ResourceLocation.fromNamespaceAndPath("youkaishomecoming", "simple_fermentation"));
        if (rt == null) return List.of();
        for (Recipe<?> r : cn.ism.mekck.recipe.RecipeCache.all(be.getLevel(), rt)) {
            try {
                @SuppressWarnings("unchecked")
                List<Ingredient> ings = (List<Ingredient>) r.getClass().getField("ingredients").get(r);
                if (ings == null || ings.isEmpty() || ings.get(0).isEmpty()) continue;
                if (!ings.get(0).test(slot0)) continue;
                List<AE2InputSpec> specs = new ArrayList<>();
                for (Ingredient ing : ings) specs.add(new AE2InputSpec(ing));
                return specs;
            } catch (Exception ignored) {
            }
        }
        return List.of();
    }

    /** 茶艺机的网络拉料目标：当前输入能合成的茶配方材料。 */
    List<AE2InputSpec> teaPullInputs() {
        int filled = 0;
        ItemStack sample = ItemStack.EMPTY;
        for (int s : be.activeInputSlots()) {
            ItemStack st = be.items.getStackInSlot(s);
            if (!st.isEmpty()) { filled++; sample = st; }
        }
        if (filled == 0) return List.of();
        for (Recipe<?> r : be.allRecipesOfKind()) {
            if (!be.isSimplyTeaResult(r)) continue;
            try {
                List<Ingredient> ings = r.getIngredients();
                boolean hit = false;
                for (Ingredient ing : ings) {
                    if (ing != null && !ing.isEmpty() && ing.test(sample)) { hit = true; break; }
                }
                if (!hit) continue;
                List<AE2InputSpec> specs = new ArrayList<>();
                for (Ingredient ing : ings) {
                    if (ing != null && !ing.isEmpty()) specs.add(new AE2InputSpec(ing));
                }
                return specs;
            } catch (Throwable ignored) {
            }
        }
        return List.of();
    }
}
