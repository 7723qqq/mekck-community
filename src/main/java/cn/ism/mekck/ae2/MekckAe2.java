package cn.ism.mekck.ae2;

import appeng.api.config.Actionable;
import appeng.api.config.AccessRestriction;
import appeng.api.config.PowerMultiplier;
import appeng.api.config.PowerUnits;
import appeng.api.crafting.IPatternDetails;
import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.networking.GridFlags;
import appeng.api.networking.GridHelper;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.IGridNodeListener;
import appeng.api.networking.IInWorldGridNodeHost;
import appeng.api.networking.IManagedGridNode;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.networking.energy.IAEPowerStorage;
import appeng.api.networking.events.GridPowerStorageStateChanged;
import appeng.api.networking.security.IActionHost;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.api.storage.MEStorage;
import appeng.api.util.AECableType;
import appeng.capabilities.Capabilities;
import cn.ism.mekck.blockentity.CookingFactoryBlockEntity;
import cn.ism.mekck.blockentity.GrillFactoryBlockEntity;
import cn.ism.mekck.blockentity.SkeweringFactoryBlockEntity;
import cn.ism.mekck.machine.MekCkFactoryType;
import cn.ism.mekck.machine.MekCkMachineTile;
import cn.ism.mekck.machine.ports.IMekCkPorted;
import cn.ism.mekck.util.KaleidoscopeCompat;
import cn.ism.mekck.util.KaleidoscopeGrillingCompat;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Containers;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.capabilities.ICapabilityProvider;
import net.minecraftforge.energy.IEnergyStorage;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.event.AttachCapabilitiesEvent;
import net.minecraftforge.items.ItemStackHandler;
import net.minecraftforge.registries.ForgeRegistries;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;
import vectorwing.farmersdelight.common.crafting.CookingPotRecipe;
import vectorwing.farmersdelight.common.crafting.CuttingBoardRecipe;
import vectorwing.farmersdelight.common.registry.ModRecipeTypes;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.UUID;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * 应用能源2（AE2）联动核心（仅当 AE2 已加载时才由 {@code util.AE2Compat} 反射加载）。
 *
 * <p>功能：</p>
 * <ul>
 *   <li>为烹饪工厂 / 穿串工厂挂接 {@code IInWorldGridNodeHost} 能力，机器可接入 ME 网络；</li>
 *   <li>每台机器 8 个 REQUIRE_CHANNEL 网格节点（1 主节点暴露接电缆 + 7 内部节点直连），共占用 8 个频道；</li>
 *   <li>以 {@link ICraftingProvider} 身份根据网络内可用食材动态注册加工样板，ME 终端可直接下单；</li>
 *   <li>pushPattern 时从网络抽取材料 → 复用机器现有订单管线制作 → 产物与空瓶/空容器自动回写网络。</li>
 * </ul>
 */
public final class MekckAe2 {
    private static final String TAG_MAIN = "MekckAe2Main";
    /** 机器占用频道数：1 主节点 + 7 内部节点 = 8 频道。 */
    private static final int CHANNELS = 8;
    private static final String TAG_EXTRA = "MekckAe2Extra";
    private static final int REFRESH_INTERVAL = 40;
    private static final Logger LOGGER = LogManager.getLogger("MekckAe2");
    private static final Map<BlockEntity, FactoryGridHost> HOSTS = new WeakHashMap<>();

    private MekckAe2() {
    }

    // ==================================================================
    //  入口（由 util.AE2Compat 反射调用）
    // ==================================================================

    public static void attachCapabilities(AttachCapabilitiesEvent<BlockEntity> event) {
        BlockEntity be = event.getObject();
        if (!cn.ism.mekck.advancement.NetworkChefProgress.isAe2Machine(be)) {
            return;
        }
        FactoryGridHost host = HOSTS.computeIfAbsent(be, FactoryGridHost::new);
        event.addCapability(new ResourceLocation("mekck", "ae2_grid_node"), new ICapabilityProvider() {
            @Override
            public <T> LazyOptional<T> getCapability(Capability<T> cap, @Nullable Direction side) {
                if (cap == Capabilities.IN_WORLD_GRID_NODE_HOST) {
                    return host.self().cast();
                }
                return LazyOptional.empty();
            }
        });
    }

    public static void serverTick(BlockEntity be, Level level, BlockPos pos) {
        if (level == null || level.isClientSide) return; // 服务端权威
        FactoryGridHost host = HOSTS.get(be);
        if (host == null) {
            host = HOSTS.computeIfAbsent(be, FactoryGridHost::new);
        }
        host.serverTick(level, pos);
    }

    public static void saveAdditional(BlockEntity be, CompoundTag tag) {
        FactoryGridHost host = HOSTS.get(be);
        if (host == null) return;
        host.saveToNBT(tag);
    }

    public static void load(BlockEntity be, CompoundTag tag) {
        FactoryGridHost host = HOSTS.computeIfAbsent(be, FactoryGridHost::new);
        host.loadFromNBT(tag);
    }

    public static void onRemoved(BlockEntity be) {
        FactoryGridHost host = HOSTS.get(be);
        if (host != null) {
            host.destroy();
        }
    }

    // ==================================================================
    //  本模组下单面板直连 ME 网络（烹饪工厂作为“无限容量合成CPU”）
    //  —— 由 util.AE2Compat 反射调用；未装 AE2 时短路为空操作。
    // ==================================================================

    public static boolean isNetworkAvailable(BlockEntity be) {
        FactoryGridHost host = HOSTS.get(be);
        return host != null && host.mainNode != null && host.mainNode.isActive();
    }

    /** 返回网络库存材料足够（≥1 份）的可下单配方 id 列表（面板 ME 来源用）。 */
    public static List<String> getNetworkOrderableRecipeIds(BlockEntity be) {
        return new ArrayList<>(getNetworkCraftableMap(be).keySet());
    }

    /** 旧入口：单配方可做份数（内部改为整表计算，调用方应优先用 {@link #getNetworkCraftableMap}）。 */
    public static int getNetworkMaxCraftable(BlockEntity be, String recipeId) {
        if (recipeId == null) return 0;
        Integer v = getNetworkCraftableMap(be).get(recipeId);
        return v == null ? 0 : v;
    }

    /**
     * 面板 ME 下单数据：{@code recipeId → 网络库存可做份数}（一次算全表，供列表请求批量使用）。
     *
     * <p>烹饪工厂 / 穿串工厂沿用各自既有的配方来源（与已验收行为一致）；
     * <b>其余机器走通用分支</b>：直接复用该机器注册到 ME 终端的样板
     * （{@code refreshPatterns} 的产物），因此「终端能做的，面板就能下单」，
     * 不需要为每台机器再写一套配方来源。</p>
     */
    public static Map<String, Integer> getNetworkCraftableMap(BlockEntity be) {
        Map<String, Integer> out = new LinkedHashMap<>();
        if (be == null) return out;
        if (be instanceof CookingFactoryBlockEntity cook) {
            Map<AEKey, Long> avail = getNetworkAvail(cook);
            if (avail == null) return out;
            // 同一产物只保留一个可下单选项（FD/森罗厨房/终焉烹饪可能出现同款食物）
            Set<String> resultSeen = new HashSet<>();
            for (Recipe<?> recipe : allCookingRecipes(cook)) {
                try {
                    List<InputSpec> specs = cookingInputs(recipe);
                    if (specs.isEmpty()) continue;
                    if (resolveInputs(specs, avail) == null) continue;
                    ItemStack result = recipe.getResultItem(cook.getLevel().registryAccess());
                    ResourceLocation itemId = result.isEmpty() ? null : ForgeRegistries.ITEMS.getKey(result.getItem());
                    String key = itemId == null ? recipe.getId().toString()
                            : itemId.toString() + (result.getTag() == null ? "" : "#" + result.getTag());
                    if (resultSeen.add(key)) {
                        out.put(recipe.getId().toString(), maxCraftable(specs, avail));
                    }
                } catch (Throwable ignored) {
                }
            }
            return out;
        }
        if (be instanceof SkeweringFactoryBlockEntity skew) {
            Map<AEKey, Long> avail = getNetworkAvail(skew);
            if (avail == null) return out;
            Set<String> resultSeen = new HashSet<>();
            for (Recipe<?> recipe : allSkeweringRecipes(skew.getLevel())) {
                try {
                    List<InputSpec> specs = skeweringInputs(recipe);
                    if (specs.isEmpty()) continue;
                    if (resolveInputs(specs, avail) == null) continue;
                    ItemStack result = recipe.getResultItem(skew.getLevel().registryAccess());
                    ResourceLocation itemId = result.isEmpty() ? null : ForgeRegistries.ITEMS.getKey(result.getItem());
                    String key = itemId == null ? recipe.getId().toString()
                            : itemId.toString() + (result.getTag() == null ? "" : "#" + result.getTag());
                    if (resultSeen.add(key)) {
                        out.put(recipe.getId().toString(), maxCraftable(specs, avail));
                    }
                } catch (Throwable ignored) {
                }
            }
            return out;
        }
        // 通用分支：与 ME 终端同一批样板（烧烤工厂 / 智能厨锅 / 智能穿串机 / 中央厨房 / 联动机器…）
        Map<AEKey, Long> avail = getNetworkAvail(be);
        if (avail == null) return out;
        for (PatternEntry entry : panelEntries(be)) {
            if (entry.recipeId == null) continue;
            int max = maxCraftableOf(entry, avail);
            out.merge(entry.recipeId.toString(), max, Math::max);
        }
        return out;
    }

    /** 单配方可做份数（按"每份所需输入"逐项取最小值）。 */
    private static int maxCraftable(List<InputSpec> specs, Map<AEKey, Long> avail) {
        int max = Integer.MAX_VALUE;
        for (InputSpec spec : specs) {
            long have = countAvailable(spec.ingredient, avail);
            max = (int) Math.min(max, have / Math.max(1, spec.count));
            if (max <= 0) return 0;
        }
        return max == Integer.MAX_VALUE ? 0 : max;
    }

    // ==================================================================
    //  面板 ME：AE 终端式「缺料清单」（缺少 X × N）
    // ==================================================================

    /**
     * AE 终端式缺料摘要：按"该配方在终端样板里的每份输入 × quantity"与网络库存对比，
     * 返回形如 {@code 缺少 小麦×3、糖×1}；材料足够（或该配方不在本机样板里）时返回 {@code null}。
     */
    public static String describeNetworkMissing(BlockEntity be, String recipeId, int quantity) {
        if (be == null || recipeId == null) return null;
        int qty = Math.max(1, quantity);
        Map<AEKey, Long> avail = getNetworkAvail(be);
        if (avail == null) return null;
        List<GenericStack> need = null;
        for (PatternEntry entry : panelEntries(be)) {
            if (entry.recipeId != null && recipeId.equals(entry.recipeId.toString())) {
                need = patternInputs(entry);
                break;
            }
        }
        if (need == null || need.isEmpty()) {
            // 兜底：该配方不在本机样板里（材料一件都没有 ⇒ 终端里根本不可做）时，
            // 直接按配方成分算缺口，这样"可做次数为 0"的配方也能给出"缺什么、缺多少"。
            need = recipeInputsByKey(be, recipeId);
        }
        if (need == null || need.isEmpty()) return null;
        List<String> missing = new ArrayList<>();
        for (GenericStack gs : need) {
            long required = Math.max(1L, gs.amount()) * qty;
            long have = countAvailableKey(gs.what(), avail);
            if (have >= required) continue;
            missing.add(displayNameOf(gs.what()) + "×" + (required - have));
        }
        if (missing.isEmpty()) return null;
        int show = Math.min(3, missing.size());
        StringBuilder sb = new StringBuilder("缺少 ");
        for (int i = 0; i < show; i++) {
            if (i > 0) sb.append('、');
            sb.append(missing.get(i));
        }
        if (missing.size() > show) sb.append(" 等 ").append(missing.size()).append(" 项");
        return sb.toString();
    }

    /** 兜底输入：按配方 id 从 RecipeManager 取成分（每项 1 个），用于样板里没有的配方。 */
    private static List<GenericStack> recipeInputsByKey(BlockEntity be, String recipeId) {
        Level level = be.getLevel();
        if (level == null) return null;
        ResourceLocation id = ResourceLocation.tryParse(recipeId);
        if (id == null) return null;
        Recipe<?> recipe = level.getRecipeManager().byKey(id).orElse(null);
        if (recipe == null) return null;
        List<GenericStack> need = new ArrayList<>();
        for (Ingredient ing : recipe.getIngredients()) {
            if (ing == null || ing.isEmpty()) continue;
            ItemStack[] items = ing.getItems();
            if (items.length == 0 || items[0].isEmpty()) continue;
            need.add(new GenericStack(AEItemKey.of(items[0]), 1));
        }
        return need;
    }

    /** 本机在 ME 终端注册的样板（面板 ME 列表与缺料清单都以此为唯一来源）。 */
    private static List<PatternEntry> panelEntries(BlockEntity be) {
        FactoryGridHost host = HOSTS.get(be);
        if (host == null || host.mainNode == null || !host.mainNode.isActive()) return List.of();
        host.refreshPatterns(); // 面板打开/切模式时按最新网络库存重算
        return host.provider.entries;
    }

    /** 样板每份所需输入（possibleInputs 首项 × multiplier）。 */
    private static List<GenericStack> patternInputs(PatternEntry entry) {
        List<GenericStack> need = new ArrayList<>();
        try {
            for (IPatternDetails.IInput input : entry.details.getInputs()) {
                GenericStack[] possible = input.getPossibleInputs();
                if (possible == null || possible.length == 0) continue;
                GenericStack first = possible[0];
                if (first == null || first.amount() <= 0) continue;
                need.add(new GenericStack(first.what(), first.amount() * Math.max(1L, input.getMultiplier())));
            }
        } catch (Throwable ignored) {
        }
        return need;
    }

    private static int maxCraftableOf(PatternEntry entry, Map<AEKey, Long> avail) {
        int max = Integer.MAX_VALUE;
        for (GenericStack gs : patternInputs(entry)) {
            long per = Math.max(1L, gs.amount());
            max = (int) Math.min(max, countAvailableKey(gs.what(), avail) / per);
            if (max <= 0) return 0;
        }
        return max == Integer.MAX_VALUE ? 0 : max;
    }

    /** 网络里该 key 的存量：先精确匹配，再按物品注册名兜底（样板键与库存键可能有 NBT/模糊差异）。 */
    private static long countAvailableKey(AEKey key, Map<AEKey, Long> avail) {
        long exact = 0L;
        Long hit = avail.get(key);
        if (hit != null) exact = hit;
        if (exact > 0L) return exact;
        if (key instanceof AEItemKey ik) {
            ResourceLocation id = ForgeRegistries.ITEMS.getKey(ik.getItem());
            if (id != null) {
                long total = 0L;
                for (Map.Entry<AEKey, Long> e : avail.entrySet()) {
                    if (e.getKey() instanceof AEItemKey other
                            && id.equals(ForgeRegistries.ITEMS.getKey(other.getItem()))) {
                        total += e.getValue();
                    }
                }
                if (total > 0L) return total;
            }
        }
        return exact;
    }

