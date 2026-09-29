package cn.ism.mekck.kitchen;

import net.minecraft.world.item.ItemStack;
import net.minecraftforge.items.ItemStackHandler;

import java.util.List;

/**
 * 中央厨房的一个订单：目标配方 + 数量 + 求解出的任务链 + 独立的订单暂存区。
 *
 * <p>中间产物进入 {@link #buffer}（每订单独立缓冲，**不进入共享存储区**，避免被其它系列或
 * 其它订单抢用）；全部步骤完成后，最终产物从暂存区移入输出存储区。</p>
 */
public final class KitchenOrder {

    /** 订单状态。 */
    public enum State {
        /** 等待执行（材料已预留）。 */
        PENDING,
        /** 正在执行任务链。 */
        RUNNING,
        /** 已完成，产物待取出。 */
        DONE,
        /** 暂停（缺料 / 输出区满等）。 */
        PAUSED
    }

    /** 订单暂存区格数（中间产物 + 预留的叶子材料）。 */
    public static final int BUFFER_SLOTS = 18;

    private final int id;
    private final List<KitchenCraftingPlan.Step> steps;
    /** 订单暂存区：叶子材料与中间产物。 */
    public final ItemStackHandler buffer = new ItemStackHandler(BUFFER_SLOTS) {
        @Override
        public int getSlotLimit(int slot) {
            return Integer.MAX_VALUE - 1;
        }
    };
    private int stepIndex = 0;
    private State state = State.PENDING;
    private String note = "";
    /** 当前步骤已进行的刻数（接入线程池后按加工时间推进）。 */
    private int stepProgress = 0;
    /** 当前步骤的总耗时（刻）；0 表示尚未计算。 */
    private int stepTotalTime = 0;

    public KitchenOrder(int id, List<KitchenCraftingPlan.Step> steps) {
        this.id = id;
        this.steps = steps;
    }

    public int id() {
        return id;
    }

    public List<KitchenCraftingPlan.Step> steps() {
        return steps;
    }

    public int stepIndex() {
        return stepIndex;
    }

    public KitchenCraftingPlan.Step currentStep() {
        return stepIndex < steps.size() ? steps.get(stepIndex) : null;
    }

    public void advanceStep() {
        stepIndex++;
        stepProgress = 0;
        stepTotalTime = 0;
    }

    /** 当前步骤已进行的刻数。 */
    public int stepProgress() {
        return stepProgress;
    }

    /** 当前步骤的总耗时（刻）。 */
    public int stepTotalTime() {
        return stepTotalTime;
    }

    /** 设置当前步骤的总耗时。 */
    public void setStepTotalTime(int total) {
        this.stepTotalTime = Math.max(1, total);
        this.stepProgress = 0;
    }

    /** 推进一刻；返回当前步骤是否已完成。 */
    public boolean tickStep() {
        stepProgress++;
        return stepProgress >= stepTotalTime;
    }

    /** 当前步骤进度比例（0~1）。 */
    public double stepRatio() {
        return stepTotalTime <= 0 ? 0.0 : Math.min(1.0, (double) stepProgress / stepTotalTime);
    }

    /** 持久化：当前步骤已进行的刻数。 */
    public int rawProgress() {
        return stepProgress;
    }

    /** 持久化：恢复当前步骤进度。 */
    public void restoreProgress(int progress, int total) {
        this.stepProgress = Math.max(0, progress);
        this.stepTotalTime = Math.max(0, total);
    }

    public State state() {
        return state;
    }

    public void setState(State state) {
        this.state = state;
    }

    public String note() {
        return note;
    }

    public void setNote(String note) {
        this.note = note == null ? "" : note;
    }

    public boolean finished() {
        return stepIndex >= steps.size();
    }

    /** 目标产物（任务链最后一步的产物）。 */
    public ItemStack targetOutput() {
        if (steps.isEmpty()) return ItemStack.EMPTY;
        var last = steps.get(steps.size() - 1);
        ItemStack stack = last.output.copy();
        stack.setCount(cn.ism.mekck.util.CountMath.mulClamp(cn.ism.mekck.util.CountMath.MAX_COUNT,
                last.output.getCount(), Math.max(1, last.batches)));
        return stack;
    }
}
