package cn.ism.mekck.util;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.fluids.capability.templates.FluidTank;

/**
 * 多流体槽（3 个独立输入格），用于让烹饪类型的机器同时容纳
 * 多种流体（水 / 奶 / 其它），以适配多种配方流体转换。
 *
 * <p>既是 {@link IFluidHandler}（供侧面配置 / 外部管道输入输出流体），
 * 又提供面向业务逻辑的便捷判定与消耗方法（区分水与非水流体）。
 */
public class MultiFluidHandler implements IFluidHandler {

    /** 每个子槽的独立容量。 */
    private final int tankCapacity;
    /** 子槽数组。 */
    private final FluidTank[] tanks;
    /** 内容变化回调（通常触发 BlockEntity.setChanged）。 */
    private final Runnable onChange;

    public MultiFluidHandler(int tankCount, int tankCapacity, Runnable onChange) {
        this.tankCapacity = tankCapacity;
        this.onChange = onChange;
        this.tanks = new FluidTank[tankCount];
        for (int i = 0; i < tanks.length; i++) {
            final int idx = i;
            tanks[i] = new FluidTank(tankCapacity, fluidStack -> true) {
                @Override
                protected void onContentsChanged() {
                    if (MultiFluidHandler.this.onChange != null) {
                        MultiFluidHandler.this.onChange.run();
                    }
                    // 向前兼容忽略外层 idx（仅供调试 / 工具方法使用）
                    ignore(idx);
                }
            };
        }
    }

    /** 占位：避免未使用警告。 */
    private static void ignore(int idx) {
    }

    /** 子槽个数（=3）。 */
    public int getTankCount() {
        return tanks.length;
    }

    /** 每个子槽的容量。 */
    public int getTankCapacity() {
        return tankCapacity;
    }

    public FluidTank getTank(int index) {
        if (index < 0 || index >= tanks.length) return null;
        return tanks[index];
    }

    /** 全部槽内流体总量（mb）。 */
    public int totalFluidAmount() {
        int total = 0;
        for (FluidTank t : tanks) total += t.getFluidAmount();
        return total;
    }

    /**
     * 判定单个槽 {@code index} 内是否存放了"水"（对 {@code Fluids.WATER}）。
     */
    private static boolean isWater(FluidStack f) {
        return !f.isEmpty() && f.getFluid().isSame(Fluids.WATER);
    }

    /**
     * 判定单个槽 {@code index} 内是否存放了"非水（奶等）"流体。
     */
    private static boolean isNotWater(FluidStack f) {
        return !f.isEmpty() && !f.getFluid().isSame(Fluids.WATER);
    }

    /**
     * 是否存在一个槽，其流体类型匹配 {@code waterMm} 所指示的类型
     * 且容量 ≥ {@code requiredMb}。
     *
     * @param waterMb    需要的是水（true）还是非水/奶（false）
     * @param requiredMb 需要的量（mb）
     */
    public boolean hasEnoughOf(boolean waterMb, int requiredMb) {
        if (requiredMb <= 0) return true;
        for (FluidTank t : tanks) {
            FluidStack f = t.getFluid();
            boolean match = waterMb ? isWater(f) : isNotWater(f);
            if (match && f.getAmount() >= requiredMb) return true;
        }
        return false;
    }

    /**
     * 所有匹配类型槽内的流体总量（mb）。
     *
     * @param waterMb 求水（true）还是非水/奶（false）
     */
    public int totalOf(boolean waterMb) {
        int sum = 0;
        for (FluidTank t : tanks) {
            FluidStack f = t.getFluid();
            boolean match = waterMb ? isWater(f) : isNotWater(f);
            if (match) sum += f.getAmount();
        }
        return sum;
    }

    /**
     * 从第一个满足类型与容量要求的槽中抽取 {@code requiredMb}。
     * 要求调用方先通过 {@link #hasEnoughOf(boolean, int)} 校验到位。
     */
    public void drainOf(boolean waterMb, int requiredMb) {
        if (requiredMb <= 0) return;
        for (FluidTank t : tanks) {
            FluidStack f = t.getFluid();
            boolean match = waterMb ? isWater(f) : isNotWater(f);
            if (match && f.getAmount() >= requiredMb) {
                t.drain(requiredMb, IFluidHandler.FluidAction.EXECUTE);
                return;
            }
        }
    }

    /** 序列化全部槽。 */
    public CompoundTag writeToNBT() {
        ListTag list = new ListTag();
        for (FluidTank t : tanks) {
            list.add(t.writeToNBT(new CompoundTag()));
        }
        CompoundTag tag = new CompoundTag();
        tag.put("Tanks", list);
        tag.putInt("Count", tanks.length);
        return tag;
    }

