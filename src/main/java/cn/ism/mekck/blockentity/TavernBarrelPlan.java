package cn.ism.mekck.blockentity;

import net.minecraft.resources.ResourceLocation;

import java.util.List;

/**
 * Tavern Barrel 酿造启动计划（第一阶段真实启动用）。
 * <p>
 * 由 RecipeManager 一次匹配生成完整计划：recipeId / 输入槽与数量 / 流体需求（4000mB 满桶）/
 * 初始瓶数 / 结果身份 / unitTime / carrier。不做多次扫描拼接。
 * 批次数量 = 非空配料槽最少物品数（上限 16；纯流体配方（无配料）默认 16）——与参考
 * BarrelRecipe.assemble 一致。
 */
public final class TavernBarrelPlan {

    public static final int MAX_FLUID_AMOUNT = 4 * 1000; // 参考 IBarrel.MAX_FLUID_AMOUNT
    public static final int MAX_BOTTLES = 16;
    // winery 复刻 vinery 陈酿桶：3 配料槽（0..2）+ 载重瓶槽（3）；真实酒馆 barrel 配方固体≤2，收敛自 4。
    public static final int MAX_INGREDIENT_SLOTS = 3;

    private final ResourceLocation recipeId;
    /** 配料消耗槽位（最多 4 个，批次启动时清空全部——参考 clearItemsAndFluid）。不可变。 */
    private final List<Integer> consumeSlots;
    /** 准备时各消耗槽的物品快照（与 consumeSlots 一一对应）。不可变（ItemStack 深拷贝）。 */
    private final List<net.minecraft.world.item.ItemStack> slotSnapshots;
    /** 需求流体（种类与数量，深拷贝）。不可变。 */
    private final net.minecraftforge.fluids.FluidStack fluid;
    /** 初始批次瓶数（1..16）。 */
    private final int bottles;
    /** 成品身份。 */
    private final ResourceLocation resultItemId;
    /** 配方 unitTime。 */
    private final int unitTime;
    /** carrier(Ingredient) JSON 副本。 */
    private final String carrierJson;
    /** 配方语义签名（ingredients/fluid/result/unitTime/carrier 序列化），供提交前比对同 ID 配方未变。 */
    private final String recipeSignature;

    private TavernBarrelPlan(ResourceLocation recipeId, List<Integer> consumeSlots,
                             List<net.minecraft.world.item.ItemStack> slotSnapshots,
                             net.minecraftforge.fluids.FluidStack fluid, int bottles,
                             ResourceLocation resultItemId, int unitTime, String carrierJson,
                             String recipeSignature) {
        this.recipeId = recipeId;
        // 防御性复制：计划独立于外部可变对象（列表结构 + ItemStack/FluidStack 深拷贝）
        this.consumeSlots = java.util.List.copyOf(consumeSlots);
        java.util.ArrayList<net.minecraft.world.item.ItemStack> snaps = new java.util.ArrayList<>(slotSnapshots.size());
        for (net.minecraft.world.item.ItemStack st : slotSnapshots) {
            snaps.add(st.copy());
        }
        this.slotSnapshots = java.util.Collections.unmodifiableList(snaps);
        this.fluid = fluid.copy();
        this.bottles = bottles;
        this.resultItemId = resultItemId;
        this.unitTime = unitTime;
        this.carrierJson = carrierJson;
        this.recipeSignature = recipeSignature;
    }

    // ── 只读访问（返回防御性副本）──

    public ResourceLocation getRecipeId() { return recipeId; }
    public List<Integer> getConsumeSlots() { return consumeSlots; } // List.copyOf 结果本身不可变
    public List<net.minecraft.world.item.ItemStack> getSlotSnapshots() {
        // 防御性副本：调用方修改返回列表不影响内部快照
        java.util.ArrayList<net.minecraft.world.item.ItemStack> out = new java.util.ArrayList<>(slotSnapshots.size());
        for (net.minecraft.world.item.ItemStack st : slotSnapshots) out.add(st.copy());
        return out;
    }
    public net.minecraftforge.fluids.FluidStack getFluid() { return fluid.copy(); }
    public int getBottles() { return bottles; }
    public ResourceLocation getResultItemId() { return resultItemId; }
    public int getUnitTime() { return unitTime; }
    public String getCarrierJson() { return carrierJson; }
    public String getRecipeSignature() { return recipeSignature; }

    /** 静态工厂 + 纯校验（可单元测试）：非法返回 null。快照与槽位必须一一对应。 */
    public static TavernBarrelPlan of(ResourceLocation recipeId, List<Integer> consumeSlots,
                                      List<net.minecraft.world.item.ItemStack> slotSnapshots,
                                      net.minecraftforge.fluids.FluidStack fluid, int bottles,
                                      ResourceLocation resultItemId, int unitTime, String carrierJson,
                                      String recipeSignature) {
        if (recipeId == null || resultItemId == null) return null;
        if (fluid == null || fluid.isEmpty() || fluid.getAmount() != MAX_FLUID_AMOUNT) return null;
        if (bottles < 1 || bottles > MAX_BOTTLES) return null;
        if (unitTime < 1) return null;
        if (carrierJson == null || carrierJson.isBlank()) return null;
        if (consumeSlots == null || consumeSlots.size() > MAX_INGREDIENT_SLOTS) return null;
        if (slotSnapshots == null || slotSnapshots.size() != consumeSlots.size()) return null;
        for (net.minecraft.world.item.ItemStack st : slotSnapshots) {
            if (st == null || st.isEmpty()) return null;
        }
        if (recipeSignature == null || recipeSignature.isBlank()) return null;
        return new TavernBarrelPlan(recipeId, consumeSlots, slotSnapshots, fluid, bottles,
                resultItemId, unitTime, carrierJson, recipeSignature);
    }

    /** 批次数量计算（参考 BarrelRecipe.assemble）：非空配料最少 count，上限 16；无配料默认 16。 */
    public static int computeBottles(List<net.minecraft.world.item.ItemStack> ingredientStacks) {
        if (ingredientStacks == null) return MAX_BOTTLES;
        int[] counts = new int[ingredientStacks.size()];
        for (int i = 0; i < ingredientStacks.size(); i++) {
            net.minecraft.world.item.ItemStack st = ingredientStacks.get(i);
            counts[i] = (st == null || st.isEmpty()) ? 0 : st.getCount();
        }
        return computeBottlesFromCounts(counts);
    }

    /** 纯 int 版本（可普通 JVM 单测）：非空配料最少 count，上限 16；无配料默认 16。 */
    public static int computeBottlesFromCounts(int[] counts) {
        int min = MAX_BOTTLES;
        boolean any = false;
        if (counts != null) {
            for (int c : counts) {
                if (c > 0) { any = true; min = Math.min(min, c); }
            }
        }
        return any ? Math.max(1, Math.min(min, MAX_BOTTLES)) : MAX_BOTTLES;
    }
}
