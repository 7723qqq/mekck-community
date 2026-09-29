package cn.ism.mekck.machine;

import mekanism.api.AutomationType;
import mekanism.api.IContentsListener;
import mekanism.common.inventory.container.slot.ContainerSlotType;
import mekanism.common.inventory.slot.BasicInventorySlot;
import net.minecraft.world.item.ItemStack;

import java.util.function.BiPredicate;

/**
 * MekCK 机器的输入/输出槽 —— <b>单槽容量可配</b>的 {@link BasicInventorySlot}（阶段 2 Task 4.9）。
 *
 * <h3>为什么不能继承 {@code InputInventorySlot} / {@code OutputInventorySlot}</h3>
 * 它们<b>不暴露 limit</b>：两者的构造链都止步于 {@code BasicInventorySlot} 的 6 参构造，
 * 而那个构造的第一行就是 {@code invokespecial} 7 参构造并传 {@code bipush 64}。
 * {@code javap -p -c} 实测（{@code mekanism.common.inventory.slot.InputInventorySlot} /
 * {@code OutputInventorySlot}，7 参构造本体见 {@code BasicInventorySlot} 偏移 11~13）：
 * <pre>
 *   BasicInventorySlot(6 参, BiPredicate, ...)   // ← Input/Output 走这条
 *       1: bipush 64
 *      12: invokespecial BasicInventorySlot."&lt;init&gt;":(ILjava/util/function/BiPredicate;...)V
 *
 *   BasicInventorySlot(7 参, int limit, ...)      // ← 唯一能指定 limit 的入口
 *      12: iconst_1
 *      13: putfield  obeyStackLimit:Z             // 偏移 11~13，恒为 true
 *      17: getstatic ContainerSlotType.NORMAL
 *      20: putfield  slotType                     // 偏移 16~20，恒为 NORMAL
 *      24: iload_1
 *      25: putfield  limit:I                      // 偏移 23~25，第一个形参就是 limit
 * </pre>
 * 也就是说：{@code InputInventorySlot} / {@code OutputInventorySlot} 的 limit 是<b>硬编码</b>的
 * {@code 64}（{@code DEFAULT_LIMIT}），子类无法通过继承改写它——它们把 limit 传进父构造就完事了。
 * 而 {@code getLimit} 又会在这个 64 之上再按物品自身堆叠上限截一次（实测
 * {@code getLimit} 偏移 14~25：{@code obeyStackLimit && !stack.isEmpty() ? min(limit, getMaxStackSize()) : limit}），
 * 于是实际单槽容量是「普通物品 64 / 末影珍珠 16 / 桶 1」。这既不是 MekCK 旧机器的行为
 * （旧 BE 的 {@code getSlotLimit} 对输入输出槽返回 {@code Integer.MAX_VALUE}），
 * 也和执行器的容量判定对不上。因此只能<b>直接继承 {@link BasicInventorySlot}</b>，
 * 走 7 参构造把可配 limit 递进去。
 *
 * <h3>三处必须手工复刻的行为（继承 {@code BasicInventorySlot} 就得自己扛）</h3>
 * <ol>
 *   <li><b>{@code obeyStackLimit = false}</b>：7 参构造把它钉成 {@code true}（偏移 11~13），
 *       而 {@code obeyStackLimit} 虽是 {@code protected} 却<b>不是 final</b>，子类可在构造器里改。
 *       全类只有 {@code getLimit} 一处读它（实测该字段在 {@code BasicInventorySlot} 字节码里
 *       只有两处：构造器里的 {@code putfield} 与 {@code getLimit} 偏移 1 的 {@code getfield}），
 *       所以关掉它<b>只</b>影响单槽容量，不牵连任何别的行为。不关的话 limit 配得再大也会被
 *       物品自身堆叠上限（桶 = 1）截回去，配置项就成了摆设。</li>
 *   <li><b>{@code ContainerSlotType}</b>：7 参构造默认 {@code NORMAL}（偏移 16~20），
 *       {@code InputInventorySlot} / {@code OutputInventorySlot} 是在<b>自己</b>的构造器末尾
 *       调 {@code setSlotType} 改回去的（实测分别在偏移 19~23 与 16~20）。本类同样在
 *       {@code super(...)} 之后调一次。这个类型决定 GUI 里的槽位配色与「可弹出/可侧配」
 *       的呈现，不能留成 NORMAL。</li>
 *   <li><b>canExtract / canInsert 两个谓词</b>：见 {@link #input} 与 {@link #output} 的注释，
 *       两者的语义<b>不一样</b>，不要图省事两边都用同一个。</li>
 * </ol>
 *
 * <h3>为什么用两个静态工厂而不是公开构造器</h3>
 * 「输入槽」与「输出槽」的差别是三样东西绑在一起的（谓词对 + 槽位类型 + 语义），
 * 暴露一个带 {@link ContainerSlotType} 形参的公开构造器只会给调用方留出错配的机会。
 */