    private static String displayNameOf(AEKey key) {
        try {
            net.minecraft.network.chat.Component name = key.getDisplayName();
            if (name != null) return name.getString();
        } catch (Throwable ignored) {
        }
        return String.valueOf(key);
    }

    /**
     * 从 ME 网络抽取 {@code quantity} 份配方材料放入机器存储，并创建 AE2 任务
     * （产物由现有 processJob 回写网络）。成功返回 true。
     */
    public static boolean pullNetworkIngredients(BlockEntity be, String recipeId, int quantity) {
        return pullNetworkIngredients(be, recipeId, quantity, null);
    }

    /**
     * 面板 ME 下单（带调味）：{@code seasoningId} 仅烧烤工厂这类三参 {@code setOrder} 机器使用，
     * 其余机器忽略（终端下单不带调味，面板可以选择风味）。
     */
    public static boolean pullNetworkIngredients(BlockEntity be, String recipeId, int quantity, String seasoningId) {
        if (quantity <= 0) return false;
        if (be instanceof CookingFactoryBlockEntity cook) {
            return pullCookingIngredients(cook, recipeId, quantity);
        }
        if (be instanceof SkeweringFactoryBlockEntity skew) {
            return pullSkeweringIngredients(skew, recipeId, quantity);
        }
        // 通用分支：任何"可下单 + 已接入网络"的机器都支持面板 ME 下单。
        // 与终端路径同构：抽料 → 插入机器 → 建 AeJob（记录产物回网量）→ setOrder(recipeId, quantity)，
        // 只是这里由玩家在面板里指定数量，**不需要 AE2 合成 CPU、也不受 CPU 字节限制**。
        return pullGenericIngredients(be, recipeId, quantity, seasoningId);
    }

    /** 通用面板 ME 下单：任意 INetworkPullable 机器（配方按 id 从 RecipeManager 取）。 */
    private static boolean pullGenericIngredients(BlockEntity be, String recipeId, int quantity, String seasoningId) {
        if (!(be instanceof INetworkPullable)) return false;
        FactoryGridHost host = HOSTS.get(be);
        if (host == null || host.mainNode == null || !host.mainNode.isActive()) return false;
        IGrid grid = host.mainNode.getGrid();
        if (grid == null) return false;
        MEStorage storage = grid.getStorageService().getInventory();
        if (storage == null) return false;
        Level level = be.getLevel();
        if (level == null) return false;
        net.minecraft.resources.ResourceLocation rid =
                net.minecraft.resources.ResourceLocation.tryParse(recipeId);
        if (rid == null) return false;
        Recipe<?> recipe = level.getRecipeManager().byKey(rid).orElse(null);
        if (recipe == null) return false;

        List<InputSpec> specs = new ArrayList<>();
        for (Ingredient ing : recipe.getIngredients()) {
            if (ing != null && !ing.isEmpty()) specs.add(new InputSpec(ing, 1));
        }
        if (specs.isEmpty()) return false;

        IActionSource src = IActionSource.ofMachine(host);
        List<GenericStack> extracted = extractAll(storage, specs, quantity, src);
        if (extracted == null) return false;
        host.insertIntoNetworkPullMachine(extracted);

        ItemStack result = recipe.getResultItem(level.registryAccess());
        if (!result.isEmpty()) {
            long total = (long) result.getCount() * Math.max(1, quantity);
            host.job = new AeJob(new PatternEntry(null, recipe.getId(),
                    new GenericStack[]{new GenericStack(AEItemKey.of(result), total)}));
        }
        setOrderReflectively(be, recipe.getId(), quantity, seasoningId);
        return true;
    }

    /**
     * 反射设置订单：各机器的 {@code setOrder} 签名不同
     * （多数为 {@code (ResourceLocation, int)}，烧烤工厂为 {@code (ResourceLocation, int, String)} 带调味）。
     */
    private static void setOrderReflectively(BlockEntity be, net.minecraft.resources.ResourceLocation recipeId,
                                             int quantity, String seasoningId) {
        try {
            java.lang.reflect.Method two = cn.ism.mekck.util.Reflect.method(be.getClass(), "setOrder",
                    net.minecraft.resources.ResourceLocation.class, int.class);
            if (two != null) {
                two.invoke(be, recipeId, quantity);
                return;
            }
            java.lang.reflect.Method three = cn.ism.mekck.util.Reflect.method(be.getClass(), "setOrder",
                    net.minecraft.resources.ResourceLocation.class, int.class, String.class);
            if (three != null) {
                three.invoke(be, recipeId, quantity, seasoningId);
                return;
            }
            // 中央厨房走 placeOrder 而非 setOrder
            if (be instanceof cn.ism.mekck.blockentity.CentralKitchenBlockEntity kitchen) {
                kitchen.placeOrder(be.getLevel(), recipeId, quantity);
            }
        } catch (Throwable ignored) {
        }
    }

    private static boolean pullCookingIngredients(CookingFactoryBlockEntity cook, String recipeId, int quantity) {
        FactoryGridHost host = HOSTS.get(cook);
        if (host == null || host.mainNode == null || !host.mainNode.isActive()) return false;
        IGrid grid = host.mainNode.getGrid();
        if (grid == null) return false;
        MEStorage storage = grid.getStorageService().getInventory();
        if (storage == null) return false;
        Recipe<?> recipe = findCookingRecipe(cook, recipeId);
        if (recipe == null) return false;
        List<InputSpec> specs = cookingInputs(recipe);
        if (specs.isEmpty()) return false;
        ItemStack result = recipe.getResultItem(cook.getLevel().registryAccess());
        if (result.isEmpty()) return false;

        IActionSource src = IActionSource.ofMachine(host);
        List<GenericStack> extracted = extractAll(storage, specs, quantity, src);
        if (extracted == null) return false;
        host.insertIntoMachine(extracted);
        long total = (long) result.getCount() * quantity;
        host.job = new AeJob(new PatternEntry(null, recipe.getId(),
                new GenericStack[]{new GenericStack(AEItemKey.of(result), total)}));
        cook.setOrder(recipe.getId(), quantity);
        return true;
    }

    private static boolean pullSkeweringIngredients(SkeweringFactoryBlockEntity skew, String recipeId, int quantity) {
        FactoryGridHost host = HOSTS.get(skew);
        if (host == null || host.mainNode == null || !host.mainNode.isActive()) return false;
        IGrid grid = host.mainNode.getGrid();
        if (grid == null) return false;
        MEStorage storage = grid.getStorageService().getInventory();
        if (storage == null) return false;
        Recipe<?> recipe = findSkeweringRecipe(skew.getLevel(), recipeId);
        if (recipe == null) return false;
        List<InputSpec> specs = skeweringInputs(recipe);
        if (specs.isEmpty()) return false;
        ItemStack result = recipe.getResultItem(skew.getLevel().registryAccess());
        if (result.isEmpty()) return false;

        IActionSource src = IActionSource.ofMachine(host);
        List<GenericStack> extracted = extractAll(storage, specs, quantity, src);
        if (extracted == null) return false;
        host.insertIntoMachine(extracted);
        long total = (long) result.getCount() * quantity;
        host.job = new AeJob(new PatternEntry(null, recipe.getId(),
                new GenericStack[]{new GenericStack(AEItemKey.of(result), total)}));
        skew.setOrder(recipe.getId(), quantity);
        return true;
    }

    // ==================================================================
    //  通用"网络拉料"（INetworkPullable 机器）
    //  —— 仅负责从 ME 网络抽取当前可处理配方的材料进机器输入槽，
    //     不写产物、不下单；简单单输入机器支持勾选持续自动补料。
    // ==================================================================

    /** 手动网络拉料：拉取机器当前可处理配方的一份输入进输入槽。成功返回 true。 */
    public static boolean pullNetworkInputs(BlockEntity be) {
        if (!(be instanceof INetworkPullable pullable)) return false;
        FactoryGridHost host = HOSTS.get(be);
        if (host == null || host.mainNode == null || !host.mainNode.isActive()) return false;
        IGrid grid = host.mainNode.getGrid();
        if (grid == null) return false;
        MEStorage storage = grid.getStorageService().getInventory();
        if (storage == null) return false;
        List<cn.ism.mekck.util.AE2InputSpec> specs = pullable.getNetworkPullInputs();
        if (specs.isEmpty()) return false;

        IActionSource src = IActionSource.ofMachine(host);

        // 单遍扫描网络满足全部需求：旧实现是"每个需求项先扫一遍网络校验、再扫一遍网络抽取"
        // （O(2 × 需求数 × 网络类型数)），81 种材料 + 大型 ME 网络会造成明显卡顿。
        // 现在只扫一遍，且需求项满足后立即从匹配集合中移除，全部满足即提前结束；
        // 匹配仍然使用 Ingredient.test（保持与配方完全一致的判定，不做物品 id 近似）。
        long[] remaining = new long[specs.size()];
        int unsatisfied = 0;
        for (int i = 0; i < specs.size(); i++) {
            remaining[i] = Math.max(0L, specs.get(i).count);
            if (remaining[i] > 0L) unsatisfied++;
        }
        List<GenericStack> extracted = new ArrayList<>();
        for (var e : storage.getAvailableStacks()) {
            if (unsatisfied <= 0) break;
            if (e.getLongValue() <= 0L) continue;
            AEKey key = e.getKey();
            if (!(key instanceof AEItemKey ik)) continue;
            net.minecraft.world.item.ItemStack probe = ik.toStack();
            for (int i = 0; i < specs.size(); i++) {
                if (remaining[i] <= 0L) continue;
                if (!specs.get(i).ingredient.test(probe)) continue;
                long take = Math.min(remaining[i], e.getLongValue());
                long got = storage.extract(key, take, Actionable.MODULATE, src);
                if (got > 0L) {
                    extracted.add(new GenericStack(key, got));
                    remaining[i] -= got;
                    if (remaining[i] <= 0L) unsatisfied--;
                }
                break; // 一个网络条目只满足一个需求项
            }
        }
        if (unsatisfied > 0) {
            rollback(storage, extracted, src);
            return false;
        }
        host.insertIntoNetworkPullMachine(extracted);
        return true;
    }

    /** 勾选持续自动补料的物品列表（INetworkPullable 机器，存于主机 NBT）。 */
    public static List<String> getSelectedAutoItemsGeneric(BlockEntity be) {
        FactoryGridHost host = HOSTS.get(be);
        if (host == null) return List.of();
        return host.getSelectedAutoItems();
    }

    public static void toggleAutoItemGeneric(BlockEntity be, String itemId) {
        FactoryGridHost host = HOSTS.get(be);
        if (host != null) {
            host.toggleAutoItem(itemId);
        }
    }

    /**
     * 自动补料开关：itemId 为空时按机器当前可处理配方的首个输入物品切换。
     * 返回给玩家显示的一句话（取消/开启/不支持），供动作栏提示。
     */
    public static String toggleAutoItemAuto(BlockEntity be) {
        if (!(be instanceof INetworkPullable pullable)) return "该方块不支持 ME 网络拉料";
        if (!pullable.supportsAutoPull()) return "该机器不支持持续自动补料";
        String itemId = null;
        for (cn.ism.mekck.util.AE2InputSpec spec : pullable.getNetworkPullInputs()) {
            for (net.minecraft.world.item.ItemStack s : spec.ingredient.getItems()) {
                if (!s.isEmpty()) {
                    itemId = registryId(s);
                    break;
                }
            }
            if (itemId != null) break;
        }
        if (itemId == null) return "当前没有可补料的输入物品（先放入一个样品或设置订单）";
        toggleAutoItemGeneric(be, itemId);
        FactoryGridHost host = HOSTS.get(be);
        boolean on = host != null && host.getSelectedAutoItems().contains(itemId);
        int limit = pullable.getAutoPullStackLimit();
        return (on ? "§a已开启" : "§7已关闭") + "§r 持续补料：§f" + itemId
                + "§r（每类型上限 " + limit + " 件，配置 auto_pull.auto_pull_stack_limit）";
    }

    /** 简单机器通用自动补料 tick：每个已勾选物品在网络有货时补 1 个进输入槽。 */
    public static void autoProcessTickGeneric(BlockEntity be) {
        if (!(be instanceof INetworkPullable pullable) || !pullable.supportsAutoPull()) return;
        FactoryGridHost host = HOSTS.get(be);
        if (host == null || host.mainNode == null || !host.mainNode.isActive()) return;
        IGrid grid = host.mainNode.getGrid();
        if (grid == null) return;
        MEStorage storage = grid.getStorageService().getInventory();
        if (storage == null) return;
        List<String> selected = host.getSelectedAutoItems();
        if (selected.isEmpty()) return;
        IActionSource src = IActionSource.ofMachine(host);
        net.minecraftforge.items.ItemStackHandler items = pullable.getNetworkPullItems();
        if (items == null) return;
        int[] range = pullable.getInputSlotRange();
        if (range.length < 2) return;
        int start = range[0];
        int end = range[1];

        // ① 单遍扫描网络：只挑出被勾选的物品，找齐即提前结束。
        //    旧实现是"每个勾选项都遍历一遍全网络"（O(勾选数 × 网络类型数)）——
        //    81 种材料 + 大型 ME 网络会直接把这个循环放大到几十万次。
        // 索引缓存：本方法现在（正常档）每 tick 都会跑，而"单遍扫全网络"对大型 ME（数千种物品）
        // 会变成每台机器每 tick 数千次遍历——80 台机器就是几十万次。网络内容变化缓慢，
        // 因此按「机器 + 勾选集合」缓存 20 tick；新增物品最多晚 1 秒被发现（抽取时仍会校验）。
        long nowTick = be.getLevel() != null ? be.getLevel().getGameTime() : 0L;
        String selKey = String.join(",", selected);
        Map<String, AEItemKey> found;
        CachedIndex cached = AUTO_INDEX.get(be);
        if (cached != null && cached.key().equals(selKey) && nowTick - cached.tick() < AUTO_INDEX_TTL) {
            found = cached.index();
        } else {
            found = indexSelected(storage, selected);
            AUTO_INDEX.put(be, new CachedIndex(selKey, nowTick, found));
        }
        if (found.isEmpty()) return;

        // ② 按"剩余空间"批量补料：一次 extract 补齐，而不是每次只抽 1 件。
        //    旧实现每次只抽 1 件、且每 10 tick 才跑一次 ⇒ 每类型 0.1 件/tick，
        //    与"ME 里存着无限材料"的实际能力差了好几个数量级。
        cn.ism.mekck.api.IBulkItemHandler dst = cn.ism.mekck.util.IntHandlerBulkView.of(items);
        int rangeLen = Math.max(0, end - start);
        // 单遍统计区间内存量：原来每个勾选项都调用一次 countInRange 扫全区间
        //（81 种勾选 × 351 槽的中央厨房 = 2.8 万次/tick）。这里只扫一遍建表，后面 O(1) 查。
        java.util.Map<String, Long> haveById = new java.util.HashMap<>();
        for (int i = start; i < end; i++) {
            net.minecraft.world.item.ItemStack s = items.getStackInSlot(i);
            if (s.isEmpty()) continue;
            String id = registryId(s);
            if (id != null) haveById.merge(id, (long) s.getCount(), Long::sum);
        }
        // 额外输入槽（联动机器的 10..13）也要计入存量：否则扩展槽里明明有料，这里却以为没有而反复补料。
        for (int slot : pullable.getExtraInputSlots()) {
            if (slot < 0 || slot >= items.getSlots()) continue;
            net.minecraft.world.item.ItemStack s = items.getStackInSlot(slot);
            if (s.isEmpty()) continue;
            String id = registryId(s);
            if (id != null) haveById.merge(id, (long) s.getCount(), Long::sum);
        }
        long perTypeBudget = cn.ism.mekck.util.LagMonitor.getMaxItemsPerDirectionLong()
                / Math.max(1, selected.size());
        boolean moved = false;
        for (String itemId : selected) {
            AEItemKey key = found.get(itemId);
            if (key == null) continue;
            long limit = pullable.getAutoPullStackLimit();
            long have = haveById.getOrDefault(itemId, 0L);
            long room = limit - have;
            if (room <= 0L) continue;
            long want = Math.min(room, perTypeBudget);
            if (want <= 0L) continue;
            long got = storage.extract(key, want, Actionable.MODULATE, src);
            if (got <= 0L) continue;
            ItemStack proto = key.toStack(1);
            long space = dst.bulkSpace(proto, start, rangeLen);
            long inserted = space <= 0L ? 0L : dst.bulkInsert(proto, Math.min(got, space), start, rangeLen, false);
            if (inserted > 0L) {
                moved = true;
            }
            if (inserted < got) {
                // 机器装不下的部分退回网络（旧实现会把它丢到地上）
                storage.insert(key, got - inserted, Actionable.MODULATE, src);
            }
        }
        if (moved) be.setChanged();
    }

