package cn.ism.mekck.util;

import mekanism.api.heat.HeatAPI;
import mekanism.api.heat.IHeatCapacitor;
import mekanism.api.heat.IHeatHandler;
import mekanism.api.heat.IMekanismHeatHandler;
import mekanism.common.capabilities.Capabilities;
import mekanism.common.capabilities.heat.BasicHeatCapacitor;
import mekanism.common.capabilities.heat.CachedAmbientTemperature;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

/**
 * MekCK 通用热组件：基于 Mekanism 热容量模型（温度 K = 热量 J / 热容量），供需要“产热/制冷”的机器复用。
 *
 * 与 Mekanism 电阻型加热器完全一致的参数：
 * - 热容量 100 J/K；
 * - 电能→热量效率 0.6（1 FE → 0.6 J 热量）；
 * - 逆传导系数 5.0、逆绝缘系数 100.0。
 *
 * 联动：通过 Mekanism 的 heat capability（{@code Capabilities.HEAT_HANDLER}）与相邻热力设备
 * （电阻加热器 / 热导线缆 / 锅炉等）双向传导，公式与 Mekanism ITileHeatHandler 一致：
 * {@code q = (本机温度 − 相邻温度) / (本机逆传导 + 相邻逆传导) × 本机热容量}。
 *
 * 对外暴露的热能力实现 {@link IMekanismHeatHandler}（而非仅 {@link IHeatHandler}），
 * 与通用机械机器处于同一层级：具备带方向的热容量容器查询、热容量/逆传导/逆绝缘的默认实现，
 * 以及 {@code IContentsListener} 的内容变更回调。
 */
public final class MekCkHeatComponent implements IMekanismHeatHandler {

    /** 热容量（J/K），与 Mekanism 电阻型加热器一致。 */
    public static final double HEAT_CAPACITY = 100.0;
    /** 电能→热量效率，与 Mekanism 电阻型加热器完全一致。 */
    public static final double HEAT_EFFICIENCY = 0.6;
    /** 逆传导系数，与电阻型加热器一致。 */
    private static final double INVERSE_CONDUCTION = 5.0;
    /** 逆绝缘系数，与电阻型加热器一致。 */
    private static final double INVERSE_INSULATION = 100.0;
    /** 每 tick 向环境回归的热量比例。 */
    private static final double AMBIENT_LOSS_RATE = 0.01;
    private static final double EPSILON = 1.0e-3;

    private final BasicHeatCapacitor capacitor;
    private final List<IHeatCapacitor> capacitors;

    public MekCkHeatComponent(Supplier<Level> levelSupplier, Supplier<BlockPos> posSupplier, Runnable onChanged) {
        this.capacitor = BasicHeatCapacitor.create(HEAT_CAPACITY, INVERSE_CONDUCTION, INVERSE_INSULATION,
                new CachedAmbientTemperature(levelSupplier, posSupplier), onChanged::run);
        this.capacitors = Collections.singletonList(capacitor);
    }

    // ==================== IMekanismHeatHandler ====================

    @Override
    public List<IHeatCapacitor> getHeatCapacitors(@Nullable Direction side) {
        return capacitors;
    }

    @Override
    public void onContentsChanged() {
        // 内容变更回调（IContentsListener）：热容量容器写入热量后由其内部触发，
        // 变更通知由构造时传入的 onChanged（机器侧 setChanged）负责。
    }

    @Override
    public void handleHeat(int capacitor, double transfer, @Nullable Direction side) {
        this.capacitor.handleHeat(transfer);
        this.capacitor.update();
    }

    // ==================== 对外便捷接口 ====================

    /** 当前温度（开尔文）。 */
    public double getTemperature() {
        return capacitor.getTemperature();
    }

    /** Mekanism 热能力（IMekanismHeatHandler），用于对外暴露 heat capability。 */
    public IMekanismHeatHandler getHandler() {
        return this;
    }

    /** 按消耗的电能产热（与电阻型加热器比例完全相同：1 FE → 0.6 J 热量）。 */
    public void addHeatFromEnergy(int energyUsed) {
        if (energyUsed <= 0) return;
        capacitor.handleHeat(energyUsed * HEAT_EFFICIENCY);
    }

    /** 直接注入/抽取热量（正数升温、负数降温），单位 J。 */
    public void handleHeat(double heat) {
        capacitor.handleHeat(heat);
    }

    /**
     * 每 tick 温度更新：自然回归环境温度 + 与相邻 Mekanism 热力设备双向传导。
     * 应在服务端每 tick 调用（空闲时也应调用，以便散热/联动）。
     */
    public void tick(Level level, BlockPos pos) {
        if (level != null && !level.isClientSide) {
            // 自然回归环境温度
            double ambient = HeatAPI.getAmbientTemp(level, pos);
            double diff = capacitor.getTemperature() - ambient;
            if (Math.abs(diff) > EPSILON) {
                capacitor.handleHeat(-diff * HEAT_CAPACITY * AMBIENT_LOSS_RATE);
            }
            // 相邻热力设备传导（Mekanism heat capability）
            for (Direction side : cn.ism.mekck.util.Directions.VALUES) {
                BlockPos neighbour = pos.relative(side);
                if (!level.hasChunkAt(neighbour)) continue;
                BlockEntity be = level.getBlockEntity(neighbour);
                if (be == null) continue;
                IHeatHandler adjacent = be.getCapability(Capabilities.HEAT_HANDLER, side.getOpposite())
                        .resolve().orElse(null);
                if (adjacent == null || adjacent == this) continue;
                double denominator = capacitor.getInverseConduction() + adjacent.getTotalInverseConduction();
                if (denominator <= 0) continue;
                double q = (capacitor.getTemperature() - adjacent.getTotalTemperature())
                        / denominator * capacitor.getHeatCapacity();
                if (Math.abs(q) < EPSILON) continue;
                capacitor.handleHeat(-q);
                adjacent.handleHeat(q);
            }
        }
        capacitor.update();
    }

    /** 序列化热容量（随机器 NBT 保存）。 */
    public CompoundTag save() {
        return capacitor.serializeNBT();
    }

    /** 反序列化热容量。 */
    public void load(CompoundTag tag) {
        if (tag != null) {
            capacitor.deserializeNBT(tag);
        }
    }
}
