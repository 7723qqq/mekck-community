package cn.ism.mekck.blockentity;

import cn.ism.mekck.MachineKind;
import cn.ism.mekck.machine.MatchedRecipe;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;

import java.util.ArrayList;
import java.util.List;
import cn.ism.mekck.RedstoneControl;
import cn.ism.mekck.SideMode;
import cn.ism.mekck.UniversalCuttingMachine;
import cn.ism.mekck.block.SimpleMachineBlock;
import cn.ism.mekck.config.MekckConfig;
import cn.ism.mekck.menu.SimpleMachineMenu;
import cn.ism.mekck.util.PowerSlotUtil;
import cn.ism.mekck.upgrade.UpgradeHelper;
import net.minecraft.core.BlockPos;
import mekanism.api.heat.IHeatCapacitor;
import mekanism.api.heat.IMekanismHeatHandler;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.energy.EnergyStorage;
import net.minecraftforge.energy.IEnergyStorage;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemStackHandler;
import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import cn.ism.mekck.registry.MekCkLegacyMachines;

/**
 * 遗留单机（14 台联动机器）的<b>配方适配器族</b>：每种外部配方类型一个 {@code matchXxx()}，
 * 以及只在它们内部被调用的辅助方法。
 *
 * <h3>为什么独立出来</h3>
 * 这是 {@code SimpleMachineBlockEntity} 里最大的一块（66 个方法 / 约 1900 行）。
 * 它关心的层次与 BE 不同：BE 管「这台机器现在有什么状态、该做什么」，
 * 适配器管「这种外部配方在本机上怎么做」。两者混在一起时，
 * 想读机器逻辑就得先跳过 1600 行配方细节。
 *
 * <h3>搬运规则（决定了哪些方法跟着走、哪些留在 BE）</h3>
 * 一个辅助方法<b>只有全部调用点都在本类内</b>时才跟着搬；否则留在 BE，
 * 这里用 {@code be.xxx()} 调。这样不会出现「同一个方法两个类里各有一份」。
 *
 * <p>本类<b>不持有任何自己的状态</b>：所有机器状态都从 {@code be} 取，
 * 因此搬运不改变任何读写时序 —— 这比「少写几个前缀」重要得多，
 * 也是这个拆分敢在只有源码断言、没有实机环境的情况下做的原因。</p>
 */
public final class SimpleMachineRecipes {

    private final SimpleMachineBlockEntity be;

    public SimpleMachineRecipes(SimpleMachineBlockEntity be) {
        this.be = be;
    }

    MatchedRecipe matchSushi() {
        String[] types = {"cuisine_ordered", "cuisine_mixed", "cuisine_fixed"};
        for (String t : types) {
            RecipeType<?> rt = cn.ism.mekck.util.RecipeCache.type(new ResourceLocation("youkaishomecoming", t));
            if (rt == null) continue;
            for (Recipe<?> r : cn.ism.mekck.util.RecipeCache.all(be.getLevel(), rt)) {
                try {
                    ResourceLocation base = (ResourceLocation) cn.ism.mekck.util.Reflect.call(r, "base");
                    if (base == null) continue;
                    @SuppressWarnings("unchecked")
                    List<Ingredient> parts = (List<Ingredient>) cn.ism.mekck.util.Reflect.call(r, "getCustomIngredients");
                    ItemStack result = (ItemStack) cn.ism.mekck.util.Reflect.call(r, "getResult");
                    if (result == null || result.isEmpty()) continue;

                    List<Ingredient> required = new ArrayList<>();
                    // 底材：若输入 0 已有对应底材物品则直接用，否则用熟米饭（+海苔）
                    ItemStack slot0 = be.items.getStackInSlot(0);
                    ResourceLocation slot0Id = slot0.isEmpty() ? null : ForgeRegistries.ITEMS.getKey(slot0.getItem());
                    if (slot0Id != null && slot0Id.equals(base)) {
                        required.add(Ingredient.of(slot0.getItem()));
                    } else {
                        boolean kelpBase = base.getPath().equals("gunkan") || base.getPath().equals("california")
                                || base.getPath().equals("hosomaki") || base.getPath().equals("futomaki");
                        required.add(be.riceIngredient());
                        if (kelpBase) {
                            required.add(kelpIngredient());
                        }
                    }
                    if (parts != null) required.addAll(parts);

                    List<Integer> slots = matchIngredients(required);
                    if (slots != null) {
                        return new MatchedRecipe(slots, result);
                    }
                } catch (Exception ignored) {
                }
            }
        }
        return null;
    }

    Ingredient kelpIngredient() {
        return Ingredient.of(net.minecraft.world.item.Items.DRIED_KELP);
    }

    MatchedRecipe matchSlicer() {
        ItemStack in = be.items.getStackInSlot(0);
        if (in.isEmpty()) return null;
        RecipeType<?> ct = cn.ism.mekck.util.RecipeCache.type(new ResourceLocation("farmersdelight", "cutting"));
        if (ct == null) return null;
        for (Recipe<?> r : cn.ism.mekck.util.RecipeCache.all(be.getLevel(), ct)) {
            List<Ingredient> ings = r.getIngredients();
            if (ings.isEmpty() || !ings.get(0).test(in)) continue;
            List<ItemStack> results = be.cuttingResults(r);
            boolean slice = false;
            for (ItemStack rs : results) {
                ResourceLocation rid = ForgeRegistries.ITEMS.getKey(rs.getItem());
                if (rid != null && rid.getPath().contains("_slice")) {
                    slice = true;
                    break;
                }
            }
            if (!slice) continue;
            List<Integer> slots = matchIngredients(List.of(ings.get(0)));
            if (slots != null) {
                ItemStack out = results.isEmpty() ? r.getResultItem(be.getLevel().registryAccess()) : results.get(0);
                return new MatchedRecipe(slots, out);
            }
        }
        // farmersdelight 切段未命中 → 烘焙坊 bakeries:bread_knife（7 配方，面包切片）
        return matchBreadKnife();
    }