    /**
     * 单遍扫描 ME 网络，只挑出被勾选的物品 id（全部找到即提前结束）。
     * 把"每个勾选项各自扫一遍网络"的 O(勾选数 × 网络类型数) 降为 O(min(网络类型数, 勾选数))。
     */
    /** 自动补料用的网络索引缓存（弱键：机器卸载后自动回收）。 */
    private static final java.util.WeakHashMap<Object, CachedIndex> AUTO_INDEX = new java.util.WeakHashMap<>();
    /** 索引缓存有效期（tick）：网络内容变化缓慢，20 tick 足够。 */
    private static final long AUTO_INDEX_TTL = 20L;

    /** 缓存的网络索引（勾选集合 + 采集时刻 + 物品索引）。 */
    private record CachedIndex(String key, long tick, Map<String, AEItemKey> index) {
    }

    private static Map<String, AEItemKey> indexSelected(MEStorage storage, List<String> selected) {
        java.util.Set<String> wanted = new java.util.HashSet<>(selected);
        Map<String, AEItemKey> found = new HashMap<>();
        for (var e : storage.getAvailableStacks()) {
            if (wanted.isEmpty()) break;
            if (e.getLongValue() <= 0 || !(e.getKey() instanceof AEItemKey ik)) continue;
            String id = registryId(ik.toStack());
            if (id != null && wanted.remove(id)) {
                found.put(id, ik);
            }
        }
        return found;
    }

    private static boolean canExtractAll(MEStorage storage, List<InputSpec> specs, int quantity, IActionSource src) {
        for (InputSpec spec : specs) {
            long need = (long) spec.count * quantity;
            if (need <= 0) continue;
            if (!canExtractFromNetwork(storage, spec.ingredient, need, src)) return false;
        }
        return true;
    }

    private static List<GenericStack> extractAll(MEStorage storage, List<InputSpec> specs, int quantity, IActionSource src) {
        // 单遍扫描网络满足全部需求项：原先每个需求项都要遍历一遍网络
        // （81 种材料的配方 + 数千种物品的网络 = 每次下单数十万次遍历，会造成明显卡顿）。
        // 需求项满足后立即从匹配集合中移除，全部满足即提前结束；不足则整体回滚（原子性不变）。
        long[] remaining = new long[specs.size()];
        int unsatisfied = 0;
        for (int i = 0; i < specs.size(); i++) {
            long need = (long) specs.get(i).count * Math.max(1, quantity);
            remaining[i] = Math.max(0L, need);
            if (remaining[i] > 0L) unsatisfied++;
        }
        List<GenericStack> extracted = new ArrayList<>();
        for (var e : storage.getAvailableStacks()) {
            if (unsatisfied <= 0) break;
            if (e.getLongValue() <= 0L) continue;
            AEKey key = e.getKey();
            if (!(key instanceof AEItemKey ik)) continue;
            ItemStack probe = ik.toStack();
            for (int i = 0; i < specs.size(); i++) {
                if (remaining[i] <= 0L) continue;
                if (!specs.get(i).ingredient.test(probe)) continue;
                long take = Math.min(remaining[i], e.getLongValue());
                long got = storage.extract(key, take, Actionable.MODULATE, src);
                if (got > 0L) {
                    extracted.add(new GenericStack(key, got));
                    remaining[i] -= got;
                    if (remaining[i] <= 0L) unsatisfied--;
                }
                break; // 一个网络条目只满足一个需求项
            }
        }
        if (unsatisfied > 0) {
            rollback(storage, extracted, src);
            return null;
        }
        return extracted;
    }

    private static Map<AEKey, Long> getNetworkAvail(BlockEntity be) {
        FactoryGridHost host = HOSTS.get(be);
        if (host == null || host.mainNode == null || !host.mainNode.isActive()) return null;
        IGrid grid = host.mainNode.getGrid();
        if (grid == null) return null;
        MEStorage storage = grid.getStorageService().getInventory();
        if (storage == null) return null;
        Map<AEKey, Long> avail = new HashMap<>();
        for (var e : storage.getAvailableStacks()) {
            if (e.getLongValue() > 0) {
                avail.put(e.getKey(), e.getLongValue());
            }
        }
        return avail;
    }

    private static Recipe<?> findCookingRecipe(CookingFactoryBlockEntity cook, String recipeId) {
        ResourceLocation id = ResourceLocation.tryParse(recipeId);
        if (id == null) return null;
        for (Recipe<?> recipe : allCookingRecipes(cook)) {
            if (recipe.getId().equals(id)) return recipe;
        }
        return null;
    }

    private static long countAvailable(Ingredient ing, Map<AEKey, Long> avail) {
        long total = 0;
        for (Map.Entry<AEKey, Long> e : avail.entrySet()) {
            AEKey key = e.getKey();
            if (key instanceof AEItemKey ik && ing.test(ik.toStack())) {
                total += e.getValue();
            }
        }
        return total;
    }

    private static boolean canExtractFromNetwork(MEStorage storage, Ingredient ing, long need, IActionSource src) {
        long total = 0;
        for (var e : storage.getAvailableStacks()) {
            if (total >= need) break;
            AEKey key = e.getKey();
            if (!(key instanceof AEItemKey ik) || !ing.test(ik.toStack())) continue;
            long got = storage.extract(key, e.getLongValue(), Actionable.SIMULATE, src);
            total += got;
        }
        return total >= need;
    }

    private static void rollback(MEStorage storage, List<GenericStack> extracted, IActionSource src) {
        for (GenericStack gs : extracted) {
            storage.insert(gs.what(), gs.amount(), Actionable.MODULATE, src);
        }
    }

    private static List<Recipe<?>> allSkeweringRecipes(Level level) {
        List<Recipe<?>> list = new ArrayList<>();
        RecipeType<?> type = cn.ism.mekck.util.RecipeCache.type(new ResourceLocation("barbequesdelight", "skewering"));
        if (type != null) {
            list.addAll(cn.ism.mekck.util.RecipeCache.all(level, type));
        }
        if (KaleidoscopeGrillingCompat.isLoaded()) {
            for (KaleidoscopeGrillingCompat.VirtualRecipe vr : KaleidoscopeGrillingCompat.getThreadingVirtualRecipes()) {
                list.add(vr);
            }
        }
        Set<ResourceLocation> seen = new HashSet<>();
        list.removeIf(r -> !seen.add(r.getId()));
        return list;
    }

    private static Recipe<?> findSkeweringRecipe(Level level, String recipeId) {
        ResourceLocation id = ResourceLocation.tryParse(recipeId);
        if (id == null) return null;
        for (Recipe<?> recipe : allSkeweringRecipes(level)) {
            if (recipe.getId().equals(id)) return recipe;
        }
        return null;
    }

    // ==================================================================
    //  ME 自动处理（切菜工厂 / 烧烤工厂）
    //  —— 勾选材料后机器记住该物品：网络中有货就持续抽取处理，耗尽仍记忆；
    //     产物自动回写网络。
    // ==================================================================

    public static void autoProcessTick(BlockEntity be) {
        // 先问 host 再开窗口：host 是一次 WeakHashMap 查找，窗口要 new 出
        // 2 个 ArrayList + 2 个 IdentityHashMap。本方法每 tick 每机器都跑，
        // 离线机器（绝大多数）在 host 这一步就返回，不必付窗口的分配。
        FactoryGridHost host = HOSTS.get(be);
        if (host == null || host.mainNode == null || !host.mainNode.isActive()) return;
        MekPortWindow window = autoWindow(be);
        if (window == null) return;
        IGrid grid = host.mainNode.getGrid();
        if (grid == null) return;
        MEStorage storage = grid.getStorageService().getInventory();
        if (storage == null) return;
        List<String> selected = getSelectedAutoItems(be);
        if (selected.isEmpty()) return;
        IActionSource src = IActionSource.ofMachine(host);
        int inputSlots = window.inputCount();

        // 1) 补料：每个已勾选物品在网络有货时抽 1 个进输入槽（存量 < 64 才补）
        for (String itemId : selected) {
            if (countInRange(window, itemId, 0, inputSlots) >= 64) continue;
            AEItemKey key = findItemInStorage(storage, itemId);
            if (key == null) continue;
            long got = storage.extract(key, 1, Actionable.MODULATE, src);
            if (got > 0) {
                insertIntoRange(be, window, key.toStack((int) got), 0, inputSlots);
            }
        }

        // 2) 产物回网：只导出勾选材料可能产出的物品
        Set<String> products = expectedAutoProducts(be, selected);
        if (!products.isEmpty()) {
            int outputStart = inputSlots;
            int outputEnd = inputSlots + window.outputCount();
            for (int i = outputStart; i < outputEnd; i++) {
                ItemStack stack = window.getStack(i);
                if (stack.isEmpty()) continue;
                String id = registryId(stack);
                if (id == null || !products.contains(id)) continue;
                AEItemKey key = AEItemKey.of(stack);
                long accepted = storage.insert(key, stack.getCount(), Actionable.MODULATE, src);
                if (accepted > 0) {
                    // 先 copy 再 shrink 再写回：Mek 槽的 getStack() 返回的是活引用
                    // （实测 BasicInventorySlot.getStack 只有 getfield current / areturn），
                    // 直接改它会绕过 slot 的变更回调，区块不会标记为脏。
                    ItemStack rest = stack.copy();
                    rest.shrink((int) accepted);
                    window.setStack(i, rest);
                }
            }
        }
    }

    /** 网络库存中该机器可处理（cutting / grilling）的材料 id 列表。 */
    public static List<String> getAutoProcessableItems(BlockEntity be) {
        FactoryGridHost host = HOSTS.get(be);
        if (host == null || host.mainNode == null || !host.mainNode.isActive()) return List.of();
        if (autoWindow(be) == null) return List.of();
        IGrid grid = host.mainNode.getGrid();
        if (grid == null) return List.of();
        MEStorage storage = grid.getStorageService().getInventory();
        if (storage == null) return List.of();
        Level level = be.getLevel();
        List<String> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (var e : storage.getAvailableStacks()) {
            if (e.getLongValue() <= 0 || !(e.getKey() instanceof AEItemKey ik)) continue;
            String id = registryId(ik.toStack());
            if (id == null || !seen.add(id)) continue;
            if (canProcess(be, ik.toStack(), level)) {
                out.add(id);
            }
        }
        return out;
    }

    /**
     * 机器「已勾选自动处理的材料」清单。
     *
     * <h3>阶段 2 Task 4.6：Mek 原生 tile 的清单存哪里，为什么</h3>
     * 旧切菜工厂把清单放在自己的 {@code autoSelectedItems} 字段（NBT 键
     * {@code AutoSelectedItems}）里。新的 {@code CuttingFactoryTile} 没有这个字段。
     * 结论是<b>不往 {@link IMekCkPorted} 加方法</b>，而是复用
     * {@link FactoryGridHost#selectedAutoItems} —— 也就是本文件里
     * {@code INetworkPullable} 通用补料路径<b>已经在用的那一份</b>
     * （NBT 键 {@code MekCkAutoSel}，读写走 {@code saveToNBT/loadFromNBT}）。
     *
     * <p>三条理由：</p>
     * <ol>
     *   <li><b>它不是 AE2 端口。</b>{@link IMekCkPorted} 的 7 个方法逐条对应外部
     *       Mek Energistics 的 {@code IMePatternAutomationHost}，那套签名只描述
     *       「样板自动化时哪些槽参与、怎么参与」。往里加一个<b>可变</b>的勾选清单
     *       会把「用户偏好」混进「端口声明」，等对方接口定稿时反而多一个对不上的方法；
     *   <li><b>网格宿主本来就是这份数据该住的地方。</b>它已经持久化、已经随节点
     *       生命周期创建销毁、已经有一套读写 API（{@code getSelectedAutoItems} /
     *       {@code toggleAutoItem}），而且语义完全相同——「这台机器记住了哪些物品」。
     *       复用它等于零新状态、零新 NBT 键、零新 getter/setter；</li>
     *   <li><b>不会有双写。</b>新的 Mek tile 不实现 {@code INetworkPullable}，
     *       所以通用补料路径（{@code autoProcessTickGeneric}）不会去动同一个清单。</li>
     * </ol>
     *
     * <p>代价要说清楚：旧存档里那批勾选<b>不会</b>自动迁移过来（旧键在 BE 的
     * {@code AutoSelectedItems}、新键在宿主的 {@code MekCkAutoSel}）。
     * 旧存档迁移 {@code MekCkLegacyMachineNbt} 已经在做一次整体翻译，
     * 要把这一项也搬过去属于存档迁移任务的范围，不在本任务。</p>
     */
    public static List<String> getSelectedAutoItems(BlockEntity be) {
        if (be instanceof IMekCkPorted) return getSelectedAutoItemsGeneric(be);
        if (be instanceof GrillFactoryBlockEntity g) return g.getAutoSelectedItems();
        return List.of();
    }

    public static void toggleAutoItem(BlockEntity be, String itemId) {
        if (be instanceof IMekCkPorted) toggleAutoItemGeneric(be, itemId);
        else if (be instanceof GrillFactoryBlockEntity g) g.toggleAutoSelectedItem(itemId);
    }

    // ----- 自动处理辅助 -----

