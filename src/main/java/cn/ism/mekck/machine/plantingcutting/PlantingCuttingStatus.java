package cn.ism.mekck.machine.plantingcutting;

/**
 * 种植切配系「生长方块格」检查结果的共享状态码。
 *
 * <h3>为什么单独成类</h3>
 * 这三个状态原先定义在 {@code blockentity/PlantingCuttingStationBlockEntity} 上，
 * 而 {@link PlantingCuttingFactoryTile#getGrowthStatus} 也在用同一套值 —— 于是
 * {@code machine → blockentity} 多出一条反向依赖（唯一的 1 条，构成
 * {@code blockentity ↔ machine} 环）。把这份<b>两者共用的语义</b>上移到 machine 侧后，
 * 反向依赖消失，而 legacy 站与已迁工厂共用同一份定义（不再是「两处各写一遍」）。
 *
 * <p>{@code blockentity} 与 {@code client} 都允许依赖 {@code machine}，所以它们继续使用
 * 这里的常量不产生新的反向边。</p>
 */
public final class PlantingCuttingStatus {

    private PlantingCuttingStatus() {
    }

    /** 生长方块格：就绪（有土且等级满足）。 */
    public static final int GROWTH_OK = 0;
    /** 生长方块格：缺方块（空槽）。 */
    public static final int GROWTH_MISSING = 1;
    /** 生长方块格：等级不足（放了土但不是本配方要求的那种）。 */
    public static final int GROWTH_TOO_LOW = 2;
}
