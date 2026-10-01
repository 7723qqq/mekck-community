package cn.ism.mekck.machine;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * 一次配方匹配的<b>不可变结果</b>：吃掉哪些槽（各多少）、产出什么、额外要放的流体 / 时长。
 *
 * <h3>为什么从 {@code SimpleMachineBlockEntity} 里独立出来</h3>
 * 它原本是那个 4841 行遗留 BE 的私有内嵌类，但**整台机器的匹配结果都用它**：
 * 34 处构造、6 个字段被外部逻辑读（消耗槽 39 处、产物 92 处、配方身份 8 处……）。
 * 留在 BE 内部时它只能被那一个类看见；独立成类之后，
 * 「机器配方匹配」这件事第一次有了自己的名字和位置，
 * 后续把匹配器（每种外部配方一个适配器）也搬出去时不必再往 BE 里开洞。
 *
 * <p>字段一律 {@code public final}：这是纯数据载体，没有行为，也没有不变量的破坏入口
 * （final + 构造器一次性赋值）。</p>
 *
 * <p>若干字段允许 {@code null} / 哨兵值，是为兼容历史上「槽非空即扣 1」的老行为 ——
 * 各字段的 javadoc 说明了各自的语义，迁移前不要收紧。</p>
 */
public final class MatchedRecipe {
    public final List<Integer> consumeSlots;
    /** 每个消耗槽的消耗数量（null = 兼容旧行为，每槽扣 1）。 */
    public final List<Integer> consumeCounts;
    /** 每个消耗槽的输入匹配条件（null = 沿用旧“槽非空即扣”行为）。 */
    public final List<net.minecraft.world.item.crafting.Ingredient> inputIngredients;
    public final ItemStack result;
    /** 配方自带处理时长（tick），0 = 用机器默认。 */
    public final int processTime;
    /** 发酵机：需消耗的输入流体（可为空）。 */
    public final net.minecraftforge.fluids.FluidStack drainFluid;
    /** 发酵机：产出的流体（可为空）。 */
    public final net.minecraftforge.fluids.FluidStack fillFluid;
    /** 真实配方 ID（可为 null = 不参与配方身份跟踪，沿用旧行为）。 */
    public final net.minecraft.resources.ResourceLocation recipeId;

    public MatchedRecipe(List<Integer> consumeSlots, ItemStack result) {
        this(consumeSlots, result, 0, net.minecraftforge.fluids.FluidStack.EMPTY, net.minecraftforge.fluids.FluidStack.EMPTY);
    }

    public MatchedRecipe(List<Integer> consumeSlots, ItemStack result, int processTime,
                  net.minecraftforge.fluids.FluidStack drainFluid, net.minecraftforge.fluids.FluidStack fillFluid) {
        this(consumeSlots, null, null, result, processTime, drainFluid, fillFluid, null);
    }

    /** 陈酿机：提交后桶内果汁液位（-1 = 本次不涉及果汁池）。 */
    public final int juiceLevelAfter;
    /** 陈酿机：提交后桶内果汁类型（配合 juiceLevelAfter）。 */
    public final String juiceTypeAfter;

    public MatchedRecipe(List<Integer> consumeSlots, List<Integer> consumeCounts,
                  List<net.minecraft.world.item.crafting.Ingredient> inputIngredients,
                  ItemStack result, int processTime,
                  net.minecraftforge.fluids.FluidStack drainFluid, net.minecraftforge.fluids.FluidStack fillFluid,
                  net.minecraft.resources.ResourceLocation recipeId) {
        this(consumeSlots, consumeCounts, inputIngredients, result, processTime, drainFluid, fillFluid,
                recipeId, -1, null);
    }

    public MatchedRecipe(List<Integer> consumeSlots, List<Integer> consumeCounts,
                  List<net.minecraft.world.item.crafting.Ingredient> inputIngredients,
                  ItemStack result, int processTime,
                  net.minecraftforge.fluids.FluidStack drainFluid, net.minecraftforge.fluids.FluidStack fillFluid,
                  net.minecraft.resources.ResourceLocation recipeId,
                  int juiceLevelAfter, String juiceTypeAfter) {
        this.consumeSlots = consumeSlots;
        this.consumeCounts = consumeCounts;
        this.inputIngredients = inputIngredients;
        this.result = result;
        this.processTime = processTime;
        this.drainFluid = drainFluid;
        this.fillFluid = fillFluid;
        this.recipeId = recipeId;
        this.juiceLevelAfter = juiceLevelAfter;
        this.juiceTypeAfter = juiceTypeAfter;
    }
}