    MatchedRecipe matchBreadKnife() {
        RecipeType<?> rt = cn.ism.mekck.util.RecipeCache.type(new ResourceLocation("bakeries", "bread_knife"));
        if (rt == null || be.getLevel() == null) return null;
        ItemStack slot0 = be.items.getStackInSlot(0);
        if (slot0.isEmpty()) return null;
        for (Recipe<?> r : cn.ism.mekck.util.RecipeCache.all(be.getLevel(), rt)) {
            try {
                List<Ingredient> ings = r.getIngredients();
                if (ings.isEmpty() || !ings.get(0).test(slot0)) continue;
                ItemStack result = r.getResultItem(be.getLevel().registryAccess());
                if (result.isEmpty()) continue;
                return new MatchedRecipe(java.util.Collections.singletonList(0), null,
                        java.util.Collections.singletonList(ings.get(0)), result, 0,
                        net.minecraftforge.fluids.FluidStack.EMPTY,
                        net.minecraftforge.fluids.FluidStack.EMPTY, r.getId());
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    MatchedRecipe matchRice() {
        RecipeType<?> ct = cn.ism.mekck.util.RecipeCache.type(new ResourceLocation("farmersdelight", "cooking"));
        if (ct == null) return null;
        for (Recipe<?> r : cn.ism.mekck.util.RecipeCache.all(be.getLevel(), ct)) {
            ItemStack res = r.getResultItem(be.getLevel().registryAccess());
            if (res.isEmpty()) continue;
            ResourceLocation rid = ForgeRegistries.ITEMS.getKey(res.getItem());
            if (rid == null) continue;
            boolean isRice = "farmersdelight:cooked_rice".equals(rid.toString()) || rid.getPath().contains("rice_ball");
            if (!isRice) continue;
            List<Ingredient> ings = r.getIngredients();
            if (ings.isEmpty()) continue;
            List<Integer> slots = matchIngredients(ings);
            if (slots != null) {
                return new MatchedRecipe(slots, res);
            }
        }
        return null;
    }

    MatchedRecipe matchCurd() {
        ItemStack in = be.items.getStackInSlot(0);
        if (in.isEmpty()) return null;
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(in.getItem());
        if (id == null) return null;
        String outId = switch (id.toString()) {
            case "trailandtales_delight:curd_block" -> "trailandtales_delight:cheese_wheel";
            case "trailandtales_delight:cherry_curd_block" -> "trailandtales_delight:cherry_cheese_wheel";
            default -> null;
        };
        if (outId != null) {
            ItemStack result = new ItemStack(ForgeRegistries.ITEMS.getValue(new ResourceLocation(outId)));
            if (!result.isEmpty())
                return new MatchedRecipe(java.util.Collections.singletonList(0), result);
        }
        // 非樱途旅事凝乳块（或产物缺失）→ 尝试青青草甸奶酪配方
        MatchedRecipe meadow = matchMeadowCheese();
        if (meadow != null) return meadow;
        // F9：再尝试 createcafe 的 create:compacting（粉/团 → 定型半成品）
        return matchCompacting();
    }

    MatchedRecipe matchCompacting() {
        if (be.getLevel() == null) return null;
        RecipeType<?> rt = cn.ism.mekck.util.RecipeCache.type(new ResourceLocation("create", "compacting"));
        if (rt == null) return null;
        ItemStack slot0 = be.items.getStackInSlot(0);
        if (slot0.isEmpty()) return null;
        for (Recipe<?> r : cn.ism.mekck.util.RecipeCache.all(be.getLevel(), rt)) {
            try {
                ResourceLocation rid = r.getId();
                if (rid == null || !"createcafe".equals(rid.getNamespace())) continue;
                List<Ingredient> ings = r.getIngredients();
                // 单输入 compacting 才能落到槽 0（createcafe 这两条均为单成分）
                if (ings.size() != 1 || ings.get(0).isEmpty() || !ings.get(0).test(slot0)) continue;
                ItemStack result = r.getResultItem(be.getLevel().registryAccess());
                if (result.isEmpty()) continue;
                int need = Math.max(1, result.getCount());
                if (slot0.getCount() < need) continue;
                return new MatchedRecipe(java.util.Collections.singletonList(0),
                        java.util.Collections.singletonList(need),
                        ings, result.copy(), 0,
                        net.minecraftforge.fluids.FluidStack.EMPTY,
                        net.minecraftforge.fluids.FluidStack.EMPTY, rid);
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    MatchedRecipe matchMeadowCheese() {
        RecipeType<?> rt = cn.ism.mekck.util.RecipeCache.type(new ResourceLocation("meadow", "cheese"));
        if (rt == null || be.getLevel() == null) return null;
        java.util.List<Integer> slots = new java.util.ArrayList<>();
        java.util.List<ItemStack> slotStacks = new java.util.ArrayList<>();
        for (int s = 0; s < be.INPUT_COUNT; s++) {
            ItemStack st = be.items.getStackInSlot(s);
            if (!st.isEmpty()) {
                slots.add(s);
                slotStacks.add(st);
            }
        }
        if (slots.isEmpty()) return null;
        for (Recipe<?> r : cn.ism.mekck.util.RecipeCache.all(be.getLevel(), rt)) {
            try {
                List<Ingredient> required = new ArrayList<>();
                for (Ingredient ing : r.getIngredients()) {
                    if (ing != null && !ing.isEmpty()) required.add(ing);
                }
                if (required.isEmpty() || slotStacks.size() != required.size()) continue;
                int[] match = net.minecraftforge.common.util.RecipeMatcher.findMatches(slotStacks, required);
                if (match == null) continue;
                ItemStack result = r.getResultItem(be.getLevel().registryAccess());
                if (result.isEmpty()) continue;
                java.util.List<Integer> consumeSlots = new java.util.ArrayList<>();
                boolean[] used = new boolean[slots.size()];
                boolean ok = true;
                for (int i = 0; i < required.size(); i++) {
                    int idx = match[i];
                    if (idx < 0 || idx >= slots.size() || used[idx]) { ok = false; break; }
                    used[idx] = true;
                    consumeSlots.add(slots.get(idx));
                }
                if (!ok) continue;
                return new MatchedRecipe(consumeSlots, null, required, result, 0,
                        net.minecraftforge.fluids.FluidStack.EMPTY,
                        net.minecraftforge.fluids.FluidStack.EMPTY, r.getId());
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    MatchedRecipe matchDry() {
        ItemStack in = be.items.getStackInSlot(0);
        if (in.isEmpty()) return null;
        RecipeType<?> rt = cn.ism.mekck.util.RecipeCache.type(new ResourceLocation("youkaishomecoming", "drying_rack"));
        if (rt != null) {
            for (Recipe<?> r : cn.ism.mekck.util.RecipeCache.all(be.getLevel(), rt)) {
                List<Ingredient> ings = r.getIngredients();
                if (ings.isEmpty() || !ings.get(0).test(in)) continue;
                List<Integer> slots = matchIngredients(List.of(ings.get(0)));
                if (slots != null) {
                    ItemStack res = r.getResultItem(be.getLevel().registryAccess());
                    if (!res.isEmpty()) {
                        return new MatchedRecipe(slots, res);
                    }
                }
            }
        }
        // youkai 未命中 → 沉浸农艺 farm_and_charm:drying（15 配方，单输入单输出）
        return matchFarmDrying();
    }

    MatchedRecipe matchFarmDrying() {
        RecipeType<?> rt = cn.ism.mekck.util.RecipeCache.type(new ResourceLocation("farm_and_charm", "drying"));
        if (rt == null || be.getLevel() == null) return null;
        ItemStack slot0 = be.items.getStackInSlot(0);
        if (slot0.isEmpty()) return null;
        for (Recipe<?> r : cn.ism.mekck.util.RecipeCache.all(be.getLevel(), rt)) {
            try {
                List<Ingredient> ings = r.getIngredients();
                if (ings.isEmpty() || !ings.get(0).test(slot0)) continue;
                ItemStack result = r.getResultItem(be.getLevel().registryAccess());
                if (result.isEmpty()) continue;
                return new MatchedRecipe(java.util.Collections.singletonList(0), null,
                        java.util.Collections.singletonList(ings.get(0)), result, 0,
                        net.minecraftforge.fluids.FluidStack.EMPTY,
                        net.minecraftforge.fluids.FluidStack.EMPTY, r.getId());
            } catch (Exception ignored) {
            }
        }
        return null;
    }


    // ── 发酵机：youkaishomecoming:simple_fermentation（物品 + 输入/输出流体 + time）──

    MatchedRecipe matchFerment() {
        RecipeType<?> rt = cn.ism.mekck.util.RecipeCache.type(new ResourceLocation("youkaishomecoming", "simple_fermentation"));
        if (rt == null) return null;
        for (Recipe<?> r : cn.ism.mekck.util.RecipeCache.all(be.getLevel(), rt)) {
            try {
                // 配方字段：ingredients / results / inputFluid / outputFluid / time（l2library 直读字段）
                @SuppressWarnings("unchecked")
                List<Ingredient> ings = (List<Ingredient>) r.getClass().getField("ingredients").get(r);
                @SuppressWarnings("unchecked")
                java.util.ArrayList<ItemStack> results = (java.util.ArrayList<ItemStack>) r.getClass().getField("results").get(r);
                net.minecraftforge.fluids.FluidStack inFluid = (net.minecraftforge.fluids.FluidStack) r.getClass().getField("inputFluid").get(r);
                net.minecraftforge.fluids.FluidStack outFluid = (net.minecraftforge.fluids.FluidStack) r.getClass().getField("outputFluid").get(r);
                int time = r.getClass().getField("time").getInt(r);
                if (ings == null || ings.isEmpty()) continue;

                // 流体校验：输入罐有足量且匹配的流体；输出罐可容纳
                if (inFluid != null && !inFluid.isEmpty()) {
                    net.minecraftforge.fluids.FluidStack stored = be.fluids.getInputTank().getFluid();
                    if (!stored.isFluidEqual(inFluid) || stored.getAmount() < inFluid.getAmount()) continue;
                }
                if (outFluid != null && !outFluid.isEmpty()) {
                    if (!be.fluids.getOutputTank().isEmpty() && !be.fluids.getOutputTank().getFluid().isFluidEqual(outFluid)) continue;
                    if (be.fluids.getOutputTank().getFluidAmount() + outFluid.getAmount() > be.fluids.getOutputTank().getCapacity()) continue;
                }

                List<Integer> slots = matchIngredients(ings);
                if (slots != null) {
                    ItemStack result = (results == null || results.isEmpty()) ? ItemStack.EMPTY : results.get(0);
                    net.minecraftforge.fluids.FluidStack drain = inFluid == null ? net.minecraftforge.fluids.FluidStack.EMPTY : inFluid;
                    net.minecraftforge.fluids.FluidStack fill = outFluid == null ? net.minecraftforge.fluids.FluidStack.EMPTY : outFluid;
                    return new MatchedRecipe(slots, result, Math.max(0, time), drain, fill);
                }
            } catch (Exception ignored) {
            }
        }
        // youkai 未命中 → 尝试 bakeries:fermentation（馥郁烘焙：多物品无序匹配 + 容器展示）
        MatchedRecipe bakeries = matchBakeriesFermentation();
        if (bakeries != null) return bakeries;
        // 仍未命中 → 盛节精酿 brewery:brewing（16 配方：3 输入无序匹配）
        MatchedRecipe brewery = matchBreweryBrewing();
        if (brewery != null) return brewery;
        // 仍未命中 → 喝啤酒啦 drinkbeer:brewing（9 配方：4 输入无序匹配 + 酿造时长）
        return matchDrinkBeerBrewing();
    }

    MatchedRecipe matchExtractor() {
        // §F19 C+E 半：先读本仓自有 mekck:extracting（酱=流体产物/巧克力=物品产物，多入、流体输入可选），
        // 未命中再回退 create:mixing（createcafe 茶/咖啡/糖浆系，反射链路照旧）。
        MatchedRecipe own = matchExtracting();
        if (own != null) return own;
        RecipeType<?> rt = cn.ism.mekck.util.RecipeCache.type(new ResourceLocation("create", "mixing"));
        if (rt == null) return null;
        for (Recipe<?> r : cn.ism.mekck.util.RecipeCache.all(be.getLevel(), rt)) {
            try {
                if (!isExtractorRecipe(r)) continue;
                java.util.List<Ingredient> ings = new ArrayList<>(r.getIngredients());
                List<Integer> slots = matchIngredients(ings);
                if (slots == null) continue;
                // 输出流体（取首个非空）
                net.minecraftforge.fluids.FluidStack outFluid = net.minecraftforge.fluids.FluidStack.EMPTY;
                for (net.minecraftforge.fluids.FluidStack f : mixingFluidResults(r)) {
                    if (f != null && !f.isEmpty()) { outFluid = f; break; }
                }
                if (outFluid.isEmpty()) continue;
                if (!be.fluids.getOutputTank().isEmpty() && !be.fluids.getOutputTank().getFluid().isFluidEqual(outFluid)) continue;
                if (be.fluids.getOutputTank().getFluidAmount() + outFluid.getAmount() > be.fluids.getOutputTank().getCapacity()) continue;
                // 输入流体（首个需者）：不足则本 tick 不匹配
                net.minecraftforge.fluids.FluidStack inFluid = net.minecraftforge.fluids.FluidStack.EMPTY;
                for (Object fi : mixingFluidIngredients(r)) {
                    net.minecraftforge.fluids.FluidStack req = firstRequiredFluid(fi);
                    if (req != null && !req.isEmpty()) {
                        net.minecraftforge.fluids.FluidStack stored = be.fluids.getInputTank().getFluid();
                        if (!stored.isFluidEqual(req) || stored.getAmount() < req.getAmount()) { inFluid = null; break; }
                        inFluid = req;
                        break;
                    }
                }
                if (inFluid == null) continue;
                return new MatchedRecipe(slots, ItemStack.EMPTY, extractorTime(r), inFluid, outFluid);
            } catch (Throwable ignored) {
            }
        }
        return null;
    }


    /**
     * §F19 C+E 半：{@code mekck:extracting} 自有匹配——1..5 物品输入（{@link #matchIngredients} 无序）
     * + 可选流体输入（精确 id 或 {@code #tag}，对 be.fluids.getInputTank() 比对）→ 产物二选一：
     * 流体产物进 be.fluids.getOutputTank()（同型/容量预检），物品产物走通用 result 通道进产物槽 5。
     */
    MatchedRecipe matchExtracting() {
        RecipeType<?> rt = cn.ism.mekck.util.RecipeCache.type(
                new ResourceLocation(UniversalCuttingMachine.MOD_ID, "extracting"));
        if (rt == null) return null;
        for (Recipe<?> r : cn.ism.mekck.util.RecipeCache.all(be.getLevel(), rt)) {
            try {
                if (!(r instanceof cn.ism.mekck.recipe.ExtractingRecipe er)) continue;
                List<Integer> slots = matchIngredients(new ArrayList<>(er.getItemIngredients()));
                if (slots == null) continue;
                // 输入流体（可选）：配方未给 ⇒ 对罐无要求；给了 ⇒ 罐内同型（含 #tag）且足量
                net.minecraftforge.fluids.FluidStack drain = net.minecraftforge.fluids.FluidStack.EMPTY;
                cn.ism.mekck.recipe.ExtractingRecipe.FluidInput inNeed = er.getInputFluid();
                if (inNeed != null) {
                    net.minecraftforge.fluids.FluidStack stored = be.fluids.getInputTank().getFluid();
                    if (!inNeed.matches(stored.getFluid()) || stored.getAmount() < inNeed.amount) continue;
                    drain = stored.copy();
                    drain.setAmount(inNeed.amount);
                }
                int time = er.getProcessTime() > 0 ? er.getProcessTime() : be.kind.processTime;
                if (!er.getFluidResult().isEmpty()) {
                    net.minecraftforge.fluids.FluidStack outFluid = er.getFluidResult();
                    if (!be.fluids.getOutputTank().isEmpty() && !be.fluids.getOutputTank().getFluid().isFluidEqual(outFluid)) continue;
                    if (be.fluids.getOutputTank().getFluidAmount() + outFluid.getAmount() > be.fluids.getOutputTank().getCapacity()) continue;
                    return new MatchedRecipe(slots, ItemStack.EMPTY, time, drain, outFluid);
                }
                ItemStack res = er.getResultItem(be.getLevel().registryAccess());
                if (res.isEmpty()) continue;
                return new MatchedRecipe(slots, res, time, drain, net.minecraftforge.fluids.FluidStack.EMPTY);
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    boolean isExtractorRecipe(Recipe<?> r) {
        ResourceLocation id = r.getId();
        if (id == null) return false;
        // §F19：mekck 命名空间的配方不受 createcafe 硬卡（正常路径下 mekck:extracting 走
        // {@link #matchExtracting} 自有循环，不经本谓词；此处为防御性放行，免得未来 mekck
        // 配方被误写进 create:mixing 时整条拒掉）。
        if ("mekck".equals(id.getNamespace())) return true;
        // Q13 破例白名单：奥利奥夹心酱（唯一用途 = oreo 组装序列内注入）
        if ("createcafe:oreo_filling_mixing".equals(id.toString())) return true;
        if (!"createcafe".equals(id.getNamespace())) return false;
        for (net.minecraftforge.fluids.FluidStack f : mixingFluidResults(r)) {
            if (f == null || f.isEmpty()) continue;
            ResourceLocation fid = ForgeRegistries.FLUIDS.getKey(f.getFluid());
            if (fid == null) continue;
            String p = fid.getPath();
            if (p.contains("tea") || p.contains("coffee") || p.contains("melted_sugar")
                    || p.contains("syrup") || p.contains("sugar")) return true;
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    java.util.List<net.minecraftforge.fluids.FluidStack> mixingFluidResults(Recipe<?> r) {
        Object o = cn.ism.mekck.util.Reflect.call(r, "getFluidResults");
        return (o instanceof java.util.List) ? (java.util.List<net.minecraftforge.fluids.FluidStack>) o : java.util.List.of();
    }

    @SuppressWarnings("unchecked")
    java.util.List<Object> mixingFluidIngredients(Recipe<?> r) {
        Object o = cn.ism.mekck.util.Reflect.call(r, "getFluidIngredients");
        return (o instanceof java.util.List) ? (java.util.List<Object>) o : java.util.List.of();
    }

    @SuppressWarnings("unchecked")
    net.minecraftforge.fluids.FluidStack firstRequiredFluid(Object fluidIngredient) {
        if (fluidIngredient == null) return net.minecraftforge.fluids.FluidStack.EMPTY;
        Object ms = cn.ism.mekck.util.Reflect.call(fluidIngredient, "getMatchingFluidStacks");
        if (!(ms instanceof java.util.List<?> list) || list.isEmpty()) return net.minecraftforge.fluids.FluidStack.EMPTY;
        if (!(list.get(0) instanceof net.minecraftforge.fluids.FluidStack base) || base.isEmpty()) return net.minecraftforge.fluids.FluidStack.EMPTY;
        net.minecraftforge.fluids.FluidStack copy = base.copy();
        Object amt = cn.ism.mekck.util.Reflect.call(fluidIngredient, "getRequiredAmount");
        if (amt instanceof Integer a && a > 0) copy.setAmount(a);
        return copy;
    }

    int extractorTime(Recipe<?> r) {
        Object d = cn.ism.mekck.util.Reflect.call(r, "getProcessingDuration");
        if (d instanceof Integer sec && sec > 0) return sec * 20;
        return be.kind.processTime;
    }

    MatchedRecipe matchBeverageAssembly() {
        RecipeType<?> rt = cn.ism.mekck.util.RecipeCache.type(
                new ResourceLocation(UniversalCuttingMachine.MOD_ID, "beverage_assembly"));
        if (rt == null) return null;
        for (Recipe<?> r : cn.ism.mekck.util.RecipeCache.all(be.getLevel(), rt)) {
            try {
                if (!(r instanceof cn.ism.mekck.recipe.BeverageAssemblyRecipe bar)) continue;
                List<Integer> slots = matchIngredients(bar.getItemIngredients());
                if (slots == null) continue;
                net.minecraftforge.fluids.FluidStack need = bar.getFluid();
                if (need == null || need.isEmpty()) continue;
                net.minecraftforge.fluids.FluidStack stored = be.fluids.getInputTank().getFluid();
                if (!stored.isFluidEqual(need) || stored.getAmount() < need.getAmount()) continue;
                ItemStack result = bar.getResultItem(be.getLevel().registryAccess());
                if (result.isEmpty()) continue;
                int time = bar.getProcessTime() > 0 ? bar.getProcessTime() : be.kind.processTime;
                return new MatchedRecipe(slots, result, time, need, net.minecraftforge.fluids.FluidStack.EMPTY);
            } catch (Throwable ignored) {
            }
        }
        return null;
    }


    // ── 包材组装机（F7 / F11 §四.4）：自有类型 mekck:packaging（若干材料→1 包材，无流体）──

    /**
     * 包材机匹配：读 {@code mekck:packaging}（同模块类），把每个输入成分分配到不同输入槽（各耗 1），
     * 产出单个包材物品。无流体参与。只读本类型，因此「仅产包材」由配方内容天然约束。
     */
    MatchedRecipe matchPackaging() {
        RecipeType<?> rt = cn.ism.mekck.util.RecipeCache.type(
                new ResourceLocation(UniversalCuttingMachine.MOD_ID, "packaging"));
        if (rt == null) return null;
        for (Recipe<?> r : cn.ism.mekck.util.RecipeCache.all(be.getLevel(), rt)) {
            try {
                if (!(r instanceof cn.ism.mekck.recipe.PackagingRecipe pr)) continue;
                List<Integer> slots = matchIngredients(pr.getItemIngredients());
                if (slots == null) continue;
                ItemStack result = pr.getResultItem(be.getLevel().registryAccess());
                if (result.isEmpty()) continue;
                int time = pr.getProcessTime() > 0 ? pr.getProcessTime() : be.kind.processTime;
                return new MatchedRecipe(slots, result, time,
                        net.minecraftforge.fluids.FluidStack.EMPTY, net.minecraftforge.fluids.FluidStack.EMPTY);
            } catch (Throwable ignored) {
            }
        }
        return null;
    }


    /**
     * 喝啤酒啦 drinkbeer:brewing（9 配方）。参考 BrewingRecipe.matches 的真实语义：
     * 无序一对一匹配（剩余列表逐个消耗），要求 supplied.size() == input.size()（非空输入数 == ingredient 数）；
     * 时间取 brewingTime（反射 getBrewingTime）；cup（啤酒杯）由 isCupQualified 校验，MekCK 一并要求并扣除对应数量的空杯。
     */
    MatchedRecipe matchDrinkBeerBrewing() {
        RecipeType<?> rt = cn.ism.mekck.util.RecipeCache.type(new ResourceLocation("drinkbeer", "brewing"));
        if (rt == null || be.getLevel() == null) return null;
        java.util.List<Integer> slots = new java.util.ArrayList<>();
        java.util.List<ItemStack> slotStacks = new java.util.ArrayList<>();
        for (int s = 0; s < be.INPUT_COUNT; s++) {
            ItemStack st = be.items.getStackInSlot(s);
            if (!st.isEmpty()) {
                slots.add(s);
                slotStacks.add(st);
            }
        }
        if (slots.isEmpty()) return null;
        for (Recipe<?> r : cn.ism.mekck.util.RecipeCache.all(be.getLevel(), rt)) {
            try {
                List<Ingredient> required = new ArrayList<>();
                List<Integer> requiredCounts = new ArrayList<>();
                for (Ingredient ing : r.getIngredients()) {
                    if (ing != null && !ing.isEmpty()) {
                        required.add(ing);
                        requiredCounts.add(1);
                    }
                }
                // 啤酒杯：原版 BrewingRecipe.isCupQualified 要求酿造库存中备齐 cup.count 个空杯，
                // 酿好后空杯被替换为成品酒（每槽 1 组，整组按 count 扣除）。
                Object cupObj = be.callNoArg(r, "getBeerCup");
                Ingredient cupIng = containerIng(cupObj);
                if (cupIng != null) {
                    required.add(cupIng);
                    requiredCounts.add(Math.max(1, ((ItemStack) cupObj).getCount()));
                }
                if (required.isEmpty() || slotStacks.size() != required.size()) continue;
                int[] match = net.minecraftforge.common.util.RecipeMatcher.findMatches(slotStacks, required);
                if (match == null) continue;
                ItemStack result = r.getResultItem(be.getLevel().registryAccess());
                if (result.isEmpty()) continue;
                java.util.List<Integer> consumeSlots = new java.util.ArrayList<>();
                java.util.List<Integer> consumeCounts = new java.util.ArrayList<>();
                boolean[] used = new boolean[slots.size()];
                boolean ok = true;
                for (int i = 0; i < required.size(); i++) {
                    int idx = match[i];
                    if (idx < 0 || idx >= slots.size() || used[idx]) { ok = false; break; }
                    used[idx] = true;
                    consumeSlots.add(slots.get(idx));
                    consumeCounts.add(requiredCounts.get(i));
                }
                if (!ok) continue;
                int time = 0;
                try {
                    Object t = cn.ism.mekck.util.Reflect.call(r, "getBrewingTime");
                    if (t instanceof Integer ti) time = Math.max(0, ti);
                } catch (Throwable ignored) {
                }
                return new MatchedRecipe(consumeSlots, consumeCounts, required, result, time,
                        net.minecraftforge.fluids.FluidStack.EMPTY,
                        net.minecraftforge.fluids.FluidStack.EMPTY, r.getId());
            } catch (Exception ignored) {
            }
        }
        return null;
    }


    /**
     * 盛节精酿 brewery:brewing（16 配方）。参考 BrewingRecipe.matches 的真实语义：
     * 无序匹配（StackedContents 计数），且**非空槽数必须等于 ingredient 数**（防吞多余材料）；
     * material 字段为分类标签（WOOD 等），不参与匹配；结果取 getResultItem。
     */
    MatchedRecipe matchBreweryBrewing() {
        RecipeType<?> rt = cn.ism.mekck.util.RecipeCache.type(new ResourceLocation("brewery", "brewing"));
        if (rt == null || be.getLevel() == null) return null;
        java.util.List<Integer> slots = new java.util.ArrayList<>();
        java.util.List<ItemStack> slotStacks = new java.util.ArrayList<>();
        for (int s = 0; s < be.INPUT_COUNT; s++) {
            ItemStack st = be.items.getStackInSlot(s);
            if (!st.isEmpty()) {
                slots.add(s);
                slotStacks.add(st);
            }
        }
        if (slots.isEmpty()) return null;
        for (Recipe<?> r : cn.ism.mekck.util.RecipeCache.all(be.getLevel(), rt)) {
            try {
                List<Ingredient> required = new ArrayList<>();
                for (Ingredient ing : r.getIngredients()) {
                    if (ing != null && !ing.isEmpty()) required.add(ing);
                }
                if (required.isEmpty() || slotStacks.size() != required.size()) continue;
                int[] match = net.minecraftforge.common.util.RecipeMatcher.findMatches(slotStacks, required);
                if (match == null) continue;
                ItemStack result = r.getResultItem(be.getLevel().registryAccess());
                if (result.isEmpty()) continue;
                java.util.List<Integer> consumeSlots = new java.util.ArrayList<>();
                boolean[] used = new boolean[slots.size()];
                boolean ok = true;
                for (int i = 0; i < required.size(); i++) {
                    int idx = match[i];
                    if (idx < 0 || idx >= slots.size() || used[idx]) { ok = false; break; }
                    used[idx] = true;
                    consumeSlots.add(slots.get(idx));
                }
                if (!ok) continue;
                return new MatchedRecipe(consumeSlots, null, required, result, 0,
                        net.minecraftforge.fluids.FluidStack.EMPTY,
                        net.minecraftforge.fluids.FluidStack.EMPTY, r.getId());
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    MatchedRecipe matchBakeriesFermentation() {
        RecipeType<?> rt = cn.ism.mekck.util.RecipeCache.type(new ResourceLocation("bakeries", "fermentation"));
        if (rt == null || be.getLevel() == null) return null;
        // 非空输入槽集合
        java.util.List<Integer> slots = new java.util.ArrayList<>();
        java.util.List<ItemStack> slotStacks = new java.util.ArrayList<>();
        for (int s = 0; s < be.INPUT_COUNT; s++) {
            ItemStack st = be.items.getStackInSlot(s);
            if (!st.isEmpty()) {
                slots.add(s);
                slotStacks.add(st);
            }
        }
        if (slots.isEmpty()) return null;
        for (Recipe<?> r : cn.ism.mekck.util.RecipeCache.all(be.getLevel(), rt)) {
            try {
                List<Ingredient> required = new ArrayList<>();
                for (Ingredient ing : r.getIngredients()) {
                    if (ing != null && !ing.isEmpty()) required.add(ing);
                }
                if (required.isEmpty() || slotStacks.size() != required.size()) continue;
                int[] match = net.minecraftforge.common.util.RecipeMatcher.findMatches(slotStacks, required);
                if (match == null) continue;
                ItemStack result = r.getResultItem(be.getLevel().registryAccess());
                if (result.isEmpty()) continue;
                // 重建消耗槽/数量/匹配条件（水桶不消耗）
                java.util.List<Integer> consumeSlots = new java.util.ArrayList<>();
                java.util.List<Integer> consumeCounts = new java.util.ArrayList<>();
                java.util.List<Ingredient> inputIngredients = new java.util.ArrayList<>();
                boolean[] used = new boolean[slots.size()];
                boolean ok = true;
                for (int i = 0; i < required.size(); i++) {
                    int idx = match[i];
                    if (idx < 0 || idx >= slots.size() || used[idx]) { ok = false; break; }
                    used[idx] = true;
                    int slot = slots.get(idx);
                    ItemStack st = be.items.getStackInSlot(slot);
                    consumeSlots.add(slot);
                    consumeCounts.add(st.is(net.minecraft.world.item.Items.WATER_BUCKET) ? 0 : 1);
                    inputIngredients.add(required.get(i));
                }
                if (!ok) continue;
                return new MatchedRecipe(consumeSlots, consumeCounts, inputIngredients, result,
                        3600, net.minecraftforge.fluids.FluidStack.EMPTY,
                        net.minecraftforge.fluids.FluidStack.EMPTY, r.getId());
            } catch (Exception ignored) {
            }
        }
        return null;
    }


    // ── 蒸制机：youkaishomecoming:steaming（FD 式 ingredient+cookingtime+result）──

    MatchedRecipe matchSteam() {
        ItemStack in = be.items.getStackInSlot(0);
        if (in.isEmpty()) return null;
        // F11 §四.5：youkai 蒸笼 + 森罗万法 kaleidoscope_cookery:steamer（均单物品入→单物品出，同型）
        ResourceLocation[] steamTypes = {
                new ResourceLocation("youkaishomecoming", "steaming"),
                new ResourceLocation("kaleidoscope_cookery", "steamer"),
        };
        for (ResourceLocation tid : steamTypes) {
            RecipeType<?> rt = cn.ism.mekck.util.RecipeCache.type(tid);
            if (rt == null) continue;
            for (Recipe<?> r : cn.ism.mekck.util.RecipeCache.all(be.getLevel(), rt)) {
                try {
                    List<Ingredient> ings = r.getIngredients();
                    if (ings.isEmpty() || !ings.get(0).test(in)) continue;
                    List<Integer> slots = matchIngredients(List.of(ings.get(0)));
                    if (slots != null) {
                        ItemStack res = r.getResultItem(be.getLevel().registryAccess());
                        if (!res.isEmpty()) {
                            return new MatchedRecipe(slots, res);
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        return null;
    }

    boolean matchesRecipeSignature(net.minecraft.world.item.crafting.Recipe<?> r, String planSignature) {
        try {
            Object fluidObj = cn.ism.mekck.util.Reflect.call(r, "fluid");
            if (!(fluidObj instanceof net.minecraft.world.level.material.Fluid f)) return false;
            Object ingredientsObj = cn.ism.mekck.util.Reflect.call(r, "ingredients");
            if (!(ingredientsObj instanceof net.minecraft.core.NonNullList<?> ingsRaw)) return false;
            java.util.List<net.minecraft.world.item.crafting.Ingredient> ings = new java.util.ArrayList<>();
            for (Object o : ingsRaw) {
                if (o instanceof net.minecraft.world.item.crafting.Ingredient ing) ings.add(ing);
            }
            Object resultObj = cn.ism.mekck.util.Reflect.call(r, "result");
            if (!(resultObj instanceof ItemStack result) || result.isEmpty()) return false;
            Object unitObj = cn.ism.mekck.util.Reflect.call(r, "unitTime");
            if (!(unitObj instanceof Integer unit)) return false;
            Object carrierObj = cn.ism.mekck.util.Reflect.call(r, "carrier");
            String carrierJson = carrierObj instanceof net.minecraft.world.item.crafting.Ingredient c
                    ? c.toJson().toString() : null;
            if (carrierJson == null) return false;
            StringBuilder sig = new StringBuilder();
            for (net.minecraft.world.item.crafting.Ingredient ing : ings) sig.append(ing.toJson()).append('|');
            sig.append(net.minecraftforge.registries.ForgeRegistries.FLUIDS.getKey(f)).append('|');
            sig.append(net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(result.getItem())).append('|');
            sig.append(result.getCount()).append('|').append(unit).append('|').append(carrierJson);
            return sig.toString().equals(planSignature);
        } catch (Throwable t) {
            return false; // 无法读取 → 保守拒绝
        }
    }


    /**
     * 匹配 Tavern barrel 配方生成启动计划（单次扫描，稳定优先级按 recipeId 字典序最小）。
     * 验证：流体满 4000mB 且种类匹配；配料 RecipeMatcher 匹配（0-4 槽；空配方要求无配料）；
     * 批次数量合法；result/carrier/unitTime 可读。任一不满足返回 null。
     */
    TavernBarrelPlan matchTavernBarrelPlan() {
        if (be.getLevel() == null) return null;
        net.minecraft.world.item.crafting.RecipeType<?> barrelT = cn.ism.mekck.compat.TavernBarrelCompat.type();
        if (barrelT == null) return null; // tavern 未安装 → 保持 Vinery 原样
        // 流体：必须满 4000mB
        if (be.fluids.getInputTank().getFluidAmount() < TavernBarrelPlan.MAX_FLUID_AMOUNT) return null;
        net.minecraftforge.fluids.FluidStack tankFluid = be.fluids.getInputTank().getFluid();
        if (tankFluid.isEmpty()) return null;
        // 输入槽（0..3 配料；槽 4 预留不参与——参考 MAX_ITEM_SLOTS=4）
        java.util.List<ItemStack> ingredientStacks = new java.util.ArrayList<>();
        for (int i = 0; i < Math.min(TavernBarrelPlan.MAX_INGREDIENT_SLOTS, be.INPUT_COUNT); i++) {
            ingredientStacks.add(be.items.getStackInSlot(i));
        }
        TavernBarrelPlan best = null;
        for (net.minecraft.world.item.crafting.Recipe<?> r : cn.ism.mekck.util.RecipeCache.all(be.getLevel(), barrelT)) {
            // 反射读取 record 访问器（避免编译期依赖 tavern）
            try {
                Object fluidObj = cn.ism.mekck.util.Reflect.call(r, "fluid");
                if (!(fluidObj instanceof net.minecraft.world.level.material.Fluid f)) continue;
                if (!tankFluid.getFluid().isSame(f)) continue; // 参考 matches 用 isSame
                Object ingredientsObj = cn.ism.mekck.util.Reflect.call(r, "ingredients");
                if (!(ingredientsObj instanceof net.minecraft.core.NonNullList<?> ingsRaw)) continue;
                java.util.List<net.minecraft.world.item.crafting.Ingredient> ings = new java.util.ArrayList<>();
                for (Object o : ingsRaw) {
                    if (o instanceof net.minecraft.world.item.crafting.Ingredient ing) ings.add(ing);
                }
                boolean emptyIngredients = true;
                for (net.minecraft.world.item.crafting.Ingredient ing : ings) {
                    if (ing != null && !ing.isEmpty()) {
                        emptyIngredients = false;
                        break;
                    }
                }
                // 完整配料匹配（参考 RecipeMatcher.findMatches）：
                // 收集全部非空配料槽；非空槽数必须 == 配方非空 Ingredient 数（多余/缺少均拒绝，
                // 避免提交时清空未参与配方的材料）；RecipeMatcher 回溯处理重复/替代 Ingredient。
                java.util.List<Integer> slots = new java.util.ArrayList<>();
                java.util.List<ItemStack> slotStacks = new java.util.ArrayList<>();
                java.util.List<ItemStack> snapshots = new java.util.ArrayList<>();
                java.util.List<net.minecraft.world.item.crafting.Ingredient> required = new java.util.ArrayList<>();
                for (net.minecraft.world.item.crafting.Ingredient ing : ings) {
                    if (ing != null && !ing.isEmpty()) required.add(ing);
                }
                if (required.isEmpty()) {
                    // 纯流体配方：配料区必须为空
                    boolean anyIngredient = false;
                    for (int s = 0; s < TavernBarrelPlan.MAX_INGREDIENT_SLOTS; s++) {
                        if (!be.items.getStackInSlot(s).isEmpty()) { anyIngredient = true; break; }
                    }
                    if (anyIngredient) continue;
                } else {
                    for (int s = 0; s < TavernBarrelPlan.MAX_INGREDIENT_SLOTS; s++) {
                        ItemStack st = be.items.getStackInSlot(s);
                        if (st.isEmpty()) continue;
                        slots.add(s);
                        slotStacks.add(st);
                        snapshots.add(st.copy());
                    }
                    if (slotStacks.size() != required.size()) continue; // 多余或缺少 → 拒绝（防吞料）
                    int[] match = net.minecraftforge.common.util.RecipeMatcher.findMatches(slotStacks, required);
                    if (match == null) continue; // 无法完整匹配（回溯失败）
                    // match[i] = slotStacks 中第 i 个 Ingredient 命中的物品索引；按槽序重建 slots
                    java.util.List<Integer> ordered = new java.util.ArrayList<>();
                    java.util.List<ItemStack> orderedSnap = new java.util.ArrayList<>();
                    boolean[] usedSlot = new boolean[slots.size()];
                    for (int i = 0; i < required.size(); i++) {
                        int itemIdx = match[i];
                        if (itemIdx < 0 || itemIdx >= slots.size() || usedSlot[itemIdx]) { ordered = null; break; }
                        usedSlot[itemIdx] = true;
                        ordered.add(slots.get(itemIdx));
                        orderedSnap.add(snapshots.get(itemIdx));
                    }
                    if (ordered == null) continue;
                    slots = ordered;
                    snapshots = orderedSnap;
                }
                Object resultObj = cn.ism.mekck.util.Reflect.call(r, "result");
                if (!(resultObj instanceof ItemStack result) || result.isEmpty()) continue;
                Object unitObj = cn.ism.mekck.util.Reflect.call(r, "unitTime");
                if (!(unitObj instanceof Integer unit) || unit < 1) continue;
                Object carrierObj = cn.ism.mekck.util.Reflect.call(r, "carrier");
                String carrierJson = carrierObj instanceof net.minecraft.world.item.crafting.Ingredient c
                        ? c.toJson().toString() : null;
                if (carrierJson == null) continue;
                int bottles = TavernBarrelPlan.computeBottles(ingredientStacks);
                // 配方语义签名：ingredients JSON + fluid + result + unitTime + carrier（供提交前比对同 ID 内容未变）
                StringBuilder sig = new StringBuilder();
                for (net.minecraft.world.item.crafting.Ingredient ing : ings) sig.append(ing.toJson()).append('|');
                sig.append(net.minecraftforge.registries.ForgeRegistries.FLUIDS.getKey(f)).append('|');
                sig.append(net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(result.getItem())).append('|');
                sig.append(result.getCount()).append('|').append(unit).append('|').append(carrierJson);
                TavernBarrelPlan plan = TavernBarrelPlan.of(r.getId(), slots, snapshots,
                        new net.minecraftforge.fluids.FluidStack(f, TavernBarrelPlan.MAX_FLUID_AMOUNT),
                        bottles, net.minecraft.resources.ResourceLocation.tryParse(
                                net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(result.getItem()).toString()),
                        unit, carrierJson, sig.toString());
                if (plan != null && (best == null || plan.getRecipeId().compareTo(best.getRecipeId()) < 0)) {
                    best = plan;
                }
            } catch (Throwable ignored) {
                // 无法读取的配方视为不匹配
            }
        }
        return best;
    }

    void absorbWineryJuice() {
        if (be.kind != MachineKind.WINERY || be.getLevel() == null || be.getLevel().isClientSide) return;
        for (int s = 0; s < be.INPUT_COUNT; s++) absorbJuiceFromSlot(s);
        absorbJuiceFromSlot(be.JUICE_SLOT);
        // 流体桶（与瓶装果汁共用果汁格）→ 抽入 be.fluids.getInputTank() 服务 tavern barrel；vinery 瓶装果汁走上面的液位池
        absorbJuiceFluidFromSlot(be.JUICE_SLOT);
    }


    void noteFluidIntake(ItemStack stack, String reason) {
        if (be.drainRejectCooldown > 0) {
            if (reason.equals(be.lastDrainReject)) {
                be.drainRejectCooldown--;
                return;
            }
            be.drainRejectCooldown = 0; // 原因变了：立即重报
        }
        be.lastDrainReject = reason;
        be.drainRejectCooldown = 100;
        ResourceLocation itemId = ForgeRegistries.ITEMS.getKey(stack.getItem());
        com.mojang.logging.LogUtils.getLogger().info(
                "[mekck] 陈酿机果汁格流体桶 item={} x{} ：{}", itemId, stack.getCount(), reason);
    }


    /** winery 果汁格里的流体容器 → 灌入 be.fluids.getInputTank()（服务 kaleidoscope_tavern barrel 批次），
     * 抽空后把空容器返还到 be.RETURN_SLOT。
     * <p>
     * <b>不筛流体种类</b>（用户 2026-09-24 二次反馈定稿）：取证发现 vinery 与森罗酒馆两个模组
     * 都**没有注册任何桶类物品**（扫两 jar 的 registry 常量池，"bucket" 0 命中），玩家能塞进果汁格的
     * 只可能是原版或其它模组的桶（水/岩浆/奶…）。上一版按「酒馆配方流体表 ∪ 名字含 juice」做白名单，
     * 等于把实际存在的桶全挡在外面，表现为「放得进、永不抽取、也不消失」。现与「手持桶右键灌机器」
     * （{@link #tryInsertHeldFluid}）同语义：任何流体都收，由配方匹配阶段自己筛。
     * <p>
     * 两条安全红线（上一轮反馈的“桶凭空消失”源于第一条）：
     * <ul>
     *   <li><b>空容器向容器本身索取</b>：{@code BucketItem} 不声明合成剩余物，用 {@code getCraftingRemainingItem()}
     *       取不到东西 → 抽空后直接吞桶；改为在副本上预演抽取后读 {@code IFluidHandlerItem.getContainer()}
     *       （{@code FluidBucketWrapper} 抽空后即为 {@code minecraft:bucket}）；</li>
     *   <li><b>全程预演</b>：抽不动 / 罐子收不下 / 空容器无处安放时整桶保持原样，绝不丢流体也不丢桶。</li>
     * </ul>
     */
    void absorbJuiceFluidFromSlot(int s) {
        ItemStack st = be.items.getStackInSlot(s);
        if (st.isEmpty()) return;
        // vinery 瓶装果汁（juiceTypeOf 非空）已由液位池逻辑处理，不在此列
        if (cn.ism.mekck.util.VineryJuice.juiceTypeOf(st) != null) return;
        // 先取**一只**做预演：森罗酒馆的果汁桶是 `JuiceBucketItem extends BucketItem` 且 `stacksTo(16)`
        // （javap 其造器：`Item$Properties.m_41487_(16)` 即 stacksTo），而 Forge 的
        // `FluidBucketWrapper.drain()` 硬要求 `container.getCount() == 1` ⇒ 一次塞一整组桶会**永远抽不动**
        // （用户 2026-09-24 二次反馈的根源），改为每 tick 只处理一只、逐只推进。
        ItemStack single = st.getCount() > 1 ? st.copyWithCount(1) : st;
        DrainProbe probe = probeDrain(single);
        if (probe == null) {
            noteFluidIntake(st, "读不出可抽取的流体（无 FLUID_HANDLER_ITEM 能力且不是原版 BucketItem）");
            return;
        }
        ResourceLocation fluidId = ForgeRegistries.FLUIDS.getKey(probe.fluid.getFluid());
        // 预演①：be.fluids.getInputTank() 能否完整接收这只桶的流体（同种流体或空罐；FluidTank 不混装）
        if (be.fluids.getInputTank().fill(probe.fluid, net.minecraftforge.fluids.capability.IFluidHandler.FluidAction.SIMULATE)
                != probe.fluid.getAmount()) {
            net.minecraftforge.fluids.FluidStack cur = be.fluids.getInputTank().getFluid();
            ResourceLocation curId = cur.isEmpty() ? null : ForgeRegistries.FLUIDS.getKey(cur.getFluid());
            noteFluidIntake(st, "储罐收不下 " + probe.fluid.getAmount() + "mB " + fluidId + "（罐内现有 "
                    + (curId == null ? "空" : curId + " ×" + cur.getAmount())
                    + "，容量 " + be.fluids.getInputTank().getCapacity() + "mB；异种流体不可混装，需先用管道抽走或等配方消耗）");
            return; // 罐满或类型冲突：整桶不动
        }
        // 预演②：be.RETURN_SLOT 能否放得下空容器（空容器为空集 = 容器自消耗，无需返还）
        if (!probe.container.isEmpty() && !canPlaceInReturnSlot(probe.container)) {
            noteFluidIntake(st, "返还格放不下空容器 " + probe.container.getDescriptionId());
            return; // 空桶无处安放：整桶不动
        }
        // ── 提交：罐收一只桶的流体，槽位留剩余满桶，空容器并入 be.RETURN_SLOT ──
        be.fluids.getInputTank().fill(probe.fluid, net.minecraftforge.fluids.capability.IFluidHandler.FluidAction.EXECUTE);
        int left = st.getCount() - 1;
        be.items.setStackInSlot(s, left > 0 ? st.copyWithCount(left) : ItemStack.EMPTY);
        if (!probe.container.isEmpty()) insertReturn(probe.container);
        be.lastDrainReject = null;
        be.drainRejectCooldown = 0;
        be.setChanged();
        // 已抽进去、但对酿造无用的提醒（酒桶合法流体集由扫描 barrel 配方得到，含 water / lava 基配方）
        if (!cn.ism.mekck.compat.TavernBarrelCompat.isBarrelFluid(be.getLevel(), probe.fluid.getFluid())) {
            noteFluidIntake(st, "已抽入储罐，但 " + fluidId + " 不是任何酒馆酒桶配方的流体，不会参与陈酿");
        }
    }

    private static final class DrainProbe {
        final net.minecraftforge.fluids.FluidStack fluid;
        final ItemStack container;
        DrainProbe(net.minecraftforge.fluids.FluidStack fluid, ItemStack container) {
            this.fluid = fluid;
            this.container = container;
        }
    }


    /**
     * 在 {@code filled} 的副本上预演一次抽取（不碰原槽位），返回「抽出的流体 + 抽空后的容器」；
     * 该容器不支持抽取（或读不动）时返回 null，调用方应保持原样。
     * <p>之所以用副本：{@code FluidBucketWrapper.drain(EXECUTE)} 是把内部容器引用换成新 ItemStack，
     * 不会回写原槽位物品，所以必须由我们自己把 {@code getContainer()} 的结果搬回槽位。</p>
     */
    DrainProbe probeDrain(ItemStack filled) {
        DrainProbe viaCapability = probeDrainViaCapability(filled);
        if (viaCapability != null) return viaCapability;
        return probeDrainViaBucketItem(filled);
    }

    DrainProbe probeDrainViaCapability(ItemStack filled) {
        try {
            ItemStack copy = filled.copy();
            var lazy = copy.getCapability(net.minecraftforge.common.capabilities.ForgeCapabilities.FLUID_HANDLER_ITEM);
            var holder = lazy.orElse(null);
            if (holder == null) { lazy.invalidate(); return null; }
            net.minecraftforge.fluids.FluidStack have = holder.getFluidInTank(0);
            if (have == null || have.isEmpty()) { lazy.invalidate(); return null; }
            net.minecraftforge.fluids.FluidStack out = holder.drain(have,
                    net.minecraftforge.fluids.capability.IFluidHandler.FluidAction.EXECUTE);
            ItemStack after = holder.getContainer();
            lazy.invalidate();
            if (out == null || out.isEmpty()) return null; // 抽不动：不消耗
            return new DrainProbe(out, after == null ? ItemStack.EMPTY : after.copy());
        } catch (Throwable t) {
            return null;
        }
    }


    /**
     * 兜底：直读 Forge 给 {@code BucketItem} 打的补丁 {@code getFluid()}，不依赖该桶是否注册了
     * {@code FLUID_HANDLER_ITEM} 能力（否则第三方桶会因能力缺失而永远抽不动）。
     * <p>只接受单只桶（调用方已负责从整组里拆一只），抽空后的容器优先用桶自己声明的合成剩余物
     * （森罗酒馆 {@code JuiceBucketItem} 即 {@code Properties.craftingRemainingItem(Items.BUCKET)}），
     * 没声明时退化为铁桶。</p>
     */
    DrainProbe probeDrainViaBucketItem(ItemStack filled) {
        if (filled.getCount() != 1) return null;
        if (!(filled.getItem() instanceof net.minecraft.world.item.BucketItem bucket)) return null;
        net.minecraft.world.level.material.Fluid fluid = bucket.getFluid();
        if (fluid == null) return null;
        ResourceLocation fid = ForgeRegistries.FLUIDS.getKey(fluid);
        if (fid == null || "empty".equals(fid.getPath())) return null;
        // 1.20.1 的 Item#getCraftingRemainingItem() 返回 Item（不是 ItemStack），AIR = 未声明剩余物
        net.minecraft.world.item.Item emptyItem = filled.getItem().getCraftingRemainingItem();
        ItemStack empty = emptyItem == null || emptyItem == net.minecraft.world.item.Items.AIR
                ? new ItemStack(net.minecraft.world.item.Items.BUCKET)
                : new ItemStack(emptyItem);
        return new DrainProbe(new net.minecraftforge.fluids.FluidStack(fluid, 1000), empty);
    }

    int returnSlotFor(ItemStack stack) {
        if (be.kind == MachineKind.WINERY && be.isVineryWineBottle(stack)) return be.WINERY_CARRIER_SLOT;
        return be.RETURN_SLOT;
    }

    boolean canPlaceInReturnSlot(ItemStack stack) {
        return returnRoom(stack) >= stack.getCount();
    }

    int returnRoom(ItemStack stack) {
        int primary = returnSlotFor(stack);
        long room = (long) returnSlotRoom(primary, stack) + returnSlotRoom(be.RETURN_SLOT, stack);
        return (int) Math.min(room, cn.ism.mekck.util.CountMath.MAX_COUNT);
    }


    /**
     * §F30：与 {@link #slotRoom} 逐行同构，**唯一差异**是容量不再 {@code min(..., stack.getMaxStackSize())}，
     * 而直取处理器槽上限 {@code be.items.getSlotLimit(slot)}（返还格 / 空瓶槽 = {@code Integer.MAX_VALUE}）。
     * <p>专供**返还链**（{@link #returnRoom}/{@link #insertReturn}）使用：果汁桶抽空后还进返还格的空桶
     * 其 {@code bucket.getMaxStackSize()=16}，旧版用 {@link #slotRoom} 把剩余容量算成 {@code min(MAX,16)=16}
     * ⇒ 攒到 16 只同类桶即判 0 余量 ⇒ {@code canPlaceInReturnSlot} 假 ⇒ 预演② 卡停整条流体抽取。
     * 写侧 {@code mergeIntoSlot} 本靠 {@code grow()} 超堆（空瓶槽 900+ 为证），只是被这一 16 的读判挡在门外。
     * <p>**保留** {@code Math.min(capacity, CountMath.MAX_COUNT)} 与 {@code long} 中间量防 int 加法溢出；
     * **保留** 异类占位返回 0（不强塞）。普通输入槽判定仍走 {@link #slotRoom}（vanilla 一组约束防漏斗灌爆）。
     */
    int returnSlotRoom(int slot, ItemStack stack) {
        if (slot < 0 || slot >= be.items.getSlots() || stack.isEmpty()) return 0;
        long capacity = be.items.getSlotLimit(slot);
        ItemStack in = be.items.getStackInSlot(slot);
        if (in.isEmpty()) return (int) Math.min(capacity, cn.ism.mekck.util.CountMath.MAX_COUNT);
        if (!ItemStack.isSameItemSameTags(in, stack)) return 0;
        return (int) Math.max(0L, Math.min(capacity, cn.ism.mekck.util.CountMath.MAX_COUNT) - in.getCount());
    }

    void insertReturn(ItemStack stack) {
        int primary = returnSlotFor(stack);
        int put = Math.min(stack.getCount(), returnSlotRoom(primary, stack));
        mergeIntoSlot(primary, stack, put);
        int rest = stack.getCount() - put;
        if (primary == be.RETURN_SLOT || rest <= 0) return;
        int spill = Math.min(rest, returnSlotRoom(be.RETURN_SLOT, stack));
        mergeIntoSlot(be.RETURN_SLOT, stack, spill);
        if (spill < rest) {
            // 两道都不收（典型：两格都被异类占位）⇒ 不静默吞瓶：能归回主槽就堆在原格（本就无上限），否则打日志报警
            be.noteJuiceReject(primary, stack, "还瓶溢出无处安放（先清空格瓶槽或退还格）");
            if (be.items.getStackInSlot(primary).isEmpty()
                    || ItemStack.isSameItemSameTags(be.items.getStackInSlot(primary), stack)) {
                mergeIntoSlot(primary, stack, rest - spill);
            }
        }
    }


    void mergeIntoSlot(int slot, ItemStack stack, int count) {
        if (count <= 0) return;
        ItemStack in = be.items.getStackInSlot(slot);
        if (in.isEmpty()) be.items.setStackInSlot(slot, stack.copyWithCount(count));
        else in.grow(count); // 调用方已按 slotRoom 预演，这里再兜一道防溢出
    }


    void absorbJuiceFromSlot(int s) {
        ItemStack st = be.items.getStackInSlot(s);
        if (st.isEmpty()) return;
        // 配料区 0..4 里的果汁如果**确实是某条陈酿配方的 ingredient**（如苹果酒要 apple 液位 15 + 1 个苹果汁物品），
        // 就绝不能把它吸进液位池——吸走了就永远凑不齐“液位 + 物品”双份需求（与 {@link #acceptsInput} 的
        // 双重身份豁免同口径）。果汁格（be.JUICE_SLOT）不受此限，照旧逐件转液位。
        // 槽 3（酒瓶槽）/槽 4（废弃槽）本就不是配料区，其内的酒瓶不参与吸收——旧版把它们当配料区逐 tick
        // 尝试吸收，是 2026-09-24 那次「读不出果汁类型」刷屏（9839 行）的来源。
        if (s < be.INPUT_COUNT
                && (s == be.WINERY_CARRIER_SLOT || s == be.WINERY_DEPRECATED_SLOT || be.machineInputMatches(st))) return;
        String type = cn.ism.mekck.util.VineryJuice.juiceTypeOf(st);
        if (type == null) {
            be.noteJuiceReject(s, st, "吸收：vinery 读不出果汁类型（非果汁或反射不可用）");
            return; // 非果汁（或 vinery 反射不可用）→ 不吸收
        }
        if (be.juiceLevel > 0 && !be.juiceType.equals(type)) { // 已有异类液位：原版语义拒收，需先清桶
            be.noteJuiceReject(s, st, "吸收：池内已是另一种果汁（原版语义，需先清空）");
            return;
        }
        int room = (be.JUICE_MAX_LEVEL - be.juiceLevel) / be.JUICE_PER_ITEM;
        if (room <= 0) {
            // §F24：旧文案「液位已满」名不副实——真实判据是余量不足再吸收下一件（88/100 也会报），误导排查
            be.noteJuiceReject(s, st, "吸收：液位余量不足再吸收一件（room=" + room + "）");
            return; // 余量不足下一件
        }
        int take = Math.min(st.getCount(), room);
        if (take <= 0) {
            be.noteJuiceReject(s, st, "吸收：单件换算不足 1");
            return;
        }
        // 倒汁必须还瓶（用户 2026-09-24 反馈：葡萄汁进池了、酒瓶没返还）：vinery 的果汁瓶
        // （{@code GrapejuiceBottleItem}）继承普通 Item、**没有** craftingRemainingItem，原版是在
        // {@code finishUsingItem}（喝掉）时手动塞一个 {@code ObjectRegistry.WINE_BOTTLE}；本机「倒汁入池」
        // 等价于喝掉 ⇒ 同样返还 vinery:wine_bottle，否则玩家的酒瓶凭空消失。
        ItemStack empties = vineryEmptyBottles(take);
        if (!empties.isEmpty()) {
            int bottleRoom = returnRoom(empties); // 还瓶落位（目标：空瓶槽，满则溢到退还槽）
            if (bottleRoom < take) take = bottleRoom; // 落位不够就少吸，宁少吸也不吞瓶
            if (take <= 0) {
                ItemStack cur3 = be.items.getStackInSlot(be.WINERY_CARRIER_SLOT);
                ItemStack cur11 = be.items.getStackInSlot(be.RETURN_SLOT);
                be.noteJuiceReject(s, st, "吸收：还瓶无处落位（空瓶槽=" + (cur3.isEmpty() ? "空" : cur3.getDescriptionId() + "×" + cur3.getCount())
                        + "，退还格=" + (cur11.isEmpty() ? "空" : cur11.getDescriptionId() + "×" + cur11.getCount()) + "）");
                return;
            }
            empties = vineryEmptyBottles(take);
        }
        if (be.juiceLevel == 0) be.juiceType = type;
        be.juiceLevel += take * be.JUICE_PER_ITEM;
        int left = st.getCount() - take;
        be.items.setStackInSlot(s, left > 0 ? st.copyWithCount(left) : ItemStack.EMPTY);
        if (!empties.isEmpty()) insertReturn(empties);
        be.setChanged();
    }

    ItemStack vineryEmptyBottles(int take) {
        if (take <= 0) return ItemStack.EMPTY;
        net.minecraft.world.item.Item bottle = ForgeRegistries.ITEMS.getValue(new ResourceLocation("vinery", "wine_bottle"));
        if (bottle == null || bottle == net.minecraft.world.item.Items.AIR) return ItemStack.EMPTY;
        return new ItemStack(bottle, take);
    }

    MatchedRecipe matchWinery() {
        RecipeType<?> rt = cn.ism.mekck.util.RecipeCache.type(new ResourceLocation("vinery", "wine_fermentation"));
        if (rt == null) return null;
        for (Recipe<?> r : cn.ism.mekck.util.RecipeCache.all(be.getLevel(), rt)) {
            try {
                // vinery 1.4.x 的 FermentationBarrelRecipe 是**扁平字段**：getJuiceType():String + getJuiceAmount():int
                // + isWineBottleRequired():boolean，**不存在** getJuiceData()。早先按不存在的嵌套结构取值，
                // 每条配方都在 juiceData==null 处 continue → 26 条全被跳过、陈酿永不启动（用户 2026-09-24 反馈）。
                String type = cn.ism.mekck.util.VineryJuice.recipeJuiceType(r);
                if (type == null) continue; // 扁平访问器/嵌套结构/同名字段都读不到 → 保守跳过该配方
                int need = cn.ism.mekck.util.VineryJuice.recipeJuiceAmount(r);
                // 原版 matches() 语义：juiceAmount<=0 时**完全不校验果汁**（任意类型、无需液位）
                boolean requireJuice = need > 0;
                // 液位非 0 且类型不同 → 原版拒收，本机同样不匹配
                if (requireJuice && be.juiceLevel > 0 && !be.juiceType.equals(type)) continue;
                // 材料（含可选空酒瓶）
                List<Ingredient> required = new ArrayList<>();
                for (Ingredient ing : r.getIngredients()) {
                    if (ing != null && !ing.isEmpty()) required.add(ing);
                }
                if (required.isEmpty()) continue;
                Object bottleReq = be.callNoArg(r, "isWineBottleRequired");
                if (bottleReq instanceof Boolean b && b) {
                    Ingredient bottle = be.itemIng("vinery", "wine_bottle");
                    if (bottle == null) continue; // 取不到空酒瓶时不放行，避免"免酒瓶"产出
                    required.add(bottle);
                }
                List<Integer> slots = matchIngredients(required);
                if (slots == null) continue;
                // 果汁液位：**液位池是唯一来源**（用户 2026-09-24 定的口径——「果汁格放入时它被转为液位，
                // 液位中对应品种足够且输入满足时才运行」）。旧版此处还允许运行时就地消费槽内果汁补液位，
                // 于是出现「果汁格里苹果汁没转液位、机器照样启动、跑完才把瓶子扣掉」的时序错觉，还会让
                // 池内 red_general 液位与苹果汁物品互相凑数（跨品种混用）。倒汁入池统一交由
                // {@link #absorbWineryJuice} 在 server tick 即时完成。
                int lvl = !requireJuice ? 0 : (be.juiceType.equals(type) ? be.juiceLevel : 0);
                List<Integer> consumeSlots = new ArrayList<>(slots);
                List<Integer> consumeCounts = new ArrayList<>();
                List<Ingredient> inputIngredients = new ArrayList<>();
                for (int i = 0; i < slots.size(); i++) {
                    consumeCounts.add(1);
                    inputIngredients.add(required.get(i));
                }
                if (lvl < need) continue; // 池内同类型液位不足 → 不启动（先把果汁格里的汁吸进池）
                ItemStack res = r.getResultItem(be.getLevel().registryAccess());
                if (res.isEmpty()) continue;
                return new MatchedRecipe(consumeSlots, consumeCounts, inputIngredients, res, 0,
                        net.minecraftforge.fluids.FluidStack.EMPTY,
                        net.minecraftforge.fluids.FluidStack.EMPTY, r.getId(), lvl - need, type);
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    MatchedRecipe matchJuicer() {
        // 葡萄压榨（mekck:grape_pressing）：多成分带数量（3 葡萄 + 1 空瓶）匹配，优先于单输入路径
        MatchedRecipe grape = matchGrapePressing();
        if (grape != null) return grape;
        ItemStack in = be.items.getStackInSlot(0);
        if (in.isEmpty()) return null;
        // Vinery 两类型独立查询：缺失任一类型只跳过该路径，不阻断 pressing_tub
        MatchedRecipe vinery = matchVineryJuice(in);
        if (vinery != null) {
            return vinery;
        }
        // KaleidoscopeTavern pressing_tub：一次查找生成完整生产计划
        // （真实 recipeId + 输入 Ingredient + 流体产物），不重复扫描、不拼接不同配方。
        // tavern 未安装时类型解析为 null，自动跳过。（不能按 id 查注册表：该模组的类型从不注册，详见门面注释）
        net.minecraft.world.item.crafting.RecipeType<?> pressT = cn.ism.mekck.compat.TavernBarrelCompat.pressingTubType();
        if (pressT != null && be.getLevel() != null) {
            MatchedRecipe best = null;
            for (net.minecraft.world.item.crafting.Recipe<?> r : cn.ism.mekck.util.RecipeCache.all(be.getLevel(), pressT)) {
                java.util.List<Ingredient> ings = r.getIngredients();
                if (ings.isEmpty() || !ings.get(0).test(in)) continue;
                net.minecraft.world.level.material.Fluid fluid = null;
                int amount = 0;
                try {
                    Object f = cn.ism.mekck.util.Reflect.call(r, "getFluid");
                    if (f instanceof net.minecraft.world.level.material.Fluid fl) fluid = fl;
                    Object a = cn.ism.mekck.util.Reflect.call(r, "getFluidAmount");
                    if (a instanceof Integer i) amount = i;
                } catch (Throwable ignored) {
                    continue; // 无法读取的配方视为不匹配
                }
                if (fluid == null || amount <= 0) continue;
                MatchedRecipe m = new MatchedRecipe(
                        java.util.Collections.singletonList(0),
                        java.util.Collections.singletonList(1),
                        java.util.Collections.singletonList(ings.get(0)),
                        net.minecraft.world.item.ItemStack.EMPTY, 0,
                        net.minecraftforge.fluids.FluidStack.EMPTY,
                        new net.minecraftforge.fluids.FluidStack(fluid, amount),
                        r.getId());
                // 稳定选择：多个配方匹配同一输入时按 recipeId 字典序取最小（不依赖遍历顺序）
                if (best == null || m.recipeId.compareTo(best.recipeId) < 0) {
                    best = m;
                }
            }
            if (best != null) {
                return best;
            }
        }
        return null;
    }


    /**
     * 鲜果榨汁机葡萄压榨（{@code mekck:grape_pressing}）：按每种成分的数量跨输入槽凑料。
     * <p>忠实折算 vinery 压榨盆 {@code 3 葡萄 + 1 空葡萄酒瓶 → 1 瓶装葡萄汁}：葡萄可堆叠在单槽内，
     * 由 {@link cn.ism.mekck.recipe.GrapePressingRecipe#countAt(int)} 提供每种成分需求量；每槽只服务一种成分，
     * 实际扣数以 {@code consumeCounts} 逐槽记录（支持一槽放 3 颗葡萄）。未装/无该配方类型时安全返回 null。</p>
     */
    MatchedRecipe matchGrapePressing() {
        if (be.getLevel() == null) return null;
        RecipeType<?> rt = cn.ism.mekck.util.RecipeCache.type(new ResourceLocation("mekck", "grape_pressing"));
        if (rt == null) return null;
        for (Recipe<?> rec : cn.ism.mekck.util.RecipeCache.all(be.getLevel(), rt)) {
            if (!(rec instanceof cn.ism.mekck.recipe.GrapePressingRecipe r)) continue;
            List<Ingredient> ings = r.getItemIngredients();
            if (ings.isEmpty()) continue;
            boolean[] used = new boolean[be.INPUT_COUNT];
            List<Integer> consumeSlots = new ArrayList<>();
            List<Integer> consumeCounts = new ArrayList<>();
            List<Ingredient> slotIngredients = new ArrayList<>();
            boolean ok = true;
            for (int i = 0; i < ings.size() && ok; i++) {
                Ingredient ing = ings.get(i);
                if (ing == null || ing.isEmpty()) continue;
                int need = r.countAt(i);
                for (int s = 0; s < be.INPUT_COUNT && need > 0; s++) {
                    if (used[s]) continue;
                    ItemStack st = be.items.getStackInSlot(s);
                    if (st.isEmpty() || !ing.test(st)) continue;
                    int take = Math.min(st.getCount(), need);
                    consumeSlots.add(s);
                    consumeCounts.add(take);
                    slotIngredients.add(ing);
                    used[s] = true;
                    need -= take;
                }
                if (need > 0) ok = false; // 该成分凑不够
            }
            if (!ok || consumeSlots.isEmpty()) continue;
            ItemStack res = r.getResultItem(be.getLevel().registryAccess());
            if (res.isEmpty()) continue;
            return new MatchedRecipe(consumeSlots, consumeCounts, slotIngredients, res, r.getProcessTime(),
                    net.minecraftforge.fluids.FluidStack.EMPTY, net.minecraftforge.fluids.FluidStack.EMPTY, r.getId());
        }
        return null;
    }

    MatchedRecipe matchVineryJuice(net.minecraft.world.item.ItemStack in) {
        if (be.getLevel() == null) return null;
        net.minecraft.world.item.crafting.RecipeType<?> mashT = cn.ism.mekck.util.RecipeCache.type(
                new net.minecraft.resources.ResourceLocation("vinery", "apple_mashing"));
        net.minecraft.world.item.crafting.RecipeType<?> fermT = cn.ism.mekck.util.RecipeCache.type(
                new net.minecraft.resources.ResourceLocation("vinery", "apple_fermenting"));
        List<net.minecraft.world.item.crafting.Recipe<?>> mashRecipes = mashT == null ? java.util.List.of()
                : cn.ism.mekck.util.RecipeCache.all(be.getLevel(), mashT);
        List<net.minecraft.world.item.crafting.Recipe<?>> fermRecipes = fermT == null ? java.util.List.of()
                : cn.ism.mekck.util.RecipeCache.all(be.getLevel(), fermT);
        // 直接放苹果浆：apple_fermenting → 苹果汁（多个匹配按 recipeId 稳定取最小）
        MatchedRecipe bestFerm = null;
        for (net.minecraft.world.item.crafting.Recipe<?> ferm : fermRecipes) {
            List<Ingredient> ings = ferm.getIngredients();
            if (ings.isEmpty() || !ings.get(0).test(in)) continue;
            ItemStack res = ferm.getResultItem(be.getLevel().registryAccess());
            if (res.isEmpty()) continue;
            // 载具酒瓶（简报 §六③）：早期只取 ings.get(0) 没读 requiresBottle → 苹果汁免瓶产出
            List<Ingredient> slotIngs = new ArrayList<>();
            slotIngs.add(ings.get(0));
            int bottleSlot = bottleForJuicer(ferm, slotIngs);
            if (bottleSlot < 0) continue; // 该配方要瓶而输入槽无瓶 → 整条不放行
            List<Integer> fSlots = new ArrayList<>();
            List<Integer> fCounts = new ArrayList<>();
            fSlots.add(0);
            fCounts.add(1);
            if (bottleSlot > 0) {
                fSlots.add(bottleSlot);
                fCounts.add(1);
            }
            MatchedRecipe m = new MatchedRecipe(fSlots, fCounts, slotIngs,
                    res, 0, net.minecraftforge.fluids.FluidStack.EMPTY, net.minecraftforge.fluids.FluidStack.EMPTY,
                    ferm.getId());
            if (bestFerm == null || m.recipeId.compareTo(bestFerm.recipeId) < 0) bestFerm = m;
        }
        if (bestFerm != null) return bestFerm;
        // 苹果 → (apple_mashing) 苹果浆 → (apple_fermenting) 苹果汁，两步合一步
        for (net.minecraft.world.item.crafting.Recipe<?> mash : mashRecipes) {
            List<Ingredient> ings = mash.getIngredients();
            if (ings.isEmpty() || !ings.get(0).test(in)) continue;
            ItemStack mashOut = mash.getResultItem(be.getLevel().registryAccess());
            if (mashOut.isEmpty()) continue;
            for (net.minecraft.world.item.crafting.Recipe<?> ferm : fermRecipes) {
                List<Ingredient> fings = ferm.getIngredients();
                if (fings.isEmpty() || !fings.get(0).test(mashOut)) continue;
                ItemStack res = ferm.getResultItem(be.getLevel().registryAccess());
                if (res.isEmpty()) continue;
                // 两步合一同样要交载具酒瓶：苹果（槽 0）+ 空酒瓶（另一输入槽）→ 苹果汁，中间态苹果浆不占消耗槽
                List<Ingredient> slotIngs = new ArrayList<>();
                slotIngs.add(ings.get(0));
                int bottleSlot = bottleForJuicer(ferm, slotIngs);
                if (bottleSlot < 0) continue;
                List<Integer> mSlots = new ArrayList<>();
                List<Integer> mCounts = new ArrayList<>();
                mSlots.add(0);
                mCounts.add(1);
                if (bottleSlot > 0) {
                    mSlots.add(bottleSlot);
                    mCounts.add(1);
                }
                return new MatchedRecipe(mSlots, mCounts, slotIngs,
                        res, 0, net.minecraftforge.fluids.FluidStack.EMPTY, net.minecraftforge.fluids.FluidStack.EMPTY,
                        ferm.getId());
            }
        }
        return null;
    }


    /**
     * 榨汁机配方的载具酒瓶：需要则在输入槽 1..{@link #be.INPUT_COUNT}-1 里找一个 {@code vinery:wine_bottle}，
     * 并把对应 {@link Ingredient} 追加到 {@code slotIngs}（与槽位表一一对应）。
     *
     * @return {@code 0}=本配方不要瓶；{@code >0}=瓶子所在槽；{@code -1}=要瓶但拿不到瓶（模组缺失或槽里没有）
     *         → 调用方须整条跳过，宁可不产也不做「免酒瓶」产出（与 {@link #matchWinery} 同口径）。
     */
    int bottleForJuicer(net.minecraft.world.item.crafting.Recipe<?> ferm, List<Ingredient> slotIngs) {
        if (!be.fermRequiresBottle(ferm)) return 0;
        Ingredient bottle = be.itemIng("vinery", "wine_bottle");
        if (bottle == null) return -1;
        int s = be.findVineryBottleSlot(1);
        if (s < 0) return -1;
        slotIngs.add(bottle);
        return s;
    }

    MatchedRecipe matchBakery() {
        RecipeType<?> rt = cn.ism.mekck.util.RecipeCache.type(new ResourceLocation("bakery", "baking_station"));
        if (rt == null) return matchBakeriesOven();
        for (Recipe<?> r : cn.ism.mekck.util.RecipeCache.all(be.getLevel(), rt)) {
            List<Ingredient> ings = r.getIngredients();
            if (ings.isEmpty()) continue;
            List<Integer> slots = matchIngredients(ings);
            if (slots != null) {
                ItemStack res = r.getResultItem(be.getLevel().registryAccess());
                if (!res.isEmpty()) {
                    return new MatchedRecipe(slots, res);
                }
            }
        }
        // letsdo-bakery 未命中 → 尝试烘焙坊 bakeries:oven（27 配方，单输入 + 温度区间）
        MatchedRecipe oven = matchBakeriesOven();
        if (oven != null) return oven;
        // 仍未命中 → 尝试烘焙坊 bakeries:coffee（8 配方，3~4 输入，按槽位有序匹配）
        return matchBakeriesCoffee();
    }


    /**
     * 烘焙坊 bakeries:coffee（8 配方）。参考 CoffeeRecipe.matches 的真实语义：
     * **按槽位有序匹配**（inputItems.get(i).test(container.getItem(i))，槽 i ↔ 第 i 个 ingredient），
     * 无时间字段（用机器默认时长）；要求 ingredient 数之后的输入槽为空（防多余材料被无视）。
     */
    MatchedRecipe matchBakeriesCoffee() {
        RecipeType<?> rt = cn.ism.mekck.util.RecipeCache.type(new ResourceLocation("bakeries", "coffee"));
        if (rt == null || be.getLevel() == null) return null;
        for (Recipe<?> r : cn.ism.mekck.util.RecipeCache.all(be.getLevel(), rt)) {
            try {
                List<Ingredient> required = new ArrayList<>();
                for (Ingredient ing : r.getIngredients()) {
                    if (ing != null && !ing.isEmpty()) required.add(ing);
                }
                if (required.isEmpty() || required.size() > be.INPUT_COUNT) continue;
                java.util.List<Integer> consumeSlots = new java.util.ArrayList<>();
                boolean ok = true;
                for (int i = 0; i < required.size(); i++) {
                    ItemStack st = be.items.getStackInSlot(i);
                    if (st.isEmpty() || !required.get(i).test(st)) { ok = false; break; }
                    consumeSlots.add(i);
                }
                if (!ok) continue;
                for (int s = required.size(); s < be.INPUT_COUNT; s++) {
                    if (!be.items.getStackInSlot(s).isEmpty()) { ok = false; break; }
                }
                if (!ok) continue;
                ItemStack result = r.getResultItem(be.getLevel().registryAccess());
                if (result.isEmpty()) continue;
                return new MatchedRecipe(consumeSlots, null, required, result, 0,
                        net.minecraftforge.fluids.FluidStack.EMPTY,
                        net.minecraftforge.fluids.FluidStack.EMPTY, r.getId());
            } catch (Exception ignored) {
            }
        }
        return null;
    }


    /**
     * 烘焙坊 bakeries:oven（27 配方）。参考 OvenBlockEntity.recipeItem/craftItem 的真实语义：
     * - 配方匹配**不含温度**（AbstractOvenRecipe.matches 只测 ingredient，6 槽任一命中）；
     * - 计时需 temperature >= min_temperature，时间到 time tick 后：temperature > max_temperature
     *   则产物替换为 1 个木炭（烧焦）；否则正常产出；
     * - temperature == perfect_temperature（且配方存在该字段）时产物 NBT 写 perfect=true。
     * MekCK 机器无温度系统 → 固定采用“理想温度”：有 perfect_temperature 用其值（必然落在
     * [min,max] 内，且可标记 perfect），否则用 min_temperature（保证可烹饪且不烧焦）。
     */
    MatchedRecipe matchBakeriesOven() {
        RecipeType<?> rt = cn.ism.mekck.util.RecipeCache.type(new ResourceLocation("bakeries", "oven"));
        if (rt == null || be.getLevel() == null) return null;
        for (Recipe<?> r : cn.ism.mekck.util.RecipeCache.all(be.getLevel(), rt)) {
            try {
                List<Ingredient> ings = r.getIngredients();
                if (ings.isEmpty()) continue;
                List<Integer> slots = matchIngredients(ings);
                if (slots == null) continue;
                ItemStack res = r.getResultItem(be.getLevel().registryAccess());
                if (res.isEmpty()) continue;
                int time = 200;
                int perfectTemp = -1;
                try {
                    Object t = cn.ism.mekck.util.Reflect.call(r, "getTime");
                    if (t instanceof Integer ti) time = ti;
                } catch (Throwable ignored) {
                }
                try {
                    Object p = cn.ism.mekck.util.Reflect.call(r, "getPerfectTemperature");
                    if (p instanceof Integer pi) perfectTemp = pi;
                } catch (Throwable ignored) {
                }
                ItemStack result = res.copy();
                if (perfectTemp != -1) {
                    // MekCK 机器不设温度条件：直接以 perfect_temperature 为理想温度，产物带品质标记
                    result.getOrCreateTag().putBoolean("perfect", true);
                }
                return new MatchedRecipe(slots, null, ings, result, Math.max(0, time),
                        net.minecraftforge.fluids.FluidStack.EMPTY,
                        net.minecraftforge.fluids.FluidStack.EMPTY, r.getId());
            } catch (Exception ignored) {
            }
        }
        return null;
    }


    // ── 灶台机：farm_and_charm:stove（含 farmers_bread 面包）──

    MatchedRecipe matchStove() {
        RecipeType<?> rt = cn.ism.mekck.util.RecipeCache.type(new ResourceLocation("farm_and_charm", "stove"));
        if (rt == null) return matchBakeriesStoneKiln();
        for (Recipe<?> r : cn.ism.mekck.util.RecipeCache.all(be.getLevel(), rt)) {
            List<Ingredient> ings = r.getIngredients();
            if (ings.isEmpty()) continue;
            List<Integer> slots = matchIngredients(ings);
            if (slots != null) {
                ItemStack res = r.getResultItem(be.getLevel().registryAccess());
                if (!res.isEmpty()) {
                    return new MatchedRecipe(slots, res);
                }
            }
        }
        // farm_and_charm 未命中 → 烘焙坊 bakeries:stone_kiln（6 配方，单输入槽 0）
        MatchedRecipe kiln = matchBakeriesStoneKiln();
        if (kiln != null) return kiln;
        // 仍未命中 → let's do 多输入加热配方（沉浸农艺 roaster / 煨茶酝露 kettle_brewing / 青青草甸 cooking）
        return matchHeatedMultiInput();
    }


    /**
     * let's do 系列的多输入加热配方（智能烤炉，含扩展输入槽 10..13）：
     * - farm_and_charm:roaster（9 配方，最多 6 输入；原版 GeneralUtil.matchesRecipe(inv, inputs, 0, 5)）；
     * - herbalbrews:kettle_brewing（9 配方，2~3 输入；原版另需水量 requiredWater 与热量 heat_needed：
     *   热量由本机自身产热代替，水量按同数值的水从输入罐扣除）；
     * - meadow:cooking（19 配方，最多 6 输入；原版需水位 fluid_amount，同样按等量水从输入罐扣除）。
     * 匹配语义：无序一对一（RecipeMatcher），要求非空输入槽数 == ingredient 数（防吞料）；
     * 时间优先读配方字段（craftingDuration / requiredDuration），读不到用机器默认。
     */
    MatchedRecipe matchHeatedMultiInput() {
        java.util.List<Integer> slots = new java.util.ArrayList<>();
        java.util.List<ItemStack> slotStacks = new java.util.ArrayList<>();
        for (int s : be.activeInputSlots()) {
            ItemStack st = be.items.getStackInSlot(s);
            if (!st.isEmpty()) {
                slots.add(s);
                slotStacks.add(st);
            }
        }
        if (slots.isEmpty()) return null;
        String[][] types = {
                {"farm_and_charm", "roaster"},
                {"herbalbrews", "kettle_brewing"},
                {"meadow", "cooking"},
        };
        for (String[] type : types) {
            RecipeType<?> rt = cn.ism.mekck.util.RecipeCache.type(new ResourceLocation(type[0], type[1]));
            if (rt == null) continue;
            for (Recipe<?> r : cn.ism.mekck.util.RecipeCache.all(be.getLevel(), rt)) {
                try {
                    List<Ingredient> required = new ArrayList<>();
                    for (Ingredient ing : r.getIngredients()) {
                        if (ing != null && !ing.isEmpty()) required.add(ing);
                    }
                    if (required.isEmpty() || slotStacks.size() != required.size()) continue;
                    int[] match = net.minecraftforge.common.util.RecipeMatcher.findMatches(slotStacks, required);
                    if (match == null) continue;
                    ItemStack result = r.getResultItem(be.getLevel().registryAccess());
                    if (result.isEmpty()) continue;
                    java.util.List<Integer> consumeSlots = new java.util.ArrayList<>();
                    boolean[] used = new boolean[slots.size()];
                    boolean ok = true;
                    for (int i = 0; i < required.size(); i++) {
                        int idx = match[i];
                        if (idx < 0 || idx >= slots.size() || used[idx]) { ok = false; break; }
                        used[idx] = true;
                        consumeSlots.add(slots.get(idx));
                    }
                    if (!ok) continue;
                    return new MatchedRecipe(consumeSlots, null, required, result, heatedRecipeTime(r),
                            heatedRecipeWater(r),
                            net.minecraftforge.fluids.FluidStack.EMPTY, r.getId());
                } catch (Exception ignored) {
                }
            }
        }
        return null;
    }


    /** 读取 let's do 加热配方的处理时长（getCraftingDuration / 字段 craftingDuration / requiredDuration），读不到返回 0（用机器默认）。 */
    int heatedRecipeTime(Recipe<?> r) {
        // 方法/字段解析结果由 Reflect 缓存（原先每次调用都要重新解析签名）
        Object o = cn.ism.mekck.util.Reflect.call(r, "getCraftingDuration");
        if (o instanceof Integer i) return Math.max(0, i);
        int fromField = cn.ism.mekck.util.Reflect.intField(r, "craftingDuration", -1);
        if (fromField < 0) fromField = cn.ism.mekck.util.Reflect.intField(r, "requiredDuration", -1);
        return Math.max(0, fromField);
    }


    /** 反射读取私有 int 字段，读不到返回 -1。 */
    int intField(Recipe<?> r, String field) {
        return cn.ism.mekck.util.Reflect.intField(r, field, -1);
    }

    Ingredient containerIng(Object containerStack) {
        if (containerStack instanceof ItemStack st && !st.isEmpty() && st.getItem() != net.minecraft.world.item.Items.AIR) {
            return Ingredient.of(st.getItem());
        }
        return null;
    }


    /**
     * let's do 加热配方的水量（原版以"水位"计，1 单位 = 1 mB）：
     * 青青草甸 meadow:cooking → getFluidAmount()；煨茶酝露 herbalbrews:kettle_brewing → 私有字段 requiredWater。
     * 读不到返回 EMPTY（不设水前置）。
     */
    net.minecraftforge.fluids.FluidStack heatedRecipeWater(Recipe<?> r) {
        int amount = -1;
        Object o = be.callNoArg(r, "getFluidAmount");
        if (o instanceof Integer i) amount = i;
        if (amount < 0) amount = intField(r, "requiredWater");
        if (amount <= 0) return net.minecraftforge.fluids.FluidStack.EMPTY;
        return new net.minecraftforge.fluids.FluidStack(net.minecraft.world.level.material.Fluids.WATER, amount);
    }


    /**
     * 烘焙坊 bakeries:stone_kiln（6 配方）。参考 StoneKilnRecipe.matches 的真实语义：
     * **只看槽 0**（inputItems.get(0).test(container.getItem(0))）；时间取 cooking_time 数组首项。
     */
    MatchedRecipe matchBakeriesStoneKiln() {
        RecipeType<?> rt = cn.ism.mekck.util.RecipeCache.type(new ResourceLocation("bakeries", "stone_kiln"));
        if (rt == null || be.getLevel() == null) return null;
        ItemStack slot0 = be.items.getStackInSlot(0);
        if (slot0.isEmpty()) return null;
        for (Recipe<?> r : cn.ism.mekck.util.RecipeCache.all(be.getLevel(), rt)) {
            try {
                List<Ingredient> ings = r.getIngredients();
                if (ings.isEmpty() || !ings.get(0).test(slot0)) continue;
                ItemStack result = r.getResultItem(be.getLevel().registryAccess());
                if (result.isEmpty()) continue;
                int time = 200;
                try {
                    Object t = cn.ism.mekck.util.Reflect.call(r, "getTime");
                    if (t instanceof int[] arr && arr.length > 0) time = arr[0];
                } catch (Throwable ignored) {
                }
                return new MatchedRecipe(java.util.Collections.singletonList(0), null,
                        java.util.Collections.singletonList(ings.get(0)), result, Math.max(0, time),
                        net.minecraftforge.fluids.FluidStack.EMPTY,
                        net.minecraftforge.fluids.FluidStack.EMPTY, r.getId());
            } catch (Exception ignored) {
            }
        }
        return null;
    }


    /**
     * 烘焙坊 bakeries:blender（26 配方）。参考 BlenderRecipe.matches 的真实语义：
     * 无序一对一匹配（多输入，最多 9 个）；12 个配方带 container 字段（原版为容器返还，
     * MekCK 未实现容器返还，此处仅按配方消耗原料、产出结果）。
     * 输入槽：0..4 + 扩展槽 10..13（搅拌机专属，共 9 槽）。
     */
    /** 智能茶艺机：执行「简单的茶」的原版合成配方（茶杯 + 茶包 + 热茶壶 → 杯装茶）。 */
    MatchedRecipe matchTea() {
        if (be.getLevel() == null) return null;
        java.util.List<Integer> slots = new java.util.ArrayList<>();
        java.util.List<ItemStack> slotStacks = new java.util.ArrayList<>();
        for (int s : be.activeInputSlots()) {
            ItemStack st = be.items.getStackInSlot(s);
            if (!st.isEmpty()) {
                slots.add(s);
                slotStacks.add(st);
            }
        }
        if (slots.isEmpty()) return null;
        for (Recipe<?> r : be.allRecipesOfKind()) {
            try {
                List<Ingredient> required = new ArrayList<>();
                for (Ingredient ing : r.getIngredients()) {
                    if (ing != null && !ing.isEmpty()) required.add(ing);
                }
                if (required.isEmpty() || slotStacks.size() != required.size()) continue;
                int[] match = net.minecraftforge.common.util.RecipeMatcher.findMatches(slotStacks, required);
                if (match == null) continue;
                ItemStack result = r.getResultItem(be.getLevel().registryAccess());
                if (result.isEmpty() || !be.isSimplyTeaResult(r)) continue;
                java.util.List<Integer> consumeSlots = new java.util.ArrayList<>();
                boolean[] used = new boolean[slots.size()];
                boolean ok = true;
                for (int i = 0; i < required.size(); i++) {
                    int idx = match[i];
                    if (idx < 0 || idx >= slots.size() || used[idx]) { ok = false; break; }
                    used[idx] = true;
                    consumeSlots.add(slots.get(idx));
                }
                if (!ok) continue;
                return new MatchedRecipe(consumeSlots, null, required, result, 0,
                        net.minecraftforge.fluids.FluidStack.EMPTY,
                        net.minecraftforge.fluids.FluidStack.EMPTY, r.getId());
            } catch (Throwable ignored) {
            }
        }
        return null;
    }


    MatchedRecipe matchBlender() {
        RecipeType<?> rt = cn.ism.mekck.util.RecipeCache.type(new ResourceLocation("bakeries", "blender"));
        if (rt == null || be.getLevel() == null) return null;
        java.util.List<Integer> slots = new java.util.ArrayList<>();
        java.util.List<ItemStack> slotStacks = new java.util.ArrayList<>();
        for (int s : be.activeInputSlots()) {
            ItemStack st = be.items.getStackInSlot(s);
            if (!st.isEmpty()) {
                slots.add(s);
                slotStacks.add(st);
            }
        }
        if (slots.isEmpty()) return null;
        for (Recipe<?> r : cn.ism.mekck.util.RecipeCache.all(be.getLevel(), rt)) {
            try {
                List<Ingredient> required = new ArrayList<>();
                for (Ingredient ing : r.getIngredients()) {
                    if (ing != null && !ing.isEmpty()) required.add(ing);
                }
                // 容器：原版 BlenderBlockEntity.isContainer 校验容器槽（槽 9）必须放配方的 container，
                // 搅拌后容器被内容物替换（如玻璃瓶 → 瓶装奶油）。
                Ingredient container = containerIng(be.callNoArg(r, "getContainer"));
                if (container != null) required.add(container);
                if (required.isEmpty() || slotStacks.size() != required.size()) continue;
                int[] match = net.minecraftforge.common.util.RecipeMatcher.findMatches(slotStacks, required);
                if (match == null) continue;
                ItemStack result = r.getResultItem(be.getLevel().registryAccess());
                if (result.isEmpty()) continue;
                java.util.List<Integer> consumeSlots = new java.util.ArrayList<>();
                boolean[] used = new boolean[slots.size()];
                boolean ok = true;
                for (int i = 0; i < required.size(); i++) {
                    int idx = match[i];
                    if (idx < 0 || idx >= slots.size() || used[idx]) { ok = false; break; }
                    used[idx] = true;
                    consumeSlots.add(slots.get(idx));
                }
                if (!ok) continue;
                return new MatchedRecipe(consumeSlots, null, required, result, 0,
                        net.minecraftforge.fluids.FluidStack.EMPTY,
                        net.minecraftforge.fluids.FluidStack.EMPTY, r.getId());
            } catch (Exception ignored) {
            }
        }
        return null;
    }


    // ── 调酒机：kaleidoscope_tavern:shaker（3 个酒类/原料 tag → 鸡尾酒）──

    /**
     * 酒馆 shaker（12 配方）。参考 ShakerRecipe.matches 与 ShakerBlockEntity.addIngredient：
     * - 3 个 ingredient，无序一对一匹配（RecipeMatcher.findMatches）；
     * - 每槽容量 1；放入的酒类必须是“优质以上”（BottleBlockItem.getBrewLevel() >= 4，
     *   即 NBT BrewLevel >= 4；非酒瓶物品不受此限制）；
     * - 无时间字段（原版为交互触发），MekCK 按机器默认处理时长（200 tick）执行。
     */
    MatchedRecipe matchShaker() {
        RecipeType<?> rt = cn.ism.mekck.compat.TavernBarrelCompat.shakerType();
        if (rt == null || be.getLevel() == null) return null;
        java.util.List<Integer> slots = new java.util.ArrayList<>();
        java.util.List<ItemStack> slotStacks = new java.util.ArrayList<>();
        for (int s = 0; s < be.INPUT_COUNT; s++) {
            ItemStack st = be.items.getStackInSlot(s);
            if (st.isEmpty()) continue;
            // 酒类品质校验（原版 BottleBlockItem.isValidForShaker）
            if (!isValidForShaker(st)) return null;
            slots.add(s);
            slotStacks.add(st);
        }
        if (slots.isEmpty()) return null;
        for (Recipe<?> r : cn.ism.mekck.util.RecipeCache.all(be.getLevel(), rt)) {
            try {
                List<Ingredient> required = new ArrayList<>();
                for (Ingredient ing : r.getIngredients()) {
                    if (ing != null && !ing.isEmpty()) required.add(ing);
                }
                if (required.isEmpty() || slotStacks.size() != required.size()) continue;
                int[] match = net.minecraftforge.common.util.RecipeMatcher.findMatches(slotStacks, required);
                if (match == null) continue;
                ItemStack result = r.getResultItem(be.getLevel().registryAccess());
                if (result.isEmpty()) continue;
                java.util.List<Integer> consumeSlots = new java.util.ArrayList<>();
                boolean[] used = new boolean[slots.size()];
                boolean ok = true;
                for (int i = 0; i < required.size(); i++) {
                    int idx = match[i];
                    if (idx < 0 || idx >= slots.size() || used[idx]) { ok = false; break; }
                    used[idx] = true;
                    consumeSlots.add(slots.get(idx));
                }
                if (!ok) continue;
                return new MatchedRecipe(consumeSlots, null, required, result, 0,
                        net.minecraftforge.fluids.FluidStack.EMPTY,
                        net.minecraftforge.fluids.FluidStack.EMPTY, r.getId());
            } catch (Exception ignored) {
            }
        }
        return null;
    }


    /** 酒瓶品质校验：tavern 酒瓶需 BrewLevel >= 4（优质以上）；非酒瓶物品放行。 */
    boolean isValidForShaker(ItemStack stack) {
        String cls = stack.getItem().getClass().getName();
        if (!cls.contains("BottleBlockItem")) return true;
        int level = stack.getTag() != null ? stack.getTag().getInt("BrewLevel") : 0;
        return level >= 4;
    }


    // ── 通用匹配：把每个 Ingredient 分配给不同输入槽（0..INPUT_COUNT-1）──

    List<Integer> matchIngredients(List<Ingredient> required) {
        if (required == null || required.isEmpty()) return null;
        boolean[] used = new boolean[be.INPUT_COUNT];
        List<Integer> slots = new ArrayList<>(required.size());
        for (Ingredient ing : required) {
            if (ing == null || ing.isEmpty()) continue;
            boolean ok = false;
            for (int s = 0; s < be.INPUT_COUNT; s++) {
                if (used[s]) continue;
                ItemStack st = be.items.getStackInSlot(s);
                if (!st.isEmpty() && ing.test(st)) {
                    used[s] = true;
                    slots.add(s);
                    ok = true;
                    break;
                }
            }
            if (!ok) return null;
        }
        return slots.isEmpty() ? null : slots;
    }

    boolean validateInputsFor(MatchedRecipe recipe) {
        if (recipe.consumeSlots == null) return true;
        for (int i = 0; i < recipe.consumeSlots.size(); i++) {
            int slot = recipe.consumeSlots.get(i);
            ItemStack st = be.items.getStackInSlot(slot);
            if (st.isEmpty()) return false;
            int need = recipe.consumeCounts != null && i < recipe.consumeCounts.size()
                    ? recipe.consumeCounts.get(i) : 1;
            if (st.getCount() < need) return false;
            if (recipe.inputIngredients != null && i < recipe.inputIngredients.size()
                    && recipe.inputIngredients.get(i) != null
                    && !recipe.inputIngredients.get(i).test(st)) {
                return false; // 输入被替换成不匹配物品
            }
        }
        if (!recipe.drainFluid.isEmpty()) {
            if (be.fluids.getInputTank().getFluidAmount() < recipe.drainFluid.getAmount()) return false;
            if (!be.fluids.getInputTank().isEmpty() && !be.fluids.getInputTank().getFluid().isFluidEqual(recipe.drainFluid)) return false;
        }
        return true;
    }

    boolean canCommitRecipe(MatchedRecipe recipe) {
        return validateInputsFor(recipe) && canAcceptOutputs(recipe);
    }


    /**
     * 生产前统一输出容量/兼容校验：物品输出 + 流体输出（填充/抽取）都必须能完整接收才允许加工。
     * 任何输入消耗发生前调用；提交阶段（complete）再次确认，保证“一次扣料、一次提交”。
     */
    boolean canAcceptOutputs(MatchedRecipe recipe) {
        if (!be.canInsertOutput(recipe.result)) return false;
        if (!recipe.fillFluid.isEmpty()) {
            // 输出罐必须为空或流体兼容，且剩余容量足够容纳完整产物（模拟填充为准）
            if (!be.fluids.getOutputTank().isEmpty() && !be.fluids.getOutputTank().getFluid().isFluidEqual(recipe.fillFluid)) return false;
            if (be.fluids.getOutputTank().fill(recipe.fillFluid, net.minecraftforge.fluids.capability.IFluidHandler.FluidAction.SIMULATE)
                    != recipe.fillFluid.getAmount()) {
                return false;
            }
        }
        if (!recipe.drainFluid.isEmpty()) {
            // 输入流体抽取量必须充足（加工中途可能被抽走）
            if (be.fluids.getInputTank().getFluidAmount() < recipe.drainFluid.getAmount()) return false;
            // 类型必须在这里就判，与 validateInputsFor 逐字同口径（那里也有这一条）：
            // 只判量会让 canWork 恒真 —— 每 tick 扣电、progress 推满，complete() 再被
            // validateInputsFor 的类型比较挡回、progress 归零，表现为「机器亮着、电一直掉、
            // 产物永远不出」的静默空转。触发路径有二：matchHeatedMultiInput 匹配时根本不看罐，
            // 以及匹配结果被 matchCached 缓存而缓存键不含罐内容（换液不换物品）。
            if (!be.fluids.getInputTank().isEmpty() && !be.fluids.getInputTank().getFluid().isFluidEqual(recipe.drainFluid)) return false;
        }
        return true;
    }


    void complete(MatchedRecipe recipe) {
        // 提交前统一验证：输入（物品 Ingredient/数量/流体）仍满足，且所有输出（物品/流体）可完整接收。
        // 失败则直接返回（未扣料、未提交、不计数；进度已在调用方归零），不凭空返还材料。
        // 全部验证通过后，同一份 recipe 依次扣料、提交产物、最后才增加订单完成计数。
        if (!canCommitRecipe(recipe)) {
            return;
        }
        for (int i = 0; i < recipe.consumeSlots.size(); i++) {
            int s = recipe.consumeSlots.get(i);
            ItemStack st = be.items.getStackInSlot(s);
            if (st.isEmpty()) continue;
            int need = recipe.consumeCounts != null && i < recipe.consumeCounts.size()
                    ? recipe.consumeCounts.get(i) : 1;
            // 调酒机容器返还（复刻原版 ShakerBlockEntity：带容器物品/药水消耗后返还空容器）
            ItemStack containerReturn = be.kind == MachineKind.COCKTAIL_SHAKER && need > 0
                    ? shakerContainerReturn(st) : ItemStack.EMPTY;
            st.shrink(Math.min(need, st.getCount()));
            if (!containerReturn.isEmpty()) {
                returnContainer(containerReturn, s);
            }
        }
        // 陈酿机：果汁液位池结算（juiceLevelAfter 已含本次充填与扣除；-1 = 不涉及）
        if (recipe.juiceLevelAfter >= 0) {
            be.juiceLevel = Math.max(0, Math.min(be.JUICE_MAX_LEVEL, recipe.juiceLevelAfter));
            be.juiceType = recipe.juiceTypeAfter == null ? "" : recipe.juiceTypeAfter;
        }
        be.insertOutputDirectly(recipe.result);
        // 流体：消耗输入流体、产出输出流体（真实提交，与验证同一份配方）
        if (!recipe.drainFluid.isEmpty()) {
            be.fluids.getInputTank().drain(recipe.drainFluid.getAmount(), net.minecraftforge.fluids.capability.IFluidHandler.FluidAction.EXECUTE);
        }
        if (!recipe.fillFluid.isEmpty()) {
            be.fluids.getOutputTank().fill(recipe.fillFluid, net.minecraftforge.fluids.capability.IFluidHandler.FluidAction.EXECUTE);
        }
        // ME 订单进度：仅在本次生产全部真实提交成功后计数
        if (be.orderRecipeId != null) {
            be.orderCompleted++;
            if (be.orderCompleted >= be.orderQuantity) {
                be.orderRecipeId = null;
                be.orderQuantity = 0;
                be.orderCompleted = 0;
            }
        }
        be.setChanged();
    }


    /**
     * 调酒机的容器返还物（复刻原版 ShakerBlockEntity.addIngredient 的语义）：
     * 1. 物品自带的合成剩余物（hasCraftingRemainingItem）；
     * 2. 药水 → 玻璃瓶；
     * 3. tavern 的 IHasContainer#getContainerItem（反射调用，未安装酒馆时跳过）。
     */
    ItemStack shakerContainerReturn(ItemStack consumed) {
        if (consumed.hasCraftingRemainingItem()) {
            return consumed.getCraftingRemainingItem();
        }
        if (consumed.getItem() instanceof net.minecraft.world.item.PotionItem) {
            return new ItemStack(net.minecraft.world.item.Items.GLASS_BOTTLE);
        }
        try {
            Object o = cn.ism.mekck.util.Reflect.call(consumed.getItem(), "getContainerItem");
            if (o instanceof ItemStack is && !is.isEmpty()) {
                return is.copy();
            }
        } catch (Throwable ignored) {
        }
        return ItemStack.EMPTY;
    }


    /** 把容器返还物放回槽位：优先原输入槽，其次产物槽，再退到任意空输入槽。 */
    void returnContainer(ItemStack remainder, int preferredSlot) {
        ItemStack inSlot = be.items.getStackInSlot(preferredSlot);
        if (inSlot.isEmpty()) {
            be.items.setStackInSlot(preferredSlot, remainder);
            return;
        }
        if (ItemStack.isSameItemSameTags(inSlot, remainder)
                && cn.ism.mekck.util.CountMath.canStack(inSlot.getCount(), remainder.getCount(), inSlot.getMaxStackSize())) {
            inSlot.grow(remainder.getCount());
            return;
        }
        if (be.canInsertOutput(remainder)) {
            be.insertOutputDirectly(remainder);
            return;
        }
        for (int s = 0; s < be.INPUT_COUNT; s++) {
            ItemStack other = be.items.getStackInSlot(s);
            if (other.isEmpty()) {
                be.items.setStackInSlot(s, remainder);
                return;
            }
            if (ItemStack.isSameItemSameTags(other, remainder)
                    && cn.ism.mekck.util.CountMath.canStack(other.getCount(), remainder.getCount(), other.getMaxStackSize())) {
                other.grow(remainder.getCount());
                return;
            }
        }
        // 极端情况（全满）：仍写入产物槽，避免凭空消失
        be.insertOutputDirectly(remainder);
    }
}
