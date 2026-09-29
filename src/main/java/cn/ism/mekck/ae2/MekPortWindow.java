package cn.ism.mekck.ae2;

import cn.ism.mekck.machine.ports.IMekCkPorted;
import mekanism.api.Action;
import mekanism.api.AutomationType;
import mekanism.api.inventory.IInventorySlot;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.items.ItemStackHandler;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * AE2 侧看到的「一台机器的全部物品槽」——阶段 2 Task 4.6。
 *
 * <h3>为什么要有这个类</h3>
 * 切菜工厂在 Task 4 之后换成了 {@code CuttingFactoryTile}（Mek 原生基类），
 * 它的槽位是 {@code List<IInventorySlot>}，而 {@code MekckAe2} 里所有消费代码
 * 都是按 {@code ItemStackHandler} 的 int 下标写的。两边唯一的公共面就是
 * 「读一格 / 写一格 / 往一格塞东西」，本类把这三件事抽象出来，
 * 让消费方不必再关心底下是哪种实现。
 *
 * <h3>下标约定</h3>
 * 统一成一段连续窗口：{@code [0, inputCount())} 是输入，
 * {@code [inputCount(), size())} 是产物。这与旧切菜/烧烤工厂
 * {@code ItemStackHandler} 的 {@code [0, 2n)} 布局逐位一致，
 * 所以旧机器改走本类后行为不变，新 tile 只是把同一段窗口换成了槽对象。
 *
 * <h3>组端口塌缩的形状（本任务的明确决定）</h3>
 * {@link IMekCkPorted#meGroupParallelItemInputs()} 为 true 时，
 * <b>N 个并行输入槽对外只呈现为 1 个输入端口</b>，具体落实为三条：
 * <ol>
 *   <li><b>地址</b>：这 N 个槽在窗口里占据<b>一段连续</b>的 {@code [0, n)}，
 *       不是一个「需要调用方自己去映射的下标数组」。塌缩发生在地址层，
 *       调用方永远只看见一个范围；</li>
 *   <li><b>容量</b>：端口容量 = 全部 N 格的剩余空间之和
 *       （{@link #bulkSpace}），不按单格上限切；</li>
 *   <li><b>AE2 侧的配料身份</b>：一条样板声明的配料数 = <b>配方的配料数</b>，
 *       与机器的槽数无关。奇点创世 81 个槽也不会变成 81 个配料口。</li>
 * </ol>
 * 需要说清楚的是：{@code MekckAe2} 现有的每一个消费方本来就是「整段输入区间」
 * 语义（{@code countInRange} 求和、{@code insertIntoRange} 顺序填充），
 * 因此这条塌缩在当前消费方集合下<b>不产生可观测的行为差异</b>——
 * 它是把「不塌缩会出错」这件事提前钉死给未来的消费方（例如 Mek Energistics 桥接），
 * 而不是现在就要改一遍跑通的行为。本类不额外发明别的塌缩形状。
 */
final class MekPortWindow {

    /**
     * 写入输入槽时用的 automationType。
     *
     * <p>实测（javap {@code InputInventorySlot} 构造器 + {@code lambda$new$0}）：
     * {@code canExtract = notExternal}，而 {@code canInsert} 是
     * 「只调 insertionCheck、<b>不看</b> automationType」的恒真谓词——
     * 也就是说 {@code InputInventorySlot} 只在<b>抽取</b>方向拦 EXTERNAL。
     * AE2 只往输入槽写，所以 {@code MANUAL} 与 {@code EXTERNAL} 在这里等价。
     * 选 {@code MANUAL} 是为了与旧 {@code ItemStackHandler.insertItem(i, stack, false)}
     * （完全没有 automationType 闸门）的旧行为逐字对齐。</p>
     */
    private static final AutomationType WRITE_AUTOMATION = AutomationType.MANUAL;

    /** 非 null 时走 Mek 槽位路径；此时 {@link #legacy} 必为 null。 */
    private final List<IInventorySlot> mekInputs;
    private final List<IInventorySlot> mekOutputs;
    /** 非 null 时走旧 {@code ItemStackHandler} 路径；此时上面两个必为 null。 */
    private final ItemStackHandler legacy;
    private final int legacyInputCount;

    private MekPortWindow(List<IInventorySlot> inputs, List<IInventorySlot> outputs) {
        this.mekInputs = inputs;
        this.mekOutputs = outputs;
        this.legacy = null;
        this.legacyInputCount = inputs.size();
    }

    private MekPortWindow(ItemStackHandler handler, int inputCount) {
        this.mekInputs = null;
        this.mekOutputs = null;
        this.legacy = handler;
        this.legacyInputCount = inputCount;
    }

    /**
     * 端口声明型机器（Mek 原生 tile）的窗口。
     *
     * <p>输入集合的合成规则：先 {@code mePatternItemInputs()}，
     * 再补 {@code mePersistentItemInputs()} 里尚未出现的，
     * 然后扣掉任何同时出现在产物槽或「只手动」白名单里的槽。
     *
     * <p>后两步不是为了防御性编程——{@code CuttingFactoryTile} 今天的
     * {@code mePersistentItemInputs()} 返空、{@code meManualOnlyItemSlots()} 只含
     * 能量槽与升级槽（两者都不在输入/产物列表里），三步都是恒等变换。
     * 保留它们是因为 Task 2 刻意让这 7 个方法<b>各有默认实现</b>：
     * 下一个实现者（其余 5 个家族）完全可以把常驻槽也写进
     * {@code mePatternItemInputs()}，那样 AE2 侧就会把同一个槽数两遍、
     * 把能量槽当配料口往里塞料。规则写在消费方，实现者才不用记。</p>
     *
     * @return 端口为空（{@code meSupportsPatternAutomation()} 为 false）时返回 null
     */
    static MekPortWindow ofPorted(IMekCkPorted ported) {
        if (ported == null || !ported.meSupportsPatternAutomation()) {
            return null;
        }
        List<IInventorySlot> inputs = new ArrayList<>();
        Set<IInventorySlot> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        inputsFrom(ported.mePatternItemInputs(), inputs, seen);
        inputsFrom(ported.mePersistentItemInputs(), inputs, seen);
        Set<IInventorySlot> excluded = Collections.newSetFromMap(new IdentityHashMap<>());
        for (IInventorySlot slot : ported.mePatternItemOutputs()) {
            if (slot != null) {
                excluded.add(slot);
            }
        }
        for (IInventorySlot slot : ported.meManualOnlyItemSlots()) {
            if (slot != null) {
                excluded.add(slot);
            }
        }
        inputs.removeIf(excluded::contains);
        if (inputs.isEmpty()) {
            return null;
        }
        List<IInventorySlot> outputs = new ArrayList<>();
        Set<IInventorySlot> used = Collections.newSetFromMap(new IdentityHashMap<>());
        used.addAll(inputs);
        for (IInventorySlot slot : ported.mePatternItemOutputs()) {
            if (slot != null && used.add(slot)) {
                outputs.add(slot);
            }
        }
        return new MekPortWindow(inputs, outputs);
    }

    /**
     * 把一组端口槽并进 {@code target}，按<b>对象同一性</b>去重。
     *
     * <p>用 {@link IdentityHashMap} 而不是 {@code HashSet}：契约里表达的是
     * 「同一格槽位」，不是「某种等价的槽位」。两者在今天等价
     * （{@code BasicInventorySlot} 没有覆写 {@code equals}），
     * 但槽位实现一旦换成带 {@code equals} 的包装类，按值去重会把
     * 「两个不同的槽恰好同值」误判成同一个而丢掉一个配料口。
     * null 元素直接跳过：{@code List.of(...)} 造不出 null，
     * 但 {@code ArrayList} 造的槽位列表可以，而那正是下一个实现者会写的。</p>
     */
    private static void inputsFrom(List<IInventorySlot> group, List<IInventorySlot> target,
                                   Set<IInventorySlot> seen) {
        if (group == null) {
            return;
        }
        for (IInventorySlot slot : group) {
            if (slot != null && seen.add(slot)) {
                target.add(slot);
            }
        }
    }

    /** 旧工厂机器（{@code [0,n)} 输入 + {@code [n,2n)} 产物）的窗口。 */
    static MekPortWindow ofLegacyFactory(ItemStackHandler handler, int inputCount) {
        if (handler == null || inputCount <= 0) {
            return null;
        }
        return new MekPortWindow(handler, inputCount);
    }

    int inputCount() {
        return mekInputs != null ? mekInputs.size() : legacyInputCount;
    }

    int outputCount() {
        return mekInputs != null ? mekOutputs.size() : legacyInputCount;
    }

    /** 输入段的窗口下标，供只吃 {@code int[]} 的旧导出代码使用。 */
    int[] inputIndices() {
        return range(0, inputCount());
    }

    /** 产物段的窗口下标。 */
    int[] outputIndices() {
        return range(inputCount(), size());
    }

    private int[] range(int from, int to) {
        int[] out = new int[Math.max(0, to - from)];
        for (int i = 0; i < out.length; i++) {
            out[i] = from + i;
        }
        return out;
    }

    private int size() {
        return inputCount() + outputCount();
    }

    private IInventorySlot mekSlot(int index) {
        return index < inputCount() ? mekInputs.get(index) : mekOutputs.get(index - inputCount());
    }

    ItemStack getStack(int index) {
        if (legacy != null) {
            return legacy.getStackInSlot(index);
        }
        ItemStack stack = mekSlot(index).getStack();
        return stack == null ? ItemStack.EMPTY : stack;
    }

    /**
     * 写回一格。
     *
     * <p>Mek 槽的 {@code setStack} 内部会 {@code stack.copy()} 并在
     * {@code !isItemValid} 时<b>抛 RuntimeException</b>（实测
     * {@code BasicInventorySlot.setStack(ItemStack, boolean)} 偏移 51-78）。
     * 本类只往本窗口自己的槽里写原样读出的物品，而
     * {@code InputInventorySlot} / {@code OutputInventorySlot} 的 validator
     * 都是 {@code alwaysTrue}（实测两者的 {@code at(listener,x,y)} 都把
     * {@code alwaysTrue} 作为第三个参数传给 6 参构造器），所以走不到那条分支。</p>
     */
    void setStack(int index, ItemStack stack) {
        if (legacy != null) {
            legacy.setStackInSlot(index, stack);
            return;
        }
        mekSlot(index).setStack(stack == null ? ItemStack.EMPTY : stack);
    }

    /**
     * 往一格塞东西，返回<b>没装下的余料</b>（与
     * {@code ItemStackHandler.insertItem} 的契约一致）。
     */
    ItemStack insertItem(int index, ItemStack stack) {
        if (legacy != null) {
            return legacy.insertItem(index, stack, false);
        }
        return mekSlot(index).insertItem(stack, Action.EXECUTE, WRITE_AUTOMATION);
    }

    /**
     * 区间 {@code [start, start+count)} 还能装多少件 {@code proto}。
     *
     * <p>这就是「组端口容量」的定义：塌缩后的输入端口容量是组内各槽剩余空间之和。
     * 单槽上限用 {@link IInventorySlot#getLimit(ItemStack)}（Mek 侧）/
     * {@code getSlotLimit}（旧 handler 侧），两者都尊重机器自己的堆叠上限。</p>
     */
    long bulkSpace(ItemStack proto, int start, int count) {
        if (proto == null || proto.isEmpty()) {
            return 0L;
        }
        long space = 0L;
        int end = Math.min(start + count, size());
        for (int i = Math.max(0, start); i < end; i++) {
            if (!isValid(i, proto)) {
                continue;
            }
            ItemStack current = getStack(i);
            if (current.isEmpty()) {
                space += limitOf(i, proto);
            } else if (ItemStack.isSameItemSameTags(current, proto)) {
                space += Math.max(0L, limitOf(i, proto) - current.getCount());
            }
        }
        return space;
    }

    /** 向区间插入最多 {@code amount} 件（按槽顺序填充），返回实际插入量。 */
    long bulkInsert(ItemStack proto, long amount, int start, int count) {
        if (proto == null || proto.isEmpty() || amount <= 0L) {
            return 0L;
        }
        long moved = 0L;
        int end = Math.min(start + count, size());
        ItemStack probe = proto.copyWithCount(1);
        for (int i = Math.max(0, start); i < end && moved < amount; i++) {
            if (!isValid(i, probe)) {
                continue;
            }
            // 每次都按「余量」重新切片：IInventorySlot.insertItem 是 int 级接口，
            // 而调用方的数量是 long（ME 网络里单个 AEKey 可以是十亿级）。
            int want = (int) Math.min(amount - moved, Integer.MAX_VALUE - 1);
            if (want <= 0) {
                break;
            }
            ItemStack leftover = insertItem(i, probe.copyWithCount(want));
            moved += want - leftover.getCount();
        }
        return moved;
    }

    private boolean isValid(int index, ItemStack stack) {
        if (legacy != null) {
            return index >= 0 && index < legacy.getSlots() && legacy.isItemValid(index, stack);
        }
        return index >= 0 && index < size() && mekSlot(index).isItemValid(stack);
    }

    private long limitOf(int index, ItemStack stack) {
        if (legacy != null) {
            return legacy.getSlotLimit(index);
        }
        return mekSlot(index).getLimit(stack);
    }
}