    /**
     * 取这台机器的「输入 + 产物」窗口；不支持自动处理时返回 null。
     *
     * <p>切菜工厂（阶段 2 Task 4.6）与研磨工厂（阶段 3 Task 1）走
     * {@link IMekCkPorted}，烧烤工厂仍走旧 {@code ItemStackHandler}。烧烤分支刻意只判这一个类：
     * 其余 3 个家族不参与 ME 自动处理，判宽了会让「自动补料」误作用到
     * 不该参与的机器上。新增家族时同理：先在 {@code portedFamily} 里登记，
     * 再来这里看是否需要补分支。</p>
     */
    private static MekPortWindow autoWindow(BlockEntity be) {
        if (be instanceof IMekCkPorted ported) {
            // 家族闸门不能省：目前只有切菜与研磨认自动处理。若放行到「所有端口声明型机器」，
            // 一台还没定配方族的机器会照着玩家勾选的清单往输入槽里塞物品——
            // 那是凭空造料，不是拉料。
            if (portedFamily(be) == null) return null;
            return MekPortWindow.ofPorted(ported);
        }
        if (be instanceof GrillFactoryBlockEntity g) {
            return MekPortWindow.ofLegacyFactory(g.getItems(), g.getInputSlots());
        }
        return null;
    }

    /**
     * 端口声明型机器的工艺类型。
     *
     * <p>{@link IMekCkPorted} 只说「哪些槽参与自动化」，不说「这台机器做什么工艺」，
     * 所以配方族要从 tile 的 {@code MekCkFactoryType} 读。
     * 只有 {@code CUTTING} 认，其余一律返回 {@code null}，由调用方按
     * 「不处理任何东西」收口——宁可少补料，也不要拿错的配方族算出错的产物
     * （后者会把不相干的物品从 ME 网络里抽走）。</p>
     */
    private static MekCkFactoryType portedFamily(BlockEntity be) {
        if (!(be instanceof MekCkMachineTile tile)) {
            return null;
        }
        MekCkFactoryType type = tile.getFactoryType();
        // 切菜（阶段 2 Task 4.6）与研磨（阶段 3 Task 1）是目前两个已接线的家族。
        // 其余四个仍返回 null：它们会走自己的旧方块实体，该分支正在迁移中。
        return (type == MekCkFactoryType.CUTTING
                || type == MekCkFactoryType.GRINDING
                || type == MekCkFactoryType.PLANTING_CUTTING) ? type : null;
    }

    /**
     * 研磨家族的配料判定：能被任一张石磨配方当作配料。
     *
     * <p>只认森罗的 millstone（{@code KaleidoscopeCompat.findMillstoneRecipe} 那一批），
     * <b>不认 {@code buildGrindingPatterns} 里列的四个类型</b>。它们是给「电力研磨机」与
     * ME 终端拿来做配方展示的，但研磨工厂自己只认石磨（旧 {@code findRecipe} 只调
     * {@code KaleidoscopeCompat.findMillstoneRecipe}）。放宽了就是拉不来的料被投进去永远不加。</p>
     */
    private static boolean grindingIngredientMatches(Level level, ItemStack stack) {
        if (!cn.ism.mekck.util.KaleidoscopeCompat.isLoaded()) return false;
        for (Recipe<?> r : cn.ism.mekck.util.RecipeCache.all(level, "kaleidoscope_cookery", "millstone")) {
            for (Ingredient ing : r.getIngredients()) {
                if (ing != null && !ing.isEmpty() && ing.test(stack)) return true;
            }
        }
        return false;
    }

    /** 研磨某张石磨配方的所有可能产出（带概率，不抽样）——产物回网的白名单按「全都可能」给。 */
    private static void addGrindingProducts(Recipe<?> recipe, Set<String> out) {
        for (cn.ism.mekck.util.KaleidoscopeCompat.MillstoneOutput o
                : cn.ism.mekck.util.KaleidoscopeCompat.getMillstoneOutputs(recipe)) {
            String id = registryId(o.stack());
            if (id != null) out.add(id);
        }
    }

    /** 该物品能否作为切菜配料的任一项（供端口声明型机器判定「网络里的这堆料我吃不吃」）。 */
    private static boolean cuttingIngredientMatches(Level level, ItemStack stack) {
        for (CuttingBoardRecipe r : (java.util.List<CuttingBoardRecipe>) (java.util.List<?>) cn.ism.mekck.util.RecipeCache.all(level, ModRecipeTypes.CUTTING.get())) {
            for (Ingredient ing : r.getIngredients()) {
                if (!ing.isEmpty() && ing.test(stack)) return true;
            }
        }
        return false;
    }

    private static boolean canProcess(BlockEntity be, ItemStack stack, Level level) {
        MekCkFactoryType family = portedFamily(be);
        if (family == MekCkFactoryType.CUTTING) {
            return cuttingIngredientMatches(level, stack);
        }
        if (family == MekCkFactoryType.GRINDING) {
            return grindingIngredientMatches(level, stack);
        }
        if (be instanceof GrillFactoryBlockEntity) {
            RecipeType<?> grillingType = cn.ism.mekck.util.RecipeCache.type(new ResourceLocation("barbequesdelight", "grilling"));
            if (grillingType != null) {
                for (Recipe<?> r : cn.ism.mekck.util.RecipeCache.all(level, grillingType)) {
                    if (grillIngredientMatches(r, stack)) return true;
                }
            }
            if (KaleidoscopeGrillingCompat.isLoaded()) {
                for (KaleidoscopeGrillingCompat.GrillingPair pair : KaleidoscopeGrillingCompat.getGrillingPairs()) {
                    if (ItemStack.isSameItem(pair.input(), stack)) return true;
                }
            }
            return false;
        }
        return false;
    }

    private static boolean grillIngredientMatches(Recipe<?> recipe, ItemStack stack) {
        try {
            java.lang.reflect.Field field = recipe.getClass().getField("ingredient");
            Ingredient ing = (Ingredient) field.get(recipe);
            return ing.test(stack);
        } catch (Exception ignored) {
        }
        List<Ingredient> ings = recipe.getIngredients();
        return !ings.isEmpty() && ings.get(0).test(stack);
    }

    private static Set<String> expectedAutoProducts(BlockEntity be, List<String> selected) {
        Set<String> out = new HashSet<>();
        Level level = be.getLevel();
        MekCkFactoryType family = portedFamily(be);
        if (family == MekCkFactoryType.GRINDING) {
            for (Recipe<?> r : cn.ism.mekck.util.RecipeCache.all(level, "kaleidoscope_cookery", "millstone")) {
                for (Ingredient ing : r.getIngredients()) {
                    if (ing == null || ing.isEmpty()) continue;
                    for (String sel : selected) {
                        if (ingredientContainsId(ing, sel)) {
                            addGrindingProducts(r, out);
                            break;
                        }
                    }
                }
            }
        } else if (family == MekCkFactoryType.CUTTING) {
            for (CuttingBoardRecipe r : (java.util.List<CuttingBoardRecipe>) (java.util.List<?>) cn.ism.mekck.util.RecipeCache.all(level, ModRecipeTypes.CUTTING.get())) {
                for (Ingredient ing : r.getIngredients()) {
                    if (ing.isEmpty()) continue;
                    for (String sel : selected) {
                        if (ingredientContainsId(ing, sel)) {
                            for (ItemStack res : r.getResults()) {
                                String id = registryId(res);
                                if (id != null) out.add(id);
                            }
                        }
                    }
                }
            }
        } else if (be instanceof GrillFactoryBlockEntity) {
            RecipeType<?> grillingType = cn.ism.mekck.util.RecipeCache.type(new ResourceLocation("barbequesdelight", "grilling"));
            if (grillingType != null) {
                for (Recipe<?> r : cn.ism.mekck.util.RecipeCache.all(level, grillingType)) {
                    Ingredient ing = grillIngredient(r);
                    if (ing == null || ing.isEmpty()) continue;
                    for (String sel : selected) {
                        if (ingredientContainsId(ing, sel)) {
                            ItemStack res = r.getResultItem(level.registryAccess());
                            String id = registryId(res);
                            if (id != null) out.add(id);
                        }
                    }
                }
            }
            if (KaleidoscopeGrillingCompat.isLoaded()) {
                for (KaleidoscopeGrillingCompat.GrillingPair pair : KaleidoscopeGrillingCompat.getGrillingPairs()) {
                    String inputId = registryId(pair.input());
                    if (inputId != null && selected.contains(inputId)) {
                        String outputId = registryId(pair.output());
                        if (outputId != null) out.add(outputId);
                    }
                }
            }
        }
        return out;
    }

    private static Ingredient grillIngredient(Recipe<?> recipe) {
        try {
            java.lang.reflect.Field field = recipe.getClass().getField("ingredient");
            return (Ingredient) field.get(recipe);
        } catch (Exception ignored) {
        }
        List<Ingredient> ings = recipe.getIngredients();
        return ings.isEmpty() ? null : ings.get(0);
    }

    private static boolean ingredientContainsId(Ingredient ing, String itemId) {
        for (ItemStack s : ing.getItems()) {
            if (itemId.equals(registryId(s))) return true;
        }
        return false;
    }

    private static String registryId(ItemStack stack) {
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
        return id == null ? null : id.toString();
    }

    /**
     * 区间内某物品的总量（<b>long</b>：81 槽 × 21 亿会溢出 int，进而误判"还不够"而反复拉料）。
     *
     * <p>这里按「一段连续窗口」求和，而不是「一台机器的第一个配料口」——
     * 切菜工厂声明 {@code meGroupParallelItemInputs() == true}，N 个并行槽对外是
     * 1 个端口，存量自然也要按组求和。</p>
     */
    private static long countInRange(MekPortWindow window, String itemId, int start, int end) {
        long count = 0L;
        for (int i = start; i < end; i++) {
            ItemStack s = window.getStack(i);
            if (!s.isEmpty() && itemId.equals(registryId(s))) count += s.getCount();
        }
        return count;
    }

    private static void insertIntoRange(BlockEntity be, MekPortWindow window, ItemStack stack,
                                        int start, int end) {
        for (int i = start; i < end && !stack.isEmpty(); i++) {
            stack = window.insertItem(i, stack);
        }
        if (!stack.isEmpty() && be.getLevel() != null && !be.getLevel().isClientSide) {
            // 大堆叠感知：AE2 拉料余料可能是上亿件，原版分堆会炸实体
            cn.ism.mekck.util.BigStackDrops.dropAbove(be.getLevel(), be.getBlockPos(), stack);
        }
    }

    private static AEItemKey findItemInStorage(MEStorage storage, String itemId) {
        for (var e : storage.getAvailableStacks()) {
            if (e.getLongValue() <= 0 || !(e.getKey() instanceof AEItemKey ik)) continue;
            if (itemId.equals(registryId(ik.toStack()))) return ik;
        }
        return null;
    }

    // ==================================================================
    //  每台机器的网格宿主：多节点 + 能力（1 主节点 + 7 内部节点 = 8 频道）
    // ==================================================================

    private static final class FactoryGridHost implements IInWorldGridNodeHost, IActionHost {
        private final BlockEntity owner;
        private final CraftingProvider provider = new CraftingProvider();
        private final LazyOptional<IInWorldGridNodeHost> self = LazyOptional.of(() -> this);
        private IManagedGridNode mainNode;
        private final List<IManagedGridNode> extraNodes = new ArrayList<>();
        private long nextRefreshTick = -1;
        private long lastChannelLog = -1;
        private long powerReaddTick = 0;
        private AeJob job;
        private final Ae2PowerStorage powerStorage = new Ae2PowerStorage();
        private CompoundTag pendingTag;
        /** 上一次联网状态（沿边检测：false→true 时允许尝试授予）。 */
        private boolean wasActive;
        /** 本机内存授予尝试标记：联网稳定后不重复调用；断网重连或归属晚到时允许重试。 */
        private boolean grantAttempted;

        FactoryGridHost(BlockEntity owner) {
            this.owner = owner;
        }

        LazyOptional<IInWorldGridNodeHost> self() {
            return self;
        }

        private final IGridNodeListener<BlockEntity> listener = new IGridNodeListener<>() {
            @Override
            public void onSaveChanges(BlockEntity nodeOwner, IGridNode node) {
                nodeOwner.setChanged();
            }

            @Override
            public void onStateChanged(BlockEntity nodeOwner, IGridNode node, State state) {
                refreshPatterns();
                if (state == State.GRID_BOOT || state == State.CHANNEL || state == State.POWER) {
                    logChannelStatus();
                }
            }
        };

        // ----- IInWorldGridNodeHost / IActionHost -----

        @Override
        public IGridNode getGridNode(Direction direction) {
            return mainNode != null ? mainNode.getNode() : null;
        }

        @Override
        public AECableType getCableConnectionType(Direction direction) {
            return AECableType.COVERED;
        }

        @Override
        public IGridNode getActionableNode() {
            return mainNode != null ? mainNode.getNode() : null;
        }

        // ----- 生命周期 -----

        void init() {
            if (owner.isRemoved() || owner.getLevel() == null || owner.getLevel().isClientSide) return;
            if (mainNode != null) return;
            Level level = owner.getLevel();
            BlockPos pos = owner.getBlockPos();
            ItemStack visual = new ItemStack(owner.getBlockState().getBlock());

            mainNode = GridHelper.createManagedNode(owner, listener)
                    .setFlags(GridFlags.REQUIRE_CHANNEL)
                    .setInWorldNode(true)
                    .setExposedOnSides(EnumSet.allOf(Direction.class))
                    .setIdlePowerUsage(0.5)
                    .setTagName(TAG_MAIN)
                    .setVisualRepresentation(visual)
                    .addService(ICraftingProvider.class, provider)
                    .addService(IAEPowerStorage.class, powerStorage);
            // 内部节点：不暴露，每个 REQUIRE_CHANNEL 节点占 1 频道。
            // 1 主节点 + 7 内部节点（全部直连主节点）= 共 8 频道。
            extraNodes.clear();
            for (int i = 1; i < CHANNELS; i++) {
                IManagedGridNode node = GridHelper.createManagedNode(owner, listener)
                        .setFlags(GridFlags.REQUIRE_CHANNEL)
                        .setInWorldNode(false)
                        .setExposedOnSides(Set.of())
                        .setIdlePowerUsage(0.0)
                        .setTagName(TAG_EXTRA + i);
                extraNodes.add(node);
            }

            // 关键：NBT 必须在 create() 之前交给节点（ManagedGridNode 存入 InitData，
            // createNode 时才按 tagName 应用）。create() 之后再 loadFromNBT 会让已入网格且
            // ready 的节点走 GridNode.loadFromNBT 的 "Resetting grid node ... to reload NBT"
            // 分支——直接 destroy() 掉该节点及其全部连接，导致机器接不上网络（历史频道问题根因之一）。
            if (pendingTag != null) {
                mainNode.loadFromNBT(pendingTag);
                for (IManagedGridNode node : extraNodes) {
                    node.loadFromNBT(pendingTag);
                }
                pendingTag = null;
            }

            mainNode.create(level, pos);
            for (IManagedGridNode node : extraNodes) {
                node.create(level, pos);
            }
            for (IManagedGridNode node : extraNodes) {
                GridHelper.createConnection(mainNode.getNode(), node.getNode());
            }
            refreshPatterns();
            logChannelStatus();
        }

