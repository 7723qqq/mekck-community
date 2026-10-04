package cn.ism.mekck.util;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.energy.EnergyStorage;
import net.minecraftforge.energy.IEnergyStorage;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * 机器能源槽位(能量板/能量立方/红石)辅助工具，行为与 Mekanism 的 EnergyInventorySlot.fillOrConvert 对齐。
 * - 从带能量的物品（能量立方、能量板等）中抽取能量注入机器内部能量。
 * - 红石可转换为固定能量。
 */
public final class PowerSlotUtil {
    /** 单份红石转换为能量的 FE 值（对应 Mekanism 的红石→能量转换）。 */
    public static final int REDSTONE_ENERGY = 1_000;
    /** 每 tick 最多消耗的红石数量，避免一次清空海量红石堆。 */
    public static final int REDSTONE_PER_TICK = 8;

    private static final ResourceLocation REDSTONE_ID = ResourceLocation.tryParse("minecraft:redstone");

    private PowerSlotUtil() {
    }

    /** 物品是否是红石。 */
    public static boolean isRedstone(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
        return id != null && id.equals(REDSTONE_ID);
    }

    /**
     * 该物品是否可作为机器的能源槽位内容物：
     * 红石，或能提供能量的能量容器物品。
     */
    public static boolean isValidEnergyItem(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        if (isRedstone(stack)) {
            return true;
        }
        LazyOptional<IEnergyStorage> cap = stack.getCapability(ForgeCapabilities.ENERGY, null);
        if (cap.isPresent()) {
            IEnergyStorage es = cap.resolve().orElse(null);
            // 既能放电（抽能入机）也能充电（机器给其充电）的物品均可放入能源槽
            return es != null && (es.canExtract() || es.canReceive());
        }
        return false;
    }

    /**
     * 从槽位中的物品抽取能量注入机器内部能量。
     *
     * @param stack          当前槽位内的物品
     * @param target         机器内部能量存储
     * @param redstonePerTick 每 tick 最多消费的红石数量（传 0 表示不进行红石转换）
     * @return 本次是否发生了能量转移 / 物品数量变化
     */
    public static boolean drain(ItemStack stack, EnergyStorage target, int redstonePerTick) {
        if (stack.isEmpty() || target == null) {
            return false;
        }
        boolean changed = false;
        int needed = target.getMaxEnergyStored() - target.getEnergyStored();
        if (needed <= 0) {
            return false;
        }

        // 1. 从带能量的物品中抽取
        LazyOptional<IEnergyStorage> cap = stack.getCapability(ForgeCapabilities.ENERGY, null);
        if (cap.isPresent()) {
            IEnergyStorage src = cap.resolve().orElse(null);
            if (src != null && src.canExtract()) {
                int toExtract = Math.min(needed, src.extractEnergy(needed, true));
                if (toExtract > 0) {
                    int got = target.receiveEnergy(toExtract, false);
                    if (got > 0) {
                        src.extractEnergy(got, false);
                        changed = true;
                        needed -= got;
                    }
                }
            }
        }

        // 2. 红石 -> 能量转换
        if (needed > 0 && redstonePerTick > 0 && isRedstone(stack) && stack.getCount() > 0) {
            int toConsume = Math.min(stack.getCount(), redstonePerTick);
            int canAdd = Math.min(needed / REDSTONE_ENERGY, toConsume);
            if (canAdd > 0) {
                int added = target.receiveEnergy(canAdd * REDSTONE_ENERGY, false);
                int consumed = added / REDSTONE_ENERGY;
                if (consumed > 0) {
                    stack.shrink(consumed);
                    changed = true;
                }
            }
        }

        return changed;
    }

