package cn.ism.mekck.machine;

import mekanism.api.Action;
import mekanism.api.AutomationType;
import mekanism.api.inventory.IInventorySlot;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.items.ItemStackHandler;

import java.util.List;

/**
 * 把 Mek 的 {@link IInventorySlot} 列表适配成 Forge 的 {@link ItemStackHandler} <b>视图</b>。
 *
 * <h3>为什么需要它</h3>
 * {@code cn.ism.mekck.ae2.INetworkPullable} 是<b>旧方块实体时代</b>的 AE2 拉料契约，
 * 它的 {@code getNetworkPullItems()} 返回类型被写死成 {@code ItemStackHandler}，
 * 而 {@code MekckAe2} 里所有消费代码（{@code IntHandlerBulkView} 的批量插入、
 * {@code FactoryGridHost.getItems()} 的产物回写）也都按这个类型写。
 * 迁到 Mek 原生 tile 之后槽位是 {@link IInventorySlot}，两边唯一的公共面就是
 * 「读一格 / 写一格 / 往一格塞东西」——本类把这三件事翻译过去。
 *
 * <h3>为什么是「视图」而不是「拷贝」</h3>
 * 本类<b>不持有任何 {@code ItemStack}</b>：{@code super(size)} 建出来的那个内部列表
 * 从头到尾没被读过（{@code getSlots/getStackInSlot/setStackInSlot/insertItem/extractItem/
 * getSlotLimit/isItemValid} 七个方法全部覆写），所有读写都直接落到传入的槽对象上。
 * 因此 AE2 侧看到的永远是机器的真实内容，不存在「视图与真身不同步」这种状态。
 *
 * <h3>写入用的 {@link AutomationType}</h3>
 * 与 {@code MekPortWindow} 的 {@code WRITE_AUTOMATION} 同值同理由：AE2 只往<b>输入槽</b>写，
 * 而 {@code MekCkSlot.input} 的 {@code canInsert} 是 {@code alwaysTrueBi}
 * （与 Mek 的 {@code InputInventorySlot.at(listener,x,y)} 逐位一致），
 * 任何 {@code AutomationType} 都放行；选 {@code MANUAL} 是为了与旧
 * {@code ItemStackHandler.insertItem(i, stack, false)}（完全没有 automationType 闸门）
 * 的旧行为逐字对齐。
 *
 * <p><b>抽取侧不能照抄这个选择</b>：{@code MekCkSlot.output} 的 {@code canInsert} 是
 * {@code internalOnly}，所以本视图的 {@code insertItem} 对产物槽会被拒——
 * 这正是「产物槽」的意义（外部自动化与玩家都放不进去），与旧
 * {@code OutputSlot.mayPlace → false} 一致。</p>
 */
public final class MekCkSlotHandler extends ItemStackHandler {

    /** 写入用的 automationType，理由见类注释。 */
    private static final AutomationType WRITE_AUTOMATION = AutomationType.MANUAL;

    private final List<IInventorySlot> slots;

    public MekCkSlotHandler(List<IInventorySlot> slots) {
        super(slots.size());
        this.slots = slots;
    }

    @Override
    public int getSlots() {
        return slots.size();
    }

    @Override
    public ItemStack getStackInSlot(int slot) {
        return valid(slot) ? slots.get(slot).getStack() : ItemStack.EMPTY;
    }

    /**
     * 写回一格。
     *
     * <p>走 {@code IInventorySlot.setStack} 而不是改 {@code getStack()} 返回的活引用：
     * 后者会绕过槽的 {@code onContentsChanged}，区块不会被标记为脏，
     * 玩家看到的是「产物回写进 ME 了，机器里却还留着」。</p>
     */
    @Override
    public void setStackInSlot(int slot, ItemStack stack) {
        if (valid(slot)) {
            slots.get(slot).setStack(stack == null ? ItemStack.EMPTY : stack);
        }
    }

    @Override
    public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
        if (!valid(slot) || stack == null || stack.isEmpty()) {
            return stack == null ? ItemStack.EMPTY : stack;
        }
        return slots.get(slot).insertItem(stack, simulate ? Action.SIMULATE : Action.EXECUTE, WRITE_AUTOMATION);
    }

    @Override
    public ItemStack extractItem(int slot, int amount, boolean simulate) {
        if (!valid(slot) || amount <= 0) {
            return ItemStack.EMPTY;
        }
        return slots.get(slot).extractItem(amount, simulate ? Action.SIMULATE : Action.EXECUTE, WRITE_AUTOMATION);
    }

    /**
     * 单槽容量。
     *
     * <p>传 {@link ItemStack#EMPTY} 而不是真实物品：本模组的槽是 {@link MekCkSlot}
     * （{@code obeyStackLimit = false}），{@code getLimit} 与传入的栈无关，
     * 空栈也拿得到真实容量；而 {@code IntHandlerBulkView.bulkSpace} 正是拿这个值
     * 去算「还能装多少」的。</p>
     */
    @Override
    public int getSlotLimit(int slot) {
        return valid(slot) ? slots.get(slot).getLimit(ItemStack.EMPTY) : 0;
    }

    @Override
    public boolean isItemValid(int slot, ItemStack stack) {
        return valid(slot) && slots.get(slot).isItemValid(stack);
    }

    private boolean valid(int slot) {
        return slot >= 0 && slot < slots.size();
    }
}
