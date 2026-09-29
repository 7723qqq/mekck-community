package cn.ism.mekck.machine;

import mekanism.api.Action;
import mekanism.api.AutomationType;
import mekanism.api.IContentsListener;
import mekanism.common.inventory.container.slot.ContainerSlotType;
import mekanism.common.inventory.slot.BasicInventorySlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * {@link MekCkSlot} 的真行为测试（阶段 2 Task 4.9）。
 *
 * <h3>为什么能在裸 JVM 里造出真槽位</h3>
 * {@code MekCkSlot} 的构造链止于 {@code BasicInventorySlot} 的 7 参构造：
 * 它只赋值字段、碰一次 {@code ItemStack.EMPTY}，不碰 {@code Level}、不碰
 * {@code BlockEntityType}，与 {@code TestMekCkSlotNbt} 里
 * {@code InputInventorySlot.at(...)} 能直接造是同一回事。剩下的
 * {@code ItemStack}/{@code Items} 靠 {@link #boot()} 那两步拉起注册表。
 *
 * <p>真 tile（{@link MekCkMachineTile}）在裸 JVM 里造不出来（构造链要
 * {@code CuttingMachineFactoryBlock} 与 Mek 的 {@code Attribute} 注册表），
 * 所以「基类换用了 {@code MekCkSlot}」与「上限来自配置」这两条改用读源文本钉死，
 * 见 {@link #tileBuildsItsSlotsThroughMekCkSlot()} 与
 * {@link #obeyStackLimitIsTurnedOffInMekCkSlot()}。</p>
 */
public class TestMekCkSlot {

    /** 单槽容量上限的测试取值。 */
    private static final int LIMIT = 512;
    /** 桶的自身堆叠上限是 1——它专门用来验「有没有被物品堆叠上限截回去」。 */
    private static final int BUCKET_MAX_STACK = 1;

    @BeforeClass
    public static void boot() {
        net.minecraft.SharedConstants.tryDetectVersion();
        try {
            net.minecraft.server.Bootstrap.bootStrap();
        } catch (Throwable ignored) {
            // Forge 网络钩子在未变换的 classpath 上必然失败；注册表此时已就绪。
        }
    }

    private static final IContentsListener NOOP = () -> {
    };

    private static MekCkSlot input() {
        return MekCkSlot.input(LIMIT, NOOP, 38, 41);
    }

    private static MekCkSlot output() {
        return MekCkSlot.output(LIMIT, NOOP, 74, 41);
    }

    // ── 容量口径 ────────────────────────────────────────────────────────

    /**
     * 单槽容量就是传进来的那个数，<b>不</b>被物品自身堆叠上限截断。
     *
     * <p>这条是整个 Task 4.9 的地基。{@code BasicInventorySlot} 的 7 参构造把
     * {@code obeyStackLimit} 钉成 {@code true}，而
     * {@code getLimit(stack)} 在那个标志为真时返回
     * {@code min(limit, stack.getMaxStackSize())}——桶（上限 1）会把 512 截成 1，
     * 配置项直接变成摆设。</p>
     */
    @Test
    public void limitIsNotClampedByTheItemsOwnStackSize() {
        assertEquals("面包（堆叠上限 64）应拿到完整上限", LIMIT, input().getLimit(new ItemStack(Items.BREAD, 1)));
        assertEquals("桶（堆叠上限 " + BUCKET_MAX_STACK + "）同样应拿到完整上限",
                LIMIT, input().getLimit(new ItemStack(Items.BUCKET, 1)));
    }

    /** 空栈也拿到同一个上限：{@code getLimit} 对空栈走的是另一条分支，两条都得是配置值。 */
    @Test
    public void emptyStackAlsoReportsTheConfiguredLimit() {
        assertEquals(LIMIT, output().getLimit(ItemStack.EMPTY));
    }

    /** 输入槽与输出槽的上限一致（同一个配置项），但槽位类型不同。 */
    @Test
    public void inputAndOutputShareTheLimitButNotTheSlotType() throws Exception {
        assertEquals(LIMIT, input().getLimit(new ItemStack(Items.BREAD, 1)));
        assertEquals(LIMIT, output().getLimit(new ItemStack(Items.BREAD, 1)));
        assertEquals(ContainerSlotType.INPUT, slotTypeOf(input()));
        assertEquals(ContainerSlotType.OUTPUT, slotTypeOf(output()));
    }

    /**
     * Mek 自己的 {@code insertItem} 认这个上限：塞 700 个只进 512 个。
     *
     * <p>「槽认上限」与「执行器按上限判定」必须同时成立，否则就是本任务要修的那个
     * 静默不一致（判定说装得下、实际只塞进去一半）。</p>
     */
    @Test
    public void insertItemStopsAtTheConfiguredLimit() {
        MekCkSlot slot = input();
        ItemStack remainder = slot.insertItem(new ItemStack(Items.BREAD, 700), Action.EXECUTE,
                AutomationType.MANUAL);
        assertEquals("槽里应有 " + LIMIT + " 个", LIMIT, slot.getCount());
        assertEquals("剩下 188 个应退回给调用方", 700 - LIMIT, remainder.getCount());
    }

    /**
     * 桶在槽里可以叠到上限（而不是 1）。
     *
     * <p>与旧方块实体一致：它对输入/输出槽覆写了 {@code getStackLimit}，
     * 同样不按物品自身堆叠上限截断（见 {@code CuttingMachineFactoryBlockEntity} 的
     * {@code getSlotLimit}/{@code getStackLimit}）。</p>
     */
    @Test
    public void bucketsCanStackBeyondOneInAMekCkSlot() {
        MekCkSlot slot = output();
        ItemStack remainder = slot.insertItem(new ItemStack(Items.BUCKET, 3), Action.EXECUTE,
                AutomationType.INTERNAL);
        assertEquals(0, remainder.getCount());
        assertEquals(3, slot.getCount());
    }

    // ── 插入/取出谓词 ───────────────────────────────────────────────────

    /** 输入槽：外部自动化不得从里面抽走原料（{@code canExtract = notExternal}）。 */
    @Test
    public void externalAutomationCannotPullOutOfAnInputSlot() {
        MekCkSlot slot = input();
        slot.setStack(new ItemStack(Items.BREAD, 10));
        assertTrue("外部自动化应被拒（extractItem 被 canExtract 拦下时返回 ItemStack.EMPTY）",
                slot.extractItem(5, Action.EXECUTE, AutomationType.EXTERNAL).isEmpty());
        assertEquals("槽内物品应原封不动", 10, slot.getCount());
        assertFalse("玩家手动取应当放行",
                slot.extractItem(5, Action.EXECUTE, AutomationType.MANUAL).isEmpty());
        assertEquals(5, slot.getCount());
    }

    /** 输出槽：只有 {@code INTERNAL}（机器自己）能写入，外部自动化与玩家手动都不行。 */
    @Test
    public void onlyInternalAutomationMayWriteIntoAnOutputSlot() {
        MekCkSlot slot = output();
        ItemStack fromPlayer = new ItemStack(Items.BREAD, 10);
        assertEquals("玩家手动放应当被原样退回", 10,
                slot.insertItem(fromPlayer, Action.EXECUTE, AutomationType.MANUAL).getCount());
        assertEquals("外部自动化放也应当被原样退回", 10,
                slot.insertItem(new ItemStack(Items.BREAD, 10), Action.EXECUTE,
                        AutomationType.EXTERNAL).getCount());
        assertTrue("机器自己写应当成功", slot
                .insertItem(new ItemStack(Items.BREAD, 10), Action.EXECUTE, AutomationType.INTERNAL)
                .isEmpty());
        assertEquals(10, slot.getCount());
    }

    // ── 源码不变量：基类换槽 + obeyStackLimit 被关掉 ──────────────────────

    /**
     * {@code MekCkMachineTile} 必须用 {@code MekCkSlot} 排方阵。
     *
     * <p>真 tile 造不出来（见类注释）。而这条一旦被改回去，后果与修复前<b>完全一致</b>：
     * 单槽又变回 {@code min(64, 物品堆叠上限)}，且执行器读到的上限与玩家看到的对不上，
     * 症状是静默降速、不留日志。</p>
     */
    @Test
    public void tileBuildsItsSlotsThroughMekCkSlot() throws IOException {
        String source = Files.readString(Path.of("src/main/java/cn/ism/mekck/machine/MekCkMachineTile.java"),
                StandardCharsets.UTF_8);
        assertTrue("方阵应当由 MekCkSlot 排", source.contains("MekCkSlot.input("));
        assertTrue("方阵应当由 MekCkSlot 排", source.contains("MekCkSlot.output("));
        assertFalse("不得再走 InputInventorySlot.at（单槽硬编码 64）",
                source.contains("InputInventorySlot.at("));
        assertFalse("不得再走 OutputInventorySlot.at（单槽硬编码 64）",
                source.contains("OutputInventorySlot.at("));
    }

    /**
     * {@code MekCkSlot} 必须在构造器里把 {@code obeyStackLimit} 关掉。
     *
     * <p>7 参构造把它钉成 {@code true}（{@code javap} 偏移 11~13），而 {@code getLimit}
     * 在该标志为真时返回 {@code min(limit, 物品自身堆叠上限)}。不关的话
     * {@link #limitIsNotClampedByTheItemsOwnStackSize()} 这条会红——本断言把原因钉在源头，
     * 免得后人只把测试夹具改宽就算「修好了」。</p>
     */
    @Test
    public void obeyStackLimitIsTurnedOffInMekCkSlot() throws IOException {
        String source = Files.readString(Path.of("src/main/java/cn/ism/mekck/machine/MekCkSlot.java"),
                StandardCharsets.UTF_8);
        assertTrue("必须在构造器里关掉 obeyStackLimit",
                source.contains("this.obeyStackLimit = false;"));
    }

    /**
     * 上限必须来自配置，不许在槽里写死。
     *
     * <p>「可配」这件事本身要钉死：{@code MekCkSlot} 只负责把 limit 递进去，
     * 取值在 {@code MekckConfig.getFactorySlotLimit}，方阵在
     * {@code MekCkMachineTile.slotLimitPerSlot}。</p>
     */
    @Test
    public void limitComesFromMekckConfig() throws IOException {
        String tile = Files.readString(Path.of("src/main/java/cn/ism/mekck/machine/MekCkMachineTile.java"),
                StandardCharsets.UTF_8);
        assertTrue("方阵的上限应当取自配置", tile.contains("MekckConfig.getFactorySlotLimit(tier)"));
        String config = Files.readString(Path.of("src/main/java/cn/ism/mekck/config/MekckConfig.java"),
                StandardCharsets.UTF_8);
        assertTrue("配置里应当有 slot_limits 段", config.contains("slot_limits"));
        assertTrue("配置里应当有 slot_limit 项", config.contains("\"slot_limit\""));
    }

    // ── 辅助 ────────────────────────────────────────────────────────────

    /**
     * 读出 {@code BasicInventorySlot.getSlotType()}。
     *
     * <p>它是 {@code protected} 且声明在 {@code mekanism.common.inventory.slot} 包里，
     * 同包的测试类也够不着（protected 的包级可见性只对<b>声明</b>它的包生效），
     * 所以走反射。槽位类型决定 GUI 里这格是「进料槽」还是「产物槽」的呈现，
     * 7 参构造默认给的是 NORMAL，漏掉 {@code setSlotType} 不会有任何编译期或运行期报错。</p>
     */
    private static ContainerSlotType slotTypeOf(BasicInventorySlot slot) throws Exception {
        Method getter = BasicInventorySlot.class.getDeclaredMethod("getSlotType");
        getter.setAccessible(true);
        return (ContainerSlotType) getter.invoke(slot);
    }
}