    /** 反序列化全部槽。兼容旧单槽 NBT（见 {@link #readLegacySingle(CompoundTag)}）。 */
    public void readFromNBT(CompoundTag tag) {
        if (tag == null) return;
        if (tag.contains("Tanks", Tag.TAG_LIST)) {
            ListTag list = tag.getList("Tanks", Tag.TAG_COMPOUND);
            int n = Math.min(tanks.length, list.size());
            for (int i = 0; i < n; i++) {
                tanks[i].readFromNBT(list.getCompound(i));
            }
        } else {
            // 旧版单槽数据回退：写入第一个槽
            readLegacySingle(tag);
        }
    }

    /** 旧版（单 FluidTank）数据回退：写回第一个子槽，其余清空。 */
    private void readLegacySingle(CompoundTag tag) {
        tanks[0].readFromNBT(tag);
        for (int i = 1; i < tanks.length; i++) {
            tanks[i].setFluid(FluidStack.EMPTY);
        }
    }

    // ==================================================================
    //   IFluidHandler（供 capability 使用）
    // ==================================================================

    @Override
    public int getTanks() {
        return tanks.length;
    }

    @Override
    public FluidStack getFluidInTank(int tank) {
        if (tank < 0 || tank >= tanks.length) return FluidStack.EMPTY;
        return tanks[tank].getFluid();
    }

    @Override
    public int getTankCapacity(int tank) {
        if (tank < 0 || tank >= tanks.length) return 0;
        return tanks[tank].getCapacity();
    }

    @Override
    public boolean isFluidValid(int tank, FluidStack stack) {
        if (tank < 0 || tank >= tanks.length) return false;
        return tanks[tank].isFluidValid(stack);
    }

    @Override
    public int fill(FluidStack resource, FluidAction action) {
        if (resource.isEmpty()) return 0;
        // 每种流体只存于一个槽位：优先填充已存放同类型流体的槽，
        // 没有则找一个空槽；绝不把同一流体拆到多个槽，避免 1B 输入
        // 却被分到所有槽、各自各存 1B 的问题。
        for (FluidTank t : tanks) {
            if (!t.isEmpty() && t.getFluid().isFluidEqual(resource)) {
                return t.fill(resource, action);
            }
        }
        for (FluidTank t : tanks) {
            if (t.isEmpty()) {
                return t.fill(resource, action);
            }
        }
        return 0;
    }

    @Override
    public FluidStack drain(FluidStack resource, FluidAction action) {
        if (resource.isEmpty()) return FluidStack.EMPTY;
        FluidStack drained = FluidStack.EMPTY;
        // 逐罐递减剩余请求量：若每个匹配罐都按完整 resource 抽取，3 罐各 1000 mB 时
        // 请求 500 mB 会抽出 1500 mB ——凭空多出 1000 mB（流体复制）。
        int remaining = resource.getAmount();
        for (FluidTank t : tanks) {
            if (remaining <= 0) break;
            if (t.getFluid().isEmpty() || !t.getFluid().isFluidEqual(resource)) continue;
            FluidStack want = resource.copy();
            want.setAmount(Math.min(remaining, t.getFluid().getAmount()));
            FluidStack d = t.drain(want, action);
            if (d.isEmpty()) continue;
            if (drained.isEmpty()) {
                drained = d.copy();
            } else {
                drained.grow(d.getAmount());
            }
            remaining -= d.getAmount();
        }
        return drained;
    }

    @Override
    public FluidStack drain(int maxDrain, FluidAction action) {
        if (maxDrain <= 0) return FluidStack.EMPTY;
        FluidStack drained = FluidStack.EMPTY;
        for (FluidTank t : tanks) {
            if (t.getFluid().isEmpty()) continue;
            int want = maxDrain - drained.getAmount();
            if (want <= 0) break;
            // 必须在 drain 之前比对流体类型：原先是先抽后判，遇到异种流体再 break，
            // 那一步已经把流体从罐里扣掉且不退还 —— 水 + 岩浆双罐时请求 2000 会吞掉 1000 岩浆。
            if (!drained.isEmpty() && !t.getFluid().isFluidEqual(drained)) break;
            FluidStack d = t.drain(want, action);
            if (d.isEmpty()) continue;
            if (drained.isEmpty()) {
                drained = d.copy();
            } else {
                drained.grow(d.getAmount());
            }
        }
        return drained;
    }

    /** 便捷：直接对指定槽位填充（供内部逻辑用）。 */
    @Deprecated
    public int fillTank(int index, FluidStack resource, boolean simulate) {
        if (index < 0 || index >= tanks.length) return 0;
        return tanks[index].fill(resource, simulate ? FluidAction.SIMULATE : FluidAction.EXECUTE);
    }
}