    /**
     * 从物品抽能、把 FE 数量回报给调用方 —— <b>不依赖 Forge {@link EnergyStorage}</b> 的重载。
     *
     * <h3>为什么需要它（2026-10-03 迁移）</h3>
     * 陈化窖等机器迁到 Mek 体系后，内部能量是 {@code MachineEnergyContainer}
     * （Mek 的 {@code FloatingLong} 容器），形参类型与上面那个重载接不上。
     *
     * <p>本重载把「机器能量容器」抽象成两件事：<b>还能装多少</b>（{@code space}）
     * 与<b>抽到的量交给谁</b>（{@code sink}，返回实际接受量）。
     * 两条分支（能量物品 / 红石转换）的口径与 {@link #drain} 逐位一致——
     * 同一份 {@value #REDSTONE_ENERGY} FE/份、同一份每 tick 份数上限。</p>
     *
     * @param stack           槽位内的物品（红石分支会就地 {@code shrink}）
     * @param space           机器当前<b>还能接收</b>的 FE（容量 − 存量）；≤0 直接返回 false
     * @param redstonePerTick 每 tick 最多消费的红石份数（0 = 不做红石转换）
     * @param sink            接收能量的回调，返回<b>实际接受</b>的 FE
     * @return 本次是否发生了能量转移或物品数量变化
     */
    public static boolean drainTo(ItemStack stack, long space, int redstonePerTick,
                                  java.util.function.LongUnaryOperator sink) {
        if (stack.isEmpty() || space <= 0L || sink == null) {
            return false;
        }
        boolean changed = false;
        long needed = space;

        // 1. 能量物品（物品侧仍是 Forge IEnergyStorage，与机器侧迁到 Mek 无关）
        LazyOptional<IEnergyStorage> cap = stack.getCapability(ForgeCapabilities.ENERGY, null);
        if (cap.isPresent()) {
            IEnergyStorage src = cap.resolve().orElse(null);
            if (src != null && src.canExtract()) {
                int want = (int) Math.min(needed, Integer.MAX_VALUE);
                int toExtract = src.extractEnergy(want, true);
                if (toExtract > 0) {
                    long got = sink.applyAsLong(toExtract);
                    if (got > 0) {
                        src.extractEnergy((int) got, false);
                        changed = true;
                        needed -= got;
                    }
                }
            }
        }

        // 2. 红石 → 能量（与 drain 同口径）
        if (needed > 0 && redstonePerTick > 0 && isRedstone(stack) && stack.getCount() > 0) {
            int toConsume = Math.min(stack.getCount(), redstonePerTick);
            long canAdd = Math.min(needed / REDSTONE_ENERGY, toConsume);
            if (canAdd > 0) {
                long added = sink.applyAsLong(canAdd * REDSTONE_ENERGY);
                int consumed = (int) (added / REDSTONE_ENERGY);
                if (consumed > 0) {
                    stack.shrink(consumed);
                    changed = true;
                }
            }
        }

        return changed;
    }

    /**
     * 把机器内部能量充入槽位中的可充电物品（发电机能量槽的输出行为）。
     * 仅当物品可接收能量、且未满、且机器当前存有能量时生效。
     *
     * @param stack          当前槽位内的物品
     * @param source         机器内部能量存储（被抽取方）
     * @param fePerTick      每 tick 最多充入的 FE
     * @return 本次是否发生了能量转移
     */
    public static boolean charge(ItemStack stack, EnergyStorage source, int fePerTick) {
        if (stack.isEmpty() || source == null) {
            return false;
        }
        if (source.getEnergyStored() <= 0) {
            return false;
        }
        LazyOptional<IEnergyStorage> cap = stack.getCapability(ForgeCapabilities.ENERGY, null);
        if (!cap.isPresent()) {
            return false;
        }
        IEnergyStorage dst = cap.resolve().orElse(null);
        if (dst == null || !dst.canReceive()) {
            return false;
        }
        if (dst.getEnergyStored() >= dst.getMaxEnergyStored()) {
            return false;
        }
        int toGive = Math.min(fePerTick, source.getEnergyStored());
        int received = dst.receiveEnergy(toGive, false);
        if (received > 0) {
            source.extractEnergy(received, false);
            return true;
        }
        return false;
    }
}