        /** 打印当前机器的网格/频道占用（init 与网格状态变化时调用，5 秒节流）。 */
        private void logChannelStatus() {
            Level level = owner.getLevel();
            if (level == null) return;
            long time = level.getGameTime();
            if (time < lastChannelLog) lastChannelLog = time;
            if (time - lastChannelLog < 100) return;
            lastChannelLog = time;
            IGridNode main = mainNode != null ? mainNode.getNode() : null;
            if (main == null) return;
            StringBuilder sb = new StringBuilder();
            sb.append("host ").append(owner.getBlockPos());
            sb.append(" grid=").append(main.getGrid() != null ? "connected" : "none");
            sb.append(" main[used=").append(main.getUsedChannels())
              .append("/max=").append(main.getMaxChannels()).append(']');
            for (int i = 0; i < extraNodes.size(); i++) {
                IGridNode n = extraNodes.get(i).getNode();
                sb.append(" extra").append(i + 1).append('=')
                  .append(n != null && n.getGrid() != null ? n.getUsedChannels() : -1);
            }
            LOGGER.info(sb);
        }

        void destroy() {
            if (mainNode != null) {
                mainNode.destroy();
                mainNode = null;
            }
            for (IManagedGridNode node : extraNodes) {
                node.destroy();
            }
            extraNodes.clear();
            // 显式清理 HOSTS 条目：WeakHashMap 的 value 强引用 key（owner），
            // 仅靠弱键无法回收，必须主动移除避免泄漏/幽灵节点。
            HOSTS.remove(owner);
        }

        void saveToNBT(CompoundTag tag) {
            if (mainNode != null) mainNode.saveToNBT(tag);
            for (IManagedGridNode node : extraNodes) {
                node.saveToNBT(tag);
            }
            if (!selectedAutoItems.isEmpty()) {
                CompoundTag sel = new CompoundTag();
                int i = 0;
                for (String itemId : selectedAutoItems) {
                    sel.putString("s" + i++, itemId);
                }
                tag.put("MekCkAutoSel", sel);
            }
        }

        void loadFromNBT(CompoundTag tag) {
            // 只缓存整个 BE tag；init() 会在 create() 之前按 tagName 读入（标准 InitData 模式）。
            boolean hasAny = tag.contains(TAG_MAIN, Tag.TAG_COMPOUND);
            for (int i = 1; i < CHANNELS && !hasAny; i++) {
                hasAny = tag.contains(TAG_EXTRA + i, Tag.TAG_COMPOUND);
            }
            pendingTag = hasAny ? tag : null;
            selectedAutoItems.clear();
            if (tag.contains("MekCkAutoSel", Tag.TAG_COMPOUND)) {
                CompoundTag sel = tag.getCompound("MekCkAutoSel");
                for (int i = 0; i < 32; i++) {
                    String s = sel.getString("s" + i);
                    if (!s.isEmpty()) selectedAutoItems.add(s);
                }
            }
        }

        // ----- 通用简单机器：勾选持续自动补料 -----

        private final List<String> selectedAutoItems = new ArrayList<>();

        List<String> getSelectedAutoItems() {
            return selectedAutoItems;
        }

        void toggleAutoItem(String itemId) {
            if (!selectedAutoItems.remove(itemId)) {
                selectedAutoItems.add(itemId);
            }
            owner.setChanged();
        }

        void serverTick(Level level, BlockPos pos) {
            if (owner.isRemoved()) {
                destroy();
                return;
            }
            if (mainNode == null) {
                init();
                if (mainNode == null) return;
            }
            powerTick(level);
            long time = level.getGameTime();
            if (time >= nextRefreshTick) {
                nextRefreshTick = time + REFRESH_INTERVAL;
                refreshPatterns();
            }
            if (job != null) {
                processJob(level);
            }
            // 通用简单机器：勾选持续自动补料
            if (owner instanceof INetworkPullable) {
                // 频率交给 LagMonitor：正常档每 tick，降档时 4/10 tick 且按方块坐标错开相位
                if (cn.ism.mekck.util.LagMonitor.shouldRunIO(time, owner.getBlockPos())) {
                    autoProcessTickGeneric(owner);
                }
            }
            // 网络厨师学徒：服务端真实联网判定（REQUIRE_CHANNEL 节点激活 + 网格存在）。
            // 状态机：联网沿边（false→true）或“归属晚到但未尝试过”时，且存在有效玩家归属才尝试授予；
            // 无归属不授予、也不消耗机会；断网重连允许重试；联网稳定后不每 tick 重复调用
            // （玩家 advancement 自身去重，本机不保存“已完成”标记）。
            if (!level.isClientSide) {
                boolean connected = mainNode != null && mainNode.isActive() && mainNode.getGrid() != null;
                if (connected) {
                    boolean edge = !wasActive;
                    wasActive = true;
                    UUID uuid = cn.ism.mekck.advancement.PlacerPersist.get(owner);
                    if (uuid != null && (edge || !grantAttempted)) {
                        grantAttempted = true;
                        onNetworkConnected(level, uuid);
                    }
                } else {
                    wasActive = false;
                    grantAttempted = false; // 断网重连后允许重新尝试
                }
            }
        }

        /** 首次真正接入网络：授予放置者“网络厨师学徒”；放置者离线则持久化待授予，登录补授。 */
        private void onNetworkConnected(Level level, UUID placerUuid) {
            if (!(level instanceof ServerLevel serverLevel) || serverLevel.getServer() == null) return;
            // 授予/离线待授予逻辑在公共层（不依赖 AE2）
            cn.ism.mekck.advancement.NetworkChefProgress.handleNetworkConnected(serverLevel, placerUuid);
        }

        // ----- AE2 供电：让 ME 线缆像给 AE 机器供电一样为本模组联网机器充能 -----

        private IEnergyStorage getMachineEnergy() {
            try {
                return owner.getCapability(ForgeCapabilities.ENERGY, null).resolve().orElse(null);
            } catch (Throwable t) {
                return null;
            }
        }

        /**
         * 每 tick：主动从 ME 网络抽取电量给机器（兜底，覆盖“网络只有存量、没有注入”的情况），
         * 并周期性重发电力状态事件，让 EnergyService 把耗尽的接收者重新登记回 requester 集合。
         */
        private void powerTick(Level level) {
            IGrid grid = mainNode != null ? mainNode.getGrid() : null;
            if (grid == null) return;
            if (level.isClientSide) return;
            IEnergyStorage e = getMachineEnergy();
            if (e != null) {
                int free = e.getMaxEnergyStored() - e.getEnergyStored();
                if (free > 0) {
                    // 每 tick 抽电量上限：1000 FE，避免瞬间抽干网络储能
                    int maxAccept = e.receiveEnergy(Math.min(free, 1000), true);
                    if (maxAccept > 0) {
                        double wantAe = PowerUnits.FE.convertTo(PowerUnits.AE, maxAccept);
                        double gotAe = grid.getEnergyService().extractAEPower(
                                wantAe, Actionable.MODULATE, PowerMultiplier.CONFIG);
                        if (gotAe > 0) {
                            double fe = PowerUnits.AE.convertTo(PowerUnits.FE, gotAe);
                            if (fe >= 1) {
                                int accepted = e.receiveEnergy((int) fe, false);
                                if (accepted > 0) owner.setChanged();
                            }
                        }
                    }
                }
            }
            // EnergyService 在注入时会把“已满/接收不完整”的 requester 移除，
            // 周期性重发事件把它们重新登记，保证网络发一次电后还能继续充电。
            long time = level.getGameTime();
            if (powerReaddTick == 0 || time >= powerReaddTick) {
                powerReaddTick = time + 20;
                try {
                    grid.postEvent(new GridPowerStorageStateChanged(
                            powerStorage, GridPowerStorageStateChanged.PowerEventType.RECEIVE_POWER));
                } catch (Throwable ignored) {
                }
            }
        }

        /**
         * AE2 电能注入点：把 ME 网络能量按 1 AE = 1000 FE（AE2 配置 powerRatioForgeEnergy）注入机器能量槽。
         * 只进不出（WRITE），机器不会反过来给网络供电。
         */
        private final class Ae2PowerStorage implements IAEPowerStorage {
            @Override
            public double injectAEPower(double amt, Actionable mode) {
                IEnergyStorage e = getMachineEnergy();
                if (e == null || amt <= 0) return amt;
                double fe = PowerUnits.AE.convertTo(PowerUnits.FE, amt);
                if (fe < 1) return amt;
                int free = e.getMaxEnergyStored() - e.getEnergyStored();
                if (free <= 0) return amt;
                int accepted = e.receiveEnergy((int) Math.min(free, fe), mode == Actionable.SIMULATE);
                if (accepted > 0 && mode == Actionable.MODULATE) owner.setChanged();
                return amt - PowerUnits.FE.convertTo(PowerUnits.AE, accepted);
            }

            @Override
            public double getAEMaxPower() {
                IEnergyStorage e = getMachineEnergy();
                return e == null ? 0 : PowerUnits.FE.convertTo(PowerUnits.AE, e.getMaxEnergyStored());
            }

            @Override
            public double getAECurrentPower() {
                IEnergyStorage e = getMachineEnergy();
                return e == null ? 0 : PowerUnits.FE.convertTo(PowerUnits.AE, e.getEnergyStored());
            }

            @Override
            public boolean isAEPublicPowerStorage() {
                return true;
            }

            @Override
            public AccessRestriction getPowerFlow() {
                return AccessRestriction.WRITE;
            }

            @Override
            public double extractAEPower(double amt, Actionable mode, PowerMultiplier usePowerMultiplier) {
                return 0; // 只进不出：机器不为网络供电
            }
        }

        // ----- 动态配方 -----

        void refreshPatterns() {
            IGrid grid = mainNode != null ? mainNode.getGrid() : null;
            if (grid == null) {
                provider.setEntries(List.of());
                return;
            }
            MEStorage storage = grid.getStorageService().getInventory();
            if (storage == null) {
                provider.setEntries(List.of());
                return;
            }
            KeyCounter available = storage.getAvailableStacks();
            Map<AEKey, Long> avail = new HashMap<>();
            for (var entry : available) {
                if (entry.getLongValue() > 0) {
                    avail.put(entry.getKey(), entry.getLongValue());
                }
            }
            Level level = owner.getLevel();
            if (level == null || level.isClientSide) {
                provider.setEntries(List.of());
                return;
            }
            // ME 下单总开关：关闭后本机配方不再注册到终端
            if (owner instanceof cn.ism.mekck.ae2.INetworkPullable pullable && !pullable.isMeOrderEnabled()) {
                provider.setEntries(List.of());
                return;
            }
            List<PatternEntry> entries;
            if (owner instanceof CookingFactoryBlockEntity cook) {
                entries = buildCookingPatterns(cook, avail);
            } else if (owner instanceof SkeweringFactoryBlockEntity) {
                entries = buildSkeweringPatterns(level, avail);
            } else if (owner instanceof cn.ism.mekck.blockentity.SimpleMachineBlockEntity sm) {
                entries = buildSimpleMachinePatterns(sm, avail);
            } else if (owner instanceof cn.ism.mekck.blockentity.UniversalCuttingMachineBlockEntity) {
                entries = buildCuttingPatterns(level, avail);
            } else if (owner instanceof cn.ism.mekck.blockentity.SkeweringMachineBlockEntity) {
                entries = buildSkeweringPatterns(level, avail);
            } else if (owner instanceof cn.ism.mekck.blockentity.GrillBlockEntity) {
                entries = buildGrillingPatterns(level, avail);
            } else if (owner instanceof cn.ism.mekck.blockentity.SmartCookingPotBlockEntity) {
                entries = buildCookingPotPatterns(level, avail);
            } else if (owner instanceof cn.ism.mekck.blockentity.ElectricGrindingMachineBlockEntity) {
                entries = buildGrindingPatterns(level, avail);
            } else if (owner instanceof cn.ism.mekck.blockentity.NutRoasterBlockEntity) {
                entries = buildSimpleSingleOutputPatterns(level, new ResourceLocation("mekck", "nut_roasting"), avail);
            } else if (owner instanceof cn.ism.mekck.blockentity.IceMakerBlockEntity) {
                entries = buildSimpleSingleOutputPatterns(level, new ResourceLocation("mekck", "ice_make"), avail);
            } else if (owner instanceof cn.ism.mekck.blockentity.ChocolateCannonBlockEntity) {
                entries = buildSimpleSingleOutputPatterns(level, new ResourceLocation("mekck", "ferrero"), avail);
            } else if (owner instanceof MekCkMachineTile && portedFamily(owner) == MekCkFactoryType.PLANTING_CUTTING) {
                // 端口声明型工厂（阶段 3）：plantcut 配方，构建器与旧的种植切配工厂同款
                entries = buildSimpleSingleOutputPatterns(level, new ResourceLocation("mekck", "plantcut"), avail);
            } else if (owner instanceof MekCkMachineTile && portedFamily(owner) == MekCkFactoryType.CUTTING) {
                // 端口声明型工厂（切菜，阶段 2 Task 4.6 起）：FD cutting 配方，
                // 与通用切菜机同一批配方，构建器可复用
                entries = buildCuttingPatterns(level, avail);
            } else if (owner instanceof MekCkMachineTile && portedFamily(owner) == MekCkFactoryType.GRINDING) {
                // 研磨工厂（阶段 3 Task 1 起）：与电力研磨机同一批配方，构建器可复用。
                // 分支不能并进上面那条：那条写死了切菜配方，不拆开的话研磨机器会在 ME 终端里
                // 展示切菜配方、并按切菜结果给物品。
                entries = buildGrindingPatterns(level, avail);
            } else if (owner instanceof GrillFactoryBlockEntity) {
                // 烧烤工厂：BBQ grilling 配方（终端下单后由 startOrder 设订单，机器按订单加工）
                entries = buildGrillingPatterns(level, avail);
            } else if (owner instanceof cn.ism.mekck.blockentity.IceFactoryBlockEntity) {
                // 制冰工厂：mekck:ice_make（机器无需订单，材料推入即自动加工）
                entries = buildSimpleSingleOutputPatterns(level, new ResourceLocation("mekck", "ice_make"), avail);
            } else if (owner instanceof cn.ism.mekck.blockentity.PlantingCuttingStationBlockEntity) {
                // 种植切配站：mekck:plantcut（同上，无需订单）
                entries = buildSimpleSingleOutputPatterns(level, new ResourceLocation("mekck", "plantcut"), avail);
            } else if (owner instanceof cn.ism.mekck.blockentity.CentralKitchenBlockEntity kitchen) {
                entries = buildKitchenPatterns(level, kitchen, avail);
            } else if (owner instanceof cn.ism.mekck.blockentity.SandwichAssemblerBlockEntity assembler) {
                entries = buildSandwichPatterns(level, assembler, avail);
            } else {
                entries = List.of();
            }
            provider.setEntries(entries);
        }

        // ----- AE2 任务执行 -----