public final class MekCkSlot extends BasicInventorySlot {

    private MekCkSlot(int limit,
                      BiPredicate<ItemStack, AutomationType> canExtract,
                      BiPredicate<ItemStack, AutomationType> canInsert,
                      IContentsListener listener,
                      int x,
                      int y,
                      ContainerSlotType slotType) {
        super(limit, canExtract, canInsert, alwaysTrue, listener, x, y);
        // 见类注释第 1 条：必须在 super(...) 之后改，构造器里赋值的那一刻起本槽的容量口径才成立。
        this.obeyStackLimit = false;
        // 见类注释第 2 条：7 参构造把类型留成 NORMAL，由这里改回调用方要的 INPUT / OUTPUT。
        setSlotType(slotType);
    }

    /**
     * 输入槽。
     *
     * <p>逐字复刻 {@code InputInventorySlot.at(listener, x, y)} 的谓词配置。
     * {@code javap -c InputInventorySlot} 实测：{@code at(listener,x,y)} 转发成
     * {@code at(alwaysTrue, listener, x, y) → at(alwaysTrue, alwaysTrue, listener, x, y)}，
     * 于是构造器收到 {@code insertionCheck = alwaysTrue}、{@code validityCheck = alwaysTrue}，
     * 再由构造器转成 {@code super(notExternal, lambda(insertionCheck), validityCheck, ...)}：
     * <ul>
     *   <li>{@code canExtract = notExternal}：外部自动化（{@code AutomationType.EXTERNAL}）
     *       <b>不得</b>从输入槽里把原料抽走——这是 Mek 区分「进料槽」与「产物槽」的核心；
     *       {@code ConstantPredicates.notExternal} 的实现即
     *       {@code automation != AutomationType.EXTERNAL}（实测其 {@code lambda$static$7}）。</li>
     *   <li>{@code canInsert = alwaysTrueBi}：玩家手放、机器内部分配都能进。
     *       原实现这里是 {@code insertionCheck.test(stack)}，而本模组的输入槽从来传的都是
     *       {@code alwaysTrue}（旧 {@code InputInventorySlot.at(listener, x, y)} 只有一个重载），
     *       所以取 {@code alwaysTrueBi} 与原实现逐位等价。</li>
     * </ul>
     * <b>注意方向</b>：{@code notExternal} 是<b>取物</b>谓词（{@code canExtract}），
     * 不是插入谓词。把它当 {@code canInsert} 传会变成「外部自动化不能往输入槽投料」，
     * 与原行为相反。</p>
     */
    public static MekCkSlot input(int limit, IContentsListener listener, int x, int y) {
        return new MekCkSlot(limit, notExternal, alwaysTrueBi, listener, x, y, ContainerSlotType.INPUT);
    }

    /**
     * 输出槽。
     *
     * <p>逐字复刻 {@code OutputInventorySlot.at(listener, x, y)} 的谓词配置——
     * 实测它的<b>私有</b>构造器是
     * {@code super(alwaysTrueBi, internalOnly, alwaysTrue, listener, x, y)}：
     * <ul>
     *   <li>{@code canExtract = alwaysTrueBi}：产物谁都能取（侧配弹出、玩家、AE2）。</li>
     *   <li>{@code canInsert = internalOnly}：只有 {@code AutomationType.INTERNAL} 能写，
     *       即<b>只有机器自己</b>能往产物槽里放。外部自动化（物流管道、AE2）与玩家手动都放不进去，
     *       这正是「产物槽」的意义。{@code ConstantPredicates.internalOnly} 的实现即
     *       {@code automation == AutomationType.INTERNAL}（实测其 {@code lambda$static$6}）。</li>
     * </ul>
     * 这里<b>不能</b>照抄输入槽用 {@code notExternal}：那样外部自动化与玩家就都能往产物槽里塞东西，
     * 产物槽形同虚设（而且这会悄悄改掉侧配/弹出之外的另一条既有行为）。</p>
     */
    public static MekCkSlot output(int limit, IContentsListener listener, int x, int y) {
        return new MekCkSlot(limit, alwaysTrueBi, internalOnly, listener, x, y, ContainerSlotType.OUTPUT);
    }
}
