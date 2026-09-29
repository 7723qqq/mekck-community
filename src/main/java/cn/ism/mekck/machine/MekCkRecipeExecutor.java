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

    /** 每 tick 调用一次。`slotCount` 是本等级的输入槽并行数。 */
    void tick(MekCkMachineTile tile, int slotCount);

    /** 是否正在工作（决定 GUI 的进度条与 AE2 的忙碌态）。 */
    boolean isBusy();

    /** 持久化订单进度等执行器自有状态。 */
    void save(CompoundTag tag);

    /** 读取执行器自有状态。旧存档无此键时必须保持默认态，不得抛异常。 */
    void load(CompoundTag tag);
}