        private void processJob(Level level) {
            if (job == null) return;
            IGrid grid = mainNode != null ? mainNode.getGrid() : null;
            if (grid == null) return;
            MEStorage storage = grid.getStorageService().getInventory();
            if (storage == null) return;
            if (!orderDone()) return;

            IActionSource src = IActionSource.ofMachine(this);
            for (GenericStack out : job.entry.outputs) {
                if (job.remainingOutput <= 0) break;
                if (out.what() instanceof AEItemKey key) {
                    long want = Math.min(job.remainingOutput, out.amount());
                    long done = exportFromSlots(storage, key, want, src);
                    job.remainingOutput -= done;
                }
            }
            if (job.remainingOutput <= 0) {
                // 产物已全部回网，再把空瓶/空容器/穿串签子等返回槽物品一并回写网络
                exportReturnSlots(storage, src);
                job = null;
                nextRefreshTick = level.getGameTime() + REFRESH_INTERVAL;
                refreshPatterns();
            }
        }

        /**
         * 端口声明型机器的槽窗口；不是端口声明型（或端口为空）时返回 null。
         *
         * <p>{@link #getItems()} 对这类机器恒返 null（{@code CuttingFactoryTile} 没有
         * {@code ItemStackHandler}），所以 {@code pushPattern} 的投料与
         * {@code processJob} 的产物回写都必须先问这个方法，
         * 否则前者会把整批材料丢到机器脚下的地上、后者永远导出不到产物，
         * 于是 {@code job.remainingOutput} 减不下去、机器被 {@code isBusy()} 永久锁死。</p>
         */
        private MekPortWindow portWindow() {
            return owner instanceof IMekCkPorted ported ? MekPortWindow.ofPorted(ported) : null;
        }

        private long exportFromSlots(MEStorage storage, AEItemKey key, long want, IActionSource src) {
            MekPortWindow window = portWindow();
            if (window != null) {
                return exportFromWindow(storage, key, want, src, window, window.outputIndices());
            }
            ItemStackHandler items = getItems();
            if (items == null) return 0;
            long done = 0;
            for (int slot : getOutputSlots()) {
                if (want - done <= 0) break;
                ItemStack stack = items.getStackInSlot(slot);
                if (stack.isEmpty() || !AEItemKey.matches(key, stack)) continue;
                long take = Math.min(want - done, stack.getCount());
                long accepted = storage.insert(key, take, Actionable.MODULATE, src);
                if (accepted > 0) {
                    stack.shrink((int) accepted);
                    if (stack.isEmpty()) items.setStackInSlot(slot, ItemStack.EMPTY);
                    done += accepted;
                }
            }
            if (done > 0) owner.setChanged();
            return done;
        }

        private long exportFromWindow(MEStorage storage, AEItemKey key, long want, IActionSource src,
                                      MekPortWindow window, int[] slots) {
            long done = 0;
            for (int slot : slots) {
                if (want - done <= 0) break;
                ItemStack stack = window.getStack(slot);
                if (stack.isEmpty() || !AEItemKey.matches(key, stack)) continue;
                long take = Math.min(want - done, stack.getCount());
                long accepted = storage.insert(key, take, Actionable.MODULATE, src);
                if (accepted > 0) {
                    // copy → shrink → setStack：Mek 槽的 setStack 会 copy 一份并触发
                    // onContentsChanged（实测 BasicInventorySlot.setStack 偏移 40-45 / 79-83），
                    // 直接改 getStack() 返回的活引用会绕过它，区块不会标记为脏。
                    ItemStack rest = stack.copy();
                    rest.shrink((int) accepted);
                    window.setStack(slot, rest);
                    done += accepted;
                }
            }
            if (done > 0) owner.setChanged();
            return done;
        }

        private void exportReturnSlots(MEStorage storage, IActionSource src) {
            ItemStackHandler items = getItems();
            if (items == null) return;
            for (int slot : getReturnSlots()) {
                ItemStack stack = items.getStackInSlot(slot);
                if (stack.isEmpty()) continue;
                AEItemKey key = AEItemKey.of(stack);
                long accepted = storage.insert(key, stack.getCount(), Actionable.MODULATE, src);
                if (accepted > 0) {
                    stack.shrink((int) accepted);
                    if (stack.isEmpty()) items.setStackInSlot(slot, ItemStack.EMPTY);
                }
            }
        }

        private boolean orderDone() {
            // 统一判定：机器把订单做完（recipeId 清空、quantity 归零）才算完成。
            // 三明治组装机/中央厨房没有这套订单字段，改为看输出槽是否已产出。
            if (owner instanceof cn.ism.mekck.blockentity.SandwichAssemblerBlockEntity asm) {
                return !asm.items.getStackInSlot(
                        cn.ism.mekck.blockentity.SandwichAssemblerBlockEntity.OUTPUT_SLOT).isEmpty();
            }
            if (owner instanceof cn.ism.mekck.blockentity.CentralKitchenBlockEntity kitchen) {
                return kitchen.orderCount() == 0;
            }
            cn.ism.mekck.ae2.OrderState state = orderStateOf(owner);
            return state == null || state.done();
        }

        /** 反射无关的统一订单状态读取（各机器都提供同名 getter）。 */
        private cn.ism.mekck.ae2.OrderState orderStateOf(Object machine) {
            try {
                Object rid = cn.ism.mekck.util.Reflect.call(machine, "getOrderRecipeId");
                Object qty = cn.ism.mekck.util.Reflect.call(machine, "getOrderQuantity");
                int remaining = qty instanceof Integer i ? i : 0;
                return new cn.ism.mekck.ae2.OrderState(rid != null, remaining);
            } catch (Throwable ignored) {
                return null;
            }
        }

        private boolean ownerBusy() {
            if (owner instanceof cn.ism.mekck.blockentity.SandwichAssemblerBlockEntity) {
                return !orderDone();
            }
            if (owner instanceof cn.ism.mekck.blockentity.CentralKitchenBlockEntity kitchen) {
                return kitchen.orderCount() > 0;
            }
            cn.ism.mekck.ae2.OrderState state = orderStateOf(owner);
            return state != null && !state.done();
        }

        private void startOrder(PatternEntry entry) {
            if (owner instanceof CookingFactoryBlockEntity c) {
                c.setOrder(entry.recipeId, 1);
            } else if (owner instanceof SkeweringFactoryBlockEntity s) {
                s.setOrder(entry.recipeId, 1);
            } else if (owner instanceof cn.ism.mekck.blockentity.SimpleMachineBlockEntity sm) {
                sm.setOrder(entry.recipeId, 1);
            } else if (owner instanceof cn.ism.mekck.blockentity.UniversalCuttingMachineBlockEntity cut) {
                cut.setOrder(entry.recipeId, 1);
            } else if (owner instanceof cn.ism.mekck.blockentity.SkeweringMachineBlockEntity sk) {
                sk.setOrder(entry.recipeId, 1);
            } else if (owner instanceof cn.ism.mekck.blockentity.GrillBlockEntity g) {
                g.setOrder(entry.recipeId, 1);
            } else if (owner instanceof cn.ism.mekck.blockentity.SmartCookingPotBlockEntity pot) {
                pot.setOrder(entry.recipeId, 1);
            } else if (owner instanceof cn.ism.mekck.blockentity.ElectricGrindingMachineBlockEntity grind) {
                grind.setOrder(entry.recipeId, 1);
            } else if (owner instanceof cn.ism.mekck.blockentity.NutRoasterBlockEntity roaster) {
                roaster.setOrder(entry.recipeId, 1);
            } else if (owner instanceof cn.ism.mekck.blockentity.IceMakerBlockEntity ice) {
                ice.setOrder(entry.recipeId, 1);
            } else if (owner instanceof cn.ism.mekck.blockentity.ChocolateCannonBlockEntity cannon) {
                cannon.setOrder(entry.recipeId, 1);
            } else if (owner instanceof cn.ism.mekck.blockentity.GrillFactoryBlockEntity gfac) {
                // 烧烤工厂的加工有订单门禁（无订单不自动加工），必须显式设订单
                gfac.setOrder(entry.recipeId, 1, null); // 第三参为调味（终端下单不带调味）
            } else if (owner instanceof cn.ism.mekck.blockentity.CentralKitchenBlockEntity kitchen) {
                // 中央厨房走订单系统（含合成链求解）
                kitchen.placeOrder(owner.getLevel(), entry.recipeId, 1);
            }
            // 三明治组装机：无需下单，材料推入后即按样品/有序格自动组装
        }

        private ItemStackHandler getItems() {
            if (owner instanceof CookingFactoryBlockEntity c) return c.items;
            if (owner instanceof SkeweringFactoryBlockEntity s) return s.items;
            if (owner instanceof cn.ism.mekck.blockentity.SimpleMachineBlockEntity sm) return sm.getItems();
            if (owner instanceof cn.ism.mekck.blockentity.UniversalCuttingMachineBlockEntity cut) return cut.getItems();
            if (owner instanceof cn.ism.mekck.blockentity.SkeweringMachineBlockEntity sk) return sk.getItems();
            if (owner instanceof cn.ism.mekck.blockentity.GrillBlockEntity g) return g.getItems();
            if (owner instanceof cn.ism.mekck.blockentity.SmartCookingPotBlockEntity pot) return pot.getItems();
            if (owner instanceof cn.ism.mekck.blockentity.ElectricGrindingMachineBlockEntity grind) return grind.getItems();
            if (owner instanceof cn.ism.mekck.blockentity.NutRoasterBlockEntity roaster) return roaster.getItems();
            if (owner instanceof cn.ism.mekck.blockentity.IceMakerBlockEntity ice) return ice.getItems();
            if (owner instanceof cn.ism.mekck.blockentity.ChocolateCannonBlockEntity cannon) return cannon.getItems();
            if (owner instanceof cn.ism.mekck.blockentity.CentralKitchenBlockEntity kitchen) return kitchen.items;
            if (owner instanceof cn.ism.mekck.blockentity.SandwichAssemblerBlockEntity asm) return asm.items;
            return null;
        }

        private int[] getOutputSlots() {
            if (owner instanceof CookingFactoryBlockEntity) {
                int[] slots = new int[CookingFactoryBlockEntity.OUTPUT_SLOTS];
                for (int i = 0; i < CookingFactoryBlockEntity.OUTPUT_SLOTS; i++) {
                    slots[i] = CookingFactoryBlockEntity.OUTPUT_SLOT_START + i;
                }
                return slots;
            }
            if (owner instanceof SkeweringFactoryBlockEntity s) {
                return new int[]{s.getOutputSlot()};
            }
            if (owner instanceof cn.ism.mekck.blockentity.SimpleMachineBlockEntity sm) {
                return new int[]{sm.OUTPUT_SLOT};
            }
            if (owner instanceof cn.ism.mekck.blockentity.UniversalCuttingMachineBlockEntity cut) {
                return new int[]{cn.ism.mekck.blockentity.UniversalCuttingMachineBlockEntity.OUTPUT_SLOT};
            }
            if (owner instanceof cn.ism.mekck.blockentity.SkeweringMachineBlockEntity) {
                return new int[]{cn.ism.mekck.blockentity.SkeweringMachineBlockEntity.OUTPUT_SLOT};
            }
            if (owner instanceof cn.ism.mekck.blockentity.GrillBlockEntity g) {
                return new int[]{cn.ism.mekck.blockentity.GrillBlockEntity.OUTPUT_SLOT};
            }
            if (owner instanceof cn.ism.mekck.blockentity.SmartCookingPotBlockEntity pot) {
                return new int[]{cn.ism.mekck.blockentity.SmartCookingPotBlockEntity.OUTPUT_SLOT};
            }
            if (owner instanceof cn.ism.mekck.blockentity.ElectricGrindingMachineBlockEntity) {
                return new int[]{cn.ism.mekck.blockentity.ElectricGrindingMachineBlockEntity.OUTPUT_SLOT};
            }
            if (owner instanceof cn.ism.mekck.blockentity.NutRoasterBlockEntity) {
                return new int[]{cn.ism.mekck.blockentity.NutRoasterBlockEntity.OUTPUT_SLOT};
            }
            if (owner instanceof cn.ism.mekck.blockentity.IceMakerBlockEntity) {
                return new int[]{cn.ism.mekck.blockentity.IceMakerBlockEntity.OUTPUT_SLOT};
            }
            if (owner instanceof cn.ism.mekck.blockentity.ChocolateCannonBlockEntity) {
                return new int[]{cn.ism.mekck.blockentity.ChocolateCannonBlockEntity.OUTPUT_SLOT};
            }
            if (owner instanceof cn.ism.mekck.blockentity.CentralKitchenBlockEntity) {
                int[] slots = new int[cn.ism.mekck.blockentity.CentralKitchenBlockEntity.OUTPUT_SLOTS];
                for (int i = 0; i < slots.length; i++) {
                    slots[i] = cn.ism.mekck.blockentity.CentralKitchenBlockEntity.OUTPUT_START + i;
                }
                return slots;
            }
            if (owner instanceof cn.ism.mekck.blockentity.SandwichAssemblerBlockEntity) {
                return new int[]{cn.ism.mekck.blockentity.SandwichAssemblerBlockEntity.OUTPUT_SLOT};
            }
            return new int[0];
        }

        private int[] getReturnSlots() {
            if (owner instanceof CookingFactoryBlockEntity) {
                int[] slots = new int[CookingFactoryBlockEntity.RETURN_SLOTS];
                for (int i = 0; i < CookingFactoryBlockEntity.RETURN_SLOTS; i++) {
                    slots[i] = CookingFactoryBlockEntity.RETURN_SLOT_START + i;
                }
                return slots;
            }
            if (owner instanceof SkeweringFactoryBlockEntity s) {
                return new int[]{s.getReturnSlot()};
            }
            return new int[0];
        }

        private int getStorageStart() {
            // 烹饪工厂：水瓶/奶瓶等必须进存储槽（6+），才能被 convertStoredFluidContainers 转为流体；
            // 穿串工厂：优先输入槽 0-2，使签子/工具可被 completeRecipe 回收到返回槽并回写网络。
            if (owner instanceof CookingFactoryBlockEntity) return CookingFactoryBlockEntity.INPUT_SLOTS;
            if (owner instanceof SkeweringFactoryBlockEntity) return 0;
            if (owner instanceof cn.ism.mekck.blockentity.SimpleMachineBlockEntity) return 0;
            if (owner instanceof cn.ism.mekck.blockentity.CentralKitchenBlockEntity) {
                return cn.ism.mekck.blockentity.CentralKitchenBlockEntity.STORAGE_START;
            }
            if (owner instanceof cn.ism.mekck.blockentity.SandwichAssemblerBlockEntity) {
                return cn.ism.mekck.blockentity.SandwichAssemblerBlockEntity.MATERIAL_START;
            }
            // 通用回退：所有机器都实现了 INetworkPullable.getInputSlotRange()
            if (owner instanceof INetworkPullable pullable) {
                int[] range = pullable.getInputSlotRange();
                if (range != null && range.length >= 2) return range[0];
            }
            return 0;
        }

