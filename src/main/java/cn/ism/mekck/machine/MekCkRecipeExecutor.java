package cn.ism.mekck.machine;

import net.minecraft.nbt.CompoundTag;

/**
 * 家族特有的配方执行器 —— L3 内容层的契约。
 *
 * <h3>为什么把它从方块实体里拆出来</h3>
 * 7 个工厂家族的方块实体有 42 个方法完全相同（升级追踪、红石、能力暴露、NBT、
 * AE2 拉料…），这些由 {@link MekCkMachineTile} 与 Mekanism 基类提供。
 * 真正家族特有的只有「怎么匹配配方、怎么把原料变成产物」。
 * 拆出来之后，一个家族的执行器是独立可读、可单测的纯逻辑单元。
 *
 * <h3>为什么不用泛型</h3>
 * 各家族的配方类型不同（切菜是 {@code vectorwing.farmersdelight.CuttingBoardRecipe}，
 * 烹饪是 {@code CookingPotRecipe}，制冰是本模组自有的 {@code mekck:ice_make}），
 * 泛型化会把 {@code IInventorySlot} 与配方类型的耦合传染到基类。
 * 这里用非泛型接口，执行器内部自己持有具体配方类型。
 */
public interface MekCkRecipeExecutor {

    /**
     * 本机有几路并行 —— <b>每路一个独立计时器、一条进度条</b>。
     *
     * <p>默认「一路一个输入槽」，与 Mek 的工厂同构（{@code TileEntityFactory.progress}
     * 的长度就是 {@code tier.processes}）。烹饪与穿串是<b>整机一次</b>的批次操作
     * （一次消耗多个输入槽里的材料做出一批），没有「第几路」可言，所以覆写成 1。</p>
     */
    default int processCount(MekCkMachineTile tile) {
        return tile == null || tile.getInputSlots() == null
                ? 1 : Math.max(1, tile.getInputSlots().size());
    }

    /**
     * 第 {@code index} 路此刻能不能开工 —— 有输入、有配方、产物装得下。
     *
     * <p><b>不得改动机器状态</b>：本方法每 tick 对每一路各调一次，用来决定这一路的进度
     * 推不推进、推不动时要不要清零。真正的加工在 {@link #process}。
     * 执行器自己的缓存（配方缓存、{@code this.tile} 绑定）可以在这里更新。</p>
     */
    boolean canProcess(MekCkMachineTile tile, int index);

    /** 加工第 {@code index} 路一次。只在 {@link #canProcess} 为真时调用。 */
    void process(MekCkMachineTile tile, int index);

    /** 是否正在工作（决定 GUI 的进度条与 AE2 的忙碌态）。 */
    boolean isBusy();

    // ── 展示态（第三轮审查补）───────────────────────────────────────────
    //
    // 这三个方法原先只在各执行器上以各自的名字存在（且部分家族缺失），
    // 于是「订单进度要显示在 GUI 上」这件事<b>没有任何统一入口</b>：
    // 菜单只能按具体类型强转，跨家族复用就断了，而新增家族极易漏掉某一个。
    //
    // 它们同时是容器同步的<b>数据来源</b>：MekCkMachineTile#addContainerTrackers
    // 把它们按 SyncableInt 推给客户端（详见该处关于「此前无同步通道」的注释）。
    // 默认实现是「无订单」，切菜那种无订单系统的执行器什么都不用写。

    /** 是否有一张单在跑。 */
    default boolean hasOrder() {
        return false;
    }

    /** 订单总份数；无订单时为 0。 */
    default int getOrderQuantity() {
        return 0;
    }

    /** 订单已完成份数；无订单时为 0。 */
    default int getOrderCompleted() {
        return 0;
    }

    /** 持久化订单进度等执行器自有状态。 */
    void save(CompoundTag tag);

    /** 读取执行器自有状态。旧存档无此键时必须保持默认态，不得抛异常。 */
    void load(CompoundTag tag);
}