        private int getStorageEnd() {
            if (owner instanceof CookingFactoryBlockEntity) return CookingFactoryBlockEntity.OUTPUT_SLOT_START;
            if (owner instanceof SkeweringFactoryBlockEntity s) return s.getStorageSlotStart() + s.getStorageSlots();
            if (owner instanceof cn.ism.mekck.blockentity.SimpleMachineBlockEntity sm) return sm.INPUT_COUNT;
            if (owner instanceof cn.ism.mekck.blockentity.CentralKitchenBlockEntity) {
                return cn.ism.mekck.blockentity.CentralKitchenBlockEntity.OUTPUT_START;
            }
            if (owner instanceof cn.ism.mekck.blockentity.SandwichAssemblerBlockEntity) {
                return cn.ism.mekck.blockentity.SandwichAssemblerBlockEntity.MATERIAL_START
                        + cn.ism.mekck.blockentity.SandwichAssemblerBlockEntity.MATERIAL_SLOTS;
            }
            // 通用回退：所有机器都实现了 INetworkPullable.getInputSlotRange()
            if (owner instanceof INetworkPullable pullable) {
                int[] range = pullable.getInputSlotRange();
                if (range != null && range.length >= 2) return range[1];
            }
            return 0;
        }

        private void insertIntoMachine(List<GenericStack> inputs) {
            MekPortWindow window = portWindow();
            if (window != null) {
                insertIntoPortWindow(inputs, window);
                return;
            }
            ItemStackHandler items = getItems();
            if (items == null) return;
            int start = getStorageStart();
            int end = getStorageEnd();
            // 批量插入：先问目标区间能装多少（一次扫描），再按量插入（一次调用内部按槽填充）。
            // 旧实现按"每种材料 × 每个目标槽"调用 insertItem（81 种 × 81 槽 = 6561 次/批），
            // 而且超出容量的部分会被丢到地上——本模组槽位上限 21 亿，81 种材料足以撑爆世界。
            cn.ism.mekck.api.IBulkItemHandler dst = cn.ism.mekck.util.IntHandlerBulkView.of(items);
            int rangeLen = Math.max(0, end - start);
            // A4：连续区间（getStorageStart/End）之外，联动机器还有不连续的扩展输入槽（搅拌机等的 10..13）。
            // 终端下单（pushPattern）与工厂面板都走本方法，此前只填区间 ⇒ >5 种材料多出来的会掉地上。
            // 与 insertIntoNetworkPullMachine 一致：区间装不下的余料再落入额外输入槽（默认空，对工厂无害）。
            int[] extraSlots = owner instanceof INetworkPullable pullable
                    ? pullable.getExtraInputSlots() : new int[0];
            for (GenericStack gs : inputs) {
                if (!(gs.what() instanceof AEItemKey ik)) continue;
                long amount = gs.amount();
                if (amount <= 0L) continue;
                ItemStack proto = ik.toStack(1);
                long space = dst.bulkSpace(proto, start, rangeLen);
                long moved = space <= 0L ? 0L : dst.bulkInsert(proto, Math.min(amount, space), start, rangeLen, false);
                long leftover = amount - moved;
                for (int i = 0; leftover > 0L && i < extraSlots.length; i++) {
                    int slot = extraSlots[i];
                    if (slot < 0 || slot >= items.getSlots()) continue;
                    int want = (int) Math.min(leftover, Integer.MAX_VALUE);
                    ItemStack rest = items.insertItem(slot, proto.copyWithCount(want), false);
                    leftover -= (want - rest.getCount());
                }
                if (leftover > 0L && owner.getLevel() != null && !owner.getLevel().isClientSide) {
                    // 连扩展槽也装不下才落到世界（原实现会把整批余料都丢出来）
                    cn.ism.mekck.util.BigStackDrops.dropAbove(owner.getLevel(), owner.getBlockPos(),
                            ik.toStack((int) Math.min(leftover, Integer.MAX_VALUE)));
                }
            }
            owner.setChanged();
        }

        /**
         * 端口声明型机器的投料。
         *
         * <p>目标窗口就是<b>整个输入组</b> {@code [0, n)}——这正是
         * {@code meGroupParallelItemInputs() == true} 在消费方的落点：N 个并行槽
         * 对 AE2 是 1 个端口，于是「这个端口能装多少」要问整组而不是第一格。
         * 逐种材料先问 {@link MekPortWindow#bulkSpace} 再按量插，
         * 与旧 {@code IntHandlerBulkView} 的两遍扫描同构，成本不随数量增长。</p>
         */
        private void insertIntoPortWindow(List<GenericStack> inputs, MekPortWindow window) {
            int count = window.inputCount();
            for (GenericStack gs : inputs) {
                if (!(gs.what() instanceof AEItemKey ik)) continue;
                long amount = gs.amount();
                if (amount <= 0L) continue;
                ItemStack proto = ik.toStack(1);
                long space = window.bulkSpace(proto, 0, count);
                long moved = space <= 0L ? 0L : window.bulkInsert(proto, Math.min(amount, space), 0, count);
                long leftover = amount - moved;
                if (leftover > 0L && owner.getLevel() != null && !owner.getLevel().isClientSide) {
                    cn.ism.mekck.util.BigStackDrops.dropAbove(owner.getLevel(), owner.getBlockPos(),
                            ik.toStack((int) Math.min(leftover, Integer.MAX_VALUE)));
                }
            }
            owner.setChanged();
        }

        /** 通用网络拉料：把抽取到的材料插入 INetworkPullable 机器的输入槽（**区间 ∪ 额外槽**）。 */
        private void insertIntoNetworkPullMachine(List<GenericStack> inputs) {
            if (!(owner instanceof INetworkPullable pullable)) return;
            ItemStackHandler items = pullable.getNetworkPullItems();
            if (items == null) return;
            int[] range = pullable.getInputSlotRange();
            int start = range.length > 0 ? range[0] : 0;
            int end = range.length > 1 ? range[1] : items.getSlots();
            // 批量插入：先问目标区间能装多少（一次扫描），再按量插入（一次调用内部按槽填充）。
            // 旧实现按"每种材料 × 每个目标槽"调用 insertItem（81 种 × 81 槽 = 6561 次/批），
            // 而且超出容量的部分会被丢到地上——本模组槽位上限 21 亿，81 种材料足以撑爆世界。
            cn.ism.mekck.api.IBulkItemHandler dst = cn.ism.mekck.util.IntHandlerBulkView.of(items);
            int rangeLen = Math.max(0, end - start);
            int[] extraSlots = pullable.getExtraInputSlots();
            for (GenericStack gs : inputs) {
                if (!(gs.what() instanceof AEItemKey ik)) continue;
                long amount = gs.amount();
                if (amount <= 0L) continue;
                ItemStack proto = ik.toStack(1);
                long space = dst.bulkSpace(proto, start, rangeLen);
                long moved = space <= 0L ? 0L : dst.bulkInsert(proto, Math.min(amount, space), start, rangeLen, false);
                long leftover = amount - moved;
                // 连续区间装不下的，再试**额外输入槽**（联动机器的扩展槽 10..13 —— 否则多出来的材料会掉地上）
                for (int i = 0; leftover > 0L && i < extraSlots.length; i++) {
                    int slot = extraSlots[i];
                    if (slot < 0 || slot >= items.getSlots()) continue;
                    int want = (int) Math.min(leftover, Integer.MAX_VALUE);
                    ItemStack rest = items.insertItem(slot, proto.copyWithCount(want), false);
                    leftover -= (want - rest.getCount());
                }
                if (leftover > 0L && owner.getLevel() != null && !owner.getLevel().isClientSide) {
                    // 只有真正装不下的部分才落到世界（原实现会把整批余料都丢出来）
                    cn.ism.mekck.util.BigStackDrops.dropAbove(owner.getLevel(), owner.getBlockPos(),
                            ik.toStack((int) Math.min(leftover, Integer.MAX_VALUE)));
                }
            }
            owner.setChanged();
        }

        // ----- 合成提供者 -----

        private final class CraftingProvider implements ICraftingProvider {
            private final List<PatternEntry> entries = new ArrayList<>();
            private final Map<AEItemKey, PatternEntry> byDefinition = new HashMap<>();
            private final Set<AEItemKey> currentDefs = new HashSet<>();

            void setEntries(List<PatternEntry> newEntries) {
                entries.clear();
                entries.addAll(newEntries);
                byDefinition.clear();
                Set<AEItemKey> defs = new HashSet<>();
                for (PatternEntry e : newEntries) {
                    byDefinition.put(e.details.getDefinition(), e);
                    defs.add(e.details.getDefinition());
                }
                if (!defs.equals(currentDefs)) {
                    currentDefs.clear();
                    currentDefs.addAll(defs);
                    if (mainNode != null) {
                        ICraftingProvider.requestUpdate(mainNode);
                    }
                }
            }

            @Override
            public List<IPatternDetails> getAvailablePatterns() {
                List<IPatternDetails> out = new ArrayList<>(entries.size());
                for (PatternEntry e : entries) {
                    out.add(e.details);
                }
                return out;
            }

            @Override
            public boolean pushPattern(IPatternDetails details, KeyCounter[] inputHolder) {
                if (job != null || ownerBusy()) return false;
                PatternEntry entry = byDefinition.get(details.getDefinition());
                if (entry == null || inputHolder == null) return false;
                // 注意：AE2 合成 CPU 在调用 pushPattern 前已经用 extractPatternInputs
                // 把材料从网络中抽到 CPU 库存并扣除，因此这里**不能再从网络抽取**，
                // 而是直接把 inputHolder（CPU 交付的实际 KeyCounter）物化成物品。
                List<GenericStack> inputs = new ArrayList<>();
                for (KeyCounter kc : inputHolder) {
                    for (var e : kc) {
                        long amount = e.getLongValue();
                        if (amount <= 0) continue;
                        inputs.add(new GenericStack(e.getKey(), amount));
                    }
                }
                if (inputs.isEmpty()) return false;
                insertIntoMachine(inputs);
                job = new AeJob(entry);
                startOrder(entry);
                return true;
            }

            @Override
            public boolean isBusy() {
                return job != null || ownerBusy();
            }
        }
    }

    // ==================================================================
    //  动态配方构建（按网络库存筛选材料齐全的配方）
    // ==================================================================

    private static List<PatternEntry> buildCookingPatterns(CookingFactoryBlockEntity cook, Map<AEKey, Long> avail) {
        Level level = cook.getLevel();
        List<PatternEntry> out = new ArrayList<>();
        for (Recipe<?> recipe : allCookingRecipes(cook)) {
            try {
                List<InputSpec> specs = cookingInputs(recipe);
                if (specs.isEmpty()) continue;
                GenericStack[] inputs = resolveInputs(specs, avail);
                if (inputs == null || inputs.length == 0) continue;
                ItemStack result = recipe.getResultItem(level.registryAccess());
                if (result.isEmpty()) continue;
                GenericStack[] outputs = new GenericStack[]{new GenericStack(AEItemKey.of(result), result.getCount())};
                IPatternDetails details = encode(inputs, outputs, level);
                if (details != null) {
                    out.add(new PatternEntry(details, recipe.getId(), outputs));
                }
            } catch (Throwable ignored) {
            }
        }
        return out;
    }

    /**
     * 中央厨房的 ME 样板：按**已安装模块的系列**收集配方（每系列一个样板集合）。
     * 终端下单后走中央厨房的订单系统（含合成链求解）。
     */
    private static List<PatternEntry> buildKitchenPatterns(Level level,
            cn.ism.mekck.blockentity.CentralKitchenBlockEntity kitchen, Map<AEKey, Long> avail) {
        List<PatternEntry> out = new ArrayList<>();
        for (var ability : kitchen.installedAbilities()) {
            // 烟火（森罗物语）虚拟配方：没有原版配方类型，单独取用
            for (Recipe<?> recipe : cn.ism.mekck.util.KaleidoscopeGrillingCompat
                    .virtualRecipesForFamily(ability.family().id)) {
                try {
                    List<InputSpec> specs = new ArrayList<>();
                    for (Ingredient ing : recipe.getIngredients()) {
                        if (ing.isEmpty()) continue;
                        specs.add(new InputSpec(ing, 1));
                    }
                    if (specs.isEmpty()) continue;
                    GenericStack[] inputs = resolveInputs(specs, avail);
                    if (inputs == null || inputs.length == 0) continue;
                    ItemStack result = recipe.getResultItem(level.registryAccess());
                    if (result.isEmpty()) continue;
                    GenericStack[] outputs = new GenericStack[]{
                            new GenericStack(AEItemKey.of(result), result.getCount())};
                    IPatternDetails details = encode(inputs, outputs, level);
                    if (details != null) {
                        out.add(new PatternEntry(details, recipe.getId(), outputs));
                    }
                } catch (Throwable ignored) {
                }
            }
            for (String typeId : ability.family().recipeTypes) {
                try {
                    RecipeType<?> type = cn.ism.mekck.util.RecipeCache.type(new ResourceLocation(typeId));
                    if (type == null) continue;
                    for (Recipe<?> recipe : cn.ism.mekck.util.RecipeCache.all(level, type)) {
                        List<InputSpec> specs = new ArrayList<>();
                        for (Ingredient ing : recipe.getIngredients()) {
                            if (ing.isEmpty()) continue;
                            specs.add(new InputSpec(ing, 1));
                        }
                        if (specs.isEmpty()) continue;
                        GenericStack[] inputs = resolveInputs(specs, avail);
                        if (inputs == null || inputs.length == 0) continue;
                        ItemStack result = recipe.getResultItem(level.registryAccess());
                        if (result.isEmpty()) continue;
                        GenericStack[] outputs = new GenericStack[]{
                                new GenericStack(AEItemKey.of(result), result.getCount())};
                        IPatternDetails details = encode(inputs, outputs, level);
                        if (details != null) {
                            out.add(new PatternEntry(details, recipe.getId(), outputs));
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        return out;
    }

    /**
     * 三明治组装机的 ME 样板：按**当前样品**（复制模式）生成一条样板，
     * 输入 = 样品的有序材料，输出 = 与该样品完全一致的三明治。
     * 样品变化时样板随之变化（样板由每次 refreshPatterns 重建）。
     */
    private static List<PatternEntry> buildSandwichPatterns(Level level,
            cn.ism.mekck.blockentity.SandwichAssemblerBlockEntity assembler, Map<AEKey, Long> avail) {
        List<PatternEntry> out = new ArrayList<>();
        try {
            java.util.List<ItemStack> layers = assembler.getMode()
                    == cn.ism.mekck.blockentity.SandwichAssemblerBlockEntity.MODE_COPY
                    ? cn.ism.mekck.blockentity.SandwichAssemblerBlockEntity.decodeSample(
                            assembler.items.getStackInSlot(
                                    cn.ism.mekck.blockentity.SandwichAssemblerBlockEntity.SAMPLE_SLOT))
                    : assembler.orderedLayers();
            if (layers.isEmpty()) return out;
            List<InputSpec> specs = new ArrayList<>();
            for (ItemStack layer : layers) {
                if (layer.isEmpty()) continue;
                specs.add(new InputSpec(net.minecraft.world.item.crafting.Ingredient.of(layer), 1));
            }
            if (specs.isEmpty()) return out;
            GenericStack[] inputs = resolveInputs(specs, avail);
            if (inputs == null || inputs.length == 0) return out;
            ItemStack result = cn.ism.mekck.blockentity.SandwichAssemblerBlockEntity.buildSandwich(layers);
            if (result.isEmpty()) return out;
            GenericStack[] outputs = new GenericStack[]{new GenericStack(AEItemKey.of(result), 1)};
            IPatternDetails details = encode(inputs, outputs, level);
            if (details != null) {
                out.add(new PatternEntry(details,
                        new ResourceLocation("mekck", "sandwich/auto"), outputs));
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    /** 电力研磨机（森罗物语石磨 + 烘焙坊筛粉 + 沉浸农艺绞碎 + mekck 磨粉）的 ME 样板。 */
    private static List<PatternEntry> buildGrindingPatterns(Level level, Map<AEKey, Long> avail) {
        List<PatternEntry> out = new ArrayList<>();
        for (String typeId : new String[]{"kaleidoscope_cookery:millstone", "bakeries:flour_sieve",
                "farm_and_charm:mincer", "mekck:grinding"}) {
            try {
                RecipeType<?> type = cn.ism.mekck.util.RecipeCache.type(new ResourceLocation(typeId));
                if (type == null) continue;
                for (Recipe<?> recipe : cn.ism.mekck.util.RecipeCache.all(level, type)) {
                    List<InputSpec> specs = new ArrayList<>();
                    for (Ingredient ing : recipe.getIngredients()) {
                        if (ing.isEmpty()) continue;
                        specs.add(new InputSpec(ing, 1));
                    }
                    if (specs.isEmpty()) continue;
                    GenericStack[] inputs = resolveInputs(specs, avail);
                    if (inputs == null || inputs.length == 0) continue;
                    ItemStack result = recipe.getResultItem(level.registryAccess());
                    if (result.isEmpty()) continue;
                    GenericStack[] outputs = new GenericStack[]{new GenericStack(AEItemKey.of(result), result.getCount())};
                    IPatternDetails details = encode(inputs, outputs, level);
                    if (details != null) {
                        out.add(new PatternEntry(details, recipe.getId(), outputs));
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        return out;
    }

    /** 电力烧烤架（烧烤乐事 grilling）的 ME 样板。 */
    private static List<PatternEntry> buildGrillingPatterns(Level level, Map<AEKey, Long> avail) {
        return buildSimpleSingleOutputPatterns(level, new ResourceLocation("barbequesdelight", "grilling"), avail);
    }

    /** 智能厨锅（农夫乐事 cooking + 森罗物语锅类）的 ME 样板。 */
    private static List<PatternEntry> buildCookingPotPatterns(Level level, Map<AEKey, Long> avail) {
        List<PatternEntry> out = new ArrayList<>(buildSimpleSingleOutputPatterns(level,
                new ResourceLocation("farmersdelight", "cooking"), avail));
        // 森罗物语：炒锅 / 汤锅配方（若模组已加载）。
        // 只列真实注册的锅类类型：实测森罗厨房 1.4.1 的配方 JSON `type` 全集为
        // pot/stockpot/flex_pot/flex_stockpot/steamer/millstone/chopping_board/teapot，
        // 早先此处写的 `stew_pot` 并不存在，会在 RecipeCache 触发一条一次性「配方类型按 id 查不到」告警
        // ⇒ 删除该死条目（flex_* / steamer 是否纳入属另一覆盖问题，未拍板，本单不动）。
        for (String typeId : new String[]{"kaleidoscope_cookery:pot", "kaleidoscope_cookery:stockpot"}) {
            try {
                out.addAll(buildSimpleSingleOutputPatterns(level, new ResourceLocation(typeId), avail));
            } catch (Throwable ignored) {
            }
        }
        return out;
    }

    /**
     * 通用「多输入 → 单产物」配方样板构建（切割 / 烧烤 / 烹饪等）。
     * 输入取配方全部 Ingredient，输出取配方结果（含全部产物）。
     */
    private static List<PatternEntry> buildSimpleSingleOutputPatterns(Level level, ResourceLocation typeId,
                                                                     Map<AEKey, Long> avail) {
        List<PatternEntry> out = new ArrayList<>();
        RecipeType<?> type = cn.ism.mekck.util.RecipeCache.type(typeId);
        if (type == null) return out;
        for (Recipe<?> recipe : cn.ism.mekck.util.RecipeCache.all(level, type)) {
            try {
                List<InputSpec> specs = new ArrayList<>();
                for (Ingredient ing : recipe.getIngredients()) {
                    if (ing.isEmpty()) continue;
                    specs.add(new InputSpec(ing, 1));
                }
                if (specs.isEmpty()) continue;
                GenericStack[] inputs = resolveInputs(specs, avail);
                if (inputs == null || inputs.length == 0) continue;
                ItemStack result = recipe.getResultItem(level.registryAccess());
                if (result.isEmpty()) continue;
                GenericStack[] outputs = new GenericStack[]{new GenericStack(AEItemKey.of(result), result.getCount())};
                IPatternDetails details = encode(inputs, outputs, level);
                if (details != null) {
                    out.add(new PatternEntry(details, recipe.getId(), outputs));
                }
            } catch (Throwable ignored) {
            }
        }
        return out;
    }

    /** 通用切菜机（农夫乐事 cutting）的 ME 样板：输入 = 配方材料，输出 = 全部产物。 */
    private static List<PatternEntry> buildCuttingPatterns(Level level, Map<AEKey, Long> avail) {
        List<PatternEntry> out = new ArrayList<>();
        RecipeType<?> type = cn.ism.mekck.util.RecipeCache.type(new ResourceLocation("farmersdelight", "cutting"));
        if (type == null) return out;
        for (Recipe<?> recipe : cn.ism.mekck.util.RecipeCache.all(level, type)) {
            try {
                List<InputSpec> specs = new ArrayList<>();
                for (Ingredient ing : recipe.getIngredients()) {
                    if (ing.isEmpty()) continue;
                    specs.add(new InputSpec(ing, 1));
                }
                if (specs.isEmpty()) continue;
                GenericStack[] inputs = resolveInputs(specs, avail);
                if (inputs == null || inputs.length == 0) continue;
                List<ItemStack> results = recipe instanceof vectorwing.farmersdelight.common.crafting.CuttingBoardRecipe cb
                        ? cb.getResults() : List.of(recipe.getResultItem(level.registryAccess()));
                List<GenericStack> outputs = new ArrayList<>();
                for (ItemStack result : results) {
                    if (!result.isEmpty()) {
                        outputs.add(new GenericStack(AEItemKey.of(result), result.getCount()));
                    }
                }
                if (outputs.isEmpty()) continue;
                GenericStack[] outputArr = outputs.toArray(new GenericStack[0]);
                IPatternDetails details = encode(inputs, outputArr, level);
                if (details != null) {
                    out.add(new PatternEntry(details, recipe.getId(), outputArr));
                }
            } catch (Throwable ignored) {
            }
        }
        return out;
    }

    private static List<PatternEntry> buildSkeweringPatterns(Level level, Map<AEKey, Long> avail) {
        List<PatternEntry> out = new ArrayList<>();
        List<Recipe<?>> recipes = new ArrayList<>();
        RecipeType<?> type = cn.ism.mekck.util.RecipeCache.type(new ResourceLocation("barbequesdelight", "skewering"));
        if (type != null) {
            recipes.addAll(cn.ism.mekck.util.RecipeCache.all(level, type));
        }
        if (KaleidoscopeGrillingCompat.isLoaded()) {
            for (KaleidoscopeGrillingCompat.VirtualRecipe vr : KaleidoscopeGrillingCompat.getThreadingVirtualRecipes()) {
                recipes.add(vr);
            }
        }
        for (Recipe<?> recipe : recipes) {
            try {
                List<InputSpec> specs = skeweringInputs(recipe);
                if (specs.isEmpty()) continue;
                GenericStack[] inputs = resolveInputs(specs, avail);
                if (inputs == null || inputs.length == 0) continue;
                ItemStack result = recipe.getResultItem(level.registryAccess());
                if (result.isEmpty()) continue;
                GenericStack[] outputs = new GenericStack[]{new GenericStack(AEItemKey.of(result), result.getCount())};
                IPatternDetails details = encode(inputs, outputs, level);
                if (details != null) {
                    out.add(new PatternEntry(details, recipe.getId(), outputs));
                }
            } catch (Throwable ignored) {
            }
        }
        return out;
    }

    private static List<Recipe<?>> allCookingRecipes(CookingFactoryBlockEntity cook) {
        Level level = cook.getLevel();
        List<Recipe<?>> list = new ArrayList<>();
        for (CookingPotRecipe r : (java.util.List<CookingPotRecipe>) (java.util.List<?>) cn.ism.mekck.util.RecipeCache.all(level, ModRecipeTypes.COOKING.get())) {
            list.add(r);
        }
        // 终焉烹饪（无尽乐事 extreme_cooking）仅奇点创世等级支持
        if (cook.getTier() == cn.ism.mekck.CuttingMachineFactoryTier.SINGULARITY) {
            RecipeType<?> shaped = cn.ism.mekck.util.RecipeCache.type(new ResourceLocation("avaritia_delight", "extreme_cooking_shaped"));
            if (shaped != null) {
                list.addAll(cn.ism.mekck.util.RecipeCache.all(level, shaped));
            }
            RecipeType<?> shapeless = cn.ism.mekck.util.RecipeCache.type(new ResourceLocation("avaritia_delight", "extreme_cooking_shapeless"));
            if (shapeless != null) {
                list.addAll(cn.ism.mekck.util.RecipeCache.all(level, shapeless));
            }
        }
        if (KaleidoscopeCompat.isLoaded()) {
            list.addAll(KaleidoscopeCompat.getAllKaleidoscopeRecipes(level));
        }
        Set<ResourceLocation> seen = new HashSet<>();
        list.removeIf(r -> !seen.add(r.getId()));
        return list;
    }

    private static List<InputSpec> cookingInputs(Recipe<?> recipe) {
        List<InputSpec> specs = new ArrayList<>();
        for (Ingredient ing : CookingFactoryBlockEntity.getSolidIngredients(recipe)) {
            specs.add(new InputSpec(ing, 1));
        }
        for (Ingredient ing : CookingFactoryBlockEntity.getFluidBottleIngredients(recipe)) {
            specs.add(new InputSpec(ing, 1));
        }
        for (Ingredient ing : CookingFactoryBlockEntity.getExtraConsumables(recipe)) {
            specs.add(new InputSpec(ing, 1));
        }
        ItemStack container = CookingFactoryBlockEntity.getConsumedContainer(recipe);
        if (!container.isEmpty()) {
            specs.add(new InputSpec(Ingredient.of(container.getItem()), 1));
        }
        return specs;
    }

    private static List<InputSpec> skeweringInputs(Recipe<?> recipe) {
        List<InputSpec> specs = new ArrayList<>();
        Ingredient tool = SkeweringFactoryBlockEntity.getIngredientField(recipe, "tool");
        if (tool != null && !tool.isEmpty()) {
            int count = Math.max(1, SkeweringFactoryBlockEntity.getCountField(recipe, "ingredientCount"));
            specs.add(new InputSpec(tool, count));
        }
        Ingredient main = SkeweringFactoryBlockEntity.getIngredientField(recipe, "ingredient");
        if (main != null && !main.isEmpty()) {
            specs.add(new InputSpec(main, 1));
        }
        Ingredient side = SkeweringFactoryBlockEntity.getIngredientField(recipe, "side");
        if (side != null && !side.isEmpty()) {
            int count = Math.max(1, SkeweringFactoryBlockEntity.getCountField(recipe, "sideCount"));
            specs.add(new InputSpec(side, count));
        }
        return specs;
    }

    private static GenericStack[] resolveInputs(List<InputSpec> specs, Map<AEKey, Long> avail) {
        List<GenericStack> out = new ArrayList<>();
        for (InputSpec spec : specs) {
            AEItemKey key = findMatch(spec.ingredient, avail);
            if (key == null) return null;
            out.add(new GenericStack(key, spec.count));
        }
        return out.toArray(new GenericStack[0]);
    }

    private static AEItemKey findMatch(Ingredient ing, Map<AEKey, Long> avail) {
        for (Map.Entry<AEKey, Long> e : avail.entrySet()) {
            if (e.getValue() <= 0) continue;
            AEKey key = e.getKey();
            if (key instanceof AEItemKey ik && ing.test(ik.toStack())) {
                return ik;
            }
        }
        return null;
    }

    /** SimpleMachine 食物机器：按 kind 枚举全部候选配方 → 终端可制作列表。 */
    private static List<PatternEntry> buildSimpleMachinePatterns(cn.ism.mekck.blockentity.SimpleMachineBlockEntity sm, Map<AEKey, Long> avail) {
        List<PatternEntry> out = new ArrayList<>();
        List<Recipe<?>> recipes = sm.allRecipesOfKind();
        for (Recipe<?> recipe : recipes) {
            try {
                List<cn.ism.mekck.util.AE2InputSpec> specs = sm.recipeInputSpecs(recipe);
                if (specs.isEmpty()) continue;
                // 转成 InputSpec 并解析网络库存
                List<InputSpec> ispecs = new ArrayList<>();
                for (cn.ism.mekck.util.AE2InputSpec s : specs) {
                    ispecs.add(new InputSpec(s.ingredient, s.count));
                }
                GenericStack[] inputs = resolveInputs(ispecs, avail);
                if (inputs == null || inputs.length == 0) continue;
                ItemStack result = recipe.getResultItem(sm.getLevel().registryAccess());
                if (result.isEmpty()) continue;
                GenericStack[] outputs = new GenericStack[]{new GenericStack(AEItemKey.of(result), result.getCount())};
                IPatternDetails details = encode(inputs, outputs, sm.getLevel());
                if (details != null) {
                    out.add(new PatternEntry(details, recipe.getId(), outputs));
                }
            } catch (Throwable ignored) {
            }
        }
        return out;
    }

    private static IPatternDetails encode(GenericStack[] inputs, GenericStack[] outputs, Level level) {
        try {
            ItemStack pattern = PatternDetailsHelper.encodeProcessingPattern(inputs, outputs);
            if (pattern.isEmpty()) return null;
            return PatternDetailsHelper.decodePattern(pattern, level);
        } catch (Throwable t) {
            return null;
        }
    }

    // ==================================================================
    //  数据结构
    // ==================================================================

    private static final class PatternEntry {
        final IPatternDetails details;
        final ResourceLocation recipeId;
        final GenericStack[] outputs;

        PatternEntry(IPatternDetails details, ResourceLocation recipeId, GenericStack[] outputs) {
            this.details = details;
            this.recipeId = recipeId;
            this.outputs = outputs;
        }
    }

    private static final class InputSpec {
        final Ingredient ingredient;
        final int count;

        InputSpec(Ingredient ingredient, int count) {
            this.ingredient = ingredient;
            this.count = count;
        }
    }

    private static final class AeJob {
        final PatternEntry entry;
        long remainingOutput;

        AeJob(PatternEntry entry) {
            this.entry = entry;
            long total = 0;
            for (GenericStack o : entry.outputs) {
                total += o.amount();
            }
            this.remainingOutput = total;
        }
    }
